#include "AudioEngine.h"
#include "PluginHost.h"
#include "ClapHost.h"
#include "Vst3Bridge.h"
#include "Lv2Host.h"

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cmath>
#include <algorithm>
#include <numeric>

#define LOG_TAG "MicPlugin.Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace micplugin {

// ─── BiquadFilter coefficient computation ────────────────────────────────────
void BiquadFilter::setPeak(float fc, float gainDb, float Q, float sr) {
    float A  = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * fc / sr;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float alpha = sinW / (2.0f * Q);
    float a0inv = 1.0f / (1.0f + alpha / A);
    b0 = (1.0f + alpha * A) * a0inv;
    b1 = (-2.0f * cosW)     * a0inv;
    b2 = (1.0f - alpha * A) * a0inv;
    a1 = b1;
    a2 = (1.0f - alpha / A) * a0inv;
}

void BiquadFilter::setLowShelf(float fc, float gainDb, float sr) {
    float A  = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * fc / sr;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float beta = std::sqrt(A) * sinW;
    float a0inv = 1.0f / ((A+1) + (A-1)*cosW + beta);
    b0 = A * ((A+1) - (A-1)*cosW + beta)          * a0inv;
    b1 = 2.0f * A * ((A-1) - (A+1)*cosW)          * a0inv;
    b2 = A * ((A+1) - (A-1)*cosW - beta)           * a0inv;
    a1 = -2.0f * ((A-1) + (A+1)*cosW)              * a0inv;
    a2 = ((A+1) + (A-1)*cosW - beta)               * a0inv;
}

void BiquadFilter::setHighShelf(float fc, float gainDb, float sr) {
    float A  = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * fc / sr;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float beta = std::sqrt(A) * sinW;
    float a0inv = 1.0f / ((A+1) - (A-1)*cosW + beta);
    b0 = A * ((A+1) + (A-1)*cosW + beta)          * a0inv;
    b1 = -2.0f * A * ((A-1) + (A+1)*cosW)         * a0inv;
    b2 = A * ((A+1) + (A-1)*cosW - beta)           * a0inv;
    a1 = 2.0f * ((A-1) - (A+1)*cosW)              * a0inv;
    a2 = ((A+1) - (A-1)*cosW - beta)              * a0inv;
}

// ─── AudioEngine ─────────────────────────────────────────────────────────────
AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    if (running_.load(std::memory_order_acquire)) return true;
    ring_.reset();
    if (!openStreams()) return false;

    // Start the input callback only after the state and DSP are fully initialized.
    running_.store(true, std::memory_order_release);
    const oboe::Result startResult = inputStream_->requestStart();
    if (startResult != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(startResult));
        running_.store(false, std::memory_order_release);
        closeStreams();
        return false;
    }

    LOGI("AudioEngine started: %dHz, %d frames/burst", sampleRate_, framesPerBurst_);
    return true;
}

void AudioEngine::stop() {
    running_.store(false, std::memory_order_release);
    closeStreams();
}

void AudioEngine::setInputDeviceId(int32_t deviceId) {
    const bool wasRunning = running_.exchange(false, std::memory_order_acq_rel);
    inputDeviceId_.store(deviceId, std::memory_order_relaxed);
    if (!wasRunning) return;

    closeStreams();
    if (!openStreams()) {
        LOGE("Could not reopen input stream for device %d", deviceId);
        return;
    }

    running_.store(true, std::memory_order_release);
    const oboe::Result result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Could not restart input stream for device %d: %s",
             deviceId, oboe::convertToText(result));
        running_.store(false, std::memory_order_release);
        closeStreams();
    }
}

int32_t AudioEngine::readProcessedAudio(float* destination, int32_t maxFrames) {
    if (destination == nullptr || maxFrames <= 0) return 0;

    const size_t available = ring_.available_read();
    const size_t count = std::min(available, static_cast<size_t>(maxFrames));
    if (count == 0) return 0;

    return ring_.read(destination, count) ? static_cast<int32_t>(count) : 0;
}

