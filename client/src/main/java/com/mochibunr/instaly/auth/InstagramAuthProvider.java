package com.mochibunr.instaly.auth;
public interface InstagramAuthProvider { boolean isConnected(); void beginLogin(); void logout(); }