package app.delrazor.extension.youtube.androidauto;

import static app.delrazor.extension.youtube.androidauto.AndroidAutoBridge.TAG;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The service Android Auto connects to. Added to the YouTube manifest by the patch.
 * <p>
 * It owns its own media session and mirrors YouTube's playing session into it:
 * metadata and playback state flow from YouTube to the car, and the car's
 * play, pause, skip and seek commands flow back to YouTube.
 * <p>
 * A proxy session is used, instead of handing Android Auto YouTube's own session token,
 * because a MediaBrowserService can set its token only once, while YouTube can
 * create new sessions during its lifetime.
 */
@SuppressWarnings("unused")
public class AndroidAutoMediaService extends MediaBrowserService
        implements AndroidAutoBridge.Listener {

    private static final String ROOT_ID = "delrazor_root";
    private static final String NOW_PLAYING_ID = "delrazor_now_playing";

    /** Bigger artwork risks the binder transaction size limit. */
    private static final int MAX_ICON_SIZE = 320;

    private static final long SUPPORTED_ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_REWIND
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;

    /**
     * Apps allowed to browse and control playback. Any other caller gets nothing.
     * GUESS: package list based on Android Auto, its desktop head unit, the Google app
     * (Assistant) and Play services. Add more here if a car or tool cannot connect.
     */
    private static final Set<String> TRUSTED_CALLERS = new HashSet<>(Arrays.asList(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox", // Google app, Assistant
            "com.google.android.carassistant",
            "com.google.android.wearable.app",
            "com.android.systemui"
    ));

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaSession proxySession;

    /** The YouTube session being mirrored. */
    private MediaController youTubeController;

    /** Every YouTube session, watched to notice when another one starts playing or one is released. */
    private final Map<MediaController, MediaController.Callback> watchers = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Android Auto service created");

        proxySession = new MediaSession(this, "DelrazorAndroidAuto");
        proxySession.setCallback(proxyCallback, mainHandler);
        proxySession.setPlaybackState(idleState());
        setSessionToken(proxySession.getSessionToken());

        AndroidAutoBridge.setListener(this);
        onSessionsChanged();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Android Auto service destroyed");
        AndroidAutoBridge.setListener(null);
        unwatchAll();
        attachTo(null);
        proxySession.release();
        super.onDestroy();
    }

    // region Browsing

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (!isTrustedCaller(clientPackageName, clientUid)) {
            Log.w(TAG, "Refused media browser connection from " + clientPackageName);
            return null;
        }
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }

        MediaDescription.Builder description = new MediaDescription.Builder()
                .setMediaId(NOW_PLAYING_ID);

        MediaMetadata metadata = youTubeController == null ? null : youTubeController.getMetadata();
        if (metadata != null) {
            description.setTitle(metadata.getDescription().getTitle())
                    .setSubtitle(metadata.getDescription().getSubtitle())
                    .setIconBitmap(scaledArtwork(metadata));
        } else {
            // TODO: Replace with translatable strings once the patch adds string resources.
            description.setTitle("Nothing playing")
                    .setSubtitle("Start a video in YouTube on your phone");
        }

        List<MediaBrowser.MediaItem> items = new ArrayList<>(1);
        items.add(new MediaBrowser.MediaItem(description.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
        result.sendResult(items);
    }

    // endregion

    // region Following YouTube's sessions

    /** Called by the bridge when YouTube creates a session. */
    @Override
    public void onSessionsChanged() {
        AndroidAutoBridge.prune();

        // Watch every YouTube session, to notice when a different one starts playing.
        unwatchAll();
        for (MediaController controller : AndroidAutoBridge.liveControllers()) {
            MediaController.Callback watcher = newWatcher(controller);
            controller.registerCallback(watcher, mainHandler);
            watchers.put(controller, watcher);
        }

        selectBestSession();
    }

    /** Mirrors the YouTube session that is playing, or the best candidate. */
    private void selectBestSession() {
        MediaController best = AndroidAutoBridge.pickController();
        if (!sameSession(best, youTubeController)) {
            attachTo(best);
        }
    }

    private void unwatchAll() {
        for (Map.Entry<MediaController, MediaController.Callback> entry : watchers.entrySet()) {
            entry.getKey().unregisterCallback(entry.getValue());
        }
        watchers.clear();
    }

    private void attachTo(MediaController controller) {
        if (youTubeController != null) {
            youTubeController.unregisterCallback(mirrorCallback);
        }
        youTubeController = controller;

        if (controller == null) {
            Log.d(TAG, "No YouTube session to mirror");
            proxySession.setMetadata(null);
            proxySession.setPlaybackState(idleState());
            proxySession.setActive(false);
        } else {
            Log.d(TAG, "Mirroring YouTube session");
            controller.registerCallback(mirrorCallback, mainHandler);
            proxySession.setActive(true);
            mirrorMetadata(controller.getMetadata());
            mirrorPlaybackState(controller.getPlaybackState());
        }
        notifyChildrenChanged(ROOT_ID);
    }

    private MediaController.Callback newWatcher(MediaController controller) {
        return new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    selectBestSession();
                }
            }

            @Override
            public void onSessionDestroyed() {
                onYouTubeSessionReleased(controller);
            }
        };
    }

    private void onYouTubeSessionReleased(MediaController controller) {
        MediaController.Callback watcher = watchers.remove(controller);
        if (watcher != null) controller.unregisterCallback(watcher);

        AndroidAutoBridge.forget(controller.getSessionToken());
        if (sameSession(controller, youTubeController)) {
            attachTo(null);
        }
        selectBestSession();
    }

    private final MediaController.Callback mirrorCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mirrorMetadata(metadata);
            notifyChildrenChanged(ROOT_ID);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mirrorPlaybackState(state);
        }

        // A released session is handled by its watcher (see newWatcher).
    };

    private void mirrorMetadata(MediaMetadata metadata) {
        proxySession.setMetadata(metadata);
    }

    private void mirrorPlaybackState(PlaybackState state) {
        if (state == null) {
            proxySession.setPlaybackState(idleState());
            return;
        }

        PlaybackState.Builder builder = new PlaybackState.Builder(state)
                // YouTube may not advertise every action. The car only shows buttons
                // for advertised actions, and every one of them is forwarded below.
                .setActions(state.getActions() | SUPPORTED_ACTIONS);
        proxySession.setPlaybackState(builder.build());
    }

    // endregion

    // region Commands from the car

    private final MediaSession.Callback proxyCallback = new MediaSession.Callback() {
        @Override
        public void onPlay() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.play();
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            // The only playable item is "now playing", so resume it.
            onPlay();
        }

        @Override
        public void onPause() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.pause();
        }

        @Override
        public void onStop() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.stop();
        }

        @Override
        public void onSkipToNext() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.skipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.skipToPrevious();
        }

        @Override
        public void onSeekTo(long position) {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.seekTo(position);
        }

        @Override
        public void onFastForward() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.fastForward();
        }

        @Override
        public void onRewind() {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.rewind();
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            MediaController.TransportControls controls = controls();
            if (controls != null) controls.sendCustomAction(action, extras);
        }
    };

    /**
     * Controls of the mirrored YouTube session, or null after showing the car
     * an error that nothing is playing.
     */
    private MediaController.TransportControls controls() {
        if (youTubeController == null) {
            selectBestSession();
        }
        if (youTubeController == null) {
            proxySession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_ERROR, 0, 0)
                    .setActions(SUPPORTED_ACTIONS)
                    // TODO: Translatable string.
                    .setErrorMessage("Start a video in YouTube on your phone first")
                    .build());
            return null;
        }
        return youTubeController.getTransportControls();
    }

    // endregion

    // region Helpers

    private static PlaybackState idleState() {
        return new PlaybackState.Builder()
                .setState(PlaybackState.STATE_NONE, 0, 0)
                .setActions(SUPPORTED_ACTIONS)
                .build();
    }

    private static boolean sameSession(MediaController a, MediaController b) {
        if (a == null || b == null) return a == b;
        return a.getSessionToken().equals(b.getSessionToken());
    }

    private static Bitmap scaledArtwork(MediaMetadata metadata) {
        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) return null;

        int largest = Math.max(art.getWidth(), art.getHeight());
        if (largest <= MAX_ICON_SIZE) return art;

        float scale = (float) MAX_ICON_SIZE / largest;
        return Bitmap.createScaledBitmap(art,
                Math.max(1, Math.round(art.getWidth() * scale)),
                Math.max(1, Math.round(art.getHeight() * scale)),
                true);
    }

    private boolean isTrustedCaller(String packageName, int uid) {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true;
        if (!TRUSTED_CALLERS.contains(packageName)) return false;

        // Make sure the uid really belongs to that package, and that it is a system or
        // preinstalled app, so a sideloaded app cannot claim one of the trusted names.
        try {
            PackageManager pm = getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || !Arrays.asList(packages).contains(packageName)) return false;

            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            boolean isSystemApp = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // Android Auto is preinstalled as a system app on Android 10 and newer, but it is
            // a Play Store app on older phones. GUESS: Android Auto is signed with the same
            // Google key as Play services, so a matching signature is accepted too.
            boolean signedLikeGms = pm.checkSignatures(packageName, "com.google.android.gms")
                    == PackageManager.SIGNATURE_MATCH;
            return isSystemApp || signedLikeGms;
        } catch (Exception ex) {
            Log.e(TAG, "Could not verify caller " + packageName, ex);
            return false;
        }
    }

    // endregion
}
