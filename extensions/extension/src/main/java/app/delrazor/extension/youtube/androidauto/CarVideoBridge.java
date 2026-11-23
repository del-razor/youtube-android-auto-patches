package app.delrazor.extension.youtube.androidauto;

import static app.delrazor.extension.youtube.androidauto.AndroidAutoBridge.TAG;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaDescrambler;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sends YouTube's decoded video to the car screen.
 * <p>
 * The patch replaces YouTube's calls to {@link MediaCodec#configure} and
 * {@link MediaCodec#setOutputSurface} with the static methods here. For every video decoder,
 * the surface YouTube asks for (the phone's player view) is remembered. While the car video
 * screen provides a surface, decoders draw on the car surface instead. When the car surface
 * goes away, decoders are switched back to the surface YouTube last asked for.
 * <p>
 * Only the video picture moves. Audio, playback and YouTube's own player keep working as usual.
 */
@SuppressWarnings("unused")
public final class CarVideoBridge {

    /** Called on the main thread when the size of the playing video changes. */
    interface VideoSizeListener {
        void onVideoSize(int width, int height);
    }

    private static final class DecoderState {
        /** The surface YouTube asked for. */
        Surface youTubeSurface;
        /** True when the decoder currently draws on the car surface. */
        boolean onCar;
        /** Protected (DRM) content cannot be drawn on the car's virtual display. */
        boolean secure;
    }

    private static final Object lock = new Object();

    // Guarded by lock. Weak keys, so released decoders are forgotten.
    private static final Map<MediaCodec, DecoderState> decoders = new WeakHashMap<>();

    // Guarded by lock.
    private static Surface carSurface;

    private static volatile WeakReference<VideoSizeListener> sizeListener = new WeakReference<>(null);
    private static volatile int videoWidth;
    private static volatile int videoHeight;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CarVideoBridge() {
    }

    // region Injection points (called from YouTube's player threads)

    /** Replaces {@link MediaCodec#configure(MediaFormat, Surface, MediaCrypto, int)}. */
    public static void configure(MediaCodec codec, MediaFormat format, Surface surface,
                                 MediaCrypto crypto, int flags) {
        Surface target = onConfigure(codec, format, surface, crypto != null);
        codec.configure(format, target, crypto, flags);
    }

    /** Replaces {@link MediaCodec#configure(MediaFormat, Surface, int, MediaDescrambler)}. */
    public static void configure(MediaCodec codec, MediaFormat format, Surface surface,
                                 int flags, MediaDescrambler descrambler) {
        Surface target = onConfigure(codec, format, surface, descrambler != null);
        codec.configure(format, target, flags, descrambler);
    }

    /** Replaces {@link MediaCodec#setOutputSurface(Surface)}. */
    public static void setOutputSurface(MediaCodec codec, Surface surface) {
        Surface target = surface;
        synchronized (lock) {
            DecoderState state = decoders.get(codec);
            if (state != null) {
                state.youTubeSurface = surface;
                if (carSurface != null && !state.secure) {
                    target = carSurface;
                    state.onCar = true;
                } else {
                    state.onCar = false;
                }
            }
        }
        codec.setOutputSurface(target);
    }

    // endregion

    private static Surface onConfigure(MediaCodec codec, MediaFormat format,
                                       Surface surface, boolean secure) {
        try {
            if (surface == null || format == null) return surface;
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("video/")) return surface;

            if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                updateVideoSize(format.getInteger(MediaFormat.KEY_WIDTH),
                        format.getInteger(MediaFormat.KEY_HEIGHT));
            }

            synchronized (lock) {
                DecoderState state = new DecoderState();
                state.youTubeSurface = surface;
                state.secure = secure;
                decoders.put(codec, state);

                if (carSurface != null && !secure) {
                    state.onCar = true;
                    Log.d(TAG, "Video decoder configured on the car screen");
                    return carSurface;
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "onConfigure failure", ex);
        }
        return surface;
    }

    private static void updateVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (width == videoWidth && height == videoHeight) return;
        videoWidth = width;
        videoHeight = height;

        mainHandler.post(() -> {
            VideoSizeListener listener = sizeListener.get();
            if (listener != null) listener.onVideoSize(width, height);
        });
    }

    // region Car screen side

    static void setVideoSizeListener(VideoSizeListener listener) {
        sizeListener = new WeakReference<>(listener);
        if (listener != null && videoWidth > 0) {
            listener.onVideoSize(videoWidth, videoHeight);
        }
    }

    /**
     * Sets the surface of the car video screen, or null to send video back to YouTube's
     * own surfaces. Switches every live video decoder right away.
     */
    static void setCarSurface(Surface surface) {
        List<MediaCodec> toCar = new ArrayList<>();
        List<MediaCodec> toPhone = new ArrayList<>();
        List<Surface> phoneSurfaces = new ArrayList<>();

        synchronized (lock) {
            carSurface = (surface != null && surface.isValid()) ? surface : null;

            for (Map.Entry<MediaCodec, DecoderState> entry : decoders.entrySet()) {
                DecoderState state = entry.getValue();
                if (carSurface != null && !state.secure) {
                    toCar.add(entry.getKey());
                    state.onCar = true;
                } else if (state.onCar) {
                    toPhone.add(entry.getKey());
                    phoneSurfaces.add(state.youTubeSurface);
                    state.onCar = false;
                }
            }
        }

        // Outside the lock: setOutputSurface waits for the codec.
        for (MediaCodec codec : toCar) {
            switchSurface(codec, surface);
        }
        for (int i = 0; i < toPhone.size(); i++) {
            switchSurface(toPhone.get(i), phoneSurfaces.get(i));
        }
        Log.d(TAG, "Car surface " + (carSurface != null ? "set" : "cleared")
                + ", moved " + toCar.size() + " to car, " + toPhone.size() + " to phone");
    }

    private static void switchSurface(MediaCodec codec, Surface surface) {
        if (surface == null || !surface.isValid()) {
            // YouTube's own surface is gone, for example because the app is in the background.
            // Park the decoder on a sink that drops frames, so it never draws on a dead surface.
            surface = discardSurface();
            if (surface == null) return;
        }
        try {
            codec.setOutputSurface(surface);
        } catch (Exception ex) {
            // The decoder was released, or YouTube is reconfiguring it.
            // The next configure() call picks up the right surface.
            Log.d(TAG, "Could not switch decoder surface: " + ex);
        }
    }

    private static ImageReader discardReader;

    /** A surface that accepts video frames and drops them. */
    private static synchronized Surface discardSurface() {
        try {
            if (discardReader == null) {
                HandlerThread thread = new HandlerThread("DelrazorDiscardFrames");
                thread.start();
                discardReader = ImageReader.newInstance(
                        Math.max(videoWidth, 16), Math.max(videoHeight, 16), ImageFormat.PRIVATE, 3);
                discardReader.setOnImageAvailableListener(reader -> {
                    Image image = reader.acquireLatestImage();
                    if (image != null) image.close();
                }, new Handler(thread.getLooper()));
            }
            return discardReader.getSurface();
        } catch (Exception ex) {
            Log.e(TAG, "Could not create discard surface", ex);
            return null;
        }
    }

    // endregion
}
