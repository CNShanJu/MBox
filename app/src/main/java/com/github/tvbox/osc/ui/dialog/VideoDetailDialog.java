package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.ClipboardUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.databinding.DialogVideoDetailBinding;
import com.github.tvbox.osc.ui.kit.InlineExpandableText;
import com.github.tvbox.osc.util.DefaultConfig;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.util.SmartGlideImageLoader;

/**
 * 详情抽屉(底部):头部固定(海报/信息/链接),简介区按内容/抽屉状态展示:
 * 收起 = ≤5 行 + 行末内联“… 展开”(可点);展开 = 全文 + 底部“收回”,超长仅在简介区内滚动。
 * 简介与抽屉 50%↔70% 状态机同步,动作经 {@link SheetResizableBottomPopup#sheetResize()} 回传。
 */
public class VideoDetailDialog extends SheetResizableBottomPopup {

    /** 简介收起时最多显示行数(超出省略 + “展开”) */
    private static final int DESC_MIN_LINES = 5;
    /** 简介区收起默认高占比(与抽屉默认一致,数值统一见 DialogHeightPolicy) */
    private static final float RATIO_COLLAPSED = DialogHeightPolicy.SHEET_RATIO_COLLAPSED;

    /** 宿主窄接口:只依赖“当前选中集直链”这一能力 */
    public interface Host {
        String getCurrentVodUrl();
    }

    @NonNull
    private final Host mHost;
    private final VodInfo mVideo;

    private TextView mTvDes;
    private TextView mTvFold;
    private ScrollView mScroll;
    private SheetResizeController mCtrl;

    private String mDescText = "";
    private CharSequence mCollapsedSpan; // 收起态文本(截断 + 同行“… 展开”可点)
    private boolean descFoldable;   // 文本超过 5 行,需要 展开/收回
    private boolean descExpanded;
    private boolean descLong;       // 展开后的全文高度超过抽屉 70% 的简介区 → 需要区内滚动
    private int descFullH;          // 展开全文高度(px,预渲染)
    private int desc5H;             // 收起 5 行高度(px)
    private int headerToDescH;      // 抽屉顶到简介区顶的固定高度(px)
    private int mLinkColor;         // 内联展开/收回链接色

    public VideoDetailDialog(@NonNull Context context, @NonNull Host host, VodInfo vodInfo) {
        super(context);
        mHost = host;
        mVideo = vodInfo;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_video_detail;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogVideoDetailBinding binding = DialogVideoDetailBinding.bind(getPopupImplView());

        binding.tvName.setText(mVideo.name);
        // 空值/未知的字段整行隐藏:数据不全的源(接口只回空壳)时,不会再列出一串"地区：未知"、
        // 也不会出现只有标签没有值的"年份："把排版撑乱
        bindInfoRow(binding.tvYear, "年份：", mVideo.year == 0 ? "" : String.valueOf(mVideo.year));
        bindInfoRow(binding.tvArea, "地区：", mVideo.area);
        bindInfoRow(binding.tvType, "类型：", mVideo.type);
        bindInfoRow(binding.tvActor, "演员：", mVideo.actor);
        bindInfoRow(binding.tvDirector, "导演：", mVideo.director);
        String vodUrl = mHost.getCurrentVodUrl();
        // 链接行为空时整行隐藏(否则只剩"链接："和复制按钮,看着像坏了)
        binding.llLink.setVisibility(TextUtils.isEmpty(vodUrl) ? View.GONE : View.VISIBLE);
        binding.url.setText(vodUrl == null ? "" : vodUrl);
        binding.tvLinkCopy.setOnClickListener(view -> {
            if (TextUtils.isEmpty(vodUrl)) return;
            ClipboardUtils.copyText(vodUrl);
            AppBubble.toastLong("已复制");
        });
        String picUrl = DefaultConfig.checkReplaceProxy(mVideo.pic);
        // 走全 App 统一图片入口:空封面/加载失败都会切到 PosterPlaceholderDrawable
        // (灰底 + 按控件尺寸排版的猫图标 + "图片加载失败"文字),加载中有骨架屏
        com.github.tvbox.osc.util.PicassoLoad.into(binding.ivThum, picUrl);
        if (!TextUtils.isEmpty(picUrl)) {
            binding.llThum.setOnClickListener(view -> {
                new XPopup.Builder(getContext())
                        .asImageViewer(binding.ivThum, picUrl, new SmartGlideImageLoader())
                        .show();
            });
        }

        // 简介内容(“简介：”为上方固定标签行,不随内容滚动)
        mDescText = removeHtmlTag(mVideo.des);
        mTvDes = binding.tvDes;
        mTvDes.setText(mDescText);
        mTvFold = findViewById(R.id.tv_fold);
        mScroll = findViewById(R.id.sheet_scroll);

        // 绑定抽屉状态机:默认收起 50%;这里不做自动 sync,由 setupDescription 按简介预渲染结果编排
        mCtrl = attachSheet(R.id.bg, R.id.sheet_scroll, R.id.tv_des, false);
        mCtrl.setActionListener(new SheetResizeController.ActionListener() {
            @Override public void onSheetExpanded() { setDescExpandedInternal(true); }
            @Override public void onSheetCollapsed() { setDescExpandedInternal(false); }
            @Override public void onSheetClosed() { }
        });
        // 布局完成后:预渲染简介(折叠/展开高度) → 决定抽屉形态并挂状态
        mTvDes.post(this::setupDescription);
    }

