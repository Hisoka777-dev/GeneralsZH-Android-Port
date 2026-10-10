/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android port 10/10/2026 The custom shortcut panel in game. Lays a
// ShortcutPanelView over SDL's surface (inside SDLActivity's mLayout, just above the
// surface so the text editor bar still draws on top) and turns button presses into key
// events through SDLActivity.onNativeKeyDown/Up -- the same entry point a hardware
// keyboard uses, so the engine sees an ordinary keyboard and nothing native changes.
//
// Map touch is untouched: the panel and its container are not clickable, so any touch
// that does not start on a button falls through to the SDL surface (see
// ShortcutPanelView). Nothing here intercepts or rewrites the surface's own events.
//
// Timing matters for combinations. Keyboard::updateKeys() (Core/GameEngine/Source/
// GameClient/Input/Keyboard.cpp) stamps every key of a frame with the modifier state as
// it stands at the END of that frame. If Ctrl-down, 1-down, 1-up and Ctrl-up all landed
// in one frame, the game would see a plain "1". So a combination is spread over frames:
// modifiers go down first, the key MODIFIER_LEAD_MS later; on release the key goes up,
// then the modifiers MODIFIER_TRAIL_MS later. A tap shorter than MIN_HOLD_MS is held
// that long, so the key-down is seen in a frame of its own.
//
// Every key is reference-counted, so two buttons sharing Ctrl (or the same key) never
// release it while the other is still held.

package com.generalsx.zerohour;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

final class ShortcutOverlay implements ShortcutPanelView.Listener {

    interface KeySink {
        void keyDown(int keyCode);
        void keyUp(int keyCode);
    }

    private static final String TAG = "GeneralsZH";

    static final long MODIFIER_LEAD_MS = 70;
    static final long MIN_HOLD_MS = 70;
    static final long MODIFIER_TRAIL_MS = 70;

    private final Activity activity;
    private final ViewGroup host;
    private final KeySink sink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseIntArray downCounts = new SparseIntArray();

    private FrameLayout container;
    private ShortcutPanelView panel;
    private TextView toggleHandle;
    private boolean buttonsHidden;
    private ShortcutLayout layout;
    /** Per button: the press in progress, or null. */
    private Press[] presses = new Press[0];

    ShortcutOverlay(Activity activity, ViewGroup host, KeySink sink) {
        this.activity = activity;
        this.host = host;
        this.sink = sink;
    }

    /** Shows, rebuilds or removes the panel from the saved settings. Call on resume. */
    void reload() {
        releaseAll();
        if (!ShortcutLayout.isEnabled(activity)) {
            detach();
            return;
        }
        layout = ShortcutLayout.load(activity);
        if (container == null) {
            attach();
        }
        panel.setShortcutLayout(layout);
        presses = new Press[layout.buttons.size()];
        applyHidden();
    }

