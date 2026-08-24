package com.arcos.transport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

/**
 * Asks the user for access to a USB device.
 *
 * <p>Android refuses to open a USB device until the user has approved it, and the
 * approval arrives asynchronously as a broadcast. Getting this wrong is the usual
 * reason a working cable still produces "permission denied", so it lives here
 * rather than in every app that uses the library.
 *
 * <pre>
 *   UsbSerialDriver driver = UsbSerialTransport.firstDriver(this);
 *   UsbPermission.request(this, driver.getDevice(), granted -&gt; {
 *       if (granted) {
 *           robot.connect();
 *       }
 *   });
 * </pre>
 *
 * <p>The alternative is a {@code USB_DEVICE_ATTACHED} intent filter in the
 * manifest, which prompts when the adapter is plugged in and skips this entirely.
 */
public final class UsbPermission {

    /** Private to this library, so it cannot collide with an app's own actions. */
    private static final String ACTION = "com.arcos.USB_PERMISSION";

    /**
     * {@code Context.RECEIVER_NOT_EXPORTED}, added in API 33. Spelled out so the
     * library still compiles against an older SDK while behaving correctly on new
     * devices, where an undeclared runtime receiver is a hard error.
     */
    private static final int RECEIVER_NOT_EXPORTED = 4;

    /**
     * {@code PendingIntent.FLAG_MUTABLE}, added in API 31. The system writes the
     * result into this intent, so it must not be immutable.
     */
    private static final int FLAG_MUTABLE = 0x02000000;

    private UsbPermission() { }

    /** Called on the main thread once the user has answered. */
    public interface Callback {
        void onResult(boolean granted);
    }

    /** True when {@code device} can already be opened without prompting. */
    public static boolean has(Context context, UsbDevice device) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return manager != null && manager.hasPermission(device);
    }

    /**
     * Prompts for access unless it has already been granted, then invokes
     * {@code callback}. Safe to call when permission already exists — the callback
     * fires immediately and no dialog appears.
     */
    public static void request(Context context, UsbDevice device, Callback callback) {
        Context app = context.getApplicationContext();
        UsbManager manager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            callback.onResult(false);
            return;
        }
        if (manager.hasPermission(device)) {
            callback.onResult(true);
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (!ACTION.equals(intent.getAction())) {
                    return;
                }
                try {
                    ctx.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered; harmless.
                }
                // EXTRA_PERMISSION_GRANTED is the authority, but re-checking the
                // manager covers firmware that answers inconsistently.
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        || manager.hasPermission(device);
                callback.onResult(granted);
            }
        };

        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 requires every runtime receiver to declare its exposure.
            app.registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }

        Intent intent = new Intent(ACTION);
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 rejects an implicit intent inside a PendingIntent.
            intent.setPackage(app.getPackageName());
        }
        // The system fills in the result, so this cannot be immutable.
        int flags = Build.VERSION.SDK_INT >= 31 ? FLAG_MUTABLE : 0;
        PendingIntent pending = PendingIntent.getBroadcast(app, 0, intent, flags);
        manager.requestPermission(device, pending);
    }
}
