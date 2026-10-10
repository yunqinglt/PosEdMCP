package dev.deploy.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.view.WindowManager;
import android.widget.TextView;

/** No loader or bridge here: the framework must inject PosEdMCP to provide them. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView text = new TextView(this);
        text.setPadding(32, 64, 32, 32);
        text.setTextSize(18);
        text.setText("Deploy native loading fixture\n\nPID: " + Process.myPid()
                + "\nUID: " + Process.myUid()
                + "\n\nNo native library is loaded by this Activity."
                + "\nEnable this app in PosEdMCP's framework scope, then restart it."
                + "\nThe probe entry returns the PID captured by its ELF constructor.");
        setContentView(text);
    }
}
