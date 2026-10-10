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

// GeneralsX @feature Android port 10/10/2026 Editor for the custom shortcut panel (see
// ShortcutLayout). Landscape and full screen, like the game, with a preview at the
// screen's own aspect ratio so a button lands in game exactly where it was put here.
// Top: a hint and the actions (add, edit, delete, defaults, save, export, import).
// Middle: the grid with the buttons; tap one to select it, drag it to move it.
// Bottom: button size and opacity.
//
// Changes stay in a working copy until Save. Export/import go through the system file
// picker as a small JSON file, so a layout can be backed up or shared.

package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ShortcutEditorActivity extends Activity implements ShortcutPanelView.Listener {

    private static final int REQUEST_EXPORT = 7101;
    private static final int REQUEST_IMPORT = 7102;
    private static final int MAX_IMPORT_BYTES = 256 * 1024;

    private ShortcutLayout working;
    private boolean dirty;

    private ShortcutPanelView panel;
    private TextView sizeLabel;
    private TextView opacityLabel;
    private SeekBar sizeBar;
    private SeekBar opacityBar;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        working = ShortcutLayout.load(this).copy();
        buildUi();
        goFullScreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullScreen();
        }
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1118);
        root.setPadding(pad, dp(8), pad, dp(8));
        setContentView(root);
        InsetUtil.applySafeInsets(root);

        TextView hint = new TextView(this);
        hint.setText(R.string.shortcuts_editor_hint);
        hint.setTextColor(0xFFE6E9EC);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        root.addView(hint, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Action row; scrolls sideways if a translation is too long for a small phone.
        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actionsScroll.addView(actions);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(6);
        alp.gravity = Gravity.START;
        root.addView(actionsScroll, alp);

        addAction(actions, R.string.shortcuts_action_add, this::onAdd);
        addAction(actions, R.string.shortcuts_action_edit, this::onEdit);
        addAction(actions, R.string.shortcuts_action_delete, this::onDelete);
        addAction(actions, R.string.shortcuts_action_defaults, this::onDefaults);
        addAction(actions, R.string.shortcuts_action_save, this::onSave);
        addAction(actions, R.string.shortcuts_action_export, this::onExport);
        addAction(actions, R.string.shortcuts_action_import, this::onImport);

        // The preview, at the game screen's aspect ratio, centred in the space left.
        FrameLayout previewHost = new FrameLayout(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        plp.topMargin = dp(8);
        root.addView(previewHost, plp);

        panel = new ShortcutPanelView(this, true);
        panel.setListener(this);
        panel.setAspect(gameScreenAspect());
        panel.setShortcutLayout(working);
        previewHost.addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        TextView caption = new TextView(this);
        caption.setText(R.string.shortcuts_editor_caption);
        caption.setTextColor(0xFF9AA4AE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(4);
        root.addView(caption, clp);

        // Sliders.
        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        root.addView(sliders, slp);

        LinearLayout sizeCol = sliderColumn(sliders);
        sizeLabel = sliderLabel(sizeCol);
        sizeBar = slider(sizeCol, ShortcutLayout.SIZE_MAX - ShortcutLayout.SIZE_MIN,
            working.sizePercent - ShortcutLayout.SIZE_MIN, value -> {
                working.sizePercent = ShortcutLayout.SIZE_MIN + value;
                dirty = true;
                updateSliderLabels();
                panel.refreshAppearance();
            });

        LinearLayout opacityCol = sliderColumn(sliders);
        opacityLabel = sliderLabel(opacityCol);
        opacityBar = slider(opacityCol, ShortcutLayout.OPACITY_MAX - ShortcutLayout.OPACITY_MIN,
            working.opacityPercent - ShortcutLayout.OPACITY_MIN, value -> {
                working.opacityPercent = ShortcutLayout.OPACITY_MIN + value;
                dirty = true;
                updateSliderLabels();
                panel.refreshAppearance();
            });

        updateSliderLabels();
    }

    private void addAction(LinearLayout row, int labelRes, Runnable action) {
        TextView b = new TextView(this);
        b.setText(labelRes);
        b.setTextColor(0xFF1B1F23);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(56));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFDCE0E3);
        bg.setCornerRadius(dp(4));
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) {
            lp.setMarginStart(dp(8));
        }
        row.addView(b, lp);
    }

    private LinearLayout sliderColumn(LinearLayout parent) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (parent.getChildCount() > 0) {
            lp.setMarginStart(dp(24));
        }
        parent.addView(col, lp);
        return col;
    }

    private TextView sliderLabel(LinearLayout col) {
        TextView t = new TextView(this);
        t.setTextColor(0xFFE6E9EC);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(t);
        return t;
    }

    private SeekBar slider(LinearLayout col, int max, int value, java.util.function.IntConsumer onChange) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4E8A2C));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF4E8A2C));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) onChange.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void updateSliderLabels() {
        sizeLabel.setText(getString(R.string.shortcuts_size_label, working.sizePercent));
        opacityLabel.setText(getString(R.string.shortcuts_opacity_label, working.opacityPercent));
    }

    /** Width / height of the game's full-screen landscape window. */
    @SuppressWarnings("deprecation")
    private float gameScreenAspect() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int longSide = Math.max(size.x, size.y);
        int shortSide = Math.min(size.x, size.y);
        return shortSide > 0 ? longSide / (float) shortSide : 16f / 9f;
    }

    @SuppressWarnings("deprecation")
    private void goFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------ panel callbacks

    @Override public void onButtonPressed(int index) {}
    @Override public void onButtonReleased(int index) {}
    @Override public void onButtonSelected(int index) {}

    @Override
    public void onLayoutEdited() {
        dirty = true;
    }

    // ------------------------------------------------------------ actions

    private void onAdd() {
        showButtonDialog(null);
    }

    private void onEdit() {
        int sel = panel.getSelected();
        if (sel < 0 || sel >= working.buttons.size()) {
            toast(getString(R.string.shortcuts_select_first));
            return;
        }
        showButtonDialog(working.buttons.get(sel));
    }

    private void onDelete() {
        int sel = panel.getSelected();
        if (sel < 0 || sel >= working.buttons.size()) {
            toast(getString(R.string.shortcuts_select_first));
            return;
        }
        working.buttons.remove(sel);
        dirty = true;
        panel.setShortcutLayout(working);
        panel.setSelected(-1);
    }

    private void onDefaults() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.shortcuts_action_defaults)
            .setMessage(R.string.shortcuts_defaults_confirm)
            .setPositiveButton(R.string.shortcuts_ok, (d, w) -> {
                ShortcutLayout def = ShortcutLayout.defaults();
                def.sizePercent = working.sizePercent;
                def.opacityPercent = working.opacityPercent;
                replaceWorking(def);
            })
            .setNegativeButton(R.string.shortcuts_cancel, null)
            .show();
    }

    private void onSave() {
        working.save(this);
        dirty = false;
        toast(getString(R.string.shortcuts_saved));
    }

    private void onExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "generalszh-shortcuts.json");
        try {
            startActivityForResult(i, REQUEST_EXPORT);
        } catch (android.content.ActivityNotFoundException e) {
            toast(getString(R.string.shortcuts_no_file_picker));
        }
    }

    private void onImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // JSON files are labelled inconsistently across file managers; accept anything.
        i.setType("*/*");
        try {
            startActivityForResult(i, REQUEST_IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            toast(getString(R.string.shortcuts_no_file_picker));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("no stream");
                out.write(working.toJson().getBytes(StandardCharsets.UTF_8));
                toast(getString(R.string.shortcuts_exported));
            } catch (Exception e) {
                toast(getString(R.string.shortcuts_export_failed));
            }
        } else if (requestCode == REQUEST_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("no stream");
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_IMPORT_BYTES) throw new java.io.IOException("too large");
                }
                ShortcutLayout imported = ShortcutLayout.fromJson(buf.toString("UTF-8"));
                replaceWorking(imported);
                toast(getString(R.string.shortcuts_imported));
            } catch (Exception e) {
                toast(getString(R.string.shortcuts_import_failed));
            }
        }
    }

    private void replaceWorking(ShortcutLayout l) {
        working = l;
        dirty = true;
        panel.setShortcutLayout(working);
        panel.setSelected(-1);
        sizeBar.setProgress(working.sizePercent - ShortcutLayout.SIZE_MIN);
        opacityBar.setProgress(working.opacityPercent - ShortcutLayout.OPACITY_MIN);
        updateSliderLabels();
    }

    // ------------------------------------------------------------ add / edit dialog

    /** {@code existing} null: add a new button. */
    private void showButtonDialog(final ShortcutLayout.Button existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        formLabel(form, R.string.shortcuts_field_label);
        final EditText labelEdit = new EditText(this);
        labelEdit.setSingleLine(true);
        labelEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        labelEdit.setFilters(new InputFilter[] { new InputFilter.LengthFilter(12) });
        labelEdit.setHint(R.string.shortcuts_field_label_hint);
        form.addView(labelEdit);

        formLabel(form, R.string.shortcuts_field_key);
        final Spinner keySpinner = new Spinner(this);
        String[] names = new String[ShortcutLayout.KEYS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = ShortcutLayout.keyName(ShortcutLayout.KEYS[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keySpinner.setAdapter(adapter);
        form.addView(keySpinner);

        formLabel(form, R.string.shortcuts_field_modifiers);
        LinearLayout mods = new LinearLayout(this);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox ctrl = modifierBox(mods, "Ctrl");
        final CheckBox alt = modifierBox(mods, "Alt");
        final CheckBox shift = modifierBox(mods, "Shift");
        form.addView(mods);

        if (existing != null) {
            labelEdit.setText(existing.label);
            for (int i = 0; i < ShortcutLayout.KEYS.length; i++) {
                if (ShortcutLayout.KEYS[i] == existing.keyCode) {
                    keySpinner.setSelection(i);
                    break;
                }
            }
            ctrl.setChecked((existing.modifiers & ShortcutLayout.MOD_CTRL) != 0);
            alt.setChecked((existing.modifiers & ShortcutLayout.MOD_ALT) != 0);
            shift.setChecked((existing.modifiers & ShortcutLayout.MOD_SHIFT) != 0);
        }

        new AlertDialog.Builder(this)
            .setTitle(existing == null ? R.string.shortcuts_dialog_add_title : R.string.shortcuts_dialog_edit_title)
            .setView(form)
            .setPositiveButton(R.string.shortcuts_ok, (d, w) -> {
                int keyCode = ShortcutLayout.KEYS[Math.max(0, keySpinner.getSelectedItemPosition())];
                int m = 0;
                if (ctrl.isChecked()) m |= ShortcutLayout.MOD_CTRL;
                if (alt.isChecked()) m |= ShortcutLayout.MOD_ALT;
                if (shift.isChecked()) m |= ShortcutLayout.MOD_SHIFT;
                String label = labelEdit.getText().toString().trim();
                if (existing == null) {
                    ShortcutLayout.Button b = new ShortcutLayout.Button(label, keyCode, m, 0.5f, 0.5f);
                    working.buttons.add(b);
                    panel.setShortcutLayout(working);
                    panel.setSelected(working.buttons.size() - 1);
                } else {
                    existing.label = label;
                    existing.keyCode = keyCode;
                    existing.modifiers = m;
                    panel.refreshAppearance();
                }
                dirty = true;
            })
            .setNegativeButton(R.string.shortcuts_cancel, null)
            .show();
    }

    private TextView formLabel(LinearLayout form, int textRes) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        form.addView(t, lp);
        return t;
    }

    private CheckBox modifierBox(LinearLayout row, String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(16));
        row.addView(box, lp);
        return box;
    }

    // ------------------------------------------------------------ leaving

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!dirty) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.shortcuts_unsaved_title)
            .setMessage(R.string.shortcuts_unsaved_message)
            .setPositiveButton(R.string.shortcuts_action_save, (d, w) -> {
                onSave();
                finish();
            })
            .setNegativeButton(R.string.shortcuts_discard, (d, w) -> finish())
            .setNeutralButton(R.string.shortcuts_cancel, null)
            .show();
    }

    private void toast(CharSequence text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
