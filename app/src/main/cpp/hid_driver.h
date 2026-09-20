#ifndef HID_DRIVER_H
#define HID_DRIVER_H

#include <cstdint>
#include <cstddef>

// 8-byte report as consumed by this project's working OTD setup
// (matches the HID digitizer descriptor packing):
//   byte0    : status — bit0=tip, bit1=barrel, bit2=eraser
//   byte1-2  : X  uint16 LE, 0..32767
//   byte3-4  : Y  uint16 LE, 0..32767
//   byte5-6  : pressure uint16 LE, 0..8191
//   byte7    : reserved
struct __attribute__((packed)) OtdReport {
    uint8_t  status = 0;    // byte 0
    uint16_t x = 0;         // bytes 1-2
    uint16_t y = 0;         // bytes 3-4
    uint16_t pressure = 0;  // bytes 5-6
    uint8_t  reserved = 0;  // byte 7
};
static_assert(sizeof(OtdReport) == 8, "OtdReport must be exactly 8 bytes");

// 8-byte standard keyboard report: 1 modifier + 1 reserved + 6 keycodes.
struct __attribute__((packed)) KeyboardReport {
    uint8_t  modifier = 0;  // bit0=LCtrl, bit1=LShift, bit2=LAlt, bit3=LGui
                            // bit4=RCtrl, bit5=RShift, bit6=RAlt, bit7=RGui
    uint8_t  reserved = 0;
    uint8_t  keycodes[6] = {0};
};
static_assert(sizeof(KeyboardReport) == 8, "KeyboardReport must be exactly 8 bytes");

class HidDriver {
public:
    bool open(const char* path);
    bool openKeyboard(const char* path);
    void close();
    void closeKeyboard();
    bool isOpen() const;
    bool isKeyboardOpen() const;

    // status: bit0=tip, bit1=barrel, bit2=eraser — written as-is to byte0.
    bool writeReport(uint16_t x, uint16_t y, uint8_t status, uint16_t pressure);
    bool writeKeyboard(uint8_t modifier, const uint8_t* keycodes, int count);
    bool releaseKeys();

    static const unsigned char* reportDescriptor();
    static size_t reportDescriptorSize();

    static const unsigned char* keyboardDescriptor();
    static size_t keyboardDescriptorSize();

    static constexpr uint16_t VID = 0x5541;
    static constexpr uint16_t PID = 0x0001;

private:
    int fd_ = -1;
    int kbFd_ = -1;
};

#endif
