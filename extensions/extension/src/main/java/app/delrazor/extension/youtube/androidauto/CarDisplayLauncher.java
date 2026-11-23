package app.delrazor.extension.youtube.androidauto;

import static app.delrazor.extension.youtube.androidauto.AndroidAutoBridge.TAG;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Opens YouTube's first window on the car's virtual display when Android refuses to let the app
 * do it itself.
 *
 * <p>Android only lets an app open a window on a virtual display it made when the app already has
 * a window there, or when it holds a system permission (ACTIVITY_EMBEDDING). So the very first
 * window needs help from something that holds it: the phone's shell user through Shizuku, or
 * root. After that first window is up, YouTube is "present" on the display and opens every
 * further window there by itself.
 *
 * <p>Nothing here touches the car's driving status. The car screen still covers YouTube when the
 * car says video is not allowed.
 */
final class CarDisplayLauncher {

    interface Callback {
        /** Called on the main thread with a short status for the car readout. */
        void onStatus(String status, boolean failed);
    }

    private static final int SHIZUKU_REQUEST_CODE = 4511;
    private static final long BINDER_WAIT_MS = 3000;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CarDisplayLauncher() {
    }

    static void launch(int displayId, ComponentName component, Callback callback) {
        String command = "am start --display " + displayId
                + " -f 0x18000000"
                + " -n " + component.flattenToShortString();

        if (shizukuReady()) {
            launchWithShizuku(command, displayId, component, callback);
            return;
        }

        // Shizuku's link can arrive a moment after YouTube starts. Wait for it briefly.
        final boolean[] done = {false};
        Shizuku.OnBinderReceivedListener listener = new Shizuku.OnBinderReceivedListener() {
            @Override
            public void onBinderReceived() {
                if (done[0]) return;
                done[0] = true;
                Shizuku.removeBinderReceivedListener(this);
                launchWithShizuku(command, displayId, component, callback);
            }
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(listener, mainHandler);
        } catch (Throwable ex) {
            Log.d(TAG, "Shizuku is not usable: " + ex);
        }
        mainHandler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            try {
                Shizuku.removeBinderReceivedListener(listener);
            } catch (Throwable ignored) {
            }
            if (hasRoot()) {
                callback.onStatus("opening YouTube with root", false);
                runInBackground(new String[]{"su", "-c", command}, false, callback);
            } else {
                callback.onStatus("Android needs Shizuku to open YouTube on the car. "
                        + "Start Shizuku on the phone, then reopen this screen.", true);
            }
        }, BINDER_WAIT_MS);
    }

    private static boolean shizukuReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ex) {
            return false;
        }
    }

    private static void launchWithShizuku(String command, int displayId, ComponentName component,
                                          Callback callback) {
        try {
            if (Shizuku.isPreV11()) {
                callback.onStatus("Shizuku is too old. Update the Shizuku app.", true);
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                callback.onStatus("opening YouTube with Shizuku", false);
                runInBackground(new String[]{"sh", "-c", command}, true, callback);
                return;
            }

            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != SHIZUKU_REQUEST_CODE) return;
                    Shizuku.removeRequestPermissionResultListener(this);
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        launchWithShizuku(command, displayId, component, callback);
                    } else {
                        callback.onStatus("Shizuku access was denied. Allow YouTube in the "
                                + "Shizuku app, then reopen this screen.", true);
                    }
                }
            }, mainHandler);
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            callback.onStatus("Allow YouTube in the Shizuku prompt on your phone (one time)", true);
        } catch (Throwable ex) {
            Log.e(TAG, "Shizuku launch failed", ex);
            callback.onStatus("Shizuku error: " + ex, true);
        }
    }

    /**
     * Runs a command, either directly (root) or as the shell user through Shizuku. Shizuku's API
     * keeps its process call private in current versions, but the method is still there.
     */
    private static void runInBackground(String[] cmd, boolean viaShizuku, Callback callback) {
        new Thread(() -> {
            String result;
            boolean failed;
            try {
                Process process;
                if (viaShizuku) {
                    Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                            String[].class, String[].class, String.class);
                    newProcess.setAccessible(true);
                    process = (Process) newProcess.invoke(null, cmd, null, null);
                } else {
                    process = Runtime.getRuntime().exec(cmd);
                }
                String out = read(process.getInputStream()) + read(process.getErrorStream());
                boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                int code = exited ? process.exitValue() : -1;
                Log.d(TAG, "Car display launch helper exited " + code + ": " + out);
                failed = code != 0 || out.contains("Error") || out.contains("Exception");
                result = failed ? "helper could not open YouTube: " + out.trim()
                        : "opening YouTube";
            } catch (Throwable ex) {
                Log.e(TAG, "Car display launch helper failed", ex);
                failed = true;
                result = "helper error: " + ex;
            }
            final String status = result;
            final boolean isFailure = failed;
            mainHandler.post(() -> callback.onStatus(status, isFailure));
        }, "DelrazorCarLauncher").start();
    }

    private static String read(InputStream stream) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (text.length() < 600) text.append(line).append(' ');
            }
        } catch (Exception ignored) {
        }
        return text.toString();
    }

    private static boolean hasRoot() {
        for (String dir : new String[]{"/system/bin", "/system/xbin", "/sbin", "/debug_ramdisk",
                "/data/adb/ksu/bin", "/data/adb/ap/bin"}) {
            if (new File(dir, "su").exists()) return true;
        }
        return false;
    }
}
