package app.delrazor.extension.youtube.androidauto;

import static app.delrazor.extension.youtube.androidauto.AndroidAutoBridge.TAG;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.car.Car;
import android.support.car.CarConnectionCallback;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.apps.auto.sdk.CarActivity;

/**
 * The YouTube screen on the car display.
 * <p>
 * Shows YouTube's own video (moved here by {@link CarVideoBridge}) with play, pause and skip
 * buttons. Video is shown only when the car's driving status allows video, the same flag the
 * head unit sends Android Auto. When the car says video is not allowed, the screen shows the
 * artwork and title instead, and audio keeps playing.
 * <p>
 * The UI is built in code because the extension cannot add resources to YouTube.
 */
@SuppressWarnings("unused")
public class CarVideoActivity extends CarActivity
        implements SurfaceHolder.Callback, CarVideoBridge.VideoSizeListener {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout videoFrame;
    private SurfaceView surfaceView;
    private ImageView artworkView;
    private TextView titleView;
    private TextView playPauseButton;
    private TextView statusView;

    private boolean surfaceReady;
    private int videoWidth;
    private int videoHeight;

    // Car driving status.
    private Car car;
    private CarSensorManager sensorManager;
    /** Null until the car reports its driving status. */
    private Boolean carAllowsVideo;
    private String statusText = "waiting for car";

    private MediaController controller;

    // region Lifecycle

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Car video screen created");

        setIgnoreConfigChanges(0xFFFFFFFF);
        try {
            getCarUiController().getStatusBarController().hideAppHeader();
        } catch (Exception ex) {
            Log.d(TAG, "Could not hide the app header: " + ex);
        }

        setContentView(buildLayout());
        CarVideoBridge.setVideoSizeListener(this);
        connectCar();
        updateStatusView();
    }

    @Override
    public void onStart() {
        super.onStart();
        attachController();
    }

    @Override
    public void onStop() {
        detachController();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Car video screen destroyed");
        CarVideoBridge.setVideoSizeListener(null);
        CarVideoBridge.setCarSurface(null);
        disconnectCar();
        super.onDestroy();
    }

    // endregion

    // region Layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Video, centered and sized to the video's aspect ratio in onVideoSize().
        videoFrame = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        videoFrame.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(videoFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Artwork, shown when the car does not allow video.
        artworkView = new ImageView(this);
        artworkView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkView.setVisibility(View.GONE);
        root.addView(artworkView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Bottom bar: title and buttons.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x99000000);
        int pad = dp(12);
        bar.setPadding(pad, pad, pad, pad);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(button("⏮", v -> {
            MediaController.TransportControls c = controls();
            if (c != null) c.skipToPrevious();
        }));
        playPauseButton = button("⏯", v -> togglePlayPause());
        bar.addView(playPauseButton);
        bar.addView(button("⏭", v -> {
            MediaController.TransportControls c = controls();
            if (c != null) c.skipToNext();
        }));

        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Small readout of the car's video flag, top right.
        statusView = new TextView(this);
        statusView.setTextColor(0xCCFFFFFF);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setBackgroundColor(0x66000000);
        statusView.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        statusParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(statusView, statusParams);

        return root;
    }

    private TextView button(String label, View.OnClickListener onClick) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true); // For rotary controllers.
        button.setClickable(true);
        button.setOnClickListener(onClick);
        int size = dp(64);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginStart(dp(8));
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    @Override
    public void onVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        layoutVideo();
    }

    /** Fits the video inside the screen, keeping its aspect ratio. */
    private void layoutVideo() {
        View parent = (View) videoFrame.getParent();
        if (parent == null || videoWidth <= 0 || videoHeight <= 0) return;
        int screenWidth = parent.getWidth();
        int screenHeight = parent.getHeight();
        if (screenWidth <= 0 || screenHeight <= 0) {
            parent.post(this::layoutVideo);
            return;
        }

        float scale = Math.min((float) screenWidth / videoWidth, (float) screenHeight / videoHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoFrame.getLayoutParams();
        params.width = Math.round(videoWidth * scale);
        params.height = Math.round(videoHeight * scale);
        params.gravity = Gravity.CENTER;
        videoFrame.setLayoutParams(params);
    }

    // endregion

    // region Video surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        applyVideoState();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        // Must move the decoders off this surface before it is destroyed.
        CarVideoBridge.setCarSurface(null);
    }

    /** Sends video to the car only when the surface exists and the car allows video. */
    private void applyVideoState() {
        boolean showVideo = surfaceReady && Boolean.TRUE.equals(carAllowsVideo);

        CarVideoBridge.setCarSurface(showVideo ? surfaceView.getHolder().getSurface() : null);
        videoFrame.setVisibility(showVideo ? View.VISIBLE : View.INVISIBLE);
        artworkView.setVisibility(showVideo ? View.GONE : View.VISIBLE);
        updateStatusView();
    }

    private void updateStatusView() {
        if (statusView == null) return;
        String flag = carAllowsVideo == null ? "unknown"
                : (carAllowsVideo ? "allowed" : "not allowed");
        statusView.setText("Car video flag: " + flag + " (" + statusText + ")");
    }

    // endregion

    // region Car driving status

    /**
     * Reads the car's driving status. Its "no video" bit is what the head unit tells
     * Android Auto about video. This screen follows it and never overrides it.
     * GUESS: Car.createCar works from a CarActivity context with this SDK.
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
     *                    report it. Without a report, video stays off, as in Android Auto.
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

    // region Playback controls

    private void attachController() {
        detachController();
        controller = AndroidAutoBridge.pickController();
        if (controller != null) {
            controller.registerCallback(controllerCallback, mainHandler);
            showMetadata(controller.getMetadata());
            showPlaybackState(controller.getPlaybackState());
        } else {
            titleView.setText("Start a video in YouTube on your phone");
        }
    }

    private void detachController() {
        if (controller != null) {
            controller.unregisterCallback(controllerCallback);
            controller = null;
        }
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            showMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            showPlaybackState(state);
        }

        @Override
        public void onSessionDestroyed() {
            // YouTube made a new session. Follow the one that is playing now.
            mainHandler.post(CarVideoActivity.this::attachController);
        }
    };

    private void showMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        CharSequence title = metadata.getDescription().getTitle();
        titleView.setText(title != null ? title : "");

        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        artworkView.setImageBitmap(art);
    }

    private void showPlaybackState(PlaybackState state) {
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        playPauseButton.setText(playing ? "⏸" : "▶");
    }

    private MediaController.TransportControls controls() {
        if (controller == null) attachController();
        return controller == null ? null : controller.getTransportControls();
    }

    private void togglePlayPause() {
        MediaController.TransportControls c = controls();
        if (c == null) return;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            c.pause();
        } else {
            c.play();
        }
    }

    /** Steering wheel and head unit media keys. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        MediaController.TransportControls c;
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                c = controls();
                if (c != null) c.skipToNext();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                c = controls();
                if (c != null) c.skipToPrevious();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    // endregion
}
