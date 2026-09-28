package io.github.anonymousfliphones.keydebounce;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

/**
 * Start at boot with Root Manager, after an automatic start that never finished (the
 * exploit probably crashed the phone): asks instead of trying again, so a crashing exploit
 * can't become a reboot loop. Shown a minute after boot, only if root still hasn't started.
 */
public class BootPromptActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BootReceiver.startAtBoot(this) || PhoneStatus.installTraceOnPhone()
                || PhoneStatus.countKeypads() >= 2 || !RootShell.rootNotStarted(this)) {
            finish();
            return;
        }
        BootReceiver.log(this, true, "Asked whether to start root and the filter.");
        new AlertDialog.Builder(this)
                .setTitle(R.string.boot_prompt_title)
                .setMessage(R.string.boot_prompt_msg)
                .setPositiveButton(R.string.start_short, (d, w) -> {
                    BootReceiver.log(this, true, "Start pressed: starting root and the filter.");
                    startActivity(new Intent(this, RootActivity.class)
                            .putExtra(RootActivity.EXTRA_COMMAND, RootActivity.ROOT_START)
                            .putExtra(RootActivity.EXTRA_START_ROOT, true));
                    finish();
                })
                .setNegativeButton(R.string.not_now, (d, w) -> {
                    BootReceiver.log(this, true, "Not now pressed. The next restart tries automatically again.");
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }
}
