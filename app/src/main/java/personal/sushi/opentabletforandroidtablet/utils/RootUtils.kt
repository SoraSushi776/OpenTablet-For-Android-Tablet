package personal.sushi.opentabletforandroidtablet.utils

import android.util.Log
import personal.sushi.opentabletforandroidtablet.LogRepository
import java.io.File
import kotlin.concurrent.thread

/**
 * Executes shell commands with root privileges via `su -c`.
 * Handles ConfigFS HID gadget setup and SELinux permission bypass.
 */
object RootUtils {

    private const val TAG = "RootUtils"
    const val HID_DEVICE_PATH = "/dev/hidg0"
    const val KEYBOARD_DEVICE_PATH = "/dev/hidg1"

    /** True if the `su` binary is present. */
    fun hasSu(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
        p.waitFor() == 0 && p.inputStream.bufferedReader().readText().isNotBlank()
    } catch (e: Exception) {
        false
    }

    data class Result(val stdout: String, val stderr: String, val exitCode: Int) {
        val success get() = exitCode == 0
    }

    /** Execute a single command via `su -c`. */
    fun execSu(vararg command: String): Result {
        val fullCmd = if (command.size == 1) command[0] else command.joinToString(" ")
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", fullCmd))
            val stdout = p.inputStream.bufferedReader().readText().trim()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            Result(stdout, stderr, code)
        } catch (e: Exception) {
            Result("", e.message ?: "exception", -1)
        }
    }

    /** Execute a multi-line shell script via `su`. */
    fun execSuScript(script: String): Result {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))

            p.outputStream.write(script.toByteArray())
            p.outputStream.flush()
            p.outputStream.close()

            var stdout = ""
            var stderr = ""
            val stdoutThread = thread { stdout = p.inputStream.bufferedReader().readText().trim() }
            val stderrThread = thread { stderr = p.errorStream.bufferedReader().readText().trim() }

            val code = p.waitFor()
            stdoutThread.join()
            stderrThread.join()

            Log.d(TAG, "script exit=$code stdout=$stdout stderr=$stderr")
            LogRepository.d(TAG, "script exit=$code")
            LogRepository.logRaw(TAG, stdout)
            if (stderr.isNotBlank()) LogRepository.e(TAG, "stderr: $stderr")
            Result(stdout, stderr, code)
        } catch (e: Exception) {
            Log.e(TAG, "execSuScript exception", e)
            LogRepository.e(TAG, "execSuScript exception: ${e.message}")
            Result("", e.message ?: "exception", -1)
        }
    }

    /** Check whether [path] exists on the filesystem. Uses root for paths the app can't see. */
    fun pathExists(path: String): Boolean {
        // For paths under /config, /sys/kernel, /dev — app process can't access them
        // due to SELinux, so check via su.
        if (path.startsWith("/config") || path.startsWith("/sys/kernel/config") || path.startsWith("/dev/hidg")) {
            val r = execSu("test -e '$path' && echo YES || echo NO")
            return r.stdout.trim() == "YES"
        }
        return File(path).exists()
    }

    /**
     * Set up the USB HID gadget via ConfigFS so that /dev/hidg0 appears.
     *
     * Key steps:
     * 1. Stop adbd and set sys.usb.config=none to prevent Android from
     *    reconfiguring USB back to ADB when cable is connected.
     * 2. Unbind UDC (from Kotlin with Thread.sleep for reliable delays).
     * 3. Remove ALL existing function symlinks (ADB, MTP, etc.) from config.
     * 4. Create HID function and symlink it to config.
     * 5. Rebind UDC.
     * 6. Set sys.usb.state=hid to reflect new state.
     *
     * @param reportDescB64  base64-encoded HID Report Descriptor.
     * @return a [Result] from the setup script.
     */
    fun setupHidGadget(reportDescB64: String, keyboardDescB64: String = ""): Result {
        // Step 0: If /dev/hidg0 already exists, force teardown first to ensure
        // the new HID descriptor is applied. The old gadget might have a stale
        // report descriptor (e.g. with Report ID) that OTD can't match.
        if (pathExists(HID_DEVICE_PATH)) {
            LogRepository.i(TAG, "[SETUP] /dev/hidg0 exists, tearing down first for fresh descriptor")
            teardownHidGadget()
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            LogRepository.i(TAG, "[SETUP] Teardown complete, starting fresh setup")
        }

        // Step 1: Dump USB properties for diagnostics.
        val props = execSu("getprop | grep -E 'sys\\.usb|persist\\.sys\\.usb|usb\\.'")
        LogRepository.i(TAG, "[SETUP] USB properties:\n${props.stdout}")

        // Step 2: Mount configfs if needed.
        if (!pathExists("/config/usb_gadget") && !pathExists("/sys/kernel/config/usb_gadget")) {
            LogRepository.i(TAG, "[SETUP] Mounting configfs")
            execSu("mkdir -p /config 2>/dev/null")
            val mountResult = execSu("mount -t configfs none /config 2>&1")
            LogRepository.d(TAG, "[SETUP] Mount /config: '${mountResult.stdout}' exit=${mountResult.exitCode}")
            if (!pathExists("/config/usb_gadget")) {
                execSu("mkdir -p /sys/kernel/config 2>/dev/null")
                val mountResult2 = execSu("mount -t configfs none /sys/kernel/config 2>&1")
                LogRepository.d(TAG, "[SETUP] Mount /sys/kernel/config: '${mountResult2.stdout}' exit=${mountResult2.exitCode}")
            }
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
        }

        val configBase = when {
            pathExists("/config/usb_gadget") -> "/config"
            pathExists("/sys/kernel/config/usb_gadget") -> "/sys/kernel/config"
            else -> {
                val mounts = execSu("cat /proc/mounts 2>/dev/null | grep configfs").stdout.trim()
                LogRepository.e(TAG, "[FAIL] configfs unavailable. Mounts: '$mounts'")
                return Result("", "configfs unavailable", 1)
            }
        }
        LogRepository.i(TAG, "[SETUP] ConfigFS ready at $configBase")

        val G = "$configBase/usb_gadget/g1"

        // Step 3: Stop adbd and prevent Android from reconfiguring USB.
        // This is critical — without this, Android's init.usb.configfs.rc
        // will rebind ADB when the USB cable is connected.
        LogRepository.i(TAG, "[SETUP] Stopping adbd and disabling USB reconfiguration")
        execSu("stop adbd 2>/dev/null || true")
        execSu("setprop sys.usb.config none 2>/dev/null || true")
        execSu("setprop sys.usb.adb.disabled 1 2>/dev/null || true")
        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        LogRepository.i(TAG, "[SETUP] adbd stopped, USB config set to none")

        // Step 4: Unbind UDC from Kotlin with proper Thread.sleep delays.
        val udcPath = "$G/UDC"
        if (pathExists(udcPath)) {
            var curUdc = execSu("cat $udcPath 2>/dev/null").stdout.trim()
            LogRepository.i(TAG, "[SETUP] Current UDC: '$curUdc'")
            if (curUdc.isNotEmpty()) {
                LogRepository.i(TAG, "[SETUP] Unbinding UDC")
                var unbound = false
                for (i in 1..15) {
                    execSu("echo '' > $udcPath 2>/dev/null")
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                    curUdc = execSu("cat $udcPath 2>/dev/null").stdout.trim()
                    if (curUdc.isEmpty()) {
                        LogRepository.i(TAG, "[SETUP] UDC unbound after retry $i")
                        unbound = true
                        break
                    }
                    LogRepository.i(TAG, "[SETUP] Still bound: '$curUdc', retry $i")
                }
                if (!unbound) {
                    LogRepository.e(TAG, "[FAIL] Cannot unbind UDC: '$curUdc'")
                    return Result("", "Cannot unbind UDC: '$curUdc'", 1)
                }
                LogRepository.i(TAG, "[SETUP] UDC unbound, waiting 2s for kernel cleanup")
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                LogRepository.i(TAG, "[SETUP] Post-unbind delay done")
            }
        }

        // Step 5: Run the rest of the setup — remove ALL existing symlinks
        // (ADB, MTP, etc.) from config, create HID function, symlink, rebind UDC.
        val script = """
            LOG() { echo "[SETUP] ${'$'}*"; }
            FAIL() { echo "[FAIL] ${'$'}*"; exit 1; }

            G=$configBase/usb_gadget/g1
            F=${'$'}G/functions/hid.usb0
            K=${'$'}G/functions/hid.usb1
            S=${'$'}G/strings/0x409

            # Determine which config dir to use.
            if [ -d "${'$'}G/configs/b.1" ]; then
              C=${'$'}G/configs/b.1
              LOG "Using existing config b.1"
            elif [ -d "${'$'}G/configs/c.1" ]; then
              C=${'$'}G/configs/c.1
              LOG "Using existing config c.1"
            else
              C=${'$'}G/configs/b.1
              LOG "Creating config b.1"
              mkdir -p "${'$'}C" 2>&1
              if [ ${'$'}? -ne 0 ] && [ ! -d "${'$'}C" ]; then
                C=${'$'}G/configs/c.1
                mkdir -p "${'$'}C" 2>&1
              fi
              [ -d "${'$'}C" ] || FAIL "Cannot create config directory"
            fi

            # Remove ALL existing function symlinks from ALL config dirs.
            # This clears ADB (f1, f2, f3...), MTP, RNDIS, etc.
            LOG "Removing ALL existing function symlinks"
            for cfg in ${'$'}G/configs/*; do
              [ -d "${'$'}cfg" ] || continue
              for link in "${'$'}cfg"/*; do
                [ -L "${'$'}link" ] || continue
                LOG "Removing ${'$'}link"
                rm "${'$'}link" 2>/dev/null
              done
            done

            # Create gadget if needed.
            [ -d "${'$'}G" ] || mkdir -p "${'$'}G"
            cd "${'$'}G"

            # Set USB descriptors.
            echo 0x5541 > idVendor
            echo 0x0001 > idProduct
            echo 0x0100 > bcdDevice
            echo 0x0200 > bcdUSB

            # Set gadget strings.
            mkdir -p "${'$'}S"
            echo "TabletHidDigitizer" > "${'$'}S/manufacturer"
            echo "HID Digitizer" > "${'$'}S/product"
            echo "0123456789" > "${'$'}S/serialnumber"

            # Create HID function if needed.
            mkdir -p "${'$'}F"
            LOG "Function dir ready"

            # Write report descriptor and attributes.
            LOG "Writing report descriptor"
            echo "$reportDescB64" | base64 -d > "${'$'}F/report_desc"
            LOG "Report descriptor written"
            echo 0 > "${'$'}F/protocol"
            echo 0 > "${'$'}F/subclass"
            echo 8 > "${'$'}F/report_length"

            # Create keyboard function if descriptor provided.
            if [ -n "$keyboardDescB64" ]; then
              LOG "Creating keyboard function"
              mkdir -p "${'$'}K"
              echo "$keyboardDescB64" | base64 -d > "${'$'}K/report_desc"
              echo 1 > "${'$'}K/protocol"
              echo 1 > "${'$'}K/subclass"
              echo 8 > "${'$'}K/report_length"
              LOG "Keyboard function ready"
            fi

            # Config strings.
            mkdir -p "${'$'}C/strings/0x409" 2>/dev/null || true
            echo 100 > "${'$'}C/MaxPower" 2>/dev/null || true
            echo "Config 1" > "${'$'}C/strings/0x409/configuration" 2>/dev/null || true

            # Create symlink: config -> HID function.
            LOG "Creating config symlink"
            ln -s "${'$'}F" "${'$'}C/hid.usb0" 2>&1
            if [ ${'$'}? -ne 0 ]; then
              if [ -L "${'$'}C/hid.usb0" ]; then
                LOG "Symlink already exists"
              else
                FAIL "Cannot create symlink: ln -s ${'$'}F ${'$'}C/hid.usb0"
              fi
            fi
            LOG "Config linked"

            # Create keyboard symlink if keyboard function exists.
            if [ -d "${'$'}K" ]; then
              ln -s "${'$'}K" "${'$'}C/hid.usb1" 2>&1
              if [ ${'$'}? -ne 0 ]; then
                if [ -L "${'$'}C/hid.usb1" ]; then
                  LOG "Keyboard symlink already exists"
                else
                  FAIL "Cannot create keyboard symlink"
                fi
              fi
              LOG "Keyboard linked"
            fi

            # Bind UDC.
            UDC=${'$'}(getprop sys.usb.controller)
            if [ -z "${'$'}{UDC}" ]; then
              UDC=${'$'}(ls /sys/class/udc/ 2>/dev/null | head -1)
            fi
            if [ -n "${'$'}{UDC}" ]; then
              LOG "Binding UDC: ${'$'}{UDC}"
              echo "${'$'}{UDC}" > ${'$'}G/UDC
            else
              LOG "WARNING: No UDC found"
            fi

            # Set USB state to prevent Android from reconfiguring.
            setprop sys.usb.state hid 2>/dev/null || true

            LOG "DONE: setup script complete"
            echo OK
        """.trimIndent()

        val result = execSuScript(script)

        // Step 6: Wait for /dev/hidg0 from Kotlin.
        if (result.success) {
            LogRepository.i(TAG, "[SETUP] Waiting for /dev/hidg0...")
            for (i in 1..10) {
                if (pathExists(HID_DEVICE_PATH)) {
                    LogRepository.i(TAG, "[SETUP] /dev/hidg0 appeared after $i checks")
                    break
                }
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
            }
            if (!pathExists(HID_DEVICE_PATH)) {
                LogRepository.e(TAG, "[FAIL] /dev/hidg0 not created")
                return Result("", "/dev/hidg0 not created after UDC bind", 1)
            }

            execSu("chmod 666 $HID_DEVICE_PATH")
            execSu("chmod 666 $KEYBOARD_DEVICE_PATH 2>/dev/null || true")

            // SELinux.
            val seState = execSu("getenforce 2>/dev/null").stdout.trim()
            LogRepository.i(TAG, "[SETUP] SELinux state: $seState")
            if (seState == "Enforcing") {
                LogRepository.i(TAG, "[SETUP] Setting SELinux to Permissive")
                execSu("setenforce 0 2>/dev/null")
            }
            LogRepository.i(TAG, "[SETUP] DONE: /dev/hidg0 ready")
        }

        return result
    }

    /** Restore SELinux to Enforcing after the device is opened. */
    fun restoreSelinux(): Result = execSu("setenforce 1")

    /** Tear down the HID gadget and restore ADB. */
    fun teardownHidGadget(): Result = execSuScript("""
        G=/config/usb_gadget/g1
        echo "" > ${'$'}G/UDC 2>/dev/null || true
        rm -f ${'$'}G/configs/*/hid.usb0 2>/dev/null || true
        rm -f ${'$'}G/configs/*/hid.usb1 2>/dev/null || true
        setenforce 1 2>/dev/null || true
        setprop sys.usb.adb.disabled 0 2>/dev/null || true
        setprop sys.usb.config adb 2>/dev/null || true
        start adbd 2>/dev/null || true
        echo OK
    """.trimIndent())
}
