//native-lib.cpp
#include "llama.h"
#include <vector>
#include <jni.h>
#include <string>
#include <cstring>
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <set>
#include <sstream>

#define LOG_TAG "SLM_NATIVE"

// [MODIFIED] Helper function now takes JNIEnv, jobject, and report flag
std::string runModel(JNIEnv *env, jobject thiz, const std::string& prompt, const std::string& model_path_str, bool reportProgress) {

    // [NEW] Find the Kotlin method if reporting is enabled
    jclass mainActivityCls = nullptr;
    jmethodID updateProgressId = nullptr;
    if (reportProgress) {
        mainActivityCls = env->GetObjectClass(thiz);
        if (mainActivityCls) {
            updateProgressId = env->GetMethodID(mainActivityCls, "updateNativeProgress", "(I)V");
        }
    }

    auto t_start = std::chrono::high_resolution_clock::now();
    bool first_token_seen = false;
    long ttft_ms = -1, itps = -1, otps = -1, oet_ms = -1;
    int generated_tokens = 0;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "runModel() started");

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();

    const char* model_path = model_path_str.c_str();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading model from: %s", model_path);

    llama_model* model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to load model from %s", model_path);
        return "Error: Model file not found";
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_threads = 4;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) return "Error: Failed to create context";

    std::vector<llama_token> prompt_tokens(prompt.size() + 8);
    int n_prompt = llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), true, false);

    if (n_prompt <= 0) return "Error: Tokenization failed";

    prompt_tokens.resize(n_prompt);

    llama_batch batch = llama_batch_init(n_prompt, 0, ctx_params.n_ctx);
    batch.n_tokens = n_prompt;
    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i] = 1;
        batch.logits[i] = false;
    }
    batch.logits[n_prompt - 1] = true;

    if (llama_decode(ctx, batch) != 0) return "Error: Decode failed";

    llama_sampler* sampler = llama_sampler_init_greedy();
    std::string output;
    int n_pos = 0;

    // We use 64 as the estimated max tokens for the percentage calculation
    const int MAX_GEN_TOKENS = 64;

    while (n_pos + batch.n_tokens < n_prompt + MAX_GEN_TOKENS) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        if (!first_token_seen) {
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - t_start).count();
            first_token_seen = true;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            output.append(buf, n);
            if (output.find('\n') != std::string::npos) break;
        }

        generated_tokens++;

        // [NEW] Update Progress via JNI
        if (reportProgress && updateProgressId) {
            int percent = (generated_tokens * 100) / MAX_GEN_TOKENS;
            if (percent > 100) percent = 100;
            // This calls the Kotlin method
            env->CallVoidMethod(thiz, updateProgressId, percent);
        }

        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;
        n_pos += batch.n_tokens;
    }

    auto t_gen_end = std::chrono::high_resolution_clock::now();
    long gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_gen_end - t_start).count();

    if (gen_ms > 0) otps = (generated_tokens * 1000L) / gen_ms;
    oet_ms = gen_ms;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Raw model output: %s", output.c_str());

    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_free_model(model);

    return "TTFT_MS=" + std::to_string(ttft_ms) + ";ITPS=" + std::to_string(itps) +
           ";OTPS=" + std::to_string(otps) + ";OET_MS=" + std::to_string(oet_ms) + "|" + output;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_inferAllergens(
        JNIEnv *env,
        jobject thiz, // Capture the activity instance
        jstring inputPrompt,
        jstring modelPathStr,
        jboolean reportProgress) { // Added parameter

    const char *promptCStr = env->GetStringUTFChars(inputPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(inputPrompt, promptCStr);

    const char *pathCStr = env->GetStringUTFChars(modelPathStr, nullptr);
    std::string modelPath(pathCStr);
    env->ReleaseStringUTFChars(modelPathStr, pathCStr);

    // Pass environment and object to C++ helper
    std::string output = runModel(env, thiz, prompt, modelPath, reportProgress);

    return env->NewStringUTF(output.c_str());
}