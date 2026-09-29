package com.anonymousfliphones.keydebounce;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
    /** The user agreed to start Root Manager's root (it runs the exploit) for this command. */
    static final String EXTRA_START_ROOT = "start_root";
    /** Started from the Start at boot prompt: clear its crash guard when the command ends. */
    static final String EXTRA_FROM_BOOT_PROMPT = "from_boot_prompt";
    // Commands understood by assets/kd/kd.sh.
    static final String DRYRUN = "dryrun";
    static final String INSTALL = "install";
    static final String VERIFY = "verify";
    static final String UNINSTALL = "uninstall";
    static final String OFF = "off";
    static final String ON = "on";
    static final String ROOT_START = "rootstart";
    static final String ROOT_STOP = "rootstop";
    static final String REBOOT = "reboot";
    static final String GRANT_LOGS = "grantlogs";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();
    private String command;
    private boolean startRoot;
    private boolean fromBootPrompt;
    private TextView title;
    private TextView output;
    private ScrollView scroll;
    private Button restart;
    private Button close;
    private boolean running;
    private boolean succeeded;

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
        close.setOnClickListener(v -> close());

        command = getIntent().getStringExtra(EXTRA_COMMAND);
        startRoot = getIntent().getBooleanExtra(EXTRA_START_ROOT, false);
        fromBootPrompt = getIntent().getBooleanExtra(EXTRA_FROM_BOOT_PROMPT, false);
        if (!Arrays.asList(DRYRUN, INSTALL, VERIFY, UNINSTALL, OFF, ON, ROOT_START, ROOT_STOP, REBOOT, GRANT_LOGS).contains(command)) {
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
                code = RootShell.run(this, dir, command, startRoot, line -> ui.post(() -> append(line)));
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
        // Not fullScroll(): that also moves focus to the ScrollView, which ran after
        // finished() focused Restart/Close and left the buttons unreachable with the D-pad.
        scroll.post(() -> scroll.scrollTo(0, output.getBottom()));
    }

    private void finished(int code) {
        running = false;
        boolean ok = code == 0;
        succeeded = ok;
        if (fromBootPrompt) BootReceiver.promptStartEnded(this, ok);
        title.setText(ok ? R.string.run_done : R.string.run_failed);
        title.setTextColor(getColor(ok ? R.color.good : R.color.bad));
        append("");
        if (ok) {
            append(getString(doneMessageFor(command)));
        } else if (code == RootShell.ROOT_NOT_STARTED) {
            append(getString(R.string.run_root_not_started));
        } else if (code == RootShell.NO_ROOT) {
            append(getString(R.string.run_no_root));
        } else {
            append(getString(R.string.run_failed_msg));
        }
        File saved = saveTranscript();
        if (saved != null) append(getString(R.string.run_saved, saved.getPath()));

        close.setVisibility(View.VISIBLE);
        if (ok && (INSTALL.equals(command) || VERIFY.equals(command) || UNINSTALL.equals(command))) {
            restart.setVisibility(View.VISIBLE);
            restart.requestFocus();
        } else {
            close.requestFocus();
        }
    }

    /**
     * Runs command as root. If Root Manager's root hasn't started since the restart, asks
     * first: starting it runs the kernel exploit, which can crash the phone.
     */
    static void launch(Activity a, String command) {
        Intent run = new Intent(a, RootActivity.class).putExtra(EXTRA_COMMAND, command);
        if (!RootShell.rootNotStarted(a)) {
            a.startActivity(run);
            return;
        }
        String msg = a.getString(R.string.root_not_started_msg);
        if (PhoneStatus.installTraceOnPhone()) msg += "\n\n" + a.getString(R.string.root_not_started_installed);
        new AlertDialog.Builder(a)
                .setTitle(R.string.root_not_started_title)
                .setMessage(msg)
                .setPositiveButton(R.string.start_root, (d, w) -> a.startActivity(run.putExtra(EXTRA_START_ROOT, true)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void close() {
        if (GRANT_LOGS.equals(command) && succeeded) {
            // READ_LOGS only reaches a process started after the grant.
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        finish();
    }

    private void confirmRestart() {
        int msg = UNINSTALL.equals(command) ? R.string.restart_after_undo : R.string.restart_after_install;
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_title)
                .setMessage(msg)
                .setPositiveButton(R.string.restart, (d, w) -> {
                    restartInBackground(this);
                    finish();
                })
                .setNegativeButton(R.string.not_yet, null)
                .show();
    }

    /**
     * Restarts the phone (kd.sh reboot) without opening the output screen: a toast says
     * "Restarting…", and another one explains if it didn't happen. startRoot, because with
     * Root Manager the restart may be the first su since boot.
     */
    static void restartInBackground(Context c) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(app, R.string.run_reboot, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                File dir = RootShell.unpack(app);
                RootShell.run(app, dir, REBOOT, true, line -> out.append(line).append('\n'));
            } catch (IOException e) {
                out.append(e.getMessage());
            }
            // Only reached if the phone didn't restart.
            String last = out.toString().trim();
            int nl = last.lastIndexOf('\n');
            String reason = nl >= 0 ? last.substring(nl + 1) : last;
            main.post(() -> Toast.makeText(app, app.getString(R.string.restart_failed, reason),
                    Toast.LENGTH_LONG).show());
        }).start();
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
            close();
        }
    }

    private static int titleFor(String command) {
        if (INSTALL.equals(command)) return R.string.run_install;
        if (VERIFY.equals(command)) return R.string.run_verify;
        if (UNINSTALL.equals(command)) return R.string.run_uninstall;
        if (OFF.equals(command)) return R.string.run_off;
        if (ON.equals(command)) return R.string.run_on;
        if (ROOT_START.equals(command)) return R.string.run_root_start;
        if (ROOT_STOP.equals(command)) return R.string.run_root_stop;
        if (REBOOT.equals(command)) return R.string.run_reboot;
        if (GRANT_LOGS.equals(command)) return R.string.run_grant_logs;
        return R.string.run_dryrun;
    }

    private static int doneMessageFor(String command) {
        if (INSTALL.equals(command)) return R.string.done_install;
        if (VERIFY.equals(command)) return R.string.done_verify;
        if (UNINSTALL.equals(command)) return R.string.done_uninstall;
        if (OFF.equals(command)) return R.string.done_off;
        if (ON.equals(command)) return R.string.done_on;
        if (ROOT_START.equals(command)) return R.string.done_root_start;
        if (ROOT_STOP.equals(command)) return R.string.done_root_stop;
        if (REBOOT.equals(command)) return R.string.run_reboot;
        if (GRANT_LOGS.equals(command)) return R.string.done_grant_logs;
        return R.string.done_dryrun;
    }
}
