package app.delrazor.extension.gasbuddy.androidauto;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone's location, using GasBuddy's own location permission. Android Auto binds the car
 * screen with the "while in use" capabilities, so this works with the phone locked.
 */
final class PhoneLocation {
    /** A last known fix newer than this is used as is. */
    private static final long FRESH_MILLIS = 2 * 60 * 1000;
    private static final long WAIT_SECONDS = 10;

    private PhoneLocation() {
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Blocks for up to {@link #WAIT_SECONDS}. Must not be called on the main thread. */
    static Location await(Context context) throws StationSourceException {
        if (!hasPermission(context)) {
            throw new StationSourceException(
                    "Open GasBuddy on your phone and allow location access.");
        }
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) throw new StationSourceException("This phone has no location service.");

        try {
            Location best = null;
            List<String> providers = manager.getProviders(true);
            for (String provider : providers) {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < FRESH_MILLIS) {
                return best;
            }

            String provider = pickProvider(manager, providers);
            if (provider == null) {
                if (best != null) return best;
                throw new StationSourceException("Turn on location on your phone.");
            }

            final Location[] fix = new Location[1];
            final CountDownLatch latch = new CountDownLatch(1);
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    fix[0] = location;
                    latch.countDown();
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
            try {
                latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                manager.removeUpdates(listener);
            }
            if (fix[0] != null) return fix[0];
            if (best != null) return best;
            throw new StationSourceException("Couldn't find your location. Try again in a moment.");
        } catch (SecurityException e) {
            throw new StationSourceException(
                    "Open GasBuddy on your phone and allow location access.", e);
        }
    }

    private static String pickProvider(LocationManager manager, List<String> enabled) {
        if (Build.VERSION.SDK_INT >= 31 && enabled.contains(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER;
        }
        if (enabled.contains(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        if (enabled.contains(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        return null;
    }
}
