// Gate A: an Oboe output stream whose data callback pulls from the Rust mixer through its
// plain C export (`hushhour_mixer_render`, inkpad-ipados HushHourCore/src/ffi.rs), outside
// UniFFI. The mixer runs at 44.1 kHz stereo float; Oboe resamples to the device rate.

#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

extern "C" void hushhour_mixer_render(float *out, uint32_t frames);

namespace {

constexpr const char *kTag = "HushHourAudio";
constexpr int32_t kMixerRate = 44100;  // hushhour_core::dsp::RATE
constexpr int32_t kChannels = 2;       // mixer::CHANNELS
// mixer::MAX_BLOCK: the mixer's scratch buffer is sized for this, so a larger pull would
// allocate on the audio thread. Oboe may ask for more (large bursts, resampler priming).
constexpr int32_t kMaxBlock = 4096;
constexpr int kReportSize = 7;

class Player : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool start() {
        std::lock_guard<std::mutex> lock(mutex_);
        wanted_ = true;
        return openAndStart();
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        wanted_ = false;
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *data, int32_t frames) override {
        auto *out = static_cast<float *>(data);
        for (int32_t done = 0; done < frames;) {
            const int32_t n = std::min(frames - done, kMaxBlock);
            hushhour_mixer_render(out + done * kChannels, static_cast<uint32_t>(n));
            done += n;
        }
        for (int32_t i = 0; i < frames * kChannels; ++i) {
            window_.peak = std::max(window_.peak, std::fabs(out[i]));
            window_.sumSquares += double(out[i]) * out[i];
        }
        window_.frames += frames;
        window_.maxCallback = std::max(window_.maxCallback, frames);
        // Hand the window over once the reader has taken the previous one (see report()).
        if (!ready_.load(std::memory_order_acquire)) {
            published_ = window_;
            window_ = Meter{};
            ready_.store(true, std::memory_order_release);
        }
        return oboe::DataCallbackResult::Continue;
    }

    // A disconnect (headphones, Bluetooth, route change) closes the stream; reopen it on the
    // new device. Oboe calls this off the audio thread, where opening a stream is allowed.
    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override {
        __android_log_print(ANDROID_LOG_WARN, kTag, "stream closed: %s", oboe::convertToText(error));
        std::lock_guard<std::mutex> lock(mutex_);
        stream_.reset();
        if (wanted_) openAndStart();
    }

    // Stream facts and the latest meter window: [stream rate, frames per burst, max frames per
    // callback, frames rendered, peak, rms, audio api (0 unspecified, 1 OpenSL ES, 2 AAudio)].
    // The callback owns `window_` and fills `published_` only while `ready_` is false; the
    // reader touches `published_` only while it is true. So each window is counted once, and
    // the windows follow one another with no gap. Zeros when no new window has been published.
    void report(double out[kReportSize]) {
        std::lock_guard<std::mutex> lock(mutex_);
        Meter m;
        if (ready_.load(std::memory_order_acquire)) {
            m = published_;
            ready_.store(false, std::memory_order_release);
        }
        const int64_t frames = m.frames;
        const double sum = m.sumSquares;
        out[0] = stream_ ? stream_->getSampleRate() : 0;
        out[1] = stream_ ? stream_->getFramesPerBurst() : 0;
        out[2] = m.maxCallback;
        out[3] = double(frames);
        out[4] = m.peak;
        out[5] = frames > 0 ? std::sqrt(sum / double(frames * kChannels)) : 0.0;
        out[6] = stream_ ? int(stream_->getAudioApi()) : 0;
    }

private:
    bool openAndStart() {
        if (stream_) return true;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(kChannels)
            ->setSampleRate(kMixerRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            // Media playback; no audio-focus request (plan decision).
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "open failed: %s", oboe::convertToText(result));
            stream_.reset();
            return false;
        }
        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "start failed: %s", oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            return false;
        }
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "started: api=%s rate=%d channels=%d format=%s burst=%d sharing=%s perf=%s",
                            oboe::convertToText(stream_->getAudioApi()), stream_->getSampleRate(),
                            stream_->getChannelCount(), oboe::convertToText(stream_->getFormat()),
                            stream_->getFramesPerBurst(), oboe::convertToText(stream_->getSharingMode()),
                            oboe::convertToText(stream_->getPerformanceMode()));
        return true;
    }

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    bool wanted_ = false;
    struct Meter {
        int64_t frames = 0;
        double sumSquares = 0.0;
        float peak = 0.0f;
        int32_t maxCallback = 0;
    };
    Meter window_;     // audio thread only
    Meter published_;  // see report()
    std::atomic<bool> ready_{false};
};

Player player;

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fiftyhoursaweek_slowpage_NativeAudio_start(JNIEnv *, jobject) {
    return player.start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fiftyhoursaweek_slowpage_NativeAudio_stop(JNIEnv *, jobject) {
    player.stop();
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_fiftyhoursaweek_slowpage_NativeAudio_report(JNIEnv *env, jobject) {
    double values[kReportSize];
    player.report(values);
    jdoubleArray array = env->NewDoubleArray(kReportSize);
    env->SetDoubleArrayRegion(array, 0, kReportSize, values);
    return array;
}
