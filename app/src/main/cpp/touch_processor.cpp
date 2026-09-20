#include "touch_processor.h"

#include <algorithm>
#include <chrono>
#include <android/log.h>

#define TAG "TabletHidDigitizer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

TouchProcessor::TouchProcessor() = default;

TouchProcessor::~TouchProcessor() {
    stop();
}

void TouchProcessor::setMapping(const MappingConfig& config) {
    std::lock_guard<std::mutex> lock(mapMutex_);
    config_ = config;
    if (config_.scaleX < 1.0f) config_.scaleX = 1.0f;
    if (config_.scaleY < 1.0f) config_.scaleY = 1.0f;
    if (config_.screenW < 1)   config_.screenW = 1;
    if (config_.screenH < 1)   config_.screenH = 1;
    LOGI("Mapping updated: region (%.1f,%.1f) %.1fx%.1f  screen=%dx%d",
         config_.offsetX, config_.offsetY, config_.scaleX, config_.scaleY,
         config_.screenW, config_.screenH);
}

void TouchProcessor::mapCoordinate(float touchX, float touchY,
                                  uint16_t& outX, uint16_t& outY) {
    MappingConfig cfg;
    {
        std::lock_guard<std::mutex> lock(mapMutex_);
        cfg = config_;
    }

    float nx = (touchX - cfg.offsetX) / cfg.scaleX;
    float ny = (touchY - cfg.offsetY) / cfg.scaleY;

    nx = std::clamp(nx, 0.0f, 1.0f);
    ny = std::clamp(ny, 0.0f, 1.0f);

    outX = static_cast<uint16_t>(nx * HID_MAX_COORD);
    outY = static_cast<uint16_t>(ny * HID_MAX_COORD);
}

void TouchProcessor::enqueueLocked(const TouchPoint& pt) {
    const bool tip = (pt.status & 0x01) != 0;

    if (!queue_.empty()) {
        TouchPoint& last = queue_.back();
        const bool lastTip = (last.status & 0x01) != 0;

        if (lastTip == tip) {
            // Same tip state → coalesce to the newest sample.
            last = pt;
            return;
        }
        // Tip edge: keep the transition. Still coalesce any trailing
        // same-state samples so a burst of MOVE after DOWN doesn't
        // bloat the queue.
    }

    queue_.push_back(pt);

    // Drop oldest same-state samples in the middle; never drop a tip edge.
    while (queue_.size() > kMaxQueued) {
        // Prefer collapsing from the front if the first two share tip state.
        if (queue_.size() >= 2 &&
            ((queue_[0].status ^ queue_[1].status) & 0x01) == 0) {
            queue_.pop_front();
        } else if (queue_.size() >= 2 &&
                   ((queue_[queue_.size() - 2].status ^ queue_.back().status) & 0x01) == 0) {
            queue_.erase(queue_.end() - 2);
        } else {
            // Only tip edges left — keep them all up to a hard cap.
            break;
        }
    }
}

bool TouchProcessor::popCoalesced(TouchPoint& out) {
    std::lock_guard<std::mutex> lock(queueMutex_);
    if (queue_.empty()) return false;

    // Take the oldest entry (must be flushed — often a tip edge),
    // but if several consecutive same-tip samples piled up, only
    // emit the last of that run.
    size_t end = 1;
    const uint8_t tipBit = queue_[0].status & 0x01;
    while (end < queue_.size() && (queue_[end].status & 0x01) == tipBit) {
        ++end;
    }
    out = queue_[end - 1];
    queue_.erase(queue_.begin(), queue_.begin() + static_cast<long>(end));
    return true;
}

