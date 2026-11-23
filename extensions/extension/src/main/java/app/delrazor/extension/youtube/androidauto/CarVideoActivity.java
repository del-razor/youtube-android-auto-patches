package app.delrazor.extension.youtube.androidauto;

import static app.delrazor.extension.youtube.androidauto.AndroidAutoBridge.TAG;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.car.Car;
import android.support.car.CarConnectionCallback;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.apps.auto.sdk.CarActivity;

import java.lang.ref.WeakReference;

/**
 * YouTube on the car display.
 * <p>
 * This screen creates a private virtual display that draws onto a surface filling the car
 * screen, and opens YouTube's own main activity on that display. YouTube treats it as a normal,
 * visible screen, so its whole app works there: home, search, the player and its controls.
 * Touches and keys from the car are passed to that activity in-process.
 * <p>
 * The car's driving status is followed: when the car reports that video is not allowed,
 * the screen is covered and only audio continues. The car's flag is never faked or ignored.
 * <p>
 * The UI is built in code because the extension cannot add resources to YouTube.
 */
@SuppressWarnings("unused")
public class CarVideoActivity extends CarActivity implements SurfaceHolder.Callback {

    private static final String VIRTUAL_DISPLAY_NAME = "DelrazorCarYouTube";

    /** How long to wait for YouTube to appear on the car display before reporting a failure. */
    private static final long LAUNCH_TIMEOUT_MS = 4000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SurfaceView surfaceView;
    private TextView coverView;
    private TextView statusView;

    // Virtual display showing YouTube.
    private VirtualDisplay virtualDisplay;
    private boolean launchRequested;
    private String launchStatus = "starting";

    /** The YouTube activity currently resumed on our virtual display. */
    private static WeakReference<Activity> hostedActivity = new WeakReference<>(null);
    private static int hostedDisplayId = Display.INVALID_DISPLAY;
    private static boolean lifecycleCallbacksRegistered;

    // Car driving status.
    private Car car;
    private CarSensorManager sensorManager;
    /** Null until the car reports its driving status. */
    private Boolean carAllowsVideo;
    private String statusText = "waiting for car";

    /** Shows the readout for a while after the screen opens, and while something is wrong. */
    private long statusVisibleUntil;

    // region Lifecycle

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Car screen created");

        setIgnoreConfigChanges(0xFFFFFFFF);
        try {
            getCarUiController().getStatusBarController().hideAppHeader();
        } catch (Exception ex) {
            Log.d(TAG, "Could not hide the app header: " + ex);
        }

