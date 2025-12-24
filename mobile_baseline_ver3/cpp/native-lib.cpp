#include "llama.h"
#include "../llama/llama.h"
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

// [FIX] Updated signature to accept model_path_str
std::string runModel(const std::string& prompt, const std::string& model_path_str) {

    auto t_start = std::chrono::high_resolution_clock::now();
    bool first_token_seen = false;
    long ttft_ms = -1, itps = -1, otps = -1, oet_ms = -1;
    int generated_tokens = 0;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "runModel() started");

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();

    // [FIX] Use the dynamic path passed from Kotlin
    const char* model_path = model_path_str.c_str();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading model from: %s", model_path);

    llama_model* model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to load model from %s", model_path);
        return "Error: Model file not found (Check path or copy logic)";
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_threads = 4;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        return "Error: Failed to create context";
    }

    // Tokenize
    std::vector<llama_token> prompt_tokens(prompt.size() + 8);
    int n_prompt = llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), true, false);
    if (n_prompt <= 0) return "Error: Tokenization failed";
    prompt_tokens.resize(n_prompt);

    // Batch
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

    // Decode
    auto t_prefill_start = std::chrono::high_resolution_clock::now();
    if (llama_decode(ctx, batch) != 0) return "Error: Decode failed";

    auto t_prefill_end = std::chrono::high_resolution_clock::now();
    long prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_prefill_end - t_prefill_start).count();
    if (prefill_ms > 0) itps = (n_prompt * 1000L) / prefill_ms;

    // Generate
    llama_sampler* sampler = llama_sampler_init_greedy();
    std::string output;
    int n_pos = 0;
    auto t_gen_start = std::chrono::high_resolution_clock::now();

    while (n_pos + batch.n_tokens < n_prompt + 20) { // Limit max tokens
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
            if (output.find('\n') != std::string::npos) break; // Stop at newline
        }
        generated_tokens++;
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;
        n_pos += batch.n_tokens;
    }

    auto t_gen_end = std::chrono::high_resolution_clock::now();
    long gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_gen_end - t_gen_start).count();
    if (gen_ms > 0) otps = (generated_tokens * 1000L) / gen_ms;
    oet_ms = gen_ms;

    // Filter Logic
    static const std::set<std::string> ALLOWED_ALLERGENS = { "milk","egg","peanut","tree nut","wheat","soy","fish","shellfish","sesame" };
    std::transform(output.begin(), output.end(), output.begin(), ::tolower);

    std::stringstream ss(output);
    std::string item;
    std::vector<std::string> detected;
    while (std::getline(ss, item, ',')) {
        item.erase(std::remove_if(item.begin(), item.end(), ::isspace), item.end());
        if (ALLOWED_ALLERGENS.count(item)) detected.push_back(item);
    }

    if (detected.empty()) output = "EMPTY";
    else {
        output.clear();
        for (size_t i = 0; i < detected.size(); i++) {
            if (i > 0) output += ", ";
            output += detected[i];
        }
    }

    // Cleanup
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
        jobject,
        jstring inputPrompt,
        jstring modelPathStr) { // [FIX] Added parameter

    const char *pathCStr = env->GetStringUTFChars(modelPathStr, nullptr);
    std::string modelPath(pathCStr);
    env->ReleaseStringUTFChars(modelPathStr, pathCStr);

    const char *promptCStr = env->GetStringUTFChars(inputPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(inputPrompt, promptCStr);

    // [FIX] Pass path to runModel
    std::string output = runModel(prompt, modelPath);

    return env->NewStringUTF(output.c_str());
}