bool AudioEngine::openStreams() {
    oboe::AudioStreamBuilder builder;

    // ─ Input stream (microphone capture) ─────────────────────────────────────
    // deviceId defaults to oboe::kUnspecified (system default mic) unless the user picked
    // a specific input (e.g. a USB sound card) in Settings — see setInputDeviceId().
    int32_t preferredInputId = inputDeviceId_.load(std::memory_order_relaxed);
    auto result = builder
        .setDirection(oboe::Direction::Input)
        ->setDeviceId(preferredInputId)
        ->setInputPreset(oboe::InputPreset::VoiceCommunication)
        ->setSessionId(oboe::SessionId::Allocate)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setSampleRate(48000)
        ->setDataCallback(this)
        ->setErrorCallback(this)
        ->openStream(inputStream_);

    if (result != oboe::Result::OK && preferredInputId != oboe::kUnspecified) {
        // The chosen device may have been unplugged/unavailable — fall back to the
        // system default rather than leaving the engine unable to start at all.
        LOGE("Failed to open input stream on device %d: %s — retrying with default device",
             preferredInputId, oboe::convertToText(result));
        builder.setDeviceId(oboe::kUnspecified);
        result = builder.openStream(inputStream_);
    }

    if (result != oboe::Result::OK) {
        // Some audio HALs do not support an allocated input session in exclusive
        // VoiceCommunication mode. Keep the engine usable with a shared generic
        // capture stream; platform NoiseSuppressor will simply remain unavailable.
        LOGE("Input stream with allocated session failed: %s; retrying shared generic capture",
             oboe::convertToText(result));
        builder.setSessionId(oboe::SessionId::None)
               ->setSharingMode(oboe::SharingMode::Shared)
               ->setInputPreset(oboe::InputPreset::Generic);
        result = builder.openStream(inputStream_);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream after compatibility fallback: %s",
             oboe::convertToText(result));
        return false;
    }

    sampleRate_    = inputStream_->getSampleRate();
    framesPerBurst_ = inputStream_->getFramesPerBurst();

    // Speech-tuned time-domain stretching parameters: short sequences and
    // overlap keep consonants clear while changing pitch without slowing speech.
    highpassAlpha_ = std::exp(-2.0f * static_cast<float>(M_PI) * 72.0f /
                              static_cast<float>(sampleRate_));
    cleanupPrevSample_ = 0.0f;
    highpassPrevInput_ = 0.0f;
    highpassPrevOutput_ = 0.0f;
    vocalUpperLowpass_ = 0.0f;
    vocalAirLowpass_ = 0.0f;
    vocalSibilanceEnvelope_ = 0.0f;
    pitchShifter_.clear();
    pitchShifter_.setSampleRate(static_cast<uint>(sampleRate_));
    pitchShifter_.setChannels(1);
    pitchShifter_.setSetting(SETTING_SEQUENCE_MS, 40);
    pitchShifter_.setSetting(SETTING_SEEKWINDOW_MS, 15);
    pitchShifter_.setSetting(SETTING_OVERLAP_MS, 8);
    pitchShifter_.setPitchSemiTones(0.0);
    pitchSemitoneCache_ = 0.0f;
    pitchFifoHead_ = 0;
    pitchFifoTail_ = 0;
    pitchWasEnabled_ = false;
    pitchConfigValid_ = true;

    LOGI("Input stream opened: %dHz, %d frames/burst", sampleRate_, framesPerBurst_);

    // The input stream itself drives the audio callback. Avoid polling an input
    // stream with a zero-timeout read from a separate output callback: that races
    // capture timing and can replace valid speech blocks with silence.
    inputStream_->setBufferSizeInFrames(framesPerBurst_ * 2);

    eqDirty_ = true;
    rebuildEQ();
    return true;
}

void AudioEngine::closeStreams() {
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
}

void AudioEngine::rebuildEQ() {
    for (int i = 0; i < kEqBands; i++) {
        float gain = params_.eqGain[i].load(std::memory_order_relaxed);
        if (i == 0)
            eqFilters_[i].setLowShelf(kEqFreqs[i], gain, (float)sampleRate_);
        else if (i == kEqBands - 1)
            eqFilters_[i].setHighShelf(kEqFreqs[i], gain, (float)sampleRate_);
        else
            eqFilters_[i].setPeak(kEqFreqs[i], gain, 1.0f, (float)sampleRate_);
        eqGainCache_[i] = gain;
    }
    eqDirty_ = false;
}

