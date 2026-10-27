package com.together.couple;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    private final Context context;
    private final TokenVault vault;
    public ApiClient(Context c){context=c.getApplicationContext();vault=new TokenVault(context);}
    public static String configuredBase(Context c){
        return c.getSharedPreferences("settings",0).getString("server","");
    }
    public static boolean setServer(Context c,String input){
        try{
            URL url=new URL(input.trim());
            if(!url.getProtocol().equals("https")||url.getHost().isEmpty()||url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null||url.getPort()!=-1)return false;
            String normal=url.toExternalForm().replaceAll("/+$","");
            c.getSharedPreferences("settings",0).edit().putString("server",normal).apply();
            return true;
        }catch(Exception ex){return false;}
    }
    public JSONObject request(String method,String path,JSONObject data)throws Exception {
        String base=configuredBase(context);
        if(base.isEmpty())throw new IllegalStateException("প্রথমে HTTPS সার্ভার URL দিন");
        // Fixed path allowlist: Javascript cannot request arbitrary hosts or retrieve other device data.
        if(!path.equals("/health") && !path.matches("/v1/(auth/(login|register)|me|partner|history(\\?page=[0-9]{1,3}(&date=[0-9]{4}-[0-9]{2}-[0-9]{2})?)?|pair/(invite|accept|disconnect)|privacy/sharing|location|history/me|account)"))
            throw new SecurityException("অনুমোদিত API নয়");
        if(!method.matches("GET|POST|DELETE"))throw new SecurityException("অনুমোদিত method নয়");
        HttpURLConnection conn=(HttpURLConnection)new URL(base+path).openConnection();
        conn.setConnectTimeout(12000);conn.setReadTimeout(16000);
        conn.setRequestMethod(method);conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        String token=vault.token();if(token!=null)conn.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(data!=null && !method.equals("GET")){
                conn.setDoOutput(true);
                byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            }
            int status=conn.getResponseCode();
            InputStream stream=status>=400?conn.getErrorStream():conn.getInputStream();
            StringBuilder result=new StringBuilder();
            if(stream!=null)try(BufferedReader in=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                String line;while((line=in.readLine())!=null)result.append(line);
            }
            JSONObject json=result.length()==0?new JSONObject():new JSONObject(result.toString());
            if(status>=400){String message=json.optString("error","HTTP "+status);throw new IllegalStateException(message);}
            if(path.equals("/v1/auth/login")||path.equals("/v1/auth/register")){
                String access=json.optString("token","");
                if(access.isEmpty())throw new IllegalStateException("সার্ভার টোকেন দেয়নি");
                vault.saveToken(access);
                json.remove("token"); // JS never receives or stores the access token.
            }
            return json;
        }finally{conn.disconnect();}
    }
}