        registerActivityTracking();
        setContentView(buildLayout());
        statusVisibleUntil = SystemClock.uptimeMillis() + 15000;
        connectCar();
    }

    @Override
    public void onStart() {
        super.onStart();
        mainHandler.post(statusTicker);
    }

    @Override
    public void onStop() {
        mainHandler.removeCallbacks(statusTicker);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Car screen destroyed");
        releaseVirtualDisplay();
        disconnectCar();
        super.onDestroy();
    }

    // endregion

    // region Layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setFocusable(true);
        surfaceView.setOnTouchListener((v, event) -> forwardTouch(event));
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Covers the screen when the car does not allow video. Swallows touches.
        coverView = new TextView(this);
        coverView.setBackgroundColor(Color.BLACK);
        coverView.setTextColor(Color.WHITE);
        coverView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        coverView.setGravity(Gravity.CENTER);
        coverView.setText("Video is off: the car does not allow video right now.\nAudio keeps playing.");
        coverView.setClickable(true);
        coverView.setVisibility(View.GONE);
        root.addView(coverView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Diagnostics, top right. Not clickable, so touches pass through to YouTube.
        statusView = new TextView(this);
        statusView.setTextColor(0xCCFFFFFF);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setBackgroundColor(0x99000000);
        statusView.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        statusParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(statusView, statusParams);

        return root;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void applyVideoState() {
        boolean allowed = Boolean.TRUE.equals(carAllowsVideo);
        coverView.setVisibility(allowed ? View.GONE : View.VISIBLE);
        if (!allowed) statusVisibleUntil = SystemClock.uptimeMillis() + 15000;
        updateStatusView();
    }

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatusView();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private void updateStatusView() {
        if (statusView == null) return;
        boolean problem = hostedActivity.get() == null || !Boolean.TRUE.equals(carAllowsVideo);
        if (!problem && SystemClock.uptimeMillis() > statusVisibleUntil) {
            statusView.setVisibility(View.GONE);
            return;
        }
        statusView.setVisibility(View.VISIBLE);

        String flag = carAllowsVideo == null ? "unknown"
                : (carAllowsVideo ? "allowed" : "not allowed");
        Activity activity = hostedActivity.get();
        statusView.setText("Car video flag: " + flag + " (" + statusText + ")\n"
                + "Car display: " + (virtualDisplay == null ? "none"
                : virtualDisplay.getDisplay().getDisplayId()) + ", " + launchStatus + "\n"
                + "On car: " + (activity == null ? "nothing" : activity.getClass().getSimpleName()));
    }

    // endregion

    // region Virtual display with YouTube on it

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (width <= 0 || height <= 0) return;
        int densityDpi = getResources().getDisplayMetrics().densityDpi;

        try {
            if (virtualDisplay == null) {
                DisplayManager displayManager =
                        (DisplayManager) getApplicationContext().getSystemService(Context.DISPLAY_SERVICE);
                // No flags: a private display only this app can show activities on.
                virtualDisplay = displayManager.createVirtualDisplay(VIRTUAL_DISPLAY_NAME,
                        width, height, densityDpi, holder.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                hostedDisplayId = virtualDisplay.getDisplay().getDisplayId();
                Log.d(TAG, "Car display " + hostedDisplayId + " created: "
                        + width + "x" + height + " @" + densityDpi);
            } else {
                virtualDisplay.setSurface(holder.getSurface());
                virtualDisplay.resize(width, height, densityDpi);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Could not create the car display", ex);
            launchStatus = "display error: " + ex.getClass().getSimpleName();
            return;
        }

        if (!launchRequested) {
            launchRequested = true;
            launchYouTube();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Keep the display, and YouTube on it, while the car hides this screen for a moment.
        if (virtualDisplay != null) virtualDisplay.setSurface(null);
    }

    private void launchYouTube() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent == null) {
                launchStatus = "no launch intent";
                return;
            }
            // A separate task, so the car gets its own YouTube window.
            // GUESS: YouTube's main activity allows a second task. If it does not, Android moves
            // the existing YouTube task to the car display instead, which also works.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(hostedDisplayId);

            try {
                getApplicationContext().startActivity(intent, options.toBundle());
                launchStatus = "opening YouTube";
                Log.d(TAG, "Opening YouTube on car display " + hostedDisplayId);
                watchLaunch();
            } catch (SecurityException denied) {
                // Android lets an app open its first window on its own virtual display only with
                // a system permission. Shizuku or root opens that first window instead.
                Log.d(TAG, "Android refused the car display launch, using the helper: " + denied);
                final int displayId = hostedDisplayId;
                launchStatus = "Android refused, trying Shizuku";
                CarDisplayLauncher.launch(displayId, intent.getComponent(), (status, failed) -> {
                    if (displayId != hostedDisplayId || hostedActivity.get() != null) return;
                    launchStatus = status;
                    if (failed) {
                        statusVisibleUntil = Long.MAX_VALUE;
                    } else if (status.equals("opening YouTube")) {
                        watchLaunch();
                    }
                    updateStatusView();
                });
            }
        } catch (Exception ex) {
            Log.e(TAG, "Could not open YouTube on the car display", ex);
            launchStatus = "launch error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            statusVisibleUntil = Long.MAX_VALUE;
        }
    }

    private void watchLaunch() {
        mainHandler.postDelayed(() -> {
            if (hostedActivity.get() == null && virtualDisplay != null) {
                launchStatus = "YouTube did not open on the car display";
                statusVisibleUntil = Long.MAX_VALUE;
            }
        }, LAUNCH_TIMEOUT_MS);
    }

    private void releaseVirtualDisplay() {
        Activity activity = hostedActivity.get();
        if (activity != null) {
            // Close the car's YouTube window instead of letting Android move it to the phone.
            try {
                activity.finishAndRemoveTask();
            } catch (Exception ex) {
                Log.d(TAG, "Could not close YouTube on the car display: " + ex);
            }
        }
        hostedActivity = new WeakReference<>(null);

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        hostedDisplayId = Display.INVALID_DISPLAY;
        launchRequested = false;
    }

    /** Finds YouTube activities as they appear on the car display. */
    private void registerActivityTracking() {
        if (lifecycleCallbacksRegistered) return;
        Application application = (Application) getApplicationContext();
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                if (displayIdOf(activity) == hostedDisplayId && hostedDisplayId != Display.INVALID_DISPLAY) {
                    Log.d(TAG, "On car display: " + activity.getClass().getName());
                    hostedActivity = new WeakReference<>(activity);
                }
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (hostedActivity.get() == activity) hostedActivity = new WeakReference<>(null);
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        });
        lifecycleCallbacksRegistered = true;
    }

    @SuppressWarnings("deprecation")
    private static int displayIdOf(Activity activity) {
        try {
            Display display = Build.VERSION.SDK_INT >= 30
                    ? activity.getDisplay()
                    : activity.getWindowManager().getDefaultDisplay();
            return display == null ? Display.INVALID_DISPLAY : display.getDisplayId();
        } catch (Exception ex) {
            return Display.INVALID_DISPLAY;
        }
    }

    // endregion

    // region Input from the car

    /** The car screen and the virtual display are the same size, so coordinates map 1:1. */
    private boolean forwardTouch(MotionEvent event) {
        Activity activity = hostedActivity.get();
        if (activity == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            statusVisibleUntil = 0; // Hide the readout once the user starts using YouTube.
        }
        MotionEvent copy = MotionEvent.obtain(event);
        try {
            activity.dispatchTouchEvent(copy);
        } catch (Exception ex) {
            Log.e(TAG, "Touch forwarding failure", ex);
        } finally {
            copy.recycle();
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleMediaKey(keyCode, event, true)) return true;
        Activity activity = hostedActivity.get();
        if (activity != null && activity.dispatchKeyEvent(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handleMediaKey(keyCode, event, false)) return true;
        Activity activity = hostedActivity.get();
        if (activity != null && activity.dispatchKeyEvent(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /** Back from the car goes to YouTube first, like on the phone. */
    @Override
    public void onBackPressed() {
        Activity activity = hostedActivity.get();
        if (activity != null) {
            activity.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    /** Steering wheel and head unit media keys control YouTube's media session. */
    private boolean handleMediaKey(int keyCode, KeyEvent event, boolean down) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                break;
            default:
                return false;
        }
        if (down) return true;

        MediaController controller = AndroidAutoBridge.pickController();
        if (controller == null) return true;
        MediaController.TransportControls controls = controller.getTransportControls();
        PlaybackState state = controller.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                controls.skipToNext();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                controls.skipToPrevious();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                controls.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                controls.pause();
                break;
            default:
                if (playing) controls.pause(); else controls.play();
        }
        return true;
    }

    // endregion

    // region Car driving status

    /**
     * Reads the car's driving status. Its "no video" bit is what the head unit tells
     * Android Auto about video. This screen follows it and never overrides it.
     */
    private void connectCar() {
        try {
            car = Car.createCar(this, new CarConnectionCallback() {
                @Override
                public void onConnected(Car connectedCar) {
                    mainHandler.post(() -> onCarConnected(connectedCar));
                }

                @Override
                public void onDisconnected(Car disconnectedCar) {
                    mainHandler.post(() -> onDrivingStatus(null, "car disconnected"));
                }
            });
            car.connect();
        } catch (Throwable ex) {
            Log.e(TAG, "Could not connect to the car", ex);
            onDrivingStatus(null, "car API error");
        }
    }

    private void onCarConnected(Car connectedCar) {
        try {
            sensorManager = connectedCar.getCarManager(CarSensorManager.class);
            if (sensorManager == null
                    || !sensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_DRIVING_STATUS)) {
                onDrivingStatus(null, "car does not report driving status");
                return;
            }

            CarSensorEvent latest = sensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_DRIVING_STATUS);
            if (latest != null) handleDrivingEvent(latest);

            sensorManager.addListener(sensorListener,
                    CarSensorManager.SENSOR_TYPE_DRIVING_STATUS, CarSensorManager.SENSOR_RATE_NORMAL);
        } catch (Throwable ex) {
            Log.e(TAG, "Could not read driving status", ex);
            onDrivingStatus(null, "sensor error");
        }
    }

    private final CarSensorManager.OnSensorChangedListener sensorListener =
            (manager, event) -> mainHandler.post(() -> handleDrivingEvent(event));

    private void handleDrivingEvent(CarSensorEvent event) {
        try {
            CarSensorEvent.DrivingStatusData data = event.getDrivingStatusData();
            onDrivingStatus(!data.isVideoRestricted(), "status " + data.status);
        } catch (Throwable ex) {
            Log.e(TAG, "Bad driving status event", ex);
        }
    }

    /**
     * @param allowsVideo True or false as reported by the car, or null if the car does not
     *                    report it. Without a report, video stays covered, as in Android Auto.
     */
    private void onDrivingStatus(Boolean allowsVideo, String detail) {
        Log.d(TAG, "Car video flag: " + allowsVideo + " (" + detail + ")");
        carAllowsVideo = allowsVideo;
        statusText = detail;
        applyVideoState();
    }

    private void disconnectCar() {
        try {
            if (sensorManager != null) sensorManager.removeListener(sensorListener);
        } catch (Throwable ignored) {
        }
        try {
            if (car != null) car.disconnect();
        } catch (Throwable ignored) {
        }
        sensorManager = null;
        car = null;
    }

    // endregion
}
