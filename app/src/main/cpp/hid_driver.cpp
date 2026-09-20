#include "hid_driver.h"

#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <chrono>
#include <thread>
#include <android/log.h>

#define TAG "TabletHidDigitizer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// HID Report Descriptor — Digitizer (0x0D) for OTD TabletReportParser.
static const unsigned char kReportDescriptor[] = {
    0x05, 0x0D,        // USAGE_PAGE (Digitizer)
    0x09, 0x02,        // USAGE (Pen)
    0xA1, 0x01,        // COLLECTION (Application)
      0x09, 0x42,      //   USAGE (Tip Switch / In Range)
      0x09, 0x44,      //   USAGE (Barrel Switch)
      0x09, 0x45,      //   USAGE (Eraser)
      0x15, 0x00,      //   LOGICAL_MINIMUM (0)
      0x25, 0x01,      //   LOGICAL_MAXIMUM (1)
      0x75, 0x01,      //   REPORT_SIZE (1)
      0x95, 0x03,      //   REPORT_COUNT (3)
      0x81, 0x02,      //   INPUT (Data,Var,Abs)
      0x75, 0x01,      //   REPORT_SIZE (1)
      0x95, 0x05,      //   REPORT_COUNT (5)
      0x81, 0x03,      //   INPUT (Const)
      0x05, 0x01,      //   USAGE_PAGE (Generic Desktop)
      0x09, 0x30,      //   USAGE (X)
      0x15, 0x00,      //   LOGICAL_MINIMUM (0)
      0x26, 0xFF, 0x7F,//   LOGICAL_MAXIMUM (32767)
      0x75, 0x10,      //   REPORT_SIZE (16)
      0x95, 0x01,      //   REPORT_COUNT (1)
      0x81, 0x02,      //   INPUT (Data,Var,Abs)
      0x09, 0x31,      //   USAGE (Y)
      0x81, 0x02,      //   INPUT (Data,Var,Abs)
      0x05, 0x0D,      //   USAGE_PAGE (Digitizer)
      0x09, 0x30,      //   USAGE (Tip Pressure)
      0x15, 0x00,      //   LOGICAL_MINIMUM (0)
      0x26, 0xFF, 0x1F,//   LOGICAL_MAXIMUM (8191)
      0x75, 0x10,      //   REPORT_SIZE (16)
      0x95, 0x01,      //   REPORT_COUNT (1)
      0x81, 0x02,      //   INPUT (Data,Var,Abs)
      0x75, 0x08,      //   REPORT_SIZE (8)
      0x95, 0x01,      //   REPORT_COUNT (1)
      0x81, 0x03,      //   INPUT (Const)
    0xC0               // END_COLLECTION
};

// Standard keyboard HID report descriptor.
static const unsigned char kKeyboardDescriptor[] = {
    0x05, 0x01,        // USAGE_PAGE (Generic Desktop)
    0x09, 0x06,        // USAGE (Keyboard)
    0xA1, 0x01,        // COLLECTION (Application)
      0x05, 0x07,      //   USAGE_PAGE (Keyboard/Keypad)
      0x19, 0xE0,      //   USAGE_MINIMUM (Left Control)
      0x29, 0xE7,      //   USAGE_MAXIMUM (Right GUI)
      0x15, 0x00,      //   LOGICAL_MINIMUM (0)
      0x25, 0x01,      //   LOGICAL_MAXIMUM (1)
      0x75, 0x01,      //   REPORT_SIZE (1)
      0x95, 0x08,      //   REPORT_COUNT (8)
      0x81, 0x02,      //   INPUT (Data,Var,Abs) — modifier bits
      0x95, 0x01,      //   REPORT_COUNT (1)
      0x75, 0x08,      //   REPORT_SIZE (8)
      0x81, 0x03,      //   INPUT (Const) — reserved
      0x95, 0x06,      //   REPORT_COUNT (6)
      0x75, 0x08,      //   REPORT_SIZE (8)
      0x15, 0x00,      //   LOGICAL_MINIMUM (0)
      0x25, 0x91,      //   LOGICAL_MAXIMUM (145)
      0x05, 0x07,      //   USAGE_PAGE (Keyboard/Keypad)
      0x19, 0x00,      //   USAGE_MINIMUM (0)
      0x29, 0x91,      //   USAGE_MAXIMUM (145)
      0x81, 0x00,      //   INPUT (Data,Array)
    0xC0               // END_COLLECTION
};

