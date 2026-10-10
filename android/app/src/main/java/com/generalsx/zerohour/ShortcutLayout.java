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

// GeneralsX @feature Android port 10/10/2026 Custom shortcut panel: on-screen buttons
// laid over the game that press keyboard keys (with Ctrl/Alt/Shift), so the game's own
// hotkeys work from the touchscreen. This class is the layout the player builds in
// ShortcutEditorActivity and that ShortcutOverlay draws in game: which buttons exist,
// what key each one presses, where it sits, and how big and see-through they are.
//
// Positions are stored as fractions (0..1) of the screen, centre of the button, so a
// layout made on one phone lands in the same place on another of a different size.
//
// Only keys the engine actually understands are offered: SDL3Keyboard.cpp's
// translateScanCodeToKeyVal() maps letters, digits, F1-F12, the arrows, Esc, Space,
// Enter, Tab, Backspace, Delete, Home/End, PageUp/PageDown and the modifiers. A key
// outside that list would be pressed by the button and silently ignored by the game.

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ShortcutLayout {

    static final String PREFS_NAME = "gzh_shortcuts";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_LAYOUT = "layout";

    static final int MOD_CTRL = 1;
    static final int MOD_ALT = 2;
    static final int MOD_SHIFT = 4;

    static final int SIZE_MIN = 50;
    static final int SIZE_MAX = 200;
    static final int OPACITY_MIN = 20;
    static final int OPACITY_MAX = 100;

    /** Edge of a button at 100% size, in dp. */
    static final int BASE_BUTTON_DP = 56;

    /** Layouts read from a file are capped so a broken or hostile file can't flood the screen. */
    private static final int MAX_BUTTONS = 60;
    private static final int FORMAT_VERSION = 1;

    static final class Button {
        String label;  // empty: show the key combination instead
        int keyCode;   // android.view.KeyEvent.KEYCODE_*
        int modifiers; // MOD_* bits
        float x;       // centre, fraction of the screen width
        float y;       // centre, fraction of the screen height

        Button(String label, int keyCode, int modifiers, float x, float y) {
            this.label = label == null ? "" : label;
            this.keyCode = keyCode;
            this.modifiers = modifiers;
            this.x = clamp01(x);
            this.y = clamp01(y);
        }

        Button copy() {
            return new Button(label, keyCode, modifiers, x, y);
        }

        /** What the button shows: its own name, or the key combination it presses. */
        String displayLabel() {
            String trimmed = label.trim();
            return trimmed.isEmpty() ? comboName(keyCode, modifiers) : trimmed;
        }
    }

    final List<Button> buttons = new ArrayList<>();
    int sizePercent = 100;
    int opacityPercent = 78;

    ShortcutLayout copy() {
        ShortcutLayout c = new ShortcutLayout();
        for (Button b : buttons) {
            c.buttons.add(b.copy());
        }
        c.sizePercent = sizePercent;
        c.opacityPercent = opacityPercent;
        return c;
    }

    // ------------------------------------------------------------ defaults

    /**
     * Two rows across the top of the screen: the control-group digits 1-9 and 0 (a tap
     * selects that group, as on a keyboard), and under them the reference layout's keys
     * E, Q, W, R, A, S, D, F, SPACE, ESC. Kept clear of the bottom of the screen, where
     * the game's own control bar sits, and below the hide/show handle at the top centre.
     */
    static ShortcutLayout defaults() {
        ShortcutLayout l = new ShortcutLayout();
        int[] digits = {
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0,
        };
        int[] letters = {
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE,
        };
        addRow(l, digits, 0.15f);
        addRow(l, letters, 0.30f);
        return l;
    }

    /** One row on the centres of a 10-column grid, at {@code y} (fraction of the height). */
    private static void addRow(ShortcutLayout l, int[] keys, float y) {
        for (int i = 0; i < keys.length; i++) {
            l.buttons.add(new Button("", keys[i], 0, (i + 0.5f) / keys.length, y));
        }
    }

    // ------------------------------------------------------------ storage

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    /** The saved layout, or the defaults when nothing was saved or the saved one is unreadable. */
    static ShortcutLayout load(Context ctx) {
        String json = prefs(ctx).getString(PREF_LAYOUT, null);
        if (json != null) {
            try {
                return fromJson(json);
            } catch (JSONException ignored) {
                // fall through to the defaults
            }
        }
        return defaults();
    }

    void save(Context ctx) {
        prefs(ctx).edit().putString(PREF_LAYOUT, toJson()).apply();
    }

    String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "generalszh-shortcuts");
            root.put("version", FORMAT_VERSION);
            root.put("size", sizePercent);
            root.put("opacity", opacityPercent);
            JSONArray list = new JSONArray();
            for (Button b : buttons) {
                JSONObject o = new JSONObject();
                o.put("label", b.label);
                o.put("key", keyName(b.keyCode));
                o.put("ctrl", (b.modifiers & MOD_CTRL) != 0);
                o.put("alt", (b.modifiers & MOD_ALT) != 0);
                o.put("shift", (b.modifiers & MOD_SHIFT) != 0);
                o.put("x", round3(b.x));
                o.put("y", round3(b.y));
                list.put(o);
            }
            root.put("buttons", list);
            return root.toString(2);
        } catch (JSONException e) {
            // Only reachable with a NaN/infinite number, which clamp01() rules out.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads a layout written by {@link #toJson()}. Keys are stored by name ("A", "F5",
     * "ESC") rather than Android key code so an exported file stays readable and
     * hand-editable. Buttons with an unknown key are skipped, not fatal.
     */
    static ShortcutLayout fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray list = root.getJSONArray("buttons");
        ShortcutLayout l = new ShortcutLayout();
        l.sizePercent = clamp(root.optInt("size", 100), SIZE_MIN, SIZE_MAX);
        l.opacityPercent = clamp(root.optInt("opacity", 78), OPACITY_MIN, OPACITY_MAX);
        for (int i = 0; i < list.length() && l.buttons.size() < MAX_BUTTONS; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) {
                continue;
            }
            int keyCode = keyCodeForName(o.optString("key", ""));
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                continue;
            }
            int mods = 0;
            if (o.optBoolean("ctrl", false)) mods |= MOD_CTRL;
            if (o.optBoolean("alt", false)) mods |= MOD_ALT;
            if (o.optBoolean("shift", false)) mods |= MOD_SHIFT;
            String label = o.optString("label", "");
            if (label.length() > 12) {
                label = label.substring(0, 12);
            }
            l.buttons.add(new Button(label, keyCode, mods,
                (float) o.optDouble("x", 0.5), (float) o.optDouble("y", 0.5)));
        }
        return l;
    }

    // ------------------------------------------------------------ keys

    /** Every key the engine maps, in the order the picker lists them. */
    static final int[] KEYS = buildKeyList();

    private static int[] buildKeyList() {
        List<Integer> k = new ArrayList<>();
        for (int c = KeyEvent.KEYCODE_A; c <= KeyEvent.KEYCODE_Z; c++) k.add(c);
        for (int c = KeyEvent.KEYCODE_1; c <= KeyEvent.KEYCODE_9; c++) k.add(c);
        k.add(KeyEvent.KEYCODE_0);
        for (int c = KeyEvent.KEYCODE_F1; c <= KeyEvent.KEYCODE_F12; c++) k.add(c);
        int[] rest = {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        };
        for (int c : rest) k.add(c);
        int[] out = new int[k.size()];
        for (int i = 0; i < out.length; i++) out[i] = k.get(i);
        return out;
    }

    static String keyName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "F" + (1 + keyCode - KeyEvent.KEYCODE_F1);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:      return "ESC";
            case KeyEvent.KEYCODE_SPACE:       return "SPACE";
            case KeyEvent.KEYCODE_ENTER:       return "ENTER";
            case KeyEvent.KEYCODE_TAB:         return "TAB";
            case KeyEvent.KEYCODE_DEL:         return "BKSP";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "DEL";
            case KeyEvent.KEYCODE_MOVE_HOME:   return "HOME";
            case KeyEvent.KEYCODE_MOVE_END:    return "END";
            case KeyEvent.KEYCODE_PAGE_UP:     return "PGUP";
            case KeyEvent.KEYCODE_PAGE_DOWN:   return "PGDN";
            case KeyEvent.KEYCODE_DPAD_UP:     return "↑";
            case KeyEvent.KEYCODE_DPAD_DOWN:   return "↓";
            case KeyEvent.KEYCODE_DPAD_LEFT:   return "←";
            case KeyEvent.KEYCODE_DPAD_RIGHT:  return "→";
            default:                           return "?";
        }
    }

    static int keyCodeForName(String name) {
        if (name == null) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        String wanted = name.trim();
        for (int keyCode : KEYS) {
            if (keyName(keyCode).equalsIgnoreCase(wanted)) {
                return keyCode;
            }
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    static String comboName(int keyCode, int modifiers) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & MOD_CTRL) != 0) sb.append("Ctrl+");
        if ((modifiers & MOD_ALT) != 0) sb.append("Alt+");
        if ((modifiers & MOD_SHIFT) != 0) sb.append("Shift+");
        sb.append(keyName(keyCode));
        return sb.toString();
    }

    // ------------------------------------------------------------ helpers

    static float clamp01(float v) {
        if (Float.isNaN(v)) return 0.5f;
        return Math.max(0f, Math.min(1f, v));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round3(float v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
