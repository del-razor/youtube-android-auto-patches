package app.delrazor.extension.youtube.androidauto;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Audio delay for the car screen.
 *
 * <p>The car shows the picture later than it plays the sound. YouTube's player times video frames
 * by how far its AudioTrack has played, and the patch routes those position calls through here.
 * While the car screen is open, the position is reported {@code delayMs} ahead, so YouTube shows
 * each frame that much earlier. On the car that looks like the sound was delayed to match the
 * picture. A negative value does the opposite.
 *
 * <p>The reported position never runs backwards, because the player would read a backwards step
 * as the counter wrapping around. When the delay is made smaller, the reported position slows
 * down to half speed until it has caught up instead.
 */
public final class CarAudioSync {

    static final int STEP_MS = 25;
    static final int MIN_MS = -500;
    static final int MAX_MS = 1500;

    private static final String PREFS = "delrazor_android_auto";
    private static final String KEY_DELAY = "car_audio_delay_ms";

    private static volatile int delayMs;
    private static volatile boolean carScreenOpen;

    /** Per AudioTrack: [0] head position state, [1] timestamp state. */
    private static final Map<AudioTrack, Shift[]> shifts = new WeakHashMap<>();

    private CarAudioSync() {
    }

    // region Car screen side

    static void load(Context context) {
        delayMs = prefs(context).getInt(KEY_DELAY, 0);
    }

    static int getDelayMs() {
        return delayMs;
    }

    static void setDelayMs(Context context, int value) {
        delayMs = Math.max(MIN_MS, Math.min(MAX_MS, value));
        prefs(context).edit().putInt(KEY_DELAY, delayMs).apply();
    }

    static void setCarScreenOpen(boolean open) {
        carScreenOpen = open;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // endregion

    // region Called from YouTube's player (patched calls)

    public static int getPlaybackHeadPosition(AudioTrack track) {
        int raw = track.getPlaybackHeadPosition();
        long target = targetFrames(track);
        Shift shift = shift(track, 0, target);
        if (shift == null) return raw;
        // The head position is an unsigned 32 bit counter.
        long real = raw & 0xFFFFFFFFL;
        return (int) shift.apply(real, target);
    }

    public static boolean getTimestamp(AudioTrack track, AudioTimestamp timestamp) {
        boolean ok = track.getTimestamp(timestamp);
        if (!ok) return false;
        long target = targetFrames(track);
        Shift shift = shift(track, 1, target);
        if (shift != null) timestamp.framePosition = shift.apply(timestamp.framePosition, target);
        return true;
    }

    private static long targetFrames(AudioTrack track) {
        int delay = delayMs;
        if (!carScreenOpen || delay == 0) return 0;
        return (long) delay * track.getSampleRate() / 1000;
    }

    /** Null when there is nothing to shift and never was for this track. */
    private static Shift shift(AudioTrack track, int slot, long target) {
        synchronized (shifts) {
            Shift[] pair = shifts.get(track);
            if (pair == null) {
                if (target == 0) return null;
                pair = new Shift[]{new Shift(), new Shift()};
                shifts.put(track, pair);
            }
            return pair[slot];
        }
    }

    private static final class Shift {
        private long lastReal = -1;
        private long lastReported = -1;
        private long applied;

        synchronized long apply(long real, long target) {
            if (lastReal >= 0 && real < lastReal) {
                // Flushed or restarted: the player resets its own counting too.
                lastReported = -1;
                applied = target;
            }
            long elapsed = lastReal < 0 ? 0 : real - lastReal;
            lastReal = real;

            if (target >= applied) {
                applied = target;
            } else {
                // Shrink by at most half the elapsed frames, so the position keeps moving forward.
                applied = Math.max(target, applied - elapsed / 2);
            }

            long reported = Math.max(0, real + applied);
            if (lastReported >= 0 && reported < lastReported) reported = lastReported;
            lastReported = reported;
            return reported;
        }
    }

    // endregion
}