// ─── Audio callback (microphone input drives processing) ─────────────────────
oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream, void* audioData, int32_t numFrames) {

    auto* samples = static_cast<float*>(audioData);
    if (samples == nullptr || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }
    if (!running_.load(std::memory_order_acquire)) {
        return oboe::DataCallbackResult::Continue;
    }

    // Oboe supplies the captured samples directly. No cross-stream polling,
    // no zero-timeout read, and no synthetic silence on a late input block.
    measureLevel(samples, numFrames, params_.inputLevelDb);

    if (!params_.masterBypass.load(std::memory_order_relaxed)) {
        processEffectsChain(samples, numFrames);
    }

    if (pluginHost_) {
        pluginHost_->process(samples, numFrames, sampleRate_);
    }

    measureLevel(samples, numFrames, params_.outputLevelDb);

    // Publish a copy to the bounded SPSC ring. The microphone callback never
    // enters the JVM, allocates, or waits for the non-real-time PCM consumer.
    static_cast<void>(ring_.write(samples, static_cast<size_t>(numFrames)));

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Audio stream error: %s", oboe::convertToText(error));
    if (!running_.load()) return;

    // Backoff: track restarts in a 10-second window
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t nowMs = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;

    if (nowMs - lastRestartMs_ > 10000) {
        // Window expired — reset counter
        restartCount_ = 0;
    }
    lastRestartMs_ = nowMs;
    restartCount_++;

    if (restartCount_ > kMaxRestarts) {
        LOGE("Too many stream restarts (%d) — giving up to avoid su popup loop", restartCount_);
        running_.store(false, std::memory_order_release);
        closeStreams();
        return;
    }

    // Exponential backoff sleep before retry (avoids hammering audio HAL)
    int backoffMs = 100 * (1 << std::min(restartCount_ - 1, 5));
    LOGI("Restarting audio streams (attempt %d/%d, backoff %dms)", restartCount_, kMaxRestarts, backoffMs);
    struct timespec sleep_ts = { backoffMs / 1000, (backoffMs % 1000) * 1000000L };
    nanosleep(&sleep_ts, nullptr);

    closeStreams();
    if (openStreams()) {
        running_.store(true, std::memory_order_release);
        const oboe::Result result = inputStream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Input stream restart failed: %s", oboe::convertToText(result));
            running_.store(false, std::memory_order_release);
            closeStreams();
        }
    } else {
        running_.store(false, std::memory_order_release);
    }
}

void AudioEngine::measureLevel(const float* buf, int32_t frames, std::atomic<float>& out) {
    float rms = 0.0f;
    for (int i = 0; i < frames; i++) rms += buf[i] * buf[i];
    rms = std::sqrt(rms / frames);
    // Smooth (IIR, coeff ≈ 0.95 @ 48kHz/128)
    float prev = std::pow(10.0f, out.load(std::memory_order_relaxed) * 0.05f);
    float smooth = prev * 0.95f + rms * 0.05f;
    out.store(linTodB(smooth), std::memory_order_relaxed);
}

// ─── Effects chain dispatch ───────────────────────────────────────────────────
void AudioEngine::processEffectsChain(float* buf, int32_t frames) {
    dsp_inputCleanup(buf, frames);

    if (params_.gateEnabled.load(std::memory_order_relaxed))
        dsp_noiseGate(buf, frames);

    // Check if EQ gains changed (cheap comparison)
    for (int i = 0; i < kEqBands; i++) {
        if (params_.eqGain[i].load(std::memory_order_relaxed) != eqGainCache_[i]) {
            eqDirty_ = true;
            break;
        }
    }
    if (eqDirty_) rebuildEQ();
    if (params_.eqEnabled.load(std::memory_order_relaxed))
        dsp_eq(buf, frames);

    if (params_.vocalShineAmount.load(std::memory_order_relaxed) > 0.001f)
        dsp_vocalShine(buf, frames);

    if (params_.compEnabled.load(std::memory_order_relaxed))
        dsp_compressor(buf, frames);

    if (params_.reverbEnabled.load(std::memory_order_relaxed))
        dsp_reverb(buf, frames);

    dsp_pitchShift(buf, frames);
}

