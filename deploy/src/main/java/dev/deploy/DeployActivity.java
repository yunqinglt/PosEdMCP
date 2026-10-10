package dev.deploy;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import dev.deploy.core.DeployCore;

/**
 * The dashboard frontend: a plain status screen for the human.
 *
 * <p>This is the "future independent frontend" reduced to its smallest form - a
 * read-out of the backend and the two machine-facing doors (the MCP endpoint
 * and the AIDL service), plus a start/stop switch for the MCP service. The real
 * symbol-analysis frontend would render {@code DeployCore} output; this screen
 * just proves the third frontend reaches the same backend.
 */
public final class DeployActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(column);

        TextView title = new TextView(this);
        title.setText("Deploy");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, dp(12), 0, dp(12));
        column.addView(status);

        Button toggle = new Button(this);
        toggle.setText(DeployMcpService.instance() != null && DeployMcpService.instance().isRunning()
                ? "Stop MCP service" : "Start MCP service");
        toggle.setOnClickListener(v -> {
            if (DeployMcpService.instance() != null && DeployMcpService.instance().isRunning()) {
                DeployMcpService.stop(this);
            } else {
                DeployMcpService.start(this);
            }
            toggle.setText(DeployMcpService.instance() != null
                    && DeployMcpService.instance().isRunning()
                    ? "Stop MCP service" : "Start MCP service");
            refresh();
        });
        column.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        DeployCore core = new DeployCore();
        DeployMcpService service = DeployMcpService.instance();
        boolean running = service != null && service.isRunning();

        StringBuilder sb = new StringBuilder();
        sb.append("Backend\n");
        try {
            JSONObject s = core.status();
            sb.append("  version: ").append(s.optString("version")).append('\n');
            sb.append("  backend: ").append(s.optString("backend")).append('\n');
            sb.append("  abi:     ").append(s.optString("abi")).append('\n');
        } catch (Throwable t) {
            sb.append("  error: ").append(t).append('\n');
        }
        sb.append("\nMCP endpoint\n");
        sb.append("  running: ").append(running).append('\n');
        sb.append("  url:     ").append(service == null
                ? "http://127.0.0.1:" + DeployMcpService.DEFAULT_PORT + "/mcp"
                : service.endpoint()).append('\n');
        sb.append("  token:   ").append(service == null ? "" : service.token()).append('\n');
        sb.append("\nAIDL service\n");
        sb.append("  exported for PosEdMCP: dev.deploy.DeployAidlService\n");
        sb.append("\nNote\n");
        sb.append("  backend is a stub; only 'overview' parses anything real.\n");
        sb.append("  The LIEF engine plugs in behind the same interface.\n");
        status.setText(sb.toString());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
