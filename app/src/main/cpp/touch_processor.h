#ifndef TOUCH_PROCESSOR_H
#define TOUCH_PROCESSOR_H

#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include "hid_driver.h"

// Mapping configuration: a rectangular sub-region of the screen is mapped
// to the full 0..32767 digitizer coordinate space.
struct MappingConfig {
    float scaleX  = 1.0f;
    float scaleY  = 1.0f;
    float offsetX = 0.0f;
    float offsetY = 0.0f;
    int   screenW = 1;
    int   screenH = 1;
};

// Touch point — already mapped to HID coordinates.
struct TouchPoint {
    uint16_t x = 0;
    uint16_t y = 0;
    uint8_t  status = 0;     // bit0=tip, bit1=barrel, bit2=eraser
    uint16_t pressure = 0;   // 0..8191
};

class TouchProcessor {
public:
    static constexpr uint16_t HID_MAX_COORD = 32767;
    static constexpr uint16_t HID_MAX_PRESSURE = 8191;
    // Tiny queue: we only need tip edges + latest position.
    static constexpr size_t kMaxQueued = 8;

    TouchProcessor();
    ~TouchProcessor();

    void setMapping(const MappingConfig& config);

    // Push a raw touch coordinate (in screen/region pixels) with pressure
    // and status. Non-blocking. Safe to call from the UI thread.
    void pushPoint(float touchX, float touchY, uint8_t status, float pressure);

    void start(HidDriver* driver);
    void stop();

private:
    void mapCoordinate(float touchX, float touchY, uint16_t& outX, uint16_t& outY);
    void enqueueLocked(const TouchPoint& pt);
    bool popCoalesced(TouchPoint& out);
    void writerLoop();
    bool writeWithRetry(const TouchPoint& pt);

    HidDriver* driver_ = nullptr;
    std::atomic<bool> running_{false};
    std::thread writerThread_;

    // Mutex-protected coalescing queue. Replaces the old SPSC ring whose
    // producer-modifies-tail overflow path raced with the consumer and
    // could tear coordinates (flicker that grew with |position|).
    std::mutex queueMutex_;
    std::deque<TouchPoint> queue_;

    std::mutex mutex_;
    std::condition_variable cv_;
    std::atomic<bool> notified_{false};

    std::mutex mapMutex_;
    MappingConfig config_{};
};

#endif // TOUCH_PROCESSOR_H