void AudioEngine::dsp_pitchShift(float* buf, int32_t frames) {
    const bool enabled = params_.pitchEnabled.load(std::memory_order_relaxed);
    if (!enabled) {
        if (pitchWasEnabled_) {
            pitchShifter_.clear();
            pitchFifoHead_ = 0;
            pitchFifoTail_ = 0;
            pitchSemitoneCache_ = 0.0f;
            pitchWasEnabled_ = false;
        }
        return; // Transparent pass-through while pitch shifting is disabled.
    }

    const float targetSemitones = std::clamp(
        params_.pitchSemitones.load(std::memory_order_relaxed), -4.0f, 4.0f);
    if (!pitchWasEnabled_) {
        pitchShifter_.clear();
        pitchFifoHead_ = 0;
        pitchFifoTail_ = 0;
        pitchWasEnabled_ = true;
        pitchConfigValid_ = false;
    }

    if (!pitchConfigValid_ || std::abs(targetSemitones - pitchSemitoneCache_) > 0.001f) {
        pitchShifter_.setPitchSemiTones(static_cast<double>(targetSemitones));
        pitchSemitoneCache_ = targetSemitones;
        pitchConfigValid_ = true;
    }

    // SoundTouch is a buffered FIFO processor: a putSamples() call does not promise
    // an equal number of immediately available output samples. Drain every ready
    // sample into this fixed-capacity FIFO, then emit only complete audio blocks.
    // This deliberately introduces the library's initial latency instead of
    // replacing missing samples with zeros and permanently losing speech.
    pitchShifter_.putSamples(buf, static_cast<uint>(frames));

    while (pitchShifter_.numSamples() > 0) {
        const size_t queued = pitchFifoHead_ - pitchFifoTail_;
        const size_t free = kPitchFifoCapacity - queued;
        if (free == 0) break;

        const size_t request = std::min({
            free,
            pitchScratch_.size(),
            static_cast<size_t>(pitchShifter_.numSamples())
        });
        if (request == 0) break;

        const uint received = pitchShifter_.receiveSamples(
            pitchScratch_.data(), static_cast<uint>(request));
        if (received == 0) break;

        for (uint i = 0; i < received; ++i) {
            pitchFifo_[(pitchFifoHead_ + i) & (kPitchFifoCapacity - 1)] =
                pitchScratch_[i];
        }
        pitchFifoHead_ += received;
    }

    if (pitchFifoHead_ - pitchFifoTail_ < static_cast<size_t>(frames)) {
        // Keep queued samples intact. Once SoundTouch has produced a full block,
        // normal one-block-in/one-block-out streaming continues without data loss.
        std::fill(buf, buf + frames, 0.0f);
        return;
    }

    for (int32_t i = 0; i < frames; ++i) {
        buf[i] = pitchFifo_[(pitchFifoTail_ + static_cast<size_t>(i)) &
                            (kPitchFifoCapacity - 1)];
    }
    pitchFifoTail_ += static_cast<size_t>(frames);
}

// ─── Input cleanup: low-frequency handling rumble + isolated digital clicks ─
void AudioEngine::dsp_inputCleanup(float* buf, int32_t frames) {
    if (frames <= 0) return;

    // Replace only a very narrow, isolated full-scale impulse whose two neighbors
    // agree. This avoids smoothing ordinary consonant attacks or voiced transients.
    float previous = cleanupPrevSample_;
    for (int i = 0; i < frames; ++i) {
        const float current = buf[i];
        const float next = (i + 1 < frames) ? buf[i + 1] : current;
        const bool isolatedClick =
            std::fabs(current) > 0.72f &&
            std::fabs(current - previous) > 0.58f &&
            std::fabs(current - next) > 0.58f &&
            std::fabs(previous - next) < 0.12f;
        const float cleaned = isolatedClick ? 0.5f * (previous + next) : current;
        buf[i] = cleaned;
        previous = cleaned;
    }
    cleanupPrevSample_ = buf[frames - 1];

    // First-order 72 Hz high-pass: reduces handling/footstep thumps and DC rumble
    // without aggressively thinning the lower fundamentals of a male voice.
    for (int i = 0; i < frames; ++i) {
        const float input = buf[i];
        const float output = highpassAlpha_ *
            (highpassPrevOutput_ + input - highpassPrevInput_);
        highpassPrevInput_ = input;
        highpassPrevOutput_ = output;
        buf[i] = output;
    }
}

