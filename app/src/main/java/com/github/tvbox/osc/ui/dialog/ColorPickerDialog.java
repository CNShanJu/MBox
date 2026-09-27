package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.ui.kit.ColorPlateView;
import com.github.tvbox.osc.ui.kit.HueBarView;
import com.github.tvbox.osc.util.Utils;
import com.google.android.material.slider.Slider;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;

import org.jetbrains.annotations.NotNull;

/**
 * 取色板:给主题的每个色值用。<b>色值文本输入与取色板是同一份数据</b> ——
 * 拖色板会实时回写文本框,改文本框也会实时更新色板(改不出合法值就只标红、不覆盖当前颜色)。
 *
 * <p>结果按主题文件的写法给出:{@code #RRGGBB};不透明度不是 100% 时给 {@code #AARRGGBB}
 * (与 {@code theme_colors.json} 的口径一致,不用单独存 alpha)。
 *
 * <p>带 alpha 的色值(如暗色主题的 {@code text_hint}:{@code #99E6E1E5})进来时会拆成
 * RGB + 不透明度两段,用户调完再合回去,不会把原有透明度悄悄抹掉。
 */
public class ColorPickerDialog extends AppCenterPopupView {

    public interface Listener {
        /** @param hex {@code #RRGGBB} 或 {@code #AARRGGBB} */
        void onPicked(String hex);
    }

    private final String title;
    private final String initialHex;
    private Listener listener;

    private ColorPlateView plate;
    private HueBarView hueBar;
    private EditText hexInput;
    private TextView alphaLabel;
    private Slider alphaSlider;
    private View preview;

    /** 当前 RGB(不含 alpha)与 alpha(0-255)分开维护:色板只管 RGB */
    private int rgb = 0x1F2937;
    private int alpha = 0xFF;
    /** 程序化回写文本框时抑制 TextWatcher,避免"改文本→改色板→再回写文本"的抖动 */
    private boolean suppressTextWatcher = false;

