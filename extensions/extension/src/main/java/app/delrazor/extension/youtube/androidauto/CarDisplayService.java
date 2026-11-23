package app.delrazor.extension.youtube.androidauto;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Runs in a separate process started by Shizuku as the phone's shell user, the same identity
 * scrcpy uses. It does the three things a normal app may not do for the car screen:
 *
 * <ul>
 *   <li>Creates a <b>trusted</b> virtual display in its <b>own display group</b> that is
 *   <b>always unlocked</b>. Android then keeps it on, and YouTube on it playing, while the phone
 *   screen is off or locked.</li>
 *   <li>Lets the phone's keyboard open on that display (IME policy "local"), so typing happens
 *   on the car.</li>
 *   <li>Injects the car's touches and keys as real input on that display, so every window there
 *   gets them: YouTube, its pop-up menus and bottom sheets, and the keyboard.</li>
 * </ul>
 *
 * <p>The app talks to it through {@link CarDisplayHost} with the transaction codes below.
 * Hidden framework methods are reached by reflection. That is allowed in this process because
 * Shizuku starts it with app_process, not as a normal app.
 */
public class CarDisplayService extends Binder {

    static final String DESCRIPTOR = "app.delrazor.extension.youtube.androidauto.CarDisplayService";

    static final int CREATE_DISPLAY = 1;      // (Surface, w, h, dpi) -> displayId or -1, error
    static final int SET_SURFACE = 2;         // (hasSurface, Surface?)
    static final int RESIZE = 3;              // (w, h, dpi)
    static final int RELEASE = 4;
    static final int INJECT = 5;              // (isMotion, MotionEvent | KeyEvent) -> ok
    static final int PING = 6;                // -> uid
    static final int LAUNCH = 7;              // (component) -> am output

    /** Shizuku sends this when it wants the service to end. */
    private static final int SHIZUKU_DESTROY = 16777115;

    // DisplayManager flags. The hidden ones are copied from the framework (same as scrcpy).
    private static final int FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int FLAG_OWN_FOCUS = 1 << 14;

    private static final int DISPLAY_IME_POLICY_LOCAL = 0;
    private static final int INJECT_MODE_ASYNC = 0;

    // Same tag as the rest of the extension. Not read from AndroidAutoBridge, so that class is
    // never loaded in this process.
    private static final String TAG = "DelrazorAndroidAuto";

    private static final String SHELL_PACKAGE = "com.android.shell";

    private final Context context;
    private VirtualDisplay display;
    private InputManager inputManager;

    public CarDisplayService() {
        this(null);
    }

    public CarDisplayService(Context context) {
        this.context = context;
        Log.d(TAG, "Car display service started as uid "
                + android.os.Process.myUid());
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == SHIZUKU_DESTROY) {
            release();
            System.exit(0);
            return true;
        }
        if (code < CREATE_DISPLAY || code > LAUNCH) return super.onTransact(code, data, reply, flags);

        data.enforceInterface(DESCRIPTOR);
        try {
            switch (code) {
                case CREATE_DISPLAY: {
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    int width = data.readInt();
                    int height = data.readInt();
                    int dpi = data.readInt();
                    int id = createDisplay(surface, width, height, dpi);
                    reply.writeNoException();
                    reply.writeInt(id);
                    return true;
                }
                case SET_SURFACE: {
                    Surface surface = data.readInt() != 0
                            ? Surface.CREATOR.createFromParcel(data) : null;
                    if (display != null) display.setSurface(surface);
                    reply.writeNoException();
                    return true;
                }
                case RESIZE: {
                    int width = data.readInt();
                    int height = data.readInt();
                    int dpi = data.readInt();
                    if (display != null) display.resize(width, height, dpi);
                    reply.writeNoException();
                    return true;
                }
                case RELEASE:
                    release();
                    reply.writeNoException();
                    return true;
                case INJECT: {
                    boolean motion = data.readInt() != 0;
                    InputEvent event = motion ? MotionEvent.CREATOR.createFromParcel(data)
                            : KeyEvent.CREATOR.createFromParcel(data);
                    boolean ok = inject(event);
                    reply.writeNoException();
                    reply.writeInt(ok ? 1 : 0);
                    return true;
                }
                case LAUNCH: {
                    String output = launch(data.readString());
                    reply.writeNoException();
                    reply.writeString(output);
                    return true;
                }
                case PING:
                    reply.writeNoException();
                    reply.writeInt(android.os.Process.myUid());
                    return true;
                default:
                    return false;
            }
        } catch (Throwable ex) {
            Log.e(TAG, "Car display service call " + code + " failed", ex);
            // Report the error to the app as an exception it can show in the car readout.
            reply.writeException(ex instanceof RuntimeException ? (RuntimeException) ex
                    : new IllegalStateException(ex.toString()));
            return true;
        }
    }

    private int createDisplay(Surface surface, int width, int height, int dpi) throws Exception {
        release();
        int flags = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH
                | FLAG_DESTROY_CONTENT_ON_REMOVAL | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP
                | FLAG_ALWAYS_UNLOCKED | FLAG_TOUCH_FEEDBACK_DISABLED;
        if (Build.VERSION.SDK_INT >= 34) flags |= FLAG_OWN_FOCUS;

        display = displayManager().createVirtualDisplay("DelrazorCarYouTube",
                width, height, dpi, surface, flags);
        int id = display.getDisplay().getDisplayId();
        Log.d(TAG, "Trusted car display " + id + " created: "
                + width + "x" + height + " @" + dpi);

        // Let the phone's keyboard open on the car display instead of the phone.
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object windowManager = wmg.getMethod("getWindowManagerService").invoke(null);
            windowManager.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
                    .invoke(windowManager, id, DISPLAY_IME_POLICY_LOCAL);
        } catch (Throwable ex) {
            Log.w(TAG, "Could not move the keyboard to the car display", ex);
        }
        return id;
    }

    /**
     * DisplayManager checks that the package name it is given belongs to the caller, which is the
     * shell user here, so it gets a context that names the shell package.
     */
    private DisplayManager displayManager() throws Exception {
        Context base = context;
        if (base == null) throw new IllegalStateException("Shizuku gave the service no context");
        Context shellContext = new ContextWrapper(base) {
            @Override
            public String getPackageName() {
                return SHELL_PACKAGE;
            }

            @Override
            public String getOpPackageName() {
                return SHELL_PACKAGE;
            }
        };
        Constructor<DisplayManager> constructor =
                DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(shellContext);
    }

    private boolean inject(InputEvent event) throws Exception {
        if (display == null) return false;
        int displayId = display.getDisplay().getDisplayId();
        if (event instanceof MotionEvent) {
            ((MotionEvent) event).setSource(InputDevice.SOURCE_TOUCHSCREEN);
        }
        Method setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        setDisplayId.invoke(event, displayId);

        if (inputManager == null) {
            inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        }
        Method injectInputEvent =
                InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        return (boolean) injectInputEvent.invoke(inputManager, event, INJECT_MODE_ASYNC);
    }

    /**
     * Opens an activity on the car display as the shell user. An app that has no window on screen
     * may not start activities (background launch rules), so the first YouTube window is opened
     * from here. YouTube opens its later screens itself from that window.
     */
    private String launch(String component) throws Exception {
        if (display == null) throw new IllegalStateException("no car display");
        int displayId = display.getDisplay().getDisplayId();
        Process process = Runtime.getRuntime().exec(new String[]{
                "am", "start", "--display", String.valueOf(displayId),
                "-f", "0x18000000", "-n", component});
        StringBuilder out = new StringBuilder();
        for (java.io.InputStream stream : new java.io.InputStream[]{
                process.getInputStream(), process.getErrorStream()}) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < 600) out.append(line).append(' ');
                }
            }
        }
        int code = process.waitFor();
        Log.d(TAG, "am start on display " + displayId + " exited " + code + ": " + out);
        if (code != 0 || out.indexOf("Error") >= 0 || out.indexOf("Exception") >= 0) {
            throw new IllegalStateException(out.toString().trim());
        }
        return out.toString().trim();
    }

    private void release() {
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
    }
}