    // ------------------------------------------------------------------
    // 简介:预渲染 + 状态
    // ------------------------------------------------------------------

    /** 布局完成后调用一次:量文本、决定“短文本按内容固定高 / 长文本可展开”,并让抽屉就位 */
    private void setupDescription() {
        try {
            if (mTvDes == null || mCtrl == null) return;
            int width = mTvDes.getWidth();
            if (width <= 0) {
                mTvDes.post(this::setupDescription);
                return;
            }
            mTvFold.setOnClickListener(v -> toggleFold());
            mTvDes.setMovementMethod(LinkMovementMethod.getInstance());
            mTvDes.setHighlightColor(android.graphics.Color.TRANSPARENT);

            TextPaint paint = mTvDes.getPaint();
            float density = getResources().getDisplayMetrics().density;
            int spacing = Math.round(4 * density);
            int lineCount = InlineExpandableText.lineCount(mDescText, paint, width, spacing);
            int lineH = mTvDes.getLineHeight();
            descFullH = Math.max(lineH, lineCount * lineH);
            desc5H = Math.min(descFullH, DESC_MIN_LINES * lineH);

            // 高度口径(收起/展开两态共用):抽屉顶 → 简介区顶(固定头) + ScrollView 上下内边距
            // + 文字 + 内容容器的底部内边距。
            // 以前漏了 ScrollView 的 8+8dp:算出来的高度比实际需要矮 16dp,收起态那 5 行就装不下
            // ——底部被截、还能滑(ScrollView 的 setEnabled(false) 其实挡不住触摸滚动,
            //  "收起态不可滚动"只能靠高度算对、内容正好装下来保证)
            headerToDescH = mScroll.getTop();
            int scrollPad = mScroll.getPaddingTop() + mScroll.getPaddingBottom();
            int bottomPad = Math.round(18 * density);
            int expandedPx = Math.round(ScreenUtils.getScreenHeight()
                    * DialogHeightPolicy.SHEET_RATIO_EXPANDED);
            int foldRowH = Math.round(24 * density);
            int availableWhenExpanded = expandedPx - headerToDescH - scrollPad - foldRowH - bottomPad;

            boolean longText = lineCount > DESC_MIN_LINES;
            if (!longText) {
                // 短文本:简介就是全部内容,既没有"展开/收回"文案,也不该被 50% 档截断
                // (固定头本来就不矮,50% 档下底部会缺一截,而这个状态又没有展开入口 → 用户根本读不全)。
                // 改为按内容自然高固定展示(上限 70%),不进 50↔70 状态机;下拉照样能压到 ≤30% 收起关闭。
                descFoldable = false;
                int needH = headerToDescH + scrollPad + descFullH + bottomPad;
                // 连 70% 都装不下(小屏/字段特别多)才允许区内滚动,否则读不到;正常情况下内容正好装下,无滚动
                descLong = needH > expandedPx;
                setDescExpandedInternal(false);
                mCtrl.forceFixedHeight(Math.min(expandedPx, needH));
                return;
            }

            // 长文本:预渲染汇总后进入“可展开”
            descFoldable = true;
            // "… 展开 / 收回"用文字高亮色(蓝色):color_highlight 是主题主色(近黑/近白),压在正文上看着"没高亮"
            mLinkColor = ContextCompat.getColor(getContext(), R.color.text_accent);
            mCollapsedSpan = InlineExpandableText.buildCollapsed(
                    mDescText, paint, width, spacing, DESC_MIN_LINES,
                    "… 展开", this::toggleFold, mLinkColor);
            descLong = descFullH > availableWhenExpanded;

            // 收起高:至少能让 5 行完整显示(不足则从 50% 上调);上限不超过 70%
            int defaultCollapsed = Math.round(ScreenUtils.getScreenHeight() * RATIO_COLLAPSED);
            int needH = headerToDescH + scrollPad + desc5H + bottomPad;
            int collapsedPx = Math.min(expandedPx, Math.max(defaultCollapsed, needH));
            mCtrl.forceExpandable(collapsedPx);
            // 初始收起态(展开按钮内联在第 5 行行末,不在独立行)
            setDescExpandedInternal(false);
        } catch (Throwable ignored) {
        }
    }

