package com.github.tvbox.osc.theme;

import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

import java.lang.ref.WeakReference;

/** Keeps the text inside the platform selection highlight white. */
public final class TextSelectionStyle {
    private TextSelectionStyle() {
    }

    /** Installs once per editable or selectable view; the original text object is never replaced. */
    public static void install(TextView view) {
        if (view == null || (!(view instanceof EditText) && !view.isTextSelectable())) return;
        view.setHighlightColor(ContextCompat.getColor(view.getContext(), R.color.selection_highlight));
        Object installed = view.getTag(R.id.text_selection_style_observer);
        if (installed instanceof Observer) {
            ((Observer) installed).bind(view.getText());
            return;
        }
        Observer observer = new Observer(view,
                ContextCompat.getColor(view.getContext(), R.color.selection_text));
        view.setTag(R.id.text_selection_style_observer, observer);
        if (view instanceof EditText) {
            view.addTextChangedListener(observer.editWatcher);
        } else {
            view.addOnAttachStateChangeListener(observer);
            if (view.isAttachedToWindow()) observer.observePreDraw(view);
        }
        observer.bind(view.getText());
    }

    /** A NoCopySpan ensures selection styling never travels with copied or pasted text. */
    private static final class WhiteSelectionSpan extends CharacterStyle
            implements UpdateAppearance, NoCopySpan {
        private final int color;

        WhiteSelectionSpan(int color) {
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setColor(color);
        }
    }

    private static final class Observer implements SpanWatcher, NoCopySpan,
            ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<TextView> viewRef;
        private final WhiteSelectionSpan colorSpan;
        private final TextWatcher editWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                // Android forbids text mutation from this callback. afterTextChanged binds below.
            }

            @Override
            public void afterTextChanged(Editable value) {
                if (viewRef.get() != null) bind(value);
            }
        };
        // Text may outlive its view (for example, while held by another model), so the watcher
        // avoids owning either the view or TextView's internal change watcher through this field.
        private WeakReference<Spannable> textRef = new WeakReference<>(null);
        private ViewTreeObserver observedTree;
        private boolean updating;

        Observer(TextView view, int selectionTextColor) {
            viewRef = new WeakReference<>(view);
            colorSpan = new WhiteSelectionSpan(selectionTextColor);
        }

        void observePreDraw(TextView view) {
            ViewTreeObserver tree = view.getViewTreeObserver();
            if (tree == observedTree) return;
            stopObservingPreDraw();
            if (tree.isAlive()) {
                tree.addOnPreDrawListener(this);
                observedTree = tree;
            }
        }

        private void stopObservingPreDraw() {
            if (observedTree != null && observedTree.isAlive()) {
                observedTree.removeOnPreDrawListener(this);
            }
            observedTree = null;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (view instanceof TextView) {
                observePreDraw((TextView) view);
                bind(((TextView) view).getText());
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stopObservingPreDraw();
        }

        @Override
        public boolean onPreDraw() {
            TextView view = viewRef.get();
            if (view == null || !view.isAttachedToWindow()) {
                stopObservingPreDraw();
                return true;
            }
            if (view.getViewTreeObserver() != observedTree) observePreDraw(view);
            CharSequence current = view.getText();
            if (current != textRef.get()) bind(current);
            return true;
        }

        void bind(CharSequence value) {
            Spannable next = value instanceof Spannable ? (Spannable) value : null;
            Spannable current = textRef.get();
            if (current != next) {
                if (current != null) {
                    current.removeSpan(this);
                    current.removeSpan(colorSpan);
                }
                textRef = new WeakReference<>(next);
            }
            if (next == null) return;
            if (next.getSpanStart(this) < 0) {
                next.setSpan(this, 0, next.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
            updateSelection();
        }

        private void updateSelection() {
            Spannable current = textRef.get();
            if (updating || current == null) return;
            int first = Selection.getSelectionStart(current);
            int last = Selection.getSelectionEnd(current);
            int start = Math.min(first, last);
            int end = Math.max(first, last);
            if (first < 0 || last < 0 || start == end || end > current.length()) {
                start = -1;
                end = -1;
            }
            if (current.getSpanStart(colorSpan) == start && current.getSpanEnd(colorSpan) == end) {
                return;
            }
            updating = true;
            try {
                current.removeSpan(colorSpan);
                if (start >= 0) {
                    current.setSpan(colorSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } finally {
                updating = false;
            }
        }

        private void selectionChanged(Object what) {
            if (what == Selection.SELECTION_START || what == Selection.SELECTION_END) {
                updateSelection();
            }
        }

        @Override
        public void onSpanAdded(Spannable value, Object what, int start, int end) {
            if (value == textRef.get()) selectionChanged(what);
        }

        @Override
        public void onSpanRemoved(Spannable value, Object what, int start, int end) {
            if (value == textRef.get()) selectionChanged(what);
        }

        @Override
        public void onSpanChanged(Spannable value, Object what, int oldStart, int oldEnd,
                                  int newStart, int newEnd) {
            if (value == textRef.get()) selectionChanged(what);
        }

    }
}
