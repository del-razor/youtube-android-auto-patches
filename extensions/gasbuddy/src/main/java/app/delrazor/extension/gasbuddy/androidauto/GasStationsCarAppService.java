package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.HostInfo;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.validation.HostValidator;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * GasBuddy's Android Auto entry, added to the manifest by the patch with the "point of interest"
 * category. Android Auto binds to it and shows the screens it returns.
 */
public final class GasStationsCarAppService extends CarAppService {
    private static final String TAG = "DelRazorGasBuddyCar";

    /**
     * Apps allowed to show the car screens: Android Auto on the phone, and the Android Automotive
     * template host. Checked by package name below, because the signing key digests HostValidator
     * wants are not published. Android sets the package from the caller's identity, so another
     * app can't pretend to be one of these.
     */
    private static final Set<String> CAR_HOSTS = new HashSet<>(Arrays.asList(
            "com.google.android.projection.gearhead",
            "com.google.android.apps.automotive.templates.host",
            "com.google.android.car.templatehost"
    ));

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        HostInfo host = getHostInfo();
        final boolean allowed = host != null && CAR_HOSTS.contains(host.getPackageName());
        if (!allowed) {
            Log.w(TAG, "Car screen refused for " + (host != null ? host.getPackageName() : "unknown host"));
        }
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return allowed ? new StationListScreen(getCarContext())
                        : new RefusedScreen(getCarContext());
            }
        };
    }

    /** Shown when an app that is not a car host asks for the screens. */
    private static final class RefusedScreen extends Screen {
        RefusedScreen(@NonNull CarContext carContext) {
            super(carContext);
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            return new MessageTemplate.Builder("This car screen only opens in Android Auto.")
                    .setTitle("GasBuddy")
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }
    }
}
