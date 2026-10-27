package com.together.couple;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicitly user-started foreground service. A visible notification provides a stop control. */
public class ShareService extends Service {
    public static final String ACTION_STOP="com.together.couple.STOP_SHARE";
    private static final String CHANNEL="location_sharing";
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private LocationManager lm;
    private LocationListener listener;
    private long lastAttempt=0;
    private volatile boolean running=false;

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && ACTION_STOP.equals(intent.getAction())){
            running=false;
            work.execute(()->{
                try{new ApiClient(this).request("POST","/v1/privacy/sharing",new JSONObject().put("enabled",false));}
                catch(Exception ignored){}
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        if(running)return START_NOT_STICKY;
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED
           && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            stopSelf();return START_NOT_STICKY;
        }
        try{
            createNotificationChannel();
            Notification notification=makeNotification();
            if(Build.VERSION.SDK_INT>=29)startForeground(7,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(7,notification);
            lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            listener=this::onLocation;
            running=true;
            getSharedPreferences("settings",0).edit().putBoolean("service_running",true).apply();
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                    && lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,15000L,0f,listener,Looper.getMainLooper());
            if(lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,15000L,0f,listener,Looper.getMainLooper());
            if(Build.VERSION.SDK_INT>=31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER,15000L,0f,listener,Looper.getMainLooper());
            return START_NOT_STICKY; // Never restart the microphone/location service secretly after a system kill.
        }catch(Exception ex){running=false;stopSelf();return START_NOT_STICKY;}
    }
    private void onLocation(Location loc){
        if(!running||loc==null||!loc.hasAccuracy()||!Double.isFinite(loc.getLatitude())
           ||!Double.isFinite(loc.getLongitude()))return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastAttempt<12000)return;
        if(Math.abs(System.currentTimeMillis()-loc.getTime())>=110000)return;
        lastAttempt=now;
        work.execute(()->{
            if(!running)return;
            try{
                JSONObject j=new JSONObject();
                j.put("latitude",loc.getLatitude());j.put("longitude",loc.getLongitude());
                j.put("accuracy",Math.max(0,loc.getAccuracy()));
                java.text.SimpleDateFormat df=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US);
                df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                j.put("gpsTime",df.format(new java.util.Date(loc.getTime())));
                new ApiClient(this).request("POST","/v1/location",j);
            }catch(Exception ignored){/* Retry with next GPS update; no location is logged locally. */}
        });
    }
    private void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Together Location Sharing",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("দুজনের সম্মতিতে লোকেশন শেয়ারিং চলছে");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private Notification makeNotification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openPending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop=new Intent(this,ShareService.class).setAction(ACTION_STOP);
        PendingIntent stopPending=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder n=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return n.setSmallIcon(com.together.couple.R.drawable.app_icon)
            .setContentTitle("Together · লোকেশন শেয়ার চালু")
            .setContentText("আপনার সম্মতিতে সঙ্গীর সঙ্গে GPS লোকেশন শেয়ার হচ্ছে")
            .setOngoing(true).setContentIntent(openPending)
            .addAction(new Notification.Action.Builder(null,"শেয়ারিং বন্ধ করুন",stopPending).build())
            .build();
    }
    @Override public void onDestroy(){
        running=false;
        if(lm!=null && listener!=null)try{lm.removeUpdates(listener);}catch(Exception ignored){}
        getSharedPreferences("settings",0).edit().putBoolean("service_running",false).apply();
        work.shutdown();super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
