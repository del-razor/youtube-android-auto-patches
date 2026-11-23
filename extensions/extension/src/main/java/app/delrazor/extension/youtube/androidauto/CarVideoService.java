package app.delrazor.extension.youtube.androidauto;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * Entry point Android Auto starts when YouTube is opened from the car's app launcher.
 * Declared in the YouTube manifest by the "Android Auto video" patch.
 */
@SuppressWarnings("unused")
public class CarVideoService extends CarActivityService {

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return CarVideoActivity.class;
    }
}
