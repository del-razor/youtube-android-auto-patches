package app.delrazor.extension.gasbuddy.signature;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Makes GasBuddy see its own original signing certificate when it asks Android for it.
 *
 * Patching re-signs the app with a different key. Google Maps checks GasBuddy's API key against
 * the package name and the certificate the app reports, so a re-signed GasBuddy gets a blank
 * map. The Maps code runs inside GasBuddy's process and reads the certificate through the
 * PackageManager, so the process's link to the package service is wrapped: package lookups for
 * GasBuddy itself get the original certificate (the public one from the Play Store APK). Nothing
 * changes for other apps, and Android itself still checks the real signature.
 */
public final class OriginalSignature {
    private static final String TAG = "DelRazorGasBuddySig";

    /** GasBuddy's Play Store signing certificate (SHA-1 29:6B:63:6F:...:0B:44:0B). */
    private static final String CERTIFICATE_BASE64 =
            "MIIDbjCCAlagAwIBAgIETBuXGDANBgkqhkiG9w0BAQUFADB5MQswCQYDVQQGEwJDQTELMAkGA1UECBMCU0sxDzANBgNVBAcTBlJlZ2luYTEeMBwGA1UEChMVR0IgSW50ZXJuZXQgU29sdXRpb25zMRQwEgYDVQQLEwtNb2JpbGUgVGVhbTEWMBQGA1UEAxMNRHVzdGluIENvdXBhbDAeFw0xMDA2MTgxNTU2MDhaFw0zNzExMDMxNTU2MDhaMHkxCzAJBgNVBAYTAkNBMQswCQYDVQQIEwJTSzEPMA0GA1UEBxMGUmVnaW5hMR4wHAYDVQQKExVHQiBJbnRlcm5ldCBTb2x1dGlvbnMxFDASBgNVBAsTC01vYmlsZSBUZWFtMRYwFAYDVQQDEw1EdXN0aW4gQ291cGFsMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuMUh3vr7cuVpgdpRvUTFceKGrI9G3yIuYC56+2ruwJV/d2npd3jA230CfYqhBsbiPZ0NaFma93QetuuoZmc2iEA7YhLOJiy7Mt2rD+Bja7wqo95PyScn74qzWOvjWE7sCX8th6gjvuXCis0M7i75FtK/xzFi/Aw9iMTT4v0e8orbIqdELwILlGFgr3D/bWJaB5yGK8S0aggYtxxUGntUWZN5uwTj2FTwMzurIrsFpNtcMEcyAEPeq/zk6UhDgBpu9etjbawZ2D6WTEnRXnR6qo9qMBQR6+GBmz7gXKvcN+MZsk0c6Gr5RGKv1yorSpSTEV8d21R+p4YuXTxZi4XxDwIDAQABMA0GCSqGSIb3DQEBBQUAA4IBAQAgYIOmzg8Gc1Nxr0JJk9bzQG0gDqsLu1BWlg16AF3DNznW9OD8tJ+uaLa0AJqmv9p1KHwGt2yqSC2kLvqiAVWdvXr0VAWLJIw7MjYPrPczSED8zwe4bkpaIVlvjeGfh8K5ruwIvWyZ3TirKJTIKNuGkRuurSgvK5iS5NJjjO0oT25sTf2HhRM1vSfEU1XDrpGVofqKZ1xCO0uNG0Likv1mt29ULhG6PVqvVX3btdH+DOY1hA1B6Sj4xOVBqYoA7EYLHIqGLUCKteePIppfLUAzvHch+F4oGsmUbin9/+dDmwWpFCCpm8yr99bDEupnWy6S6BfTn1b12TXhc2/1AT1M";

    private static boolean installed;

    private OriginalSignature() {
    }

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        try {
            final String packageName = context.getPackageName();
            final Signature original = new Signature(Base64.decode(CERTIFICATE_BASE64, Base64.DEFAULT));

            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method getPackageManager = activityThread.getDeclaredMethod("getPackageManager");
            final Object realService = getPackageManager.invoke(null);
            Class<?> serviceInterface = Class.forName("android.content.pm.IPackageManager");

            Object wrapped = Proxy.newProxyInstance(serviceInterface.getClassLoader(),
                    new Class<?>[]{serviceInterface}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            boolean ours = args != null && args.length > 0 && packageName.equals(args[0]);
                            if (ours && "hasSigningCertificate".equals(name) && args.length > 1
                                    && args[1] instanceof byte[]) {
                                if (Arrays.equals((byte[]) args[1], original.toByteArray())) return true;
                            }
                            Object result;
                            try {
                                result = method.invoke(realService, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                            if (ours && result instanceof PackageInfo) {
                                replaceSignatures((PackageInfo) result, original);
                            }
                            return result;
                        }
                    });

            Field serviceField = activityThread.getDeclaredField("sPackageManager");
            serviceField.setAccessible(true);
            serviceField.set(null, wrapped);

            // The app's own PackageManager already holds the old link.
            replaceLink(context.getPackageManager(), wrapped);
            Context app = context.getApplicationContext();
            if (app != null) replaceLink(app.getPackageManager(), wrapped);

            // Android caches package lookups per process. Drop what was cached before this ran.
            try {
                PackageManager.class.getDeclaredMethod("disablePackageInfoCache").invoke(null);
            } catch (Throwable ignored) {
                // Older Android has no such cache.
            }
            Log.i(TAG, "Original signature in place");
        } catch (Throwable t) {
            Log.e(TAG, "Couldn't put the original signature in place; Google Maps may stay blank", t);
        }
    }

    private static void replaceLink(PackageManager manager, Object wrapped) {
        try {
            Field field = manager.getClass().getDeclaredField("mPM");
            field.setAccessible(true);
            field.set(manager, wrapped);
        } catch (Throwable t) {
            Log.w(TAG, "PackageManager link not replaced", t);
        }
    }

    @SuppressWarnings("deprecation")
    private static void replaceSignatures(PackageInfo info, Signature original) {
        if (info.signatures != null) info.signatures = new Signature[]{original};
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            try {
                Field detailsField = info.signingInfo.getClass().getDeclaredField("mSigningDetails");
                detailsField.setAccessible(true);
                Object details = detailsField.get(info.signingInfo);
                if (details == null) return;
                // android.content.pm.SigningDetails (Android 13+) or PackageParser.SigningDetails.
                setField(details, new String[]{"mSignatures", "signatures"}, new Signature[]{original});
                setField(details, new String[]{"mPastSigningCertificates", "pastSigningCertificates"}, null);
            } catch (Throwable t) {
                Log.w(TAG, "Signing info not replaced", t);
            }
        }
    }

    private static void setField(Object target, String[] names, Object value) {
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Try the next name.
            } catch (Throwable t) {
                Log.w(TAG, "Couldn't set " + name, t);
                return;
            }
        }
    }
}
