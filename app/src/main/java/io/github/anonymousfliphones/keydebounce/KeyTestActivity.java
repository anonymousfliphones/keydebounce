package io.github.anonymousfliphones.keydebounce;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lists every key press and release the app receives, with timing, and flags
 * the patterns behind the double digits: a key going down while another is
 * held (overlap) and the same key again within a few ms of its release (bounce).
 */
public class KeyTestActivity extends Activity {
    /** A same-key press this soon after its release is faster than a person types. */
    private static final long FAST_MS = 50;
    private static final int MAX_LINES = 60;

    private final Map<Integer, Long> held = new LinkedHashMap<>();
    private final Map<Integer, Long> lastUp = new HashMap<>();
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView summary;
    private TextView events;
    private long lastTime = -1;
    private int presses, overlaps, fast, doubles;

    // The last event recorded; the same event also reaches dispatchKeyEvent.
    private KeyEvent seen;
    private long seenTime;
    private int seenAction, seenCode, seenRepeat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_test);
        summary = findViewById(R.id.summary);
        events = findViewById(R.id.events);
        ((KeyWatchLayout) findViewById(R.id.root)).setWatcher(this::record);
        findViewById(R.id.typed).requestFocus();
        showSummary();
    }

    // Catches events that reach the activity without passing the watcher
    // (e.g. if the text field has lost focus).
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        record(event);
        return super.dispatchKeyEvent(event);
    }

    private void record(KeyEvent ev) {
        if (ev == seen && ev.getEventTime() == seenTime && ev.getAction() == seenAction
                && ev.getKeyCode() == seenCode && ev.getRepeatCount() == seenRepeat) {
            return;
        }
        seen = ev;
        seenTime = ev.getEventTime();
        seenAction = ev.getAction();
        seenCode = ev.getKeyCode();
        seenRepeat = ev.getRepeatCount();

        int code = ev.getKeyCode();
        long t = ev.getEventTime();
        String key = label(code);
        String gap = lastTime < 0 ? "" : "  +" + (t - lastTime) + "ms";
        StringBuilder s = new StringBuilder();

        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() > 0) {
                // Auto-repeat while held: note it once.
                if (ev.getRepeatCount() == 1) add("  " + key + " held, repeating");
                return;
            }
            presses++;
            s.append("↓ ").append(key).append(gap);
            if (held.containsKey(code)) {
                doubles++;
                s.append("\n  ! ").append(key).append(" down twice, no release");
            }
            for (int other : held.keySet()) {
                if (other == code) continue;
                overlaps++;
                s.append("\n  ! OVERLAP: ").append(label(other)).append(" still held");
            }
            Long up = lastUp.get(code);
            if (up != null && t - up < FAST_MS) {
                fast++;
                s.append("\n  ! again ").append(t - up).append("ms after release");
            }
            held.put(code, t);
        } else if (ev.getAction() == KeyEvent.ACTION_UP) {
            Long down = held.remove(code);
            s.append("↑ ").append(key).append(gap);
            if (down != null) s.append("  held ").append(t - down).append("ms");
            lastUp.put(code, t);
        } else {
            return;
        }
        lastTime = t;
        add(s.toString());
        showSummary();
    }

    private void add(String line) {
        lines.addFirst(line);
        while (lines.size() > MAX_LINES) lines.removeLast();
        events.setText(TextUtils.join("\n", lines));
    }

    private void showSummary() {
        summary.setText(getString(R.string.tester_summary, presses, overlaps, fast, doubles));
        boolean clean = overlaps == 0 && fast == 0 && doubles == 0;
        summary.setTextColor(getColor(clean ? R.color.text : R.color.bad));
    }

    private static String label(int code) {
        String s = KeyEvent.keyCodeToString(code);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }
}
