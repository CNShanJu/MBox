package com.github.tvbox.osc.ui.dialog;

/**
 * 弹窗"内容区超高时限高"的纯计算(不依赖 Android,可 JVM 单测)。
 * <p>
 * 用于「标题 + 可滚动内容区 + 按钮」这类居中弹窗:内容区最多占
 * {@code 弹窗最大高度 − 标题/按钮/间距/内边距},超出由内容区自己滚动,
 * 保证按钮永远留在弹窗内(否则内容超高时按钮会被顶出屏幕,实测"两个按钮被挤得看不见")。
 */
public final class DialogClamp {

    /** 限高结果:是否需限高,以及内容区应取的高度 */
    public static final class Clamp {
        public final boolean clamped;
        public final int height;

        Clamp(boolean clamped, int height) {
            this.clamped = clamped;
            this.height = height;
        }
    }

    private DialogClamp() {
    }

    /**
     * 计算内容区(滚动区)高度。
     *
     * @param maxH            弹窗允许的最大高度(&lt;=0 表示无上限,不限高)
     * @param fixedH          固定区高度:弹窗内边距 + 标题/按钮等其他子视图自然高度(含其外边距)
     * @param naturalScrollH  内容区自然高度(UNSPECIFIED 量出来的真实期望高度)
     * @return 说明区可用高度和自然高度中较小的一个；固定区占满窗口时返回 0
     */
    public static Clamp clampScrollHeight(int maxH, int fixedH, int naturalScrollH) {
        if (maxH <= 0 || naturalScrollH <= 0 || fixedH < 0) {
            return new Clamp(false, Math.max(0, naturalScrollH));
        }
        int allowed = maxH - fixedH;
        if (allowed <= 0) {
            // 窄分屏/大字号时优先保住按钮，不能再让说明把按钮推出窗口。
            return new Clamp(true, 0);
        }
        if (naturalScrollH <= allowed) {
            // 放得下:回推自然高度,让卡片按内容撑开(而不是停在压扁状态)
            return new Clamp(false, naturalScrollH);
        }
        return new Clamp(true, allowed);
    }
}