bool HidDriver::open(const char* path) {
    if (fd_ >= 0) return true;
    // O_NONBLOCK: writer thread retries EAGAIN instead of stalling the
    // whole digitizer pipeline when the host is slow to consume reports.
    fd_ = ::open(path, O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd_ < 0) {
        LOGE("open(%s) failed: errno=%d (%s)", path, errno, strerror(errno));
        return false;
    }
    LOGI("Opened digitizer HID %s (fd=%d)", path, fd_);
    return true;
}

bool HidDriver::openKeyboard(const char* path) {
    if (kbFd_ >= 0) return true;
    kbFd_ = ::open(path, O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (kbFd_ < 0) {
        LOGE("open keyboard(%s) failed: errno=%d (%s)", path, errno, strerror(errno));
        return false;
    }
    LOGI("Opened keyboard HID %s (fd=%d)", path, kbFd_);
    return true;
}

void HidDriver::close() {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
        LOGI("Closed digitizer HID");
    }
}

void HidDriver::closeKeyboard() {
    if (kbFd_ >= 0) {
        releaseKeys();
        ::close(kbFd_);
        kbFd_ = -1;
        LOGI("Closed keyboard HID");
    }
}

bool HidDriver::isOpen() const { return fd_ >= 0; }
bool HidDriver::isKeyboardOpen() const { return kbFd_ >= 0; }

bool HidDriver::writeReport(uint16_t x, uint16_t y, uint8_t status, uint16_t pressure) {
    if (fd_ < 0) return false;

    // Keep the layout that this project's OTD setup already tracks correctly:
    // byte0=status (tip=bit0), X@1-2, Y@3-4, pressure@5-6.
    // Do not shift fields — a "correct on paper" OTD 0.6 layout made the
    // cursor fly on the user's host parser.
    OtdReport report{};
    report.status   = status;
    report.x        = x;
    report.y        = y;
    report.pressure = pressure;
    report.reserved = 0;

    ssize_t written = ::write(fd_, &report, sizeof(report));
    if (written != sizeof(report)) {
        if (errno != EAGAIN && errno != EWOULDBLOCK)
            LOGE("digitizer write: written=%zd errno=%d (%s)",
                 written, errno, strerror(errno));
        return false;
    }
    return true;
}

bool HidDriver::writeKeyboard(uint8_t modifier, const uint8_t* keycodes, int count) {
    if (kbFd_ < 0) return false;
    KeyboardReport rpt{};
    rpt.modifier = modifier;
    for (int i = 0; i < count && i < 6; i++)
        rpt.keycodes[i] = keycodes[i];

    for (int attempt = 0; attempt < 4; ++attempt) {
        ssize_t written = ::write(kbFd_, &rpt, sizeof(rpt));
        if (written == sizeof(rpt)) return true;
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            LOGE("keyboard write: written=%zd errno=%d (%s)",
                 written, errno, strerror(errno));
            return false;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(300));
    }
    return false;
}

bool HidDriver::releaseKeys() {
    return writeKeyboard(0, nullptr, 0);
}

const unsigned char* HidDriver::reportDescriptor() { return kReportDescriptor; }
size_t HidDriver::reportDescriptorSize() { return sizeof(kReportDescriptor); }
const unsigned char* HidDriver::keyboardDescriptor() { return kKeyboardDescriptor; }
size_t HidDriver::keyboardDescriptorSize() { return sizeof(kKeyboardDescriptor); }
