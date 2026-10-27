#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_cosmerearchives_shortscreator_NativeWhisper_recognize(JNIEnv* env, jobject,
                                                               jstring modelPath, jfloatArray samples) {
    const char* raw = env->GetStringUTFChars(modelPath, nullptr);
    std::string path(raw);
    env->ReleaseStringUTFChars(modelPath, raw);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!ctx) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Unable to initialize Whisper model"); return nullptr; }

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> audio(count);
    env->GetFloatArrayRegion(samples, 0, count, audio.data());
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language = "en";
    p.n_threads = 4;
    p.no_timestamps = false;
    p.print_realtime = false;
    p.print_progress = false;
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    int result = whisper_full(ctx, p, audio.data(), static_cast<int>(audio.size()));
    if (result != 0) {
        whisper_free(ctx);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Whisper transcription failed");
        return nullptr;
    }

    std::vector<std::string> entries;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const int n = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < n; ++j) {
            const char* rawToken = whisper_full_get_token_text(ctx, i, j);
            if (!rawToken) continue;
            std::string word(rawToken);
            if (word.find_first_not_of(" \t\r\n") == std::string::npos || word[0] == '[' || word[0] == '<') continue;
            auto token = whisper_full_get_token_data(ctx, i, j);
            if (token.t1 <= token.t0 || token.t0 < 0) continue;
            entries.push_back(word + "\t" + std::to_string(token.t0 * 10) + "\t" + std::to_string(token.t1 * 10));
        }
    }
    whisper_free(ctx);
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
    for (size_t i = 0; i < entries.size(); ++i) {
        jstring entry = env->NewStringUTF(entries[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), entry);
        env->DeleteLocalRef(entry);
    }
    return out;
}
