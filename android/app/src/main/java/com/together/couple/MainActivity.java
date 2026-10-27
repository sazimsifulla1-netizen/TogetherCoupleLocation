package com.together.couple;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowInsets;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final int LOCATION_REQ=501;
    private String permissionCallbackId=null;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(23,19,47));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(23,19,47));
        web=new WebView(this);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,android.webkit.WebResourceRequest request){return true;}
            @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest req,android.webkit.WebResourceError error){
                if(req.isForMainFrame())Toast.makeText(MainActivity.this,"ইন্টারফেস লোড হয়নি",Toast.LENGTH_SHORT).show();
            }
        });
        if(Build.VERSION.SDK_INT>=30) web.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets a=insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(a.left,a.top,a.right,a.bottom);
            return insets;
        });
        web.addJavascriptInterface(new Bridge(),"TogetherNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }
    private void reply(String id,boolean ok,JSONObject result){
        String js="window.nativeReply("+JSONObject.quote(id)+","+ok+","+JSONObject.quote(result.toString())+")";
        runOnUiThread(()->{if(web!=null)web.evaluateJavascript(js,null);});
    }
    private void fail(String id,String message){
        JSONObject obj=new JSONObject();
        try{obj.put("error",message);}catch(Exception ignored){}
        reply(id,false,obj);
    }
    private void startNativeShare(String id){
        runOnUiThread(()->{
            boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            boolean coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            if(!fine&&!coarse){
                permissionCallbackId=id;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQ);
            }else{startServiceAndReply(id);}
        });
    }
    private void startServiceAndReply(String id){
        try{
            android.location.LocationManager lm=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);
            if(Build.VERSION.SDK_INT>=28 && !lm.isLocationEnabled()){
                fail(id,"ফোনের Location/GPS চালু করুন");return;
            }
            Intent in=new Intent(this,ShareService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(in);else startService(in);
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},502);
            reply(id,true,new JSONObject().put("started",true));
        }catch(Exception e){fail(id,"লোকেশন সার্ভিস চালু করা যায়নি: "+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==LOCATION_REQ && permissionCallbackId!=null){
            String id=permissionCallbackId;permissionCallbackId=null;
            boolean coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            if(coarse||fine)startServiceAndReply(id);
            else fail(id,"লোকেশন অনুমতি না দিলে শেয়ার চালু করা যাবে না");
        }
    }
    class Bridge {
        @JavascriptInterface public void call(String id,String method,String path,String body){
            worker.execute(()->{
                try{
                    JSONObject payload=body==null||body.isEmpty()?null:new JSONObject(body);
                    reply(id,true,new ApiClient(MainActivity.this).request(method,path,payload));
                }catch(Exception e){fail(id,e.getMessage()==null?"ইন্টারনেট বা সার্ভার সমস্যা":e.getMessage());}
            });
        }
        @JavascriptInterface public void setServer(String id,String url){
            runOnUiThread(()->{
                String old=ApiClient.configuredBase(MainActivity.this);
                if(!ApiClient.setServer(MainActivity.this,url)){
                    fail(id,"HTTPS:// দিয়ে সঠিক সার্ভার URL দিন");return;
                }
                if(!old.equals(ApiClient.configuredBase(MainActivity.this))){
                    stopService(new Intent(MainActivity.this,ShareService.class));
                    new TokenVault(MainActivity.this).clear();
                }
                try{reply(id,true,new JSONObject().put("server",ApiClient.configuredBase(MainActivity.this)));}
                catch(Exception e){fail(id,"সার্ভার URL সংরক্ষণ হয়নি");}
            });
        }
        @JavascriptInterface public void config(String id){
            try{
                JSONObject j=new JSONObject().put("server",ApiClient.configuredBase(MainActivity.this))
                    .put("loggedIn",new TokenVault(MainActivity.this).token()!=null)
                    .put("serviceActive",getSharedPreferences("settings",0).getBoolean("service_running",false));
                reply(id,true,j);
            }catch(Exception e){fail(id,"কনফিগ পড়তে সমস্যা");}
        }
        @JavascriptInterface public void startShare(String id){startNativeShare(id);}
        @JavascriptInterface public void stopShare(String id){
            runOnUiThread(()->{
                stopService(new Intent(MainActivity.this,ShareService.class));
                reply(id,true,new JSONObject());
            });
        }
        @JavascriptInterface public void shareInvite(String id,String code){
            runOnUiThread(()->{
                if(code==null || !code.matches("[A-HJ-NP-Z2-9]{10}")){
                    fail(id,"ইনভাইট কোড সঠিক নয়");return;
                }
                try{
                    Intent send=new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT,
                        "Together ♥ Couple Location\nআমার সঙ্গে যুক্ত হতে এই কোড দিন: " + code +
                        "\nএই কোড ১০ মিনিটের মধ্যে ব্যবহার করতে হবে।");
                    startActivity(Intent.createChooser(send,"সঙ্গীকে কোড পাঠান"));
                    reply(id,true,new JSONObject());
                }catch(Exception ex){fail(id,"কোড শেয়ার করা যায়নি");}
            });
        }
        @JavascriptInterface public void logout(String id){
            runOnUiThread(()->{
                stopService(new Intent(MainActivity.this,ShareService.class));
                new TokenVault(MainActivity.this).clear();
                reply(id,true,new JSONObject());
            });
        }
    }
    @Override public void onBackPressed(){
        if(web!=null)web.evaluateJavascript("window.onAndroidBack&&window.onAndroidBack()",null);
        else super.onBackPressed();
    }
    @Override public void onDestroy(){
        if(web!=null){web.removeJavascriptInterface("TogetherNative");web.destroy();web=null;}
        worker.shutdown();super.onDestroy();
    }
}
