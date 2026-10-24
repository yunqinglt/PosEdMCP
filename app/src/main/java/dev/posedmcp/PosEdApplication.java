package dev.posedmcp;

import android.app.Application;

/** Lightweight in every app process, including :bootstrap. No server auto-start here. */
public final class PosEdApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Logx.initialize(this);
    }
}