    private void attach() {
        float density = activity.getResources().getDisplayMetrics().density;

        container = new FrameLayout(activity);
        container.setClickable(false);
        container.setFocusable(false);
        container.setMotionEventSplittingEnabled(true);

        panel = new ShortcutPanelView(activity, false);
        panel.setListener(this);
        container.addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A small handle at the top centre hides and shows the buttons, for screens
        // (menus, cutscenes) where they are in the way. Not saved: every launch starts shown.
        toggleHandle = new TextView(activity);
        toggleHandle.setText("⌨");  // keyboard symbol
        toggleHandle.setTextColor(Color.WHITE);
        toggleHandle.setTypeface(Typeface.DEFAULT_BOLD);
        toggleHandle.setTextSize(16);
        toggleHandle.setGravity(Gravity.CENTER);
        toggleHandle.setFocusable(false);
        toggleHandle.setSoundEffectsEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99111B24);
        bg.setStroke(Math.round(1.5f * density), 0xAA4E8A2C);
        toggleHandle.setBackground(bg);
        toggleHandle.setContentDescription(activity.getString(R.string.shortcuts_toggle_handle));
        toggleHandle.setOnClickListener(v -> {
            buttonsHidden = !buttonsHidden;
            releaseAll();
            applyHidden();
        });
        int handleSize = Math.round(34 * density);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(handleSize, handleSize,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = Math.round(4 * density);
        container.addView(toggleHandle, hlp);

        // Index 1: right above SDL's surface (index 0) and below anything added later,
        // such as the game's text editor bar.
        int index = Math.min(1, host.getChildCount());
        host.addView(container, index, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void applyHidden() {
        if (panel != null) {
            panel.setVisibility(buttonsHidden ? View.GONE : View.VISIBLE);
        }
        if (toggleHandle != null) {
            toggleHandle.setAlpha(buttonsHidden ? 0.45f : 0.85f);
        }
    }

    void detach() {
        releaseAll();
        if (container != null) {
            host.removeView(container);
        }
        container = null;
        panel = null;
        toggleHandle = null;
        layout = null;
        presses = new Press[0];
    }

    /** Lets go of everything held right now, without delays. Call on pause. */
    void releaseAll() {
        handler.removeCallbacksAndMessages(null);
        for (int i = 0; i < presses.length; i++) {
            presses[i] = null;
        }
        for (int i = downCounts.size() - 1; i >= 0; i--) {
            int keyCode = downCounts.keyAt(i);
            if (downCounts.valueAt(i) > 0) {
                send(keyCode, false);
            }
        }
        downCounts.clear();
    }

    // ------------------------------------------------------------ presses

    /** One button press: which keys it put down, and when the main key went down. */
    private final class Press {
        final int keyCode;
        final int[] modifierKeys;
        boolean modifiersDown;
        boolean keyIsDown;
        long keyDownAt;
        boolean released;

        Press(int keyCode, int[] modifierKeys) {
            this.keyCode = keyCode;
            this.modifierKeys = modifierKeys;
        }
    }

    @Override
    public void onButtonPressed(int index) {
        if (layout == null || index < 0 || index >= layout.buttons.size()) {
            return;
        }
        if (presses[index] != null) {
            finishNow(presses[index]);  // a new press while the last one was still finishing
        }
        ShortcutLayout.Button b = layout.buttons.get(index);
        final Press p = new Press(b.keyCode, modifierKeysFor(b.modifiers));
        presses[index] = p;
        for (int m : p.modifierKeys) {
            press(m);
        }
        p.modifiersDown = true;
        Runnable keyDown = () -> {
            if (index >= presses.length || presses[index] != p) {
                return;  // superseded by a newer press, or everything was released
            }
            press(p.keyCode);
            p.keyIsDown = true;
            p.keyDownAt = SystemClock.uptimeMillis();
            if (p.released) {
                scheduleRelease(index, p);  // the finger already lifted during the lead time
            }
        };
        if (p.modifierKeys.length > 0) {
            handler.postDelayed(keyDown, MODIFIER_LEAD_MS);
        } else {
            keyDown.run();
        }
    }

    @Override
    public void onButtonReleased(int index) {
        if (index < 0 || index >= presses.length || presses[index] == null) {
            return;
        }
        Press p = presses[index];
        p.released = true;
        if (p.keyIsDown) {
            scheduleRelease(index, p);
        }
        // else: the delayed key-down sees `released` and schedules the release itself.
    }

    private void scheduleRelease(final int index, final Press p) {
        long heldFor = SystemClock.uptimeMillis() - p.keyDownAt;
        long wait = Math.max(0, MIN_HOLD_MS - heldFor);
        handler.postDelayed(() -> {
            if (!p.keyIsDown) {
                return;
            }
            release(p.keyCode);
            p.keyIsDown = false;
            if (p.modifierKeys.length == 0) {
                clearPress(index, p);
                return;
            }
            handler.postDelayed(() -> {
                releaseModifiers(p);
                clearPress(index, p);
            }, MODIFIER_TRAIL_MS);
        }, wait);
    }

    /** Ends a press immediately (used when the same button is pressed again mid-release). */
    private void finishNow(Press p) {
        if (p.keyIsDown) {
            release(p.keyCode);
            p.keyIsDown = false;
        }
        releaseModifiers(p);
        // Its pending runnables are now no-ops: they check keyIsDown, modifiersDown and identity.
        p.released = true;
    }

    private void releaseModifiers(Press p) {
        if (!p.modifiersDown) {
            return;
        }
        p.modifiersDown = false;
        for (int m : p.modifierKeys) {
            release(m);
        }
    }

    private void clearPress(int index, Press p) {
        if (index < presses.length && presses[index] == p) {
            presses[index] = null;
        }
    }

    private static int[] modifierKeysFor(int mods) {
        int n = Integer.bitCount(mods & (ShortcutLayout.MOD_CTRL | ShortcutLayout.MOD_ALT | ShortcutLayout.MOD_SHIFT));
        int[] keys = new int[n];
        int i = 0;
        if ((mods & ShortcutLayout.MOD_CTRL) != 0) keys[i++] = KeyEvent.KEYCODE_CTRL_LEFT;
        if ((mods & ShortcutLayout.MOD_ALT) != 0) keys[i++] = KeyEvent.KEYCODE_ALT_LEFT;
        if ((mods & ShortcutLayout.MOD_SHIFT) != 0) keys[i++] = KeyEvent.KEYCODE_SHIFT_LEFT;
        return keys;
    }

    // ------------------------------------------------------------ reference counting

    private void press(int keyCode) {
        int count = downCounts.get(keyCode, 0);
        downCounts.put(keyCode, count + 1);
        if (count == 0) {
            send(keyCode, true);
        }
    }

    private void release(int keyCode) {
        int count = downCounts.get(keyCode, 0);
        if (count <= 0) {
            return;
        }
        downCounts.put(keyCode, count - 1);
        if (count == 1) {
            send(keyCode, false);
        }
    }

    private void send(int keyCode, boolean down) {
        try {
            if (down) {
                sink.keyDown(keyCode);
            } else {
                sink.keyUp(keyCode);
            }
        } catch (Throwable t) {
            // e.g. UnsatisfiedLinkError if the native library never loaded
            Log.w(TAG, "shortcut key " + keyCode + (down ? " down" : " up") + " failed", t);
        }
    }

    // Edit-mode callbacks: unused in game.
    @Override public void onButtonSelected(int index) {}
    @Override public void onLayoutEdited() {}
}
