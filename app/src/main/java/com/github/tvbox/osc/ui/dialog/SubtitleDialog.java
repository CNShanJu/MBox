package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 字幕设置弹窗（统一走 XPopup 居中弹窗 AppCenterPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new SubtitleDialog(activity)} + 3 个 setXxxListener + {@code show()};
 * XPopup 内容视图 onCreate 才可 findViewById,故 setter 存字段、onCreate 内绑定。
 */
public class SubtitleDialog extends AppCenterPopupView {

    public TextView selectInternal;
    private TextView selectLocal;
    private TextView selectRemote;
    private TextView subtitleSizeMinus;
    private TextView subtitleSizeText;
    private TextView subtitleSizePlus;
    private TextView subtitleTimeMinus;
    private TextView subtitleTimeText;
    private TextView subtitleTimePlus;
    private TextView subtitleStyleOne;
    private TextView subtitleStyleTwo;
    private TextView subtitleOpen;
    private TextView subtitleClose;
    private int subtitleTextSize;
    private int subtitleDelayMillis;

    private SearchSubtitleListener mSearchSubtitleListener;
    private LocalFileChooserListener mLocalFileChooserListener;
    private SubtitleViewListener mSubtitleViewListener;
    private final android.app.Activity ownerActivity;
    private final PlayConfig.Listener subtitleStateListener = () -> post(() -> {
        if (isShow() && subtitleOpen != null) updateSubtitleState(PlayConfig.isSubtitleOpen());
    });

