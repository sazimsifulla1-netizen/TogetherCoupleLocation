Package com.together.couple;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareService extends Service {

    public static final String ACTION_STOP = "com.together.couple.STOP_SHARE";
    private static final String CHANNEL = "location_sharing";

    // লোকেশন আপডেটের ইন্টারভাল (যেমন: ১৫ মিনিট বা প্রয়োজন অনুযায়ী পরিবর্তন করতে পারেন)
    private static final long LOCATION_INTERVAL_MS = 15 * 60 * 1000L;

    // লোকেশন খুব পুরোনো হলে পাঠানো হবে না (২ মিনিট)
    private static final long MAX_LOCATION_AGE_MS = 2 * 60 * 1000L;

    private final ExecutorService work = Executors.newSingleThreadExecutor();

    private LocationManager lm;
    private LocationListener listener;

    private long lastAttempt = 0;
    private volatile boolean running = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;

            work.execute(() -> {
                try {
                    new ApiClient(this).request(
                            "POST",
                            "/v1/privacy/sharing",
                            new JSONObject().put("enabled", false)
                    );
                } catch (Exception ignored) {
                }
                stopSelf();
            });

            return START_NOT_STICKY;
        }

        if (running) {
            return START_NOT_STICKY;
        }

        // লোকেশন পারমিশন চেক (Android 10+ এর জন্য Background Location সহ)
        boolean hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        
        if (!hasFine && !hasCoarse) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // ব্যাকগ্রাউন্ড পারমিশন না থাকলে ফোরগ্রাউন্ড সার্ভিস সঠিকভাবে চলতে সমস্যা হতে পারে
                // সেক্ষেত্রে প্রয়োজনে ব্যবহারকারীকে নোটিফিকেশন বা অ্যাক্টিভিটির মাধ্যমে পারমিশন চাইতে হবে।
            }
        }

        try {
            createNotificationChannel();
            Notification notification = makeNotification();

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        7,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            } else {
                startForeground(7, notification);
            }

            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            listener = this::onLocation;

            running = true;

            getSharedPreferences("settings", 0)
                    .edit()
                    .putBoolean("service_running", true)
                    .apply();

            // GPS Provider
            if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        10f, // ১০ মিটার নড়াচড়া করলে আপডেট ধরবে
                        listener,
                        Looper.getMainLooper()
                );
            }

            // Network Provider
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        10f,
                        listener,
                        Looper.getMainLooper()
                );
            }

            // Fused Provider (API 31+)
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        10f,
                        listener,
                        Looper.getMainLooper()
                );
            }

            return START_STICKY; // সার্ভিস কোনো কারণে কিল হলে সিস্টেম যেন আবার রিস্টার্ট করার চেষ্টা করে

        } catch (Exception ex) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void onLocation(Location loc) {
        if (!running || loc == null || !loc.hasAccuracy()) {
            return;
        }

        if (!Double.isFinite(loc.getLatitude()) || !Double.isFinite(loc.getLongitude())) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        // নির্দিষ্ট সময়ের আগে দ্বিতীয়বার পাঠাবে না
        if (lastAttempt != 0 && now - lastAttempt < LOCATION_INTERVAL_MS) {
            return;
        }

        // পুরোনো লোকেশন পাঠানো হবে না
        if (Math.abs(System.currentTimeMillis() - loc.getTime()) > MAX_LOCATION_AGE_MS) {
            return;
        }

        lastAttempt = now;

        work.execute(() -> {
            if (!running) {
                return;
            }

            try {
                JSONObject j = new JSONObject();
                j.put("latitude", loc.getLatitude());
                j.put("longitude", loc.getLongitude());
                j.put("accuracy", Math.max(0, loc.getAccuracy()));

                SimpleDateFormat df = new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        Locale.US
                );
                df.setTimeZone(TimeZone.getTimeZone("UTC"));

                j.put("gpsTime", df.format(new Date(loc.getTime())));

                new ApiClient(this).request(
                        "POST",
                        "/v1/location",
                        j
                );

            } catch (Exception ignored) {
                // ইন্টারনেট না থাকলে বা ফেইল করলে পরবর্তী আপডেটে আবার চেষ্টা হবে
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "Together Location Sharing",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("দুজনের সম্মতিতে লোকেশন শেয়ারিং চলছে");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification makeNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stop = new Intent(this, ShareService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                1,
                stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder n = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return n.setSmallIcon(com.together.couple.R.drawable.app_icon)
                .setContentTitle("Together · লোকেশন শেয়ার চালু")
                .setContentText("আপনার সম্মতিতে লোকেশন আপডেটের কাজ চলছে")
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(
                        new Notification.Action.Builder(
                                null,
                                "শেয়ারিং বন্ধ করুন",
                                stopPending
                        ).build()
                )
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;

        if (lm != null && listener != null) {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }

        getSharedPreferences("settings", 0)
                .edit()
                .putBoolean("service_running", false)
                .apply();

        work.shutdown();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
