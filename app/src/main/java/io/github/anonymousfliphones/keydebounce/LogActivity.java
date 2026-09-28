package io.github.anonymousfliphones.keydebounce;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The daemon's logcat lines (tag keydebounce) and how many corrections it made. */
public class LogActivity extends Activity {
    private static final int MAX_LINES = 300;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView summary;
    private TextView lines;
    private View grant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        summary = findViewById(R.id.summary);
        lines = findViewById(R.id.lines);
        grant = findViewById(R.id.grant);
        grant.setOnClickListener(v -> grantWithRoot(this));
        findViewById(R.id.refresh).setOnClickListener(v -> load());
        findViewById(R.id.refresh).requestFocus();
    }

    static boolean canReadLogs(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs "pm grant ... READ_LOGS" through root, unless the XP3800 root is off. */
    static void grantWithRoot(Activity a) {
        if (RootShell.rootInactive(a)) {
            new AlertDialog.Builder(a)
                    .setTitle(R.string.root_inactive_title)
                    .setMessage(R.string.root_inactive_msg)
                    .setPositiveButton(R.string.close, null)
                    .show();
            return;
        }
        a.startActivity(new Intent(a, RootActivity.class)
                .putExtra(RootActivity.EXTRA_COMMAND, RootActivity.GRANT_LOGS));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (!canReadLogs(this)) {
            summary.setText(getString(R.string.log_need_permission, getPackageName()));
            lines.setText("");
            grant.setVisibility(View.VISIBLE);
            grant.requestFocus();
            return;
        }
        grant.setVisibility(View.GONE);
        summary.setText(R.string.log_loading);
        new Thread(() -> {
            List<String> out = new ArrayList<>();
            String error = null;
            try {
                Process p = new ProcessBuilder("logcat", "-d", "-v", "time", "-s", "keydebounce")
                        .redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.startsWith("---------")) out.add(line);
                    }
                }
                p.waitFor();
            } catch (IOException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String failed = error;
            ui.post(() -> show(out, failed));
        }).start();
    }

    private void show(List<String> log, String error) {
        if (error != null) {
            summary.setText(getString(R.string.log_failed, error));
            lines.setText("");
            return;
        }
        int overlaps = 0;
        int bounces = 0;
        boolean offSwitch = false;
        for (String l : log) {
            if (l.contains("overlap:")) overlaps++;
            if (l.contains("bounce:")) bounces++;
            if (l.contains("kill switch present")) offSwitch = true;
            if (l.contains("running, debounce_ms=")) offSwitch = false;
        }
        StringBuilder s = new StringBuilder(getString(R.string.log_counts, overlaps, bounces));
        s.append('\n').append(getString(R.string.log_scope));
        if (offSwitch) s.append("\n\n").append(getString(R.string.log_off_switch));
        if (log.isEmpty()) s.append("\n\n").append(getString(R.string.log_empty));
        summary.setText(s);

        List<String> recent = new ArrayList<>(log.subList(Math.max(0, log.size() - MAX_LINES), log.size()));
        Collections.reverse(recent);
        lines.setText(TextUtils.join("\n", recent));
    }
}
