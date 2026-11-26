package app.delrazor.extension.gasbuddy.androidauto;

import android.app.Application;
import android.os.Build;

/**
 * The car screen runs in its own process, "<package>:androidauto" (set by the patch).
 *
 * GasBuddy's Application.onCreate sets up React Native, Firebase, AppsFlyer, Localytics,
 * Foursquare, MicroBlink and more on the main thread. When Android Auto starts GasBuddy cold, that
 * takes long enough for Android Auto to report the app as not responding. None of it is needed
 * for the car screen, so the patched onCreate returns right after Application.onCreate in this
 * process. GasBuddy's own process is not affected.
 */
public final class CarProcess {
    static final String SUFFIX = ":androidauto";

    private CarProcess() {
    }

    /** Called at the start of GasBuddy's MainApplication.onCreate. True skips the rest of it. */
    public static boolean skipAppStartup() {
        try {
            boolean car = isCarProcess();
            if (car) CarDiagnostics.mark("GasBuddy startup skipped in the car process");
            return car;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isCarProcess() {
        String name = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : procName();
        return name != null && name.endsWith(SUFFIX);
    }

    private static String procName() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] data = new byte[256];
            int read = in.read(data);
            int end = 0;
            while (end < read && data[end] != 0) end++;
            return new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
