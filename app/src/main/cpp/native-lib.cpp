#include <jni.h>
#include <string>
#include "hid_driver.h"
#include "touch_processor.h"
#include <android/log.h>

#define TAG "TabletHidDigitizer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static HidDriver      g_hid;
static TouchProcessor g_processor;

extern "C" {

// ── Digitizer HID ──

JNIEXPORT jboolean JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeOpenHid(
        JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    bool ok = g_hid.open(p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeCloseHid(JNIEnv*, jobject) {
    g_processor.stop();
    g_hid.close();
}

JNIEXPORT jboolean JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeIsHidOpen(JNIEnv*, jobject) {
    return g_hid.isOpen() ? JNI_TRUE : JNI_FALSE;
}

// ── Keyboard HID ──

JNIEXPORT jboolean JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeOpenKeyboard(
        JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    bool ok = g_hid.openKeyboard(p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeCloseKeyboard(JNIEnv*, jobject) {
    g_hid.closeKeyboard();
}

JNIEXPORT jboolean JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeIsKeyboardOpen(JNIEnv*, jobject) {
    return g_hid.isKeyboardOpen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeSendKeys(
        JNIEnv* env, jobject, jbyte modifier, jbyteArray keys) {
    jsize len = env->GetArrayLength(keys);
    jbyte buf[6] = {0};
    if (len > 6) len = 6;
    env->GetByteArrayRegion(keys, 0, len, buf);
    g_hid.writeKeyboard(static_cast<uint8_t>(modifier),
                        reinterpret_cast<const uint8_t*>(buf), len);
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeReleaseKeys(JNIEnv*, jobject) {
    g_hid.releaseKeys();
}

JNIEXPORT jbyteArray JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeGetKeyboardDescriptor(
        JNIEnv* env, jobject) {
    const unsigned char* desc = HidDriver::keyboardDescriptor();
    size_t size = HidDriver::keyboardDescriptorSize();
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr)
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte*>(desc));
    return arr;
}

// ── Touch processing ──

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeSetMapping(
        JNIEnv*, jobject,
        jfloat scaleX, jfloat scaleY, jfloat offsetX, jfloat offsetY,
        jint screenW, jint screenH) {
    MappingConfig cfg;
    cfg.scaleX = scaleX;  cfg.scaleY = scaleY;
    cfg.offsetX = offsetX; cfg.offsetY = offsetY;
    cfg.screenW = screenW; cfg.screenH = screenH;
    g_processor.setMapping(cfg);
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeProcessTouch(
        JNIEnv*, jobject,
        jfloat x, jfloat y, jboolean tipSwitch, jfloat pressure) {
    uint8_t status = tipSwitch == JNI_TRUE ? 0x01 : 0x00;
    g_processor.pushPoint(x, y, status, pressure);
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeStartWriter(JNIEnv*, jobject) {
    g_processor.start(&g_hid);
}

JNIEXPORT void JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeStopWriter(JNIEnv*, jobject) {
    g_processor.stop();
}

JNIEXPORT jbyteArray JNICALL
Java_personal_sushi_opentabletforandroidtablet_HidBridge_nativeGetReportDescriptor(JNIEnv* env, jobject) {
    const unsigned char* desc = HidDriver::reportDescriptor();
    size_t size = HidDriver::reportDescriptorSize();
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr)
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte*>(desc));
    return arr;
}

} // extern "C"