// ─── Noise Gate ──────────────────────────────────────────────────────────────
void AudioEngine::dsp_noiseGate(float* buf, int32_t frames) {
    float threshLin  = dBToLin(params_.gateThresholdDb.load(std::memory_order_relaxed));
    float attackCoef = 1.0f - std::exp(-1.0f / (params_.gateAttackMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));
    float releaseCoef= 1.0f - std::exp(-1.0f / (params_.gateReleaseMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));

    for (int i = 0; i < frames; i++) {
        // RMS with single-pole smoothing
        float x2 = buf[i] * buf[i];
        gateRms_ = gateRms_ * 0.995f + x2 * 0.005f;
        float rms = std::sqrt(gateRms_);

        float target = (rms > threshLin) ? 1.0f : 0.0f;
        float coef   = (target > gateEnv_) ? attackCoef : releaseCoef;
        gateEnv_    += coef * (target - gateEnv_);
        buf[i]      *= gateEnv_;
    }
}

// ─── Vocal shine: restrained upper-harmonic warmth + adaptive de-essing ───────
// This is a light timbral enhancer, not pitch correction or AI voice conversion.
// It leaves the fundamental and lower vocal body untouched.
void AudioEngine::dsp_vocalShine(float* buf, int32_t frames) {
    if (frames <= 0) return;

    const float amount = std::clamp(
        params_.vocalShineAmount.load(std::memory_order_relaxed), 0.0f, 1.0f);
    if (amount <= 0.001f) {
        vocalUpperLowpass_ = 0.0f;
        vocalAirLowpass_ = 0.0f;
        vocalSibilanceEnvelope_ = 0.0f;
        return;
    }

    // Separate the upper vocal band so the exciter does not distort the bass/fundamental.
    const float upperAlpha = std::exp(
        -2.0f * static_cast<float>(M_PI) * 650.0f / static_cast<float>(sampleRate_));
    // Detect only the sharp sibilant region; preserve normal vowel brightness.
    const float airAlpha = std::exp(
        -2.0f * static_cast<float>(M_PI) * 5200.0f / static_cast<float>(sampleRate_));
    const float attack = 1.0f - std::exp(-1.0f / (0.002f * sampleRate_));
    const float release = 1.0f - std::exp(-1.0f / (0.055f * sampleRate_));

    constexpr float kDrive = 1.65f;
    const float normalization = std::tanh(kDrive);
    const float harmonicBlend = 0.14f * amount;
    constexpr float kSibilanceThreshold = 0.045f;

    for (int i = 0; i < frames; ++i) {
        const float input = buf[i];

        vocalUpperLowpass_ =
            upperAlpha * vocalUpperLowpass_ + (1.0f - upperAlpha) * input;
        const float upperBand = input - vocalUpperLowpass_;

        const float saturated = std::tanh(kDrive * upperBand) / normalization;
        float output = input + (saturated - upperBand) * harmonicBlend;

        vocalAirLowpass_ =
            airAlpha * vocalAirLowpass_ + (1.0f - airAlpha) * output;
        const float sibilantBand = output - vocalAirLowpass_;
        const float sibilantLevel = std::fabs(sibilantBand);
        const float detectorCoef =
            (sibilantLevel > vocalSibilanceEnvelope_) ? attack : release;
        vocalSibilanceEnvelope_ +=
            detectorCoef * (sibilantLevel - vocalSibilanceEnvelope_);

        const float excess = std::max(
            0.0f, vocalSibilanceEnvelope_ - kSibilanceThreshold);
        const float reduction = std::clamp(excess * 4.0f, 0.0f, 0.45f) * amount;
        output -= sibilantBand * reduction;

        // Headroom guard; the following compressor remains responsible for level control.
        buf[i] = std::clamp(output, -0.985f, 0.985f);
    }
}

// ─── 10-band EQ ──────────────────────────────────────────────────────────────
void AudioEngine::dsp_eq(float* buf, int32_t frames) {
    for (int i = 0; i < frames; i++) {
        float x = buf[i];
        for (int b = 0; b < kEqBands; b++) {
            x = eqFilters_[b].process(x);
        }
        buf[i] = x;
    }
}

