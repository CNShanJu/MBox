package com.github.tvbox.osc.ui.adapter;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.chad.library.adapter.base.util.MultiTypeDelegate;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.util.MD5;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 搜索结果适配器：支持「单列列表」、「宫格网格」、「通栏卡片」三种展示
 * （MultiTypeDelegate 按 mode 分发 item 布局）。{@link #setMode(int)} 切换展示形态，
 * 切换后需 notifyDataSetChanged；数据与点击监听不受影响。
 */
public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    /** 单列列表 */
    public static final int MODE_LIST = 0;
    /** 宫格/网格(3:4 卡,多列) */
    public static final int MODE_GRID = 1;
    /** 通栏卡片(整行满宽横向卡) */
    public static final int MODE_BANNER = 2;

    private static final int TYPE_LIST = 0;
    private static final int TYPE_GRID = 1;
    private static final int TYPE_BANNER = 2;

    private int mode = MODE_LIST;

    /** 本次会话已成功加载过的海报 URL:刷新/滚动回显时命中缓存不再重闪 shimmer,减轻宫格/通栏图多时的卡顿 */
    private final java.util.Set<String> loadedUrls = new java.util.HashSet<>();
    /**
     * 加载失败过的海报 URL → 失败时间(ms):窗口内滑回/复用直接显示失败占位、不再重复请求;
     * 窗口过期(瞬时失败如开局无网/CDN 抖动)后允许重试一次,不必关掉页面才能重新加载。
     */
    private final java.util.Map<String, Long> failedUrls = new java.util.HashMap<>();
    /** "失败不重试"窗口(ms) */
    private static final long FAILED_TTL_MS = 60_000L;

    /** 该 URL 是否仍处于"失败不重试"窗口内(过期即移除,允许重新加载) */
    private boolean recentlyFailed(String url) {
        Long at = failedUrls.get(url);
        if (at == null) return false;
        if (System.currentTimeMillis() - at >= FAILED_TTL_MS) {
            failedUrls.remove(url);
            return false;
        }
        return true;
    }

    /** 视图上记录的"当前已展示图片 URL"tag(同图跳过重载,防滑回闪) */
    private static final int TAG_LAST_URL = 0x3D000001;
    /** "延迟启动扫光"Runnable tag(加载结果到来时取消) */
    private static final int TAG_SHIMMER_RUN = 0x3D000002;
    /** 扫光延迟(ms):加载在此内完成(缓存/较快网络)则不启动骨架屏,只有真正慢(>1s)才扫光 */
    private static final long SHIMMER_DELAY_MS = 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
        setMultiTypeDelegate(new MultiTypeDelegate<Movie.Video>() {
            @Override
            protected int getItemType(Movie.Video item) {
                return mode == MODE_GRID ? TYPE_GRID : (mode == MODE_BANNER ? TYPE_BANNER : TYPE_LIST);
            }
        });
        getMultiTypeDelegate().registerItemType(TYPE_LIST, R.layout.item_search);
        getMultiTypeDelegate().registerItemType(TYPE_GRID, R.layout.item_search_grid);
        getMultiTypeDelegate().registerItemType(TYPE_BANNER, R.layout.item_search_banner);
    }

    public int getMode() {
        return mode;
    }

    public boolean isGrid() {
        return mode == MODE_GRID;
    }

    /** 切换展示形态(MODE_LIST/MODE_GRID/MODE_BANNER)；需在使用方 setLayoutManager 后调用，随后 notify 刷新布局 */
    public void setMode(int mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        notifyDataSetChanged();
    }

    /** 兼容旧调用：单列/宫格切换 */
    public void setGrid(boolean grid) {
        setMode(grid ? MODE_GRID : MODE_LIST);
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        // 片名
        setText(helper, R.id.tvName, item.name);

        // 海报卡公共:左上角评分角标 + 底部渐变黑底叠集数(有才显示)
        bindPosterCard(helper, item);

        switch (mode) {
            case MODE_GRID:
                bindGrid(helper, item);
                break;
            case MODE_BANNER:
                bindBanner(helper, item);
                break;
            default:
                bindList(helper, item);
        }

        // 来源(置底;来源名空则整行隐藏,内容上移)
        String sourceName = safeSourceName(item.sourceKey);
        setVisible(helper, R.id.llSite, !sourceName.isEmpty());
        setText(helper, R.id.tvSite, sourceName);

        ImageView ivThumb = helper.getView(R.id.ivThumb);
        loadPoster(ivThumb, item);
    }

    /** 共用海报卡:评分(左上角橙色徽标) + 集数(底部渐变黑底),有才显示 */
    private void bindPosterCard(BaseViewHolder helper, Movie.Video item) {
        String score = item.score == null ? "" : item.score.trim();
        setVisible(helper, R.id.tvScore, !score.isEmpty());
        setText(helper, R.id.tvScore, score);

        // 集数:内容为空则整块渐变黑底隐藏(避免空条)
        String note = item.note == null ? "" : item.note.trim();
        setVisible(helper, R.id.llNoteBar, !note.isEmpty());
        setText(helper, R.id.tvNote, note);
    }

    /** 宫格:类型地区语言 一行,导演+时间(直接显示导演名),演员(带前缀) */
    private void bindGrid(BaseViewHolder helper, Movie.Video item) {
        // 类型 空格 地区 空格 语言
        String meta = joinTypeAreaLang(item);
        setVisible(helper, R.id.tvMeta, !meta.isEmpty());
        setText(helper, R.id.tvMeta, meta);

        // 导演 时间:直接显示导演名(无"导演："前缀),有年份则加空格年
        String directorTime = joinDirectorYear(item);
        setVisible(helper, R.id.tvDirector, !directorTime.isEmpty());
        setText(helper, R.id.tvDirector, directorTime);

        boolean hasActor = item.actor != null && !item.actor.isEmpty();
        setVisible(helper, R.id.tvActor, hasActor);
        if (hasActor) {
            setText(helper, R.id.tvActor, "演员：" + item.actor.trim());
        }
    }

    /** 通栏卡片:剧集名称(支持换行);评分/集数已由海报卡处理 */
    private void bindBanner(BaseViewHolder helper, Movie.Video item) {
        setText(helper, R.id.tvName, item.name);
    }

    /** 单列列表:时间(单独一行),类型 地区(一行),集数;演员不展示,集数信息由文字行承载 */
    private void bindList(BaseViewHolder helper, Movie.Video item) {
        // 时间
        String year = item.year > 0 ? String.valueOf(item.year) : "";
        setVisible(helper, R.id.tvYear, !year.isEmpty());
        setText(helper, R.id.tvYear, year);

        // 类型 空格 地区
        String typeArea = joinTypeArea(item);
        setVisible(helper, R.id.tvMeta, !typeArea.isEmpty());
        setText(helper, R.id.tvMeta, typeArea);

        // 集数(原演员行;空则隐藏,内容上移)
        String note = item.note == null ? "" : item.note.trim();
        setVisible(helper, R.id.tvActor, !note.isEmpty());
        setText(helper, R.id.tvActor, note);

        // 集数已在文字行显示,隐藏海报角底部渐变黑底(避免重复)
        setVisible(helper, R.id.llNoteBar, false);
    }

    /** 类型 空格 地区(空段跳过;整行为空则隐藏) */
    private String joinTypeArea(Movie.Video item) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(item.type)) sb.append(item.type.trim()).append(' ');
        if (!TextUtils.isEmpty(item.area)) sb.append(item.area.trim());
        return sb.toString().trim();
    }

    /** 类型 空格 地区 空格 语言(空段跳过;整行为空则隐藏) */
    private String joinTypeAreaLang(Movie.Video item) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(item.type)) sb.append(item.type.trim()).append(' ');
        if (!TextUtils.isEmpty(item.area)) sb.append(item.area.trim()).append(' ');
        if (!TextUtils.isEmpty(item.lang)) sb.append(item.lang.trim());
        return sb.toString().trim();
    }

    /** 导演 时间:直接显示导演名(无前缀),有年份则加空格年(如"韦正 2012");两者皆无则空 */
    private String joinDirectorYear(Movie.Video item) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(item.director)) sb.append(item.director.trim()).append(' ');
        if (item.year > 0) sb.append(item.year);
        return sb.toString().trim();
    }

    private void loadPoster(ImageView ivThumb, Movie.Video item) {
        if (ivThumb == null) return;
        // 占位统一走 ImageView 背景层(统一占位组件 PosterPlaceholderDrawable),src 只放实图:
        // 三布局(列表/宫格/通栏)共用同一目标规格与稳定缓存键(不含 position),
        // 切换布局/滚动复用均命中同一缓存,不再重复下载或拉原图。
        String url = item.pic == null ? "" : item.pic.trim();
        if (url.isEmpty()) {
            // 无封面:显示"加载失败"占位(统一占位组件,见 util/PosterPlaceholderDrawable)
            com.github.tvbox.osc.util.PicassoLoad.showFailedPlaceholder(ivThumb);
            return;
        }
        // 已失败过且仍在窗口内的 URL:直接显示失败占位,不再重新发起请求(修复滑回又重载);清 src 露出占位
        if (recentlyFailed(url)) {
            ivThumb.setTag(TAG_LAST_URL, url);
            com.github.tvbox.osc.util.PicassoLoad.showFailedPlaceholder(ivThumb);
            return;
        }
        // 恢复为正常占位(上一张可能是"加载失败");此处不动 src,下面同图去重命中要保留已显示的图
        com.github.tvbox.osc.util.PicassoLoad.setLoadingPlaceholder(ivThumb);
        // 同一张图已显示(滑回/复用相同项):不重载、不闪
        if (url.equals(ivThumb.getTag(TAG_LAST_URL))) return;
        ivThumb.setTag(TAG_LAST_URL, url);
        ivThumb.setImageDrawable(null);
        int w = AutoSizeUtils.dp2px(mContext, 200);
        int h = AutoSizeUtils.dp2px(mContext, 267);
        int radius = AutoSizeUtils.dp2px(mContext, 12);
        String cacheKey = MD5.string2MD5(url + "_search_poster_200x267");
        // 已成功加载过(会话内缓存命中)→ 不再闪骨架屏,直接出图;
        // 其余情况扫光延迟启动:命中(内存/磁盘)缓存的图会在延迟内就绪并取消,避免"闪一下"
        boolean cached = loadedUrls.contains(url);
        if (!cached) {
            // 排新的延迟启动前必须先撤掉上一条(与本方法末尾的 cancelShimmer 成对):
            // 否则"上一张图还在延迟窗口内 → 该 view 被复用到新 URL"时,旧 run 的 tag 会被覆盖成孤儿,
            // 它在图片已经出图之后才触发,给已出图的 view 再叠一层没人会停的扫光
            // (那个动画器是 INFINITE 的,会一直按 60fps tick 并顺着 Drawable→View 引用把视图留住)。
            cancelShimmer(ivThumb);
            final String tagUrl = url;
            Runnable run = () -> {
                // 双保险:view 已经复用到别的图、或实图已经出图(src 已被 Picasso 填上)就不启动
                if (!tagUrl.equals(ivThumb.getTag(TAG_LAST_URL))) return;
                if (ivThumb.getDrawable() != null) return;
                com.github.tvbox.osc.ui.kit.PicassoShimmer.start(ivThumb);
            };
            ivThumb.setTag(TAG_SHIMMER_RUN, run);
            MAIN.postDelayed(run, SHIMMER_DELAY_MS);
        }
        Picasso.get()
                .load(url)
                .transform(new RoundTransformation(cacheKey)
                        .centerCorp(true)
                        .override(w, h)
                        .roundRadius(radius, RoundTransformation.RoundType.ALL))
                .into(ivThumb, new com.squareup.picasso.Callback() {
                    @Override
                    public void onSuccess() {
                        loadedUrls.add(url);
                        failedUrls.remove(url); // 重试成功:清掉失败记录
                        cancelShimmer(ivThumb);
                        com.github.tvbox.osc.ui.kit.PicassoShimmer.stop(ivThumb);
                    }

                    @Override
                    public void onError(Exception e) {
                        // 加载失败:记入失败窗口(窗口内滑回不再重试)并切到带"图片加载失败"文字的占位
                        failedUrls.put(url, System.currentTimeMillis());
                        com.github.tvbox.osc.util.PicassoLoad.showFailedPlaceholder(ivThumb);
                    }
                });
    }

    private void cancelShimmer(ImageView iv) {
        Object run = iv.getTag(TAG_SHIMMER_RUN);
        if (run instanceof Runnable) {
            MAIN.removeCallbacks((Runnable) run);
        }
        iv.setTag(TAG_SHIMMER_RUN, null);
    }

    private String safeSourceName(String sourceKey) {
        try {
            if (TextUtils.isEmpty(sourceKey)) return "";
            SourceBean source = SourceConfigProviders.get().getSource(sourceKey);
            return source == null ? "" : source.getName();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 布局可能不含某 id(列表无 tvDirector 等),setVisible/setText 需空安全 */
    private void setVisible(BaseViewHolder helper, int id, boolean visible) {
        View v = helper.getView(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setText(BaseViewHolder helper, int id, String text) {
        View v = helper.getView(id);
        if (v instanceof TextView) ((TextView) v).setText(text);
    }
}
