package io.github.anonymousfliphones.keydebounce;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs one kd.sh command as root and shows its output live. */
public class RootActivity extends Activity {
    static final String EXTRA_COMMAND = "command";
    // Commands understood by assets/kd/kd.sh.
    static final String DRYRUN = "dryrun";
    static final String INSTALL = "install";
    static final String UNINSTALL = "uninstall";
    static final String OFF = "off";
    static final String ON = "on";
    static final String ROOT_START = "rootstart";
    static final String ROOT_STOP = "rootstop";
    static final String REBOOT = "reboot";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();
    private String command;
    private TextView title;
    private TextView output;
    private ScrollView scroll;
    private Button restart;
    private Button close;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        title = findViewById(R.id.title);
        output = findViewById(R.id.output);
        scroll = findViewById(R.id.scroll);
        restart = findViewById(R.id.restart);
        close = findViewById(R.id.close);
        restart.setOnClickListener(v -> confirmRestart());
        close.setOnClickListener(v -> finish());

        command = getIntent().getStringExtra(EXTRA_COMMAND);
        if (!Arrays.asList(DRYRUN, INSTALL, UNINSTALL, OFF, ON, ROOT_START, ROOT_STOP, REBOOT).contains(command)) {
            finish();
            return;
        }
        title.setText(titleFor(command));
        if (savedInstanceState != null) {
            // Recreated mid-run: never start a root command a second time.
            append(getString(R.string.run_interrupted));
            finished(RootShell.NO_ROOT);
            return;
        }
        start();
    }

    private void start() {
        running = true;
        new Thread(() -> {
            int code;
            try {
                File dir = RootShell.unpack(this);
                code = RootShell.run(dir, command, line -> ui.post(() -> append(line)));
            } catch (IOException e) {
                ui.post(() -> append(getString(R.string.run_unpack_failed, e.getMessage())));
                code = RootShell.NO_ROOT;
            }
            int result = code;
            ui.post(() -> finished(result));
        }).start();
    }

    private void append(String line) {
        transcript.append(line).append('\n');
        output.append(line + "\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void finished(int code) {
        running = false;
        boolean ok = code == 0;
        title.setText(ok ? R.string.run_done : R.string.run_failed);
        title.setTextColor(getColor(ok ? R.color.good : R.color.bad));
        append("");
        if (ok) {
            append(getString(doneMessageFor(command)));
        } else if (code == RootShell.NO_ROOT) {
            append(getString(R.string.run_no_root));
        } else {
            append(getString(R.string.run_failed_msg));
        }
        File saved = saveTranscript();
        if (saved != null) append(getString(R.string.run_saved, saved.getPath()));

        close.setVisibility(View.VISIBLE);
        if (ok && (INSTALL.equals(command) || UNINSTALL.equals(command))) {
            restart.setVisibility(View.VISIBLE);
            restart.requestFocus();
        } else {
            close.requestFocus();
        }
    }

    private void confirmRestart() {
        int msg = INSTALL.equals(command) ? R.string.restart_after_install : R.string.restart_after_undo;
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_title)
                .setMessage(msg)
                .setPositiveButton(R.string.restart, (d, w) -> {
                    startActivity(new Intent(this, RootActivity.class).putExtra(EXTRA_COMMAND, REBOOT));
                    finish();
                })
                .setNegativeButton(R.string.not_yet, null)
                .show();
    }

    /** Keeps a copy in Android/data/<package>/files, readable with adb pull. */
    private File saveTranscript() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, command + ".log");
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(transcript.toString().getBytes(StandardCharsets.UTF_8));
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (running) {
            Toast.makeText(this, R.string.run_busy, Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }

    private static int titleFor(String command) {
        if (INSTALL.equals(command)) return R.string.run_install;
        if (UNINSTALL.equals(command)) return R.string.run_uninstall;
        if (OFF.equals(command)) return R.string.run_off;
        if (ON.equals(command)) return R.string.run_on;
        if (ROOT_START.equals(command)) return R.string.run_root_start;
        if (ROOT_STOP.equals(command)) return R.string.run_root_stop;
        if (REBOOT.equals(command)) return R.string.run_reboot;
        return R.string.run_dryrun;
    }

    private static int doneMessageFor(String command) {
        if (INSTALL.equals(command)) return R.string.done_install;
        if (UNINSTALL.equals(command)) return R.string.done_uninstall;
        if (OFF.equals(command)) return R.string.done_off;
        if (ON.equals(command)) return R.string.done_on;
        if (ROOT_START.equals(command)) return R.string.done_root_start;
        if (ROOT_STOP.equals(command)) return R.string.done_root_stop;
        if (REBOOT.equals(command)) return R.string.run_reboot;
        return R.string.done_dryrun;
    }
}
