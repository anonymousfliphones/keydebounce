package io.github.anonymousfliphones.keydebounce;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

/**
 * Start at boot with Root Manager: a minute after boot, if root still hasn't started,
 * asks whether to start it. The first su after a restart runs Root Manager's exploit,
 * which can crash the phone, so it only runs when the user presses Start.
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
                    BootReceiver.log(this, true, "Not now pressed. Still checking in case root starts another way.");
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }
}