    /** 点内联“展开”/底部“收回”:与抽屉展开态同步(展开=70%,收回=收起高) */
    private void toggleFold() {
        if (mCtrl == null) return;
        if (descExpanded) {
            mCtrl.collapse();
        } else {
            mCtrl.expand();
        }
    }

    /** 内部切换文本状态(抽屉展开回调 / 初始化调用;不回回调抽屉,避免循环) */
    private void setDescExpandedInternal(boolean expanded) {
        descExpanded = expanded;
        applyDescMode(expanded);
    }

    /** 应用简介展示态:
     *  收起 = 内联截断文本(第 5 行行末“… 展开”可点),不可滚动;
     *  展开 = 全文 + 行末内联“收回”,超长才在区内滚动 */
    private void applyDescMode(boolean expanded) {
        if (mTvDes == null || mScroll == null) return;
        if (!descFoldable) {
            mTvDes.setText(mDescText);
            mTvDes.setMaxLines(Integer.MAX_VALUE);
            mTvDes.setEllipsize(null);
            mTvFold.setVisibility(View.GONE);
            // 短文本正常都能装下(高度按自然高固定)→ 无可滚动;只有连 70% 都装不下时才允许区内滑动
            mScroll.setEnabled(descLong);
            return;
        }
        if (expanded) {
            // 展开 = 全文 + 行末内联“ 收回”(紧跟结束文本,不再单独一行)
            mTvDes.setText(InlineExpandableText.buildExpanded(
                    mDescText, " 收回", this::toggleFold, mLinkColor));
            mTvDes.setMaxLines(Integer.MAX_VALUE);
            mTvDes.setEllipsize(null);
            mTvFold.setVisibility(View.GONE);   // 收回按钮内联在全文末尾
            mScroll.setEnabled(descLong);
            if (!descLong) mScroll.scrollTo(0, 0);
        } else {
            mTvDes.setText(mCollapsedSpan != null ? mCollapsedSpan : mDescText);
            mTvDes.setMaxLines(DESC_MIN_LINES); // 保险:内联文本本就 ≤5 行,不再补省略号
            mTvDes.setEllipsize(null);
            mTvFold.setVisibility(View.GONE);   // 收起时按钮内联在文本行末,不再独立显示
            mScroll.setEnabled(false);          // 收起态简介不可滚动
            mScroll.scrollTo(0, 0);
        }
    }

    private String getText(String str) {
        return TextUtils.isEmpty(str) ? "未知" : str;
    }

    /** 绑定一行"标签：值";值为空或"未知"时整行隐藏(数据不全的源不再撑出一串空标签) */
    private void bindInfoRow(TextView tv, String label, String value) {
        String v = getText(value);
        if ("未知".equals(v)) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setVisibility(View.VISIBLE);
        tv.setText(label + v);
    }

    private String removeHtmlTag(String info) {
        if (TextUtils.isEmpty(info)) {
            return "暂无";
        }
        return info.replaceAll("\\<.*?\\>", "").replaceAll("\\s", "");
    }
}
