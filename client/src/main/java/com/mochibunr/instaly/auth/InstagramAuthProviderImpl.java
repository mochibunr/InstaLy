package com.mochibunr.instaly.auth;
import android.content.*;
/** Local state only; real Instagram authentication is injected through this boundary. */
public final class InstagramAuthProviderImpl implements InstagramAuthProvider {
 private static final String P="instaly_auth",C="connected"; private final SharedPreferences prefs;
 public InstagramAuthProviderImpl(Context c){prefs=c.getSharedPreferences(P,Context.MODE_PRIVATE);}
 public boolean isConnected(){return prefs.getBoolean(C,false);} public void beginLogin(){} public void logout(){prefs.edit().clear().apply();}
 public void markDemoConnected(){prefs.edit().putBoolean(C,true).apply();}
}