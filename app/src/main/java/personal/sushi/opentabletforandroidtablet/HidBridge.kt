package personal.sushi.opentabletforandroidtablet

class HidBridge {

    init {
        System.loadLibrary("tablet_hid")
    }

    external fun nativeOpenHid(path: String): Boolean
    external fun nativeCloseHid()
    external fun nativeIsHidOpen(): Boolean

    external fun nativeOpenKeyboard(path: String): Boolean
    external fun nativeCloseKeyboard()
    external fun nativeIsKeyboardOpen(): Boolean
    external fun nativeSendKeys(modifier: Byte, keys: ByteArray)
    external fun nativeReleaseKeys()
    external fun nativeGetKeyboardDescriptor(): ByteArray

    external fun nativeSetMapping(
        scaleX: Float, scaleY: Float,
        offsetX: Float, offsetY: Float,
        screenW: Int, screenH: Int
    )

    external fun nativeProcessTouch(x: Float, y: Float, tipSwitch: Boolean, pressure: Float)
    external fun nativeStartWriter()
    external fun nativeStopWriter()
    external fun nativeGetReportDescriptor(): ByteArray
}
