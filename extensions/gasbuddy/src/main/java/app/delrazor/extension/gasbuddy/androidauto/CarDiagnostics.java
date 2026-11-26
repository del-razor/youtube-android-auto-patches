package app.delrazor.extension.gasbuddy.androidauto;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * A short log of what the car screen did, written to Download/GasBuddy-car-log.txt so it can be
 * shared without a computer. It records when the car process started, when Android Auto
 * reached each step, and the main thread's stack whenever it is stuck for more than two seconds,
 * which is what makes Android Auto say the app isn't responding.
 */
final class CarDiagnostics {
    private static final String TAG = "DelRazorGasBuddyCar";
    private static final String FILE_NAME = "GasBuddy-car-log.txt";
    private static final String URI_FILE = "delrazor_car_log_uri";
    private static final int MAX_LINES = 300;
    private static final long STALL_MS = 2000;

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static Handler writer;
    private static boolean watchdogStarted;

    private CarDiagnostics() {
    }

    static void mark(String event) {
        long sinceStart = Build.VERSION.SDK_INT >= 24
                ? SystemClock.uptimeMillis() - Process.getStartUptimeMillis() : -1;
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  +" + sinceStart + " ms  [" + Thread.currentThread().getName() + "]  " + event;
        Log.i(TAG, line);
        synchronized (lock) {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
        scheduleWrite();
    }

    /** Watches the main thread and records its stack while it is stuck. */
    static synchronized void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        final Handler main = new Handler(Looper.getMainLooper());
        Thread watchdog = new Thread(() -> {
            while (true) {
                final long sent = SystemClock.uptimeMillis();
                final boolean[] answered = {false};
                main.post(() -> answered[0] = true);
                boolean reported = false;
                while (!answered[0]) {
                    sleep(250);
                    long waited = SystemClock.uptimeMillis() - sent;
                    if (!reported && waited >= STALL_MS) {
                        reported = true;
                        mark("MAIN THREAD STUCK " + waited + " ms at:\n" + stack(Looper.getMainLooper().getThread()));
                    }
                }
                long waited = SystemClock.uptimeMillis() - sent;
                if (reported) mark("Main thread free again after " + waited + " ms");
                sleep(1000);
            }
        }, "DelRazorCarWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static String stack(Thread thread) {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement element : thread.getStackTrace()) {
            text.append("      at ").append(element).append('\n');
            if (text.length() > 6000) break;
        }
        return text.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // Keep watching.
        }
    }

    private static void scheduleWrite() {
        synchronized (lock) {
            if (writer == null) {
                android.os.HandlerThread thread = new android.os.HandlerThread("DelRazorCarLog");
                thread.start();
                writer = new Handler(thread.getLooper());
            }
            writer.removeCallbacksAndMessages(null);
            writer.postDelayed(CarDiagnostics::write, 500);
        }
    }

    private static void write() {
        Context context = application();
        if (context == null || Build.VERSION.SDK_INT < 29) return;
        StringBuilder text = new StringBuilder("GasBuddy car screen log (del-razor patch)\n\n");
        synchronized (lock) {
            for (String line : lines) text.append(line).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        ContentResolver resolver = context.getContentResolver();
        try {
            Uri uri = savedUri(context);
            OutputStream out = null;
            if (uri != null) {
                try {
                    out = resolver.openOutputStream(uri, "wt");
                } catch (Exception e) {
                    out = null;
                }
            }
            if (out == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return;
                saveUri(context, uri);
                out = resolver.openOutputStream(uri, "wt");
            }
            if (out == null) return;
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Writing the car log failed", e);
        }
    }

    private static Uri savedUri(Context context) {
        File file = new File(context.getFilesDir(), URI_FILE);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = in.read(data);
            return read > 0 ? Uri.parse(new String(data, 0, read, StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveUri(Context context, Uri uri) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), URI_FILE))) {
            out.write(uri.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // A new file is made next time.
        }
    }

    private static Context application() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Application) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
