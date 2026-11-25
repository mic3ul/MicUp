#include <jni.h>
#include <opus.h>

#include <array>
#include <cstdint>
#include <new>
#include <vector>

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

struct RepacketizerState {
    OpusRepacketizer* repacketizer;
};

RepacketizerState* fromRepacketizerHandle(jlong handle) {
    return reinterpret_cast<RepacketizerState*>(static_cast<uintptr_t>(handle));
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


extern "C" JNIEXPORT jlong JNICALL
Java_com_whatsapp_weirdoaudio_OpusPacketAggregator_nativeCreate(
        JNIEnv* env, jclass) {
    auto* state = new (std::nothrow) RepacketizerState{opus_repacketizer_create()};
    if (state == nullptr || state->repacketizer == nullptr) {
        delete state;
        throwJava(env, "java/lang/IllegalStateException",
                  "Could not allocate Opus repacketizer");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(state));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_whatsapp_weirdoaudio_OpusPacketAggregator_nativeAdd(
        JNIEnv* env, jclass, jlong handle, jbyteArray packet) {
    RepacketizerState* state = fromRepacketizerHandle(handle);
    if (state == nullptr || state->repacketizer == nullptr || packet == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Opus repacketizer is closed");
        return OPUS_BAD_ARG;
    }

    const jsize length = env->GetArrayLength(packet);
    if (length <= 0 || length > 1277) {
        throwJava(env, "java/lang/IllegalArgumentException",
                  "Invalid Opus frame packet size");
        return OPUS_BAD_ARG;
    }

    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(packet, 0, length,
                            reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return OPUS_BAD_ARG;

    return opus_repacketizer_cat(state->repacketizer, bytes.data(),
                                 static_cast<opus_int32>(length));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_whatsapp_weirdoaudio_OpusPacketAggregator_nativeGetPacket(
        JNIEnv* env, jclass, jlong handle) {
    RepacketizerState* state = fromRepacketizerHandle(handle);
    if (state == nullptr || state->repacketizer == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Opus repacketizer is closed");
        return nullptr;
    }

    constexpr opus_int32 kMaxRepacketizedBytes = 1277 * 6;
    std::array<unsigned char, kMaxRepacketizedBytes> bytes{};
    const opus_int32 length = opus_repacketizer_out(
            state->repacketizer, bytes.data(), kMaxRepacketizedBytes);
    if (length <= 0 || length > kMaxRepacketizedBytes) {
        throwJava(env, "java/lang/IllegalStateException",
                  "Could not assemble Opus frames into a packet");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(length),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whatsapp_weirdoaudio_OpusPacketAggregator_nativeReset(
        JNIEnv* env, jclass, jlong handle) {
    RepacketizerState* state = fromRepacketizerHandle(handle);
    if (state == nullptr || state->repacketizer == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Opus repacketizer is closed");
        return;
    }
    opus_repacketizer_init(state->repacketizer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whatsapp_weirdoaudio_OpusPacketAggregator_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    RepacketizerState* state = fromRepacketizerHandle(handle);
    if (state == nullptr) return;
    if (state->repacketizer != nullptr) opus_repacketizer_destroy(state->repacketizer);
    delete state;
}
