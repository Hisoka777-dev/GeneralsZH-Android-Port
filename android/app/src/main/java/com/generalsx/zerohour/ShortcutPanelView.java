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

// GeneralsX @feature Android port 10/10/2026 Draws a ShortcutLayout's buttons at their
// saved positions. One view, two modes:
//
//  - Play (ShortcutOverlay, in game): each button reports press and release to a
//    Listener, which turns them into key events. The panel itself is NOT clickable, so
//    a touch that lands between buttons is never claimed here: Android's dispatch moves
//    on to the next view underneath -- the game's own SDL surface -- exactly as if the
//    panel were not there. Only a finger that starts on a button belongs to the button,
//    and ViewGroup's default motion-event splitting lets other fingers keep panning and
//    zooming the map at the same time.
//
//  - Edit (ShortcutEditorActivity): draws the placement grid, keeps the preview at the
//    screen's aspect ratio, and lets a button be selected and dragged.

package com.generalsx.zerohour;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

final class ShortcutPanelView extends ViewGroup {

    interface Listener {
        /** Play mode: a finger went down on button {@code index}. */
        void onButtonPressed(int index);
        /** Play mode: that finger lifted, or the touch was cancelled. */
        void onButtonReleased(int index);
        /** Edit mode: button {@code index} was selected (-1 for none). */
        void onButtonSelected(int index);
        /** Edit mode: a drag finished and the layout's positions changed. */
        void onLayoutEdited();
    }

    static final int GRID_COLUMNS = 10;

    private final boolean editMode;
    private final float density;
    private final Paint gridPaint = new Paint();
    private ShortcutLayout layout = new ShortcutLayout();
    private Listener listener;
    private int selected = -1;
    /** Edit mode: width / height the preview keeps, so positions match the game screen. */
    private float aspect = 0f;

    ShortcutPanelView(Context context, boolean editMode) {
        super(context);
        this.editMode = editMode;
        this.density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setClipChildren(false);
        setMotionEventSplittingEnabled(true);
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(Math.max(1f, density));
        if (editMode) {
            setWillNotDraw(false);
            setBackgroundColor(0xFF151E28);
        }
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAspect(float aspect) {
        this.aspect = aspect;
        requestLayout();
    }

    ShortcutLayout getShortcutLayout() {
        return layout;
    }

    int getSelected() {
        return selected;
    }

    /** Rebuilds the buttons from {@code newLayout} (kept by reference: edits go into it). */
    void setShortcutLayout(ShortcutLayout newLayout) {
        layout = newLayout;
        if (selected >= layout.buttons.size()) {
            selected = -1;
        }
        removeAllViews();
        for (int i = 0; i < layout.buttons.size(); i++) {
            addView(createButtonView(i));
        }
        refreshAppearance();
    }

    void setSelected(int index) {
        selected = index;
        refreshAppearance();
        if (listener != null) {
            listener.onButtonSelected(index);
        }
    }

    /** Re-applies size, opacity, labels and selection without rebuilding the views. */
    void refreshAppearance() {
        for (int i = 0; i < getChildCount(); i++) {
            TextView v = (TextView) getChildAt(i);
            ShortcutLayout.Button b = layout.buttons.get(i);
            v.setText(b.displayLabel());
            v.setAlpha(layout.opacityPercent / 100f);
            applyBackground(v, false, i == selected);
        }
        requestLayout();
        invalidate();
    }

    int buttonSizePx() {
        return Math.round(ShortcutLayout.BASE_BUTTON_DP * density * layout.sizePercent / 100f);
    }

    // ------------------------------------------------------------ buttons

    @SuppressLint("ClickableViewAccessibility")
    private TextView createButtonView(final int index) {
        TextView v = new TextView(getContext());
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setMaxLines(1);
        v.setIncludeFontPadding(false);
        v.setAutoSizeTextTypeUniformWithConfiguration(8, 18, 1, TypedValue.COMPLEX_UNIT_SP);
        int pad = Math.round(4 * density);
        v.setPadding(pad, pad, pad, pad);
        // Never take focus: the game's surface must keep keyboard focus.
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        v.setSoundEffectsEnabled(false);
        v.setOnTouchListener(editMode ? new DragHandler(index) : new PressHandler(index));
        return v;
    }

    private void applyBackground(TextView v, boolean pressed, boolean isSelected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * density);
        int stroke = isSelected ? 0xFFE8C547 : 0xFF4E8A2C;  // selected: gold; otherwise the launcher green
        bg.setColor(pressed ? 0xF04E8A2C : 0xCC111B24);
        bg.setStroke(Math.round((isSelected ? 3 : 2) * density), stroke);
        v.setBackground(bg);
    }