    public ColorPickerDialog(@NonNull @NotNull Context context, String title, String initialHex) {
        super(context);
        this.title = title == null ? "选择颜色" : title;
        this.initialHex = initialHex;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_color_picker;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        plate = findViewById(R.id.plate);
        hueBar = findViewById(R.id.hue);
        hexInput = findViewById(R.id.et_hex);
        alphaLabel = findViewById(R.id.tv_alpha);
        alphaSlider = findViewById(R.id.slider_alpha);
        preview = findViewById(R.id.v_preview);

        int parsed = ThemePalette.parseColor(initialHex, rgb);
        rgb = parsed & 0xFFFFFF;
        alpha = (parsed >>> 24) & 0xFF;

        ((TextView) findViewById(R.id.tv_title)).setText(title);
        plate.setColor(rgb);
        hueBar.setHue(hueOf(rgb));
        alphaSlider.setValue(Math.round(alpha / 255f * 100f));
        updateAlphaLabel();
        syncHexText();
        updatePreview();

        plate.setOnColorChanged(color -> {
            rgb = color & 0xFFFFFF;
            syncHexText();
            updatePreview();
        });
        hueBar.setOnHueChanged(hue -> {
            plate.setHue(hue);
            rgb = plate.getColor() & 0xFFFFFF;
            syncHexText();
            updatePreview();
        });
        alphaSlider.addOnChangeListener((slider, value, fromUser) -> {
            alpha = Math.round(value / 100f * 255f);
            updateAlphaLabel();
            syncHexText();
            updatePreview();
        });
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressTextWatcher) return;
                applyHexInput(s == null ? "" : s.toString());
            }
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.btn_ok).setOnClickListener(v -> {
            if (listener != null) listener.onPicked(resultHex());
            dismiss();
        });
        keepFixedPosition();
    }

    /**
     * 弹窗位置<b>固定住</b>,不跟着软键盘上移。
     *
     * <p>用户口径:"会随着输入框往上挤,不合理,固定一下"。两件事一起做才有效:
     * <ol>
     *   <li>布局根 {@code focusableInTouchMode} + 这里清掉输入框焦点:打开弹窗时不自动弹键盘
     *       (之前一打开就被顶上去,就是这个原因);</li>
     *   <li>{@code popupInfo.isMoveUpToKeyboard = false}:即便用户真去点输入框,
     *       弹窗也待在原地,不再整块上移。</li>
     * </ol>
     */
    private void keepFixedPosition() {
        try {
            if (hexInput != null) hexInput.clearFocus();
            if (popupInfo != null) popupInfo.isMoveUpToKeyboard = false;
        } catch (Throwable ignored) {
        }
    }

    /** 文本框 → 色板(非法值只标红,不打断用户输入) */
    private void applyHexInput(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            hexInput.setTextColor(theme(R.color.text_foreground));
            return;
        }
        boolean ok = text.matches("(?i)^#?([0-9a-f]{6}|[0-9a-f]{8})$");
        if (!ok) {
            hexInput.setTextColor(theme(R.color.text_danger));
            return;
        }
        hexInput.setTextColor(theme(R.color.text_foreground));
        int value = ThemePalette.parseColor(text.startsWith("#") ? text : "#" + text, rgb);
        rgb = value & 0xFFFFFF;
        alpha = (value >>> 24) & 0xFF;
        plate.setColor(rgb);
        hueBar.setHue(hueOf(rgb));
        suppressTextWatcher = true;
        alphaSlider.setValue(Math.round(alpha / 255f * 100f));
        suppressTextWatcher = false;
        updateAlphaLabel();
        updatePreview();
    }

    private void syncHexText() {
        String hex = resultHex();
        suppressTextWatcher = true;
        hexInput.setText(hex);
        hexInput.setSelection(hex.length());
        hexInput.setTextColor(theme(R.color.text_foreground));
        suppressTextWatcher = false;
    }

    private String resultHex() {
        return alpha >= 0xFF
                ? String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF)
                : String.format(java.util.Locale.ROOT, "#%02X%06X", alpha, rgb & 0xFFFFFF);
    }

    private void updatePreview() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setColor((alpha << 24) | (rgb & 0xFFFFFF));
        g.setStroke(Math.max(1, (int) dp(1)), theme(R.color.btn_stroke));
        preview.setBackground(g);
    }

    private void updateAlphaLabel() {
        alphaLabel.setText(Math.round(alpha / 255f * 100f) + "%");
    }

    private static float hueOf(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb | 0xFF000000, hsv);
        return hsv[0];
    }

    private int theme(int colorRes) {
        return androidx.core.content.ContextCompat.getColor(getContext(), colorRes);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 统一弹出入口 */
    public static void show(Context context, String title, String initialHex, Listener listener) {
        ColorPickerDialog dialog = new ColorPickerDialog(context, title, initialHex);
        dialog.setListener(listener);
        new XPopup.Builder(context)
                .isDarkTheme(Utils.isAppDarkTheme())
                // 弹窗位置固定,不随软键盘上移(见 keepFixedPosition 的说明)
                .moveUpToKeyboard(false)
                .asCustom(dialog)
                .show();
    }

    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isAppDarkTheme())
                    .moveUpToKeyboard(false)
                    .setPopupCallback(new XPopupCallback() {
                        @Override public void onCreated(BasePopupView v) { }
                        @Override public void beforeShow(BasePopupView v) { }
                        @Override public void onShow(BasePopupView v) { }
                        @Override public void onDismiss(BasePopupView v) { }
                        @Override public void beforeDismiss(BasePopupView v) { }
                        @Override public boolean onBackPressed(BasePopupView v) { return false; }
                        @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                        @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                        @Override public void onClickOutside(BasePopupView v) { }
                    })
                    .asCustom(this).show();
        }
        return super.show();
    }
}
