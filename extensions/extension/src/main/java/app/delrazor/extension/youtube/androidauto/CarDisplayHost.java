package app.delrazor.extension.youtube.androidauto;

import static app.delrazor.extension.youtube.androidauto.AndroidAutoBridge.TAG;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import rikka.shizuku.Shizuku;

/**
 * App side of {@link CarDisplayService}: starts it through Shizuku and calls it.
 * All methods run on the main thread.
 */
final class CarDisplayHost {

    interface Listener {
        void onConnected();

        /** Shizuku is not there or not allowed. {@code waitingForUser} means a prompt is open. */
        void onUnavailable(String reason, boolean waitingForUser);
    }

    /** Raise when CarDisplayService changes, so Shizuku restarts it instead of reusing it. */
    private static final int SERVICE_VERSION = 1;
    private static final int PERMISSION_REQUEST_CODE = 4512;
    private static final long BINDER_WAIT_MS = 3000;
    private static final long BIND_TIMEOUT_MS = 10000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Listener listener;
    private final Shizuku.UserServiceArgs args;

    private IBinder service;
    private boolean closed;
    private boolean bound;

    CarDisplayHost(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.args = new Shizuku.UserServiceArgs(new ComponentName(this.context.getPackageName(),
                CarDisplayService.class.getName()))
                .daemon(false)
                .processNameSuffix("car_display")
                .debuggable(false)
                .version(SERVICE_VERSION);
    }

    boolean isConnected() {
        return service != null && service.isBinderAlive();
    }

    // region Connecting

    void connect() {
        if (ping()) {
            onBinder();
            return;
        }
        // Shizuku's link can arrive a moment after YouTube starts.
        final boolean[] done = {false};
        Shizuku.OnBinderReceivedListener received = new Shizuku.OnBinderReceivedListener() {
            @Override
            public void onBinderReceived() {
                if (done[0] || closed) return;
                done[0] = true;
                Shizuku.removeBinderReceivedListener(this);
                onBinder();
            }
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(received, mainHandler);
        } catch (Throwable ex) {
            Log.d(TAG, "Shizuku is not usable: " + ex);
        }
        mainHandler.postDelayed(() -> {
            if (done[0] || closed) return;
            done[0] = true;
            try {
                Shizuku.removeBinderReceivedListener(received);
            } catch (Throwable ignored) {
            }
            listener.onUnavailable("Shizuku is not running", false);
        }, BINDER_WAIT_MS);
    }

    private static boolean ping() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ex) {
            return false;
        }
    }

    private void onBinder() {
        try {
            if (Shizuku.isPreV11()) {
                listener.onUnavailable("Shizuku is too old, update it", false);
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind();
                return;
            }
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return;
                    Shizuku.removeRequestPermissionResultListener(this);
                    if (closed) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        bind();
                    } else {
                        listener.onUnavailable("Shizuku access was denied for YouTube", false);
                    }
                }
            }, mainHandler);
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            listener.onUnavailable("Allow YouTube in the Shizuku prompt on your phone (one time)", true);
        } catch (Throwable ex) {
            Log.e(TAG, "Shizuku error", ex);
            listener.onUnavailable("Shizuku error: " + ex, false);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            if (binder == null || !binder.pingBinder()) {
                listener.onUnavailable("the Shizuku car display service did not start", false);
                return;
            }
            service = binder;
            Log.d(TAG, "Car display service connected");
            listener.onConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Car display service disconnected");
            service = null;
        }
    };

    private void bind() {
        try {
            Shizuku.bindUserService(args, connection);
            bound = true;
        } catch (Throwable ex) {
            Log.e(TAG, "Could not start the car display service", ex);
            listener.onUnavailable("could not start the Shizuku service: " + ex, false);
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!closed && service == null) {
                listener.onUnavailable("the Shizuku car display service did not start", false);
            }
        }, BIND_TIMEOUT_MS);
    }

    void close() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (service != null) {
            try {
                call(CarDisplayService.RELEASE, null);
            } catch (Exception ignored) {
            }
        }
        if (bound) {
            try {
                // Ends the service process too.
                Shizuku.unbindUserService(args, connection, true);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        service = null;
    }

    // endregion

    // region Calls

    private interface Writer {
        void write(Parcel data);
    }

    private Parcel call(int code, Writer writer) throws Exception {
        IBinder binder = service;
        if (binder == null) throw new IllegalStateException("car display service not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CarDisplayService.DESCRIPTOR);
            if (writer != null) writer.write(data);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply;
        } catch (Exception ex) {
            reply.recycle();
            throw ex;
        } finally {
            data.recycle();
        }
    }

    /** Creates the trusted car display and returns its id. Throws with the reason on failure. */
    int createDisplay(Surface surface, int width, int height, int dpi) throws Exception {
        Parcel reply = call(CarDisplayService.CREATE_DISPLAY, data -> {
            surface.writeToParcel(data, 0);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(dpi);
        });
        try {
            return reply.readInt();
        } finally {
            reply.recycle();
        }
    }

    /** Opens the component on the car display as the shell user. Throws with the reason. */
    void launch(ComponentName component) throws Exception {
        call(CarDisplayService.LAUNCH,
                data -> data.writeString(component.flattenToShortString())).recycle();
    }

    void setSurface(Surface surface) {
        try {
            call(CarDisplayService.SET_SURFACE, data -> {
                data.writeInt(surface == null ? 0 : 1);
                if (surface != null) surface.writeToParcel(data, 0);
            }).recycle();
        } catch (Exception ex) {
            Log.w(TAG, "Could not set the car display surface", ex);
        }
    }

    void resize(int width, int height, int dpi) {
        try {
            call(CarDisplayService.RESIZE, data -> {
                data.writeInt(width);
                data.writeInt(height);
                data.writeInt(dpi);
            }).recycle();
        } catch (Exception ex) {
            Log.w(TAG, "Could not resize the car display", ex);
        }
    }

    void release() {
        try {
            call(CarDisplayService.RELEASE, null).recycle();
        } catch (Exception ex) {
            Log.w(TAG, "Could not release the car display", ex);
        }
    }

    /** Sends a touch or key from the car to the car display as real input. */
    void inject(InputEvent event) {
        try {
            call(CarDisplayService.INJECT, data -> {
                boolean motion = event instanceof MotionEvent;
                data.writeInt(motion ? 1 : 0);
                if (motion) {
                    ((MotionEvent) event).writeToParcel(data, 0);
                } else {
                    ((KeyEvent) event).writeToParcel(data, 0);
                }
            }).recycle();
        } catch (Exception ex) {
            Log.w(TAG, "Could not send input to the car display", ex);
        }
    }

    // endregion
}