    public SubtitleDialog(@NonNull @NotNull Context context) {
        super(context);
        ownerActivity = context instanceof android.app.Activity ? (android.app.Activity) context : null;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_subtitle;
    }

    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        initView();
        PlayConfig.subscribe(subtitleStateListener);
    }

    @Override
    protected void onDismiss() {
        PlayConfig.unsubscribe(subtitleStateListener);
        super.onDismiss();
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
                    .asCustom(this).show();
        }
        return super.show();
    }

    private void initView() {
        selectInternal = findViewById(R.id.selectInternal);
        selectLocal = findViewById(R.id.selectLocal);
        selectRemote = findViewById(R.id.selectRemote);
        subtitleSizeMinus = findViewById(R.id.subtitleSizeMinus);
        subtitleSizeText = findViewById(R.id.subtitleSizeText);
        subtitleSizePlus = findViewById(R.id.subtitleSizePlus);
        subtitleTimeMinus = findViewById(R.id.subtitleTimeMinus);
        subtitleTimeText = findViewById(R.id.subtitleTimeText);
        subtitleTimePlus = findViewById(R.id.subtitleTimePlus);
        subtitleStyleOne = findViewById(R.id.subtitleStyleOne);
        subtitleStyleTwo = findViewById(R.id.subtitleStyleTwo);
        subtitleOpen = findViewById(R.id.subtitleOpen);
        subtitleClose = findViewById(R.id.subtitleClose);

        selectLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                if (mLocalFileChooserListener != null) mLocalFileChooserListener.openLocalFileChooserDialog();
            }
        });

        selectRemote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                if (mSearchSubtitleListener != null) mSearchSubtitleListener.openSearchSubtitleDialog();
            }
        });

        subtitleTextSize = SubtitleHelper.getTextSize(ownerActivity != null ? ownerActivity : (android.app.Activity) getContext());
        updateSubtitleSizeText();
        subtitleSizeMinus.setOnClickListener(view -> adjustSubtitleSize(-2));
        subtitleSizePlus.setOnClickListener(view -> adjustSubtitleSize(2));

        subtitleDelayMillis = SubtitleHelper.getTimeDelay();
        updateSubtitleDelayText();
        subtitleTimeMinus.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            adjustSubtitleDelay(-500);
        });
        subtitleTimePlus.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            adjustSubtitleDelay(500);
        });
        selectInternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                if (mSubtitleViewListener != null) mSubtitleViewListener.selectInternalSubtitle();
            }
        });

        subtitleStyleOne.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int style = 0;
                dismiss();
                if (mSubtitleViewListener != null) mSubtitleViewListener.setTextStyle(style);
                AppBubble.toast("设置样式成功");
            }
        });

        subtitleStyleTwo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int style = 1;
                dismiss();
                if (mSubtitleViewListener != null) mSubtitleViewListener.setTextStyle(style);
                AppBubble.toast("设置样式成功");
            }
        });
        findViewById(R.id.subtitleOpen).setOnClickListener(v -> {
            // 只切换字幕开关,不关闭弹窗:用户可继续在同一个弹窗里调整字号/延迟/样式/选字幕
            updateSubtitleState(true);
            if (mSubtitleViewListener != null) mSubtitleViewListener.subtitleOpen(true);
        });
        findViewById(R.id.subtitleClose).setOnClickListener(v -> {
            // 只切换字幕开关,不关闭弹窗
            updateSubtitleState(false);
            if (mSubtitleViewListener != null) mSubtitleViewListener.subtitleOpen(false);
        });

        // 清楚显示当前字幕开/关状态(默认关闭)
        updateSubtitleState(PlayConfig.isSubtitleOpen());
    }

    private void adjustSubtitleSize(int delta) {
        subtitleTextSize = Math.max(12, Math.min(60, subtitleTextSize + delta));
        updateSubtitleSizeText();
        SubtitleHelper.setTextSize(subtitleTextSize);
        if (mSubtitleViewListener != null) mSubtitleViewListener.setTextSize(subtitleTextSize);
    }

    private void updateSubtitleSizeText() {
        subtitleSizeText.setText(getContext().getString(R.string.subtitle_size_value, subtitleTextSize));
    }

    private void adjustSubtitleDelay(int deltaMillis) {
        subtitleDelayMillis += deltaMillis;
        updateSubtitleDelayText();
        SubtitleHelper.setTimeDelay(subtitleDelayMillis);
        if (mSubtitleViewListener != null) mSubtitleViewListener.setSubtitleDelay(deltaMillis);
    }

    private void updateSubtitleDelayText() {
        String seconds = subtitleDelayMillis == 0 ? "0" : Double.toString(subtitleDelayMillis / 1000.0);
        subtitleTimeText.setText(getContext().getString(R.string.subtitle_delay_value, seconds));
    }

    /** 高亮当前字幕状态:开启->"✓ 打开字幕",关闭->"关闭字幕"(无勾);选项区仅开启时展示 */
    private void updateSubtitleState(boolean open) {
        int activeColor = getContext().getResources().getColor(R.color.colorPrimary);
        int normalColor = getContext().getResources().getColor(R.color.text_foreground);
        if (open) {
            subtitleOpen.setText("✓ 打开字幕");
            subtitleOpen.setTextColor(activeColor);
            subtitleClose.setText("关闭字幕");
            subtitleClose.setTextColor(normalColor);
        } else {
            subtitleClose.setText("关闭字幕");
            subtitleClose.setTextColor(activeColor);
            subtitleOpen.setText("打开字幕");
            subtitleOpen.setTextColor(normalColor);
        }
        // 来源入口始终可选；这里只收起字号、延迟与样式设置。
        findViewById(R.id.ll_subtitle_options).setVisibility(open ? View.VISIBLE : View.GONE);
    }

    public void setLocalFileChooserListener(LocalFileChooserListener localFileChooserListener) {
        mLocalFileChooserListener = localFileChooserListener;
    }

    public interface LocalFileChooserListener {
        void openLocalFileChooserDialog();
    }

    public void setSearchSubtitleListener(SearchSubtitleListener searchSubtitleListener) {
        mSearchSubtitleListener = searchSubtitleListener;
    }

    public interface SearchSubtitleListener {
        void openSearchSubtitleDialog();
    }

    public void setSubtitleViewListener(SubtitleViewListener subtitleViewListener) {
        mSubtitleViewListener = subtitleViewListener;
    }

    public interface SubtitleViewListener {
        void setTextSize(int size);

        void setSubtitleDelay(int milliseconds);

        void selectInternalSubtitle();

        void setTextStyle(int style);

        void subtitleOpen(boolean b);
    }
}
