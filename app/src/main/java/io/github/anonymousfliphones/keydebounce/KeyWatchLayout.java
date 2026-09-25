package io.github.anonymousfliphones.keydebounce;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.LinearLayout;

/** Sees every key event before the input method does. */
public class KeyWatchLayout extends LinearLayout {
    interface Watcher {
        void onKey(KeyEvent event);
    }

    private Watcher watcher;

    public KeyWatchLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setWatcher(Watcher w) {
        watcher = w;
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (watcher != null) watcher.onKey(event);
        return super.dispatchKeyEventPreIme(event);
    }
}
