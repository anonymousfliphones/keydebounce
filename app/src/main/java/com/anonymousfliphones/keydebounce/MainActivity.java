package com.anonymousfliphones.keydebounce;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/** Status of the fix, plus install, undo, off/on, key tester and log. */
public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    private static final String ASKED_LOGS = "asked_read_logs";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private InputManager inputManager;
    private TextView state;
    private TextView details;
    private PhoneStatus status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        state = findViewById(R.id.state);
        details = findViewById(R.id.details);
        findViewById(R.id.install).setOnClickListener(v -> onInstall());
        findViewById(R.id.undo).setOnClickListener(v -> onUndo());
        findViewById(R.id.toggle).setOnClickListener(v -> onToggle());
        findViewById(R.id.root_mode).setOnClickListener(v -> onRootMode());
        findViewById(R.id.boot_notify).setOnClickListener(v -> onBootNotify());
        findViewById(R.id.bounce_filter).setOnClickListener(v -> onBounceFilter());
        findViewById(R.id.tester).setOnClickListener(v -> startActivity(new Intent(this, KeyTestActivity.class)));
        findViewById(R.id.log).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.repo_link).setOnClickListener(v -> openRepo());
        findViewById(R.id.install).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inputManager.registerInputDeviceListener(this, ui);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inputManager.unregisterInputDeviceListener(this);
    }

    // The daemon adding or removing its replacement keypad shows up here.
    @Override
    public void onInputDeviceAdded(int deviceId) {
        refresh();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        refresh();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        refresh();
    }

    private void refresh() {
        new Thread(() -> {
            PhoneStatus s = PhoneStatus.read();
            ui.post(() -> show(s));
        }).start();
    }

    private void show(PhoneStatus s) {
        status = s;
        int headline;
        int color;
        if (s.keypads == 0) {
            headline = R.string.state_unsupported;
            color = R.color.bad;
        } else if (s.installed() && s.filtering()) {
            headline = R.string.state_on;
            color = R.color.good;
        } else if (s.installed()) {
            headline = R.string.state_off;
            color = R.color.warn;
        } else if (s.partlyInstalled()) {
            headline = R.string.state_partial;
            color = R.color.warn;
        } else if (s.filtering()) {
            headline = R.string.state_root_mode;
            color = R.color.good;
        } else {
            headline = R.string.state_none;
            color = R.color.neutral;
        }
        state.setText(headline);
        state.setTextColor(getColor(color));

        String service = s.service.isEmpty() ? getString(R.string.unknown) : s.service;
        String text = getString(R.string.details,
                yesNo(s.binaryInstalled && s.rcInstalled), policyText(s.policy), service,
                getString(s.filtering() ? R.string.active : R.string.not_active),
                getString(s.suFound ? R.string.found : R.string.not_found));
        if (s.installed() && !s.filtering()) text += "\n\n" + getString(R.string.state_off_hint);
        if (!s.installed() && BootReceiver.startAtBoot(this)) text += "\n" + getString(R.string.start_at_boot_on);
        details.setText(text);
        maybeAskForLogs(s);
    }

    /** Once, on first open: offer to grant READ_LOGS through root for the correction log. */
    private void maybeAskForLogs(PhoneStatus s) {
        if (!s.suFound || LogActivity.canReadLogs(this)) return;
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (p.getBoolean(ASKED_LOGS, false)) return;
        p.edit().putBoolean(ASKED_LOGS, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.grant_logs_title)
                .setMessage(getString(R.string.grant_logs_msg, getPackageName()))
                .setPositiveButton(R.string.grant_logs_button, (d, w) -> LogActivity.grantWithRoot(this))
                .setNegativeButton(R.string.not_now, null)
                .show();
    }

    private String policyText(int policy) {
        if (policy == PhoneStatus.POLICY_UNKNOWN) return getString(R.string.cant_check);
        return yesNo(policy == PhoneStatus.POLICY_YES);
    }

    private String yesNo(boolean b) {
        return getString(b ? R.string.yes : R.string.no);
    }

    private void onInstall() {
        PhoneStatus s = status;
        if (s == null) return;
        if (!s.supported) {
            message(R.string.unsupported_title, getString(R.string.unsupported_msg, Build.VERSION.RELEASE,
                    getString(s.keypads > 0 ? R.string.found : R.string.not_found)));
        } else if (s.policy == PhoneStatus.POLICY_YES || s.installed()) {
            onAlreadyInstalled(s);
        } else if (!s.suFound) {
            message(R.string.need_root_title, getString(R.string.need_root_install));
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.install_title)
                    .setMessage(R.string.install_msg)
                    .setPositiveButton(R.string.install, (d, w) -> run(RootActivity.INSTALL))
                    .setNeutralButton(R.string.dry_run_only, (d, w) -> run(RootActivity.DRYRUN))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    /** Offers install's final checks (the .bak backup above all) again, changing nothing. */
    private void onAlreadyInstalled(PhoneStatus s) {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.install_title);
        if (s.suFound) {
            b.setMessage(getString(R.string.already_installed) + "\n\n" + getString(R.string.check_install_msg))
                    .setPositiveButton(R.string.check_install, (d, w) -> run(RootActivity.VERIFY))
                    .setNegativeButton(R.string.close, null);
        } else {
            b.setMessage(R.string.already_installed).setPositiveButton(R.string.close, null);
        }
        b.show();
    }

    private void onUndo() {
        PhoneStatus s = status;
        if (s == null) return;
        if (!s.installed() && !s.partlyInstalled()) {
            message(R.string.undo_title, getString(R.string.nothing_to_undo));
        } else if (!s.suFound) {
            message(R.string.need_root_title, getString(R.string.need_root_undo));
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.undo_title)
                    .setMessage(R.string.undo_msg)
                    .setPositiveButton(R.string.undo, (d, w) -> run(RootActivity.UNINSTALL))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void onToggle() {
        PhoneStatus s = status;
        if (s == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.toggle_title);
        if (!s.installed()) {
            b.setMessage(R.string.toggle_not_installed).setPositiveButton(R.string.close, null);
        } else if (s.suFound) {
            b.setMessage(getString(R.string.toggle_msg) + "\n\n" + getString(R.string.toggle_root_note))
                    .setPositiveButton(R.string.off_now, (d, w) -> run(RootActivity.OFF))
                    .setNeutralButton(R.string.on_now, (d, w) -> run(RootActivity.ON))
                    .setNegativeButton(R.string.close, null);
        } else {
            b.setMessage(R.string.toggle_msg).setPositiveButton(R.string.close, null);
        }
        b.show();
    }

    /** Root mode: run the daemon through su without installing anything. */
    private void onRootMode() {
        PhoneStatus s = status;
        if (s == null) return;
        if (!s.supported) {
            message(R.string.unsupported_title, getString(R.string.unsupported_msg, Build.VERSION.RELEASE,
                    getString(s.keypads > 0 ? R.string.found : R.string.not_found)));
        } else if (s.anyTrace()) {
            message(R.string.root_mode_title, getString(R.string.root_mode_installed));
        } else if (!s.suFound) {
            message(R.string.need_root_title, getString(R.string.need_root_mode));
        } else {
            boolean atBoot = BootReceiver.startAtBoot(this);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.root_mode_title)
                    .setMessage(getString(R.string.root_mode_msg,
                            getString(atBoot ? R.string.on_word : R.string.off_word)))
                    // Three buttons share one row on a 240 px screen, so the labels stay short.
                    .setPositiveButton(R.string.start_short, (d, w) -> run(RootActivity.ROOT_START))
                    .setNeutralButton(R.string.stop, (d, w) -> run(RootActivity.ROOT_STOP))
                    .setNegativeButton(R.string.boot_button, (d, w) -> onStartAtBoot())
                    .show();
        }
    }

    private void onStartAtBoot() {
        boolean atBoot = BootReceiver.startAtBoot(this);
        String text = getString(R.string.boot_msg, getString(atBoot ? R.string.on_word : R.string.off_word));
        if (RootShell.isXp3RootInstalled(this)) text += "\n\n" + getString(R.string.boot_xp3_note);
        new AlertDialog.Builder(this)
                .setTitle(R.string.boot_title)
                .setMessage(text)
                .setPositiveButton(atBoot ? R.string.turn_off : R.string.turn_on, (d, w) -> {
                    BootReceiver.setStartAtBoot(this, !atBoot);
                    refresh();
                    if (atBoot) {
                        Toast.makeText(this, R.string.boot_now_off, Toast.LENGTH_LONG).show();
                    } else {
                        askRestartForBoot();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** The optional notification when Start at boot starts the filter. */
    private void onBootNotify() {
        boolean on = BootNotifier.enabled(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.notify_title)
                .setMessage(getString(R.string.notify_msg, getString(on ? R.string.on_word : R.string.off_word)))
                .setPositiveButton(on ? R.string.turn_off : R.string.turn_on, (d, w) -> {
                    BootNotifier.setEnabled(this, !on);
                    Toast.makeText(this, on ? R.string.notify_now_off : R.string.notify_now_on,
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Start at boot only acts after a restart: offer one now. */
    private void askRestartForBoot() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.boot_restart_title)
                .setMessage(R.string.boot_restart_msg)
                .setPositiveButton(R.string.restart_now, (d, w) -> RootActivity.restartInBackground(this))
                .setNegativeButton(R.string.later, null)
                .show();
    }

    /** No-root, bounce-only fallback. Untested: see BounceFilterService and the README. */
    private void onBounceFilter() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bounce_filter_title)
                .setMessage(getString(R.string.bounce_filter_msg, getPackageName(), getString(R.string.app_name)))
                .setPositiveButton(R.string.bounce_filter_open_settings,
                        (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void run(String command) {
        RootActivity.launch(this, command);
    }

    /** Opens the GitHub repo; many locked-down phones have no browser, so then it just shows the address. */
    private void openRepo() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.repo_url))));
        } catch (ActivityNotFoundException e) {
            message(R.string.repo_title, getString(R.string.repo_url));
        }
    }

    private void message(int title, String text) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
