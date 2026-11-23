package app.delrazor.extension.youtube.androidauto;

import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps track of the media sessions YouTube creates, so the Android Auto service
 * can mirror the one that is playing.
 *
 * {@link #onMediaSessionCreated(MediaSession)} is called by patched YouTube code
 * right after every {@code new MediaSession(...)}.
 */
@SuppressWarnings("unused")
public final class AndroidAutoBridge {

    static final String TAG = "DelrazorAndroidAuto";

    /** Called on the main thread when YouTube creates a new media session. */
    interface Listener {
        void onSessionsChanged();
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Only touched on the main thread.
    private static final List<WeakReference<MediaSession>> sessions = new ArrayList<>();

    private static WeakReference<Listener> listener = new WeakReference<>(null);

    private AndroidAutoBridge() {
    }

    /**
     * Injection point. Called right after YouTube constructs a framework media session.
     * Can be called on any thread.
     */
    public static void onMediaSessionCreated(MediaSession session) {
        if (session == null) return;

        mainHandler.post(() -> {
            try {
                sessions.add(new WeakReference<>(session));
                Log.d(TAG, "YouTube media session created");

                Listener current = listener.get();
                if (current != null) current.onSessionsChanged();
            } catch (Exception ex) {
                Log.e(TAG, "onMediaSessionCreated failure", ex);
            }
        });
    }

    static void setListener(Listener newListener) {
        listener = new WeakReference<>(newListener);
    }

    /**
     * Controllers of every live YouTube session, newest first.
     * Must be called on the main thread.
     */
    static List<MediaController> liveControllers() {
        List<MediaController> controllers = new ArrayList<>();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            MediaSession session = sessions.get(i).get();
            if (session == null) continue;
            try {
                controllers.add(session.getController());
            } catch (Exception ex) {
                // The session was released.
            }
        }
        return controllers;
    }

    /**
     * The controller of the YouTube session that best represents what is playing:
     * a playing session first, then the newest one that has metadata,
     * then the newest one. Null if YouTube has no live session.
     * Must be called on the main thread.
     */
    static MediaController pickController() {
        MediaController withMetadata = null;
        MediaController newest = null;

        for (MediaController controller : liveControllers()) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                return controller;
            }
            if (withMetadata == null && controller.getMetadata() != null) {
                withMetadata = controller;
            }
            if (newest == null) {
                newest = controller;
            }
        }

        return withMetadata != null ? withMetadata : newest;
    }

    /** Stops tracking a session YouTube released. Must be called on the main thread. */
    static void forget(MediaSession.Token token) {
        Iterator<WeakReference<MediaSession>> it = sessions.iterator();
        while (it.hasNext()) {
            MediaSession session = it.next().get();
            if (session == null || session.getSessionToken().equals(token)) it.remove();
        }
    }

    /** Drops sessions that were garbage collected. Must be called on the main thread. */
    static void prune() {
        Iterator<WeakReference<MediaSession>> it = sessions.iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) it.remove();
        }
    }
}