    /** Play mode: a press is a key down, a release (or cancel) a key up. */
    private final class PressHandler implements OnTouchListener {
        private final int index;
        private boolean down;

        PressHandler(int index) {
            this.index = index;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    applyBackground((TextView) v, true, false);
                    if (listener != null) listener.onButtonPressed(index);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (down) {
                        down = false;
                        applyBackground((TextView) v, false, false);
                        if (listener != null) listener.onButtonReleased(index);
                    }
                    return true;
                default:
                    // Sliding off the button keeps it held, like a real key under a finger.
                    return true;
            }
        }
    }

    /** Edit mode: touch selects, moving drags, lifting snaps to the grid. */
    private final class DragHandler implements OnTouchListener {
        private final int index;
        private float grabDx;
        private float grabDy;

        DragHandler(int index) {
            this.index = index;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (index >= layout.buttons.size() || getWidth() == 0 || getHeight() == 0) {
                return false;
            }
            ShortcutLayout.Button b = layout.buttons.get(index);
            // Panel-relative finger position (the button itself moves under the finger).
            float px = e.getRawX() - panelScreenX();
            float py = e.getRawY() - panelScreenY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    grabDx = b.x * getWidth() - px;
                    grabDy = b.y * getHeight() - py;
                    if (selected != index) {
                        setSelected(index);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    b.x = ShortcutLayout.clamp01((px + grabDx) / getWidth());
                    b.y = ShortcutLayout.clamp01((py + grabDy) / getHeight());
                    requestLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    snapToGrid(b);
                    requestLayout();
                    if (listener != null) listener.onLayoutEdited();
                    return true;
                default:
                    return true;
            }
        }
    }

    private float panelScreenX() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        return loc[0];
    }

    private float panelScreenY() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        return loc[1];
    }

    int gridRows() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return 5;
        }
        return Math.max(3, Math.round(GRID_COLUMNS * (float) getHeight() / getWidth()));
    }

    /** Snaps a centre to the nearest half cell, so buttons line up in cells or on their edges. */
    void snapToGrid(ShortcutLayout.Button b) {
        float stepX = 1f / (GRID_COLUMNS * 2);
        float stepY = 1f / (gridRows() * 2);
        b.x = ShortcutLayout.clamp01(Math.round(b.x / stepX) * stepX);
        b.y = ShortcutLayout.clamp01(Math.round(b.y / stepY) * stepY);
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        if (editMode && aspect > 0f && w > 0 && h > 0) {
            if (w / (float) h > aspect) {
                w = Math.round(h * aspect);
            } else {
                h = Math.round(w / aspect);
            }
        }
        int size = buttonSizePx();
        int childSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childSpec, childSpec);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int size = buttonSizePx();
        for (int i = 0; i < getChildCount() && i < layout.buttons.size(); i++) {
            ShortcutLayout.Button btn = layout.buttons.get(i);
            // Keep every button whole on screen, whatever its saved centre.
            int left = Math.round(btn.x * w - size / 2f);
            int top = Math.round(btn.y * h - size / 2f);
            left = Math.max(0, Math.min(w - size, left));
            top = Math.max(0, Math.min(h - size, top));
            getChildAt(i).layout(left, top, left + size, top + size);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!editMode) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        int rows = gridRows();
        for (int c = 1; c < GRID_COLUMNS; c++) {
            float x = w * c / (float) GRID_COLUMNS;
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (int row = 1; row < rows; row++) {
            float y = h * row / (float) rows;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
    }

    // A touch that starts between buttons is not ours: never claim it, in either mode.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode && event.getActionMasked() == MotionEvent.ACTION_DOWN && selected != -1) {
            setSelected(-1);  // tapping empty space in the editor deselects, then lets go
        }
        return false;
    }
}