void TouchProcessor::pushPoint(float touchX, float touchY, uint8_t status, float pressure) {
    // Map pressure: MotionEvent.getPressure() is typically 0..1.
    //   tip=0            → pressure 0 (hover / pen up)
    //   tip=1, p>0       → scaled pressure
    //   tip=1, p==0      → 0 (in-range hover). Do NOT default to max —
    //                      that turned every hover packet into full-pressure
    //                      tip-down and broke host-side hover handling.
    //   tip=1, p<0       → max (sentinel: touch without pressure data)
    uint16_t hidPressure = 0;
    if (status & 0x01) {
        if (pressure > 0.0f) {
            hidPressure = static_cast<uint16_t>(
                std::clamp(pressure, 0.0f, 1.0f) * HID_MAX_PRESSURE);
        } else if (pressure < 0.0f) {
            hidPressure = HID_MAX_PRESSURE;
        } else {
            hidPressure = 0;
        }
    }

    uint16_t hidX = 0, hidY = 0;
    mapCoordinate(touchX, touchY, hidX, hidY);

    TouchPoint pt{};
    pt.x        = hidX;
    pt.y        = hidY;
    pt.status   = status;
    pt.pressure = hidPressure;

    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        enqueueLocked(pt);
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        notified_.store(true, std::memory_order_release);
    }
    cv_.notify_one();
}

void TouchProcessor::start(HidDriver* driver) {
    if (running_.load()) return;
    driver_ = driver;
    running_.store(true, std::memory_order_release);
    writerThread_ = std::thread(&TouchProcessor::writerLoop, this);
    LOGI("Writer thread started");
}

void TouchProcessor::stop() {
    if (!running_.load()) return;
    running_.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        notified_.store(true, std::memory_order_release);
    }
    cv_.notify_all();
    if (writerThread_.joinable()) {
        writerThread_.join();
    }
    driver_ = nullptr;
    LOGI("Writer thread stopped");
}

bool TouchProcessor::writeWithRetry(const TouchPoint& pt) {
    if (!driver_ || !driver_->isOpen()) return false;

    // EAGAIN on /dev/hidg* means the previous IN report has not been
    // consumed yet. Dropping the packet makes OTD's cursor jump between
    // the last successful sample and the next one — amplitude grows with
    // distance from the mapping origin. Retry instead of drop.
    constexpr int kMaxAttempts = 8;
    for (int attempt = 0; attempt < kMaxAttempts; ++attempt) {
        if (driver_->writeReport(pt.x, pt.y, pt.status, pt.pressure)) {
            return true;
        }
        if (!running_.load(std::memory_order_acquire)) {
            return false;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(250));
    }
    LOGE("writeReport dropped after retries: x=%u y=%u st=0x%02x p=%u",
         pt.x, pt.y, pt.status, pt.pressure);
    return false;
}

void TouchProcessor::writerLoop() {
    uint16_t lastX = 0, lastY = 0, lastPressure = 0;
    uint8_t lastStatus = 0;
    bool hasLast = false;

    while (running_.load(std::memory_order_acquire)) {
        TouchPoint pt{};
        bool got = popCoalesced(pt);

        if (got) {
            // Skip only exact duplicates. Any tip/pressure/position change
            // must reach the host — hover packets are tip/in-range updates.
            const bool identical = hasLast &&
                pt.x == lastX && pt.y == lastY &&
                pt.status == lastStatus && pt.pressure == lastPressure;
            if (!identical) {
                if (writeWithRetry(pt)) {
                    lastX = pt.x;
                    lastY = pt.y;
                    lastStatus = pt.status;
                    lastPressure = pt.pressure;
                    hasLast = true;
                } else if (running_.load(std::memory_order_acquire)) {
                    std::lock_guard<std::mutex> lock(queueMutex_);
                    if (queue_.size() < kMaxQueued) {
                        queue_.push_front(pt);
                    }
                }
            }
            continue;
        }

        std::unique_lock<std::mutex> lock(mutex_);
        cv_.wait_for(lock, std::chrono::milliseconds(4), [this] {
            return notified_.exchange(false, std::memory_order_acq_rel) ||
                   !running_.load(std::memory_order_acquire);
        });
        notified_.store(false, std::memory_order_release);
    }

    // Final drain: tip-up at the last known position (not 0,0).
    if (driver_ && driver_->isOpen()) {
        if (hasLast) {
            driver_->writeReport(lastX, lastY, 0x00, 0);
        } else {
            driver_->writeReport(0, 0, 0x00, 0);
        }
    }
}