// ─── Feed-forward RMS Compressor with soft knee ──────────────────────────────
void AudioEngine::dsp_compressor(float* buf, int32_t frames) {
    float threshold = params_.compThresholdDb.load(std::memory_order_relaxed);
    float ratio     = params_.compRatio.load(std::memory_order_relaxed);
    float makeup    = dBToLin(params_.compMakeupDb.load(std::memory_order_relaxed));
    float attackCoef = 1.0f - std::exp(-1.0f / (params_.compAttackMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));
    float releaseCoef= 1.0f - std::exp(-1.0f / (params_.compReleaseMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));
    static constexpr float kKneeWidth = 6.0f; // dB

    float totalGR = 0.0f;
    for (int i = 0; i < frames; i++) {
        // RMS envelope
        compEnv_ = compEnv_ * (1.0f - attackCoef) + (buf[i]*buf[i]) * attackCoef;
        float levelDb = linTodB(std::sqrt(compEnv_) + 1e-10f);

        // Soft-knee gain computation
        float grDb = 0.0f;
        float over = levelDb - threshold;
        if (over > kKneeWidth * 0.5f) {
            grDb = (over) * (1.0f - 1.0f/ratio);
        } else if (over > -kKneeWidth * 0.5f) {
            float t = (over + kKneeWidth * 0.5f) / kKneeWidth;
            grDb = t * t * (over) * (1.0f - 1.0f/ratio);
        }
        float targetGain = dBToLin(-grDb) * makeup;

        // Smooth gain application
        float coef = (targetGain < 1.0f) ? attackCoef : releaseCoef;
        compGainReduction_ += coef * (targetGain - compGainReduction_);
        buf[i] *= compGainReduction_;
        totalGR += grDb;
    }
    params_.gainReductionDb.store(totalGR / frames, std::memory_order_relaxed);
}

// ─── Schroeder Reverb (4 comb + 2 allpass) ───────────────────────────────────
void AudioEngine::dsp_reverb(float* buf, int32_t frames) {
    float mix     = params_.reverbMix.load(std::memory_order_relaxed);
    float room    = params_.reverbRoomSize.load(std::memory_order_relaxed);
    float damping = params_.reverbDamping.load(std::memory_order_relaxed);

    for (int b = 0; b < 4; b++) {
        combs_[b].feedback = 0.7f + room * 0.27f;
        combs_[b].damp     = damping * 0.4f;
    }

    for (int i = 0; i < frames; i++) {
        float dry = buf[i];
        float wet = 0.0f;
        for (auto& c : combs_)  wet += c.process(dry * 0.015f);
        for (auto& a : aps_)    wet  = a.process(wet);
        buf[i] = dry * (1.0f - mix) + wet * mix;
    }
}

// Pitch shifting is handled by the speech-tuned SoundTouch streaming stage
// in processEffectsChain(), not by a custom delay-line approximation.

} // namespace micplugin

// ─────────────────────────────────────────────────────────────────────────────
// JNI Bindings
// ─────────────────────────────────────────────────────────────────────────────
using namespace micplugin;

