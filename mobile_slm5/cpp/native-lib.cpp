// native-lib.cpp
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

// Helper function: runs model inference and calculates detailed metrics
std::string runModel(JNIEnv *env, jobject thiz, const std::string& prompt,
                     const std::string& model_path_str, bool reportProgress) {

    // 1. Prepare JNI callback (used for updating progress bar)
    jclass mainActivityCls = nullptr;
    jmethodID updateProgressId = nullptr;
    if (reportProgress) {
        mainActivityCls = env->GetObjectClass(thiz);
        if (mainActivityCls) {
            updateProgressId =
                    env->GetMethodID(mainActivityCls, "updateNativeProgress", "(I)V");
        }
    }

    // --- Stage 0: Load model (NOT included in inference metrics) ---
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading model...");
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    const char* model_path = model_path_str.c_str();

    llama_model* model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        return "Error: Model file not found";
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_threads = 4; // Recommended: 4 or 6 threads

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) return "Error: Failed to create context";

    // --- Stage 1: Tokenization ---
    const llama_vocab* vocab = llama_model_get_vocab(model);
    std::vector<llama_token> prompt_tokens(prompt.size() + 8);

    int n_prompt = llama_tokenize(
            vocab,
            prompt.c_str(),
            prompt.size(),
            prompt_tokens.data(),
            prompt_tokens.size(),
            true,
            false
    );

    if (n_prompt <= 0) return "Error: Tokenization failed";
    prompt_tokens.resize(n_prompt);

    // --- Stage 2: Prompt Processing (calculate ITPS) ---
    // [FIX] Start inference timing (excluding model loading)
    auto t_inference_start = std::chrono::high_resolution_clock::now();

    llama_batch batch = llama_batch_init(n_prompt, 0, ctx_params.n_ctx);
    batch.n_tokens = n_prompt;

    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i] = 1;
        batch.logits[i] = false;
    }

    // Request logits only for the last prompt token
    batch.logits[n_prompt - 1] = true;

    // Execute prompt decoding
    if (llama_decode(ctx, batch) != 0)
        return "Error: Decode failed";

    // [FIX] Measure prompt processing time
    auto t_prompt_end = std::chrono::high_resolution_clock::now();
    long prompt_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_prompt_end - t_inference_start
            ).count();

    // [FIX] Calculate ITPS (Input Tokens Per Second)
    long itps = 0;
    if (prompt_ms > 0) {
        itps = (n_prompt * 1000L) / prompt_ms;
    }

    // --- Stage 3: Token Generation ---
    llama_sampler* sampler = llama_sampler_init_greedy();
    std::string output;

    int n_pos = 0;
    int generated_tokens = 0;
    long ttft_ms = -1;
    bool first_token_seen = false;

    // [FIX] Start generation timing
    auto t_gen_start = std::chrono::high_resolution_clock::now();

    const int MAX_GEN_TOKENS = 64; // Output token limit

    while (n_pos + batch.n_tokens < n_prompt + MAX_GEN_TOKENS) {

        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        // [FIX] Calculate TTFT (Time To First Token)
        // relative to inference start
        if (!first_token_seen) {
            auto t_now = std::chrono::high_resolution_clock::now();
            ttft_ms =
                    std::chrono::duration_cast<std::chrono::milliseconds>(
                            t_now - t_inference_start
                    ).count();
            first_token_seen = true;
        }

        char buf[128];
        int n = llama_token_to_piece(
                vocab, token, buf, sizeof(buf), 0, true
        );

        if (n > 0) {
            output.append(buf, n);

            // Simple stopping condition:
            // stop when newline is generated (to avoid unnecessary text)
            if (output.find('\n') != std::string::npos) break;
        }

        generated_tokens++;

        // Update progress to Java layer
        if (reportProgress && updateProgressId) {
            int percent = (generated_tokens * 100) / MAX_GEN_TOKENS;
            if (percent > 100) percent = 100;
            env->CallVoidMethod(thiz, updateProgressId, percent);
        }

        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;

        n_pos += batch.n_tokens;
    }

    // --- Stage 4: Final Metrics Calculation (OTPS, OET) ---
    auto t_gen_end = std::chrono::high_resolution_clock::now();

    // Generation time (generation phase only)
    long gen_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_gen_end - t_gen_start
            ).count();

    // [FIX] Calculate OTPS (Output Tokens Per Second)
    long otps = 0;
    if (gen_ms > 0) {
        otps = (generated_tokens * 1000L) / gen_ms;
    }

    // OET (Output Evaluation Time): total inference time
    long oet_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_gen_end - t_inference_start
            ).count();

    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Metrics: ITPS=%ld, OTPS=%ld, TTFT=%ld",
            itps, otps, ttft_ms
    );

    // Release resources
    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_free_model(model);

    // Return format: METADATA | OUTPUT
    return "TTFT_MS=" + std::to_string(ttft_ms) +
           ";ITPS=" + std::to_string(itps) +
           ";OTPS=" + std::to_string(otps) +
           ";OET_MS=" + std::to_string(oet_ms) +
           "|" + output;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_inferAllergens(
        JNIEnv *env,
        jobject thiz,
        jstring inputPrompt,
        jstring modelPathStr,
        jboolean reportProgress) {

    // Convert Java prompt string to C++ string
    const char *promptCStr = env->GetStringUTFChars(inputPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(inputPrompt, promptCStr);

    // Convert Java model path string to C++ string
    const char *pathCStr = env->GetStringUTFChars(modelPathStr, nullptr);
    std::string modelPath(pathCStr);
    env->ReleaseStringUTFChars(modelPathStr, pathCStr);

    // Run inference
    std::string output = runModel(env, thiz, prompt, modelPath, reportProgress);

    // Return result back to Java
    return env->NewStringUTF(output.c_str());
}