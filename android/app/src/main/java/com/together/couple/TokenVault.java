package com.together.couple;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Tokens encrypted with non-exportable keys managed by Android Keystore. */
public class TokenVault {
    private static final String KEY_ALIAS="together_login_secret_v1";
    private final SharedPreferences prefs;
    TokenVault(Context c){prefs=c.getApplicationContext().getSharedPreferences("secure_session",Context.MODE_PRIVATE);}
    private SecretKey getKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(KEY_ALIAS))return (SecretKey)ks.getKey(KEY_ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
    synchronized void saveToken(String token){
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,getKey(),new SecureRandom());
            byte[] encrypted=cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            prefs.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("token",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
        }catch(Exception e){throw new IllegalStateException("Cannot securely save login",e);}
    }
    synchronized String token(){
        String e=prefs.getString("token",null),iv=prefs.getString("iv",null);
        if(e==null||iv==null)return null;
        try{
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,getKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(e,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception ex){clear();return null;}
    }
    synchronized void clear(){prefs.edit().remove("token").remove("iv").apply();}
}