static AudioEngine* gEngine    = nullptr;
static PluginHost*  gPluginHost = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_micplugin_audio_OboeEngine_nativeCreate(JNIEnv*, jobject) {
    auto* engine = new AudioEngine();
    auto* host   = new PluginHost();
    engine->setPluginHost(host);
    gEngine    = engine;
    gPluginHost = host;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    delete engine;
    delete gPluginHost;
    gEngine     = nullptr;
    gPluginHost = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_micplugin_audio_OboeEngine_nativeStart(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    return engine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeStop(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    engine->stop();
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetParam(
    JNIEnv*, jobject, jlong handle, jint effectId, jint paramId, jfloat value) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    auto& p = engine->params();
    switch (effectId) {
        case 0: // Gate
            if (paramId==0) p.gateThresholdDb.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.gateAttackMs.store(value, std::memory_order_relaxed);
            else if (paramId==2) p.gateReleaseMs.store(value, std::memory_order_relaxed);
            else if (paramId==3) p.gateEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 1: // EQ
            if (paramId >= 0 && paramId < 10)
                p.eqGain[paramId].store(value, std::memory_order_relaxed);
            else if (paramId == 10) p.eqEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 2: // Compressor
            if (paramId==0) p.compThresholdDb.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.compRatio.store(value, std::memory_order_relaxed);
            else if (paramId==2) p.compAttackMs.store(value, std::memory_order_relaxed);
            else if (paramId==3) p.compReleaseMs.store(value, std::memory_order_relaxed);
            else if (paramId==4) p.compMakeupDb.store(value, std::memory_order_relaxed);
            else if (paramId==5) p.compEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 3: // Reverb
            if (paramId==0) p.reverbMix.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.reverbRoomSize.store(value, std::memory_order_relaxed);
            else if (paramId==2) p.reverbDamping.store(value, std::memory_order_relaxed);
            else if (paramId==3) p.reverbEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 4: // Pitch
            if (paramId==0) p.pitchSemitones.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.pitchEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 5: // Vocal Shine intensity [0, 1]
            if (paramId == 0)
                p.vocalShineAmount.store(std::clamp(value, 0.0f, 1.0f),
                                         std::memory_order_relaxed);
            break;
        case 99: // Master bypass
            p.masterBypass.store(value > 0.5f, std::memory_order_relaxed);
            break;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetLevels(JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    auto& p = engine->params();
    float levels[3] = {
        p.inputLevelDb.load(std::memory_order_relaxed),
        p.outputLevelDb.load(std::memory_order_relaxed),
        p.gainReductionDb.load(std::memory_order_relaxed)
    };
    jfloatArray arr = env->NewFloatArray(3);
    env->SetFloatArrayRegion(arr, 0, 3, levels);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_micplugin_audio_OboeEngine_nativeLoadPlugin(
    JNIEnv* env, jobject, jlong handle, jstring soPath, jint formatId) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    if (!gPluginHost) return 0;
    const char* path = env->GetStringUTFChars(soPath, nullptr);
    jlong result = gPluginHost->loadPlugin(path, formatId, engine->sampleRate());
    env->ReleaseStringUTFChars(soPath, path);
    return result;
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeUnloadPlugin(
    JNIEnv*, jobject, jlong handle, jlong pluginHandle) {
    if (gPluginHost) gPluginHost->unloadPlugin(pluginHandle);
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetPluginParam(
    JNIEnv*, jobject, jlong handle, jlong pluginHandle, jint paramId, jfloat value) {
    if (gPluginHost) gPluginHost->setPluginParam(pluginHandle, paramId, value);
}

JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetSampleRate(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle)->sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetFramesPerBurst(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle)->framesPerBurst();
}

JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeReadProcessedAudio(
    JNIEnv* env, jobject, jlong handle, jfloatArray destination, jint maxFrames) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    if (engine == nullptr || destination == nullptr || maxFrames <= 0) return 0;

    const jsize arrayLength = env->GetArrayLength(destination);
    const jint boundedFrames = std::min({maxFrames, static_cast<jint>(arrayLength), 1024});
    if (boundedFrames <= 0) return 0;

    float samples[1024];
    const int32_t framesRead = engine->readProcessedAudio(samples, boundedFrames);
    if (framesRead > 0) {
        env->SetFloatArrayRegion(destination, 0, framesRead, samples);
    }
    return static_cast<jint>(framesRead);
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetInputDevice(
    JNIEnv*, jobject, jlong handle, jint deviceId) {
    auto* engine = reinterpret_cast<micplugin::AudioEngine*>(handle);
    if (engine) engine->setInputDeviceId(deviceId);
    LOGI("Input device set to %d", deviceId);
}


JNIEXPORT jstring JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetPluginParams(
    JNIEnv* env, jobject, jlong /*engineHandle*/, jlong pluginHandle) {
    if (!gPluginHost) return env->NewStringUTF("[]");
    auto inst = gPluginHost->findPlugin(pluginHandle);
    if (!inst) return env->NewStringUTF("[]");

    std::string json;
    switch (inst->format) {
        case PluginFormat::CLAP: json = ClapHost::getParams(inst); break;
        case PluginFormat::LV2:  json = Lv2Host::getParams(inst);  break;
        case PluginFormat::VST3: json = Vst3Bridge::getParams(inst); break;
        default: json = "[]"; break;
    }
    return env->NewStringUTF(json.c_str());
}



JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetInputSessionId(
    JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<micplugin::AudioEngine*>(handle);
    return engine ? engine->getInputSessionId() : -1;
}


} // extern "C"
