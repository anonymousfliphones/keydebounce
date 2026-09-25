package io.github.anonymousfliphones.keydebounce;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        summary = findViewById(R.id.summary);
        lines = findViewById(R.id.lines);
        findViewById(R.id.refresh).setOnClickListener(v -> load());
        findViewById(R.id.refresh).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            summary.setText(getString(R.string.log_need_permission, getPackageName()));
            lines.setText("");
            return;
        }
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
