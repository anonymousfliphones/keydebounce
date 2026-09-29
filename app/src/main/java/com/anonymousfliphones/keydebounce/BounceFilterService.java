package com.anonymousfliphones.keydebounce;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * No-root, bounce-only fallback for phones that aren't rooted.
 *
 * Swallows a ghost DOWN+UP pair for the same key that arrives within
 * BOUNCE_WINDOW_MS of that key's real release. Unlike the root daemon, this
 * can only drop a key event that already happened; it can't synthesize a new
 * one. That's enough for contact bounce (5 -> "55"), but it cannot fix key
 * overlap (5,6 -> "566"), which needs a release for the held key that never
 * actually occurred. See the "no-root bounce filter" section of the README.
 */
public class BounceFilterService extends AccessibilityService {
    private static final long BOUNCE_WINDOW_MS = 20;

    private int lastUpKeyCode = -1;
    private long lastUpTime = -1;
    private int swallowingKeyCode = -1;

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        long time = event.getEventTime();

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (keyCode == swallowingKeyCode) {
                // The release half of a DOWN we already swallowed as bounce.
                swallowingKeyCode = -1;
                return true;
            }
            lastUpKeyCode = keyCode;
            lastUpTime = time;
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && keyCode == lastUpKeyCode && lastUpTime >= 0
                && (time - lastUpTime) < BOUNCE_WINDOW_MS) {
            swallowingKeyCode = keyCode;
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: this service only filters key events.
    }

    @Override
    public void onInterrupt() {
    }
}
