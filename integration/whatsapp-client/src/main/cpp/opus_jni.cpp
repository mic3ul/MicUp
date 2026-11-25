#include <jni.h>
#include <opus.h>

#include <array>
#include <cstdint>

namespace {
constexpr jint kSampleRate = 48000;
constexpr jint kChannels = 1;
constexpr jint kFrameSamples = 960;  // 20 ms at 48 kHz.
constexpr opus_int32 kMaxPacketBytes = 4000;

void throwJava(JNIEnv* env, const char* className, const char* message) {
    jclass type = env->FindClass(className);
    if (type != nullptr) {
        env->ThrowNew(type, message);
    }
}

OpusEncoder* fromHandle(jlong handle) {
    return reinterpret_cast<OpusEncoder*>(static_cast<uintptr_t>(handle));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_whatsapp_weirdoaudio_OpusPcmEncoder_nativeCreate(
        JNIEnv* env, jclass, jint sampleRate, jint channels, jint bitrate) {
    if (sampleRate != kSampleRate || channels != kChannels) {
        throwJava(env, "java/lang/IllegalArgumentException",
                  "weirdo PTT encoder requires 48 kHz mono PCM");
        return 0;
    }
    if (bitrate < 6000 || bitrate > 64000) {
        throwJava(env, "java/lang/IllegalArgumentException",
                  "Opus voice bitrate must be between 6000 and 64000 bps");
        return 0;
    }

    int error = OPUS_OK;
    OpusEncoder* encoder = opus_encoder_create(
            sampleRate, channels, OPUS_APPLICATION_VOIP, &error);
    if (encoder == nullptr || error != OPUS_OK) {
        if (encoder != nullptr) opus_encoder_destroy(encoder);
        throwJava(env, "java/lang/IllegalStateException",
                  "Could not create the Opus voice encoder");
        return 0;
    }

    const int controls[] = {
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)),
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5)),
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)),
        opus_encoder_ctl(encoder, OPUS_SET_VBR(1)),
        opus_encoder_ctl(encoder, OPUS_SET_DTX(0)),
        opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(0)),
        opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(0)),
    };

    for (int result : controls) {
        if (result != OPUS_OK) {
            opus_encoder_destroy(encoder);
            throwJava(env, "java/lang/IllegalStateException",
                      "Could not configure the Opus voice encoder");
            return 0;
        }
    }

    return static_cast<jlong>(reinterpret_cast<uintptr_t>(encoder));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_whatsapp_weirdoaudio_OpusPcmEncoder_nativeGetLookahead(
        JNIEnv* env, jclass, jlong handle) {
    OpusEncoder* encoder = fromHandle(handle);
    if (encoder == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Opus encoder is closed");
        return 0;
    }

    opus_int32 lookahead = 0;
    const int result = opus_encoder_ctl(encoder, OPUS_GET_LOOKAHEAD(&lookahead));
    if (result != OPUS_OK || lookahead < 0 || lookahead > 65535) {
        throwJava(env, "java/lang/IllegalStateException",
                  "Could not query Opus encoder lookahead");
        return 0;
    }
    return static_cast<jint>(lookahead);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_whatsapp_weirdoaudio_OpusPcmEncoder_nativeEncodeFrame(
        JNIEnv* env, jclass, jlong handle, jshortArray pcm, jint offset) {
    OpusEncoder* encoder = fromHandle(handle);
    if (encoder == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Opus encoder is closed");
        return nullptr;
    }
    if (pcm == nullptr || offset < 0 ||
        offset > env->GetArrayLength(pcm) - kFrameSamples) {
        throwJava(env, "java/lang/IllegalArgumentException",
                  "A complete 960-sample mono PCM frame is required");
        return nullptr;
    }

    std::array<opus_int16, kFrameSamples> frame{};
    env->GetShortArrayRegion(pcm, offset, kFrameSamples,
                             reinterpret_cast<jshort*>(frame.data()));
    if (env->ExceptionCheck()) return nullptr;

    std::array<unsigned char, kMaxPacketBytes> packet{};
    const opus_int32 bytes = opus_encode(
            encoder, frame.data(), kFrameSamples, packet.data(), kMaxPacketBytes);
    if (bytes < 0 || bytes > kMaxPacketBytes) {
        throwJava(env, "java/lang/IllegalStateException",
                  "Opus failed to encode the PCM frame");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes));
    if (result == nullptr) return nullptr;
    if (bytes > 0) {
        env->SetByteArrayRegion(
                result, 0, static_cast<jsize>(bytes),
                reinterpret_cast<const jbyte*>(packet.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whatsapp_weirdoaudio_OpusPcmEncoder_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    OpusEncoder* encoder = fromHandle(handle);
    if (encoder != nullptr) opus_encoder_destroy(encoder);
}
