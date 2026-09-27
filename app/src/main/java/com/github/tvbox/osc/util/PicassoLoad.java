package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ImageView;

import com.github.tvbox.osc.ui.kit.PicassoShimmer;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.io.File;

/**
 * 图片加载统一入口(首页/搜索/详情/收藏/历史/下载等所有海报位):
 * 占位不再作为 src 铺满(占位塞 src 会被 scaleType centerCrop 等比放大再裁剪,小卡片上直接
 * "撑出显示范围";方形占位在 3:4 卡片里还会有"突然挤瘦"跳变),
 * 而是用 ImageView 的背景层({@link PosterPlaceholderDrawable}:灰底 + 居中图标,按宿主尺寸自适应):
 * 图标不随盒子缩放裁剪, 始终居中等比; 真实图片加载成功后 centerCrop 铺满盖住背景层。
 * 加载中在背景层上叠"骨架屏扫光"(PicassoShimmer),加载完成/失败移除,避免灰底硬切。
 *
 * <p>防闪:① 同一张图已在当前视图显示时直接跳过(滑回/复用不再重载重闪);
 * ② 扫光延迟一小段再启动——命中内存/磁盘缓存的图会在延迟内就绪并取消扫光,只有真正
 * 要网络加载(慢)的图才会扫光。
 */
public class PicassoLoad {

    /** 视图上记录的"当前已展示图片 URL"tag,用于同一张图跳过重载 */
    private static final int TAG_LAST_URL = 0x3D000001;
    /** 视图上记录的"延迟启动扫光"Runnable tag,加载结果到来时取消 */
    private static final int TAG_SHIMMER_RUN = 0x3D000002;
    /** 扫光延迟(ms):加载在此内完成(缓存/较快网络)则不启动骨架屏,只有真正慢(>1s)才扫光 */
    private static final long SHIMMER_DELAY_MS = 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /**
     * 会话内已成功加载过的 URL:滑回/复用命中则不再重启骨架屏,避免"顶部卡最初不扫、滑回却扫"的不一致。
     * <p>
     * 必须是<b>有界</b>的:原来用 {@code ConcurrentHashMap.newKeySet()} 存下所有成功过的海报地址,
     * 会话里滑得越多条目越多(列表页每张海报一条),长会话纯涨。这里与 {@link #sessionFailed} 同口径,
     * 用带容量上限的 LRU(超出淘汰最久未用的);淘汰只会让那张图"滑回时再扫一次骨架屏",不影响正确性。
     */
    private static final int LOADED_CACHE_MAX = 1000;
    private static final java.util.Set<String> sessionLoaded =
            java.util.Collections.newSetFromMap(
                    java.util.Collections.synchronizedMap(
                            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                                @Override
                                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                                    return size() > LOADED_CACHE_MAX;
                                }
                            }));
    /** "失败不重试"窗口(ms):瞬时失败(开局无网/CDN 抖动/代理未就绪)窗口内不重试,过期后允许再试一次 */
    private static final long FAILED_TTL_MS = 60_000L;
    /** 失败记录上限:只用于窗口内去重,超出按最久未用淘汰,避免长会话无界增长 */
    private static final int FAILED_CACHE_MAX = 500;
    /**
     * 会话内加载失败过的 URL → 失败时间(ms):命中且未过期时直接显示失败占位,不再重复发起请求
     * (修复"滑到底再滑回又重新加载")。用带 TTL 的映射而非永久集合:网络恢复后能自愈,
     * 不必杀进程才能重新加载。
     */
    private static final java.util.Map<String, Long> sessionFailed =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Long>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                    return size() > FAILED_CACHE_MAX;
                }
            });

    /** 该 URL 是否仍处于"失败不重试"窗口内(过期即移除,允许重新加载) */
    private static boolean recentlyFailed(String url) {
        Long at = sessionFailed.get(url);
        if (at == null) return false;
        if (System.currentTimeMillis() - at >= FAILED_TTL_MS) {
            sessionFailed.remove(url);
            return false;
        }
        return true;
    }

    /**
     * 只把占位换成"加载态"(灰底 + 居中图标),<b>不动 src</b>。
     * 给"先铺占位、再做同图去重"的场景用(去重命中时不能清 src,否则已显示的图会被抹掉)。
     */
    public static void setLoadingPlaceholder(ImageView iv) {
        if (iv == null) return;
        iv.setBackground(PosterPlaceholderDrawable.loading(iv.getContext()));
    }

    /** 加载态占位 + 清 src + 停扫光:换绑新图前"露出占位"用(下载页等本地图源入口) */
    public static void showLoadingPlaceholder(ImageView iv) {
        if (iv == null) return;
        cancelShimmer(iv);
        PicassoShimmer.stop(iv);
        iv.setImageDrawable(null);
        setLoadingPlaceholder(iv);
    }

    /** 失败/无封面占位(灰底 + 图标 + "图片加载失败")+ 清 src + 停扫光 */
    public static void showFailedPlaceholder(ImageView iv) {
        if (iv == null) return;
        cancelShimmer(iv);
        PicassoShimmer.stop(iv);
        iv.setImageDrawable(null);
        iv.setBackground(PosterPlaceholderDrawable.failed(iv.getContext()));
    }

    public static void into(final ImageView iv, String url) {
        if (iv == null) return;
        // 清掉 src:复用列表项时移除上一张真实图, 露出灰底占位层
        String trimUrl = url == null ? "" : url.trim();
        if (TextUtils.isEmpty(trimUrl)) {
            // 空 URL:无封面,显示"加载失败"占位
            showFailedPlaceholder(iv);
            return;
        }
        // 已失败过且仍在窗口内的 URL:直接显示失败占位,不再重新发起请求(修复滑回又重载);清 src 露出占位
        if (recentlyFailed(trimUrl)) {
            iv.setTag(TAG_LAST_URL, trimUrl);
            showFailedPlaceholder(iv);
            return;
        }
        // 恢复为正常占位(上一张可能是"加载失败");此时不动 src —— 下面同图去重命中要保留已显示的图
        setLoadingPlaceholder(iv);
        // 同一张图已显示(滑回/复用相同项):不重载、不闪
        if (trimUrl.equals(iv.getTag(TAG_LAST_URL))) return;
        iv.setTag(TAG_LAST_URL, trimUrl);
        iv.setImageDrawable(null);
        // 复用列表项切到新图前:先取消上一张遗留的延迟扫光任务并停掉正在跑的扫光。
        // 否则上一张的 run 仍挂在 MAIN 上,待它到点触发时新图往往已 onSuccess(回调只能清掉
        // 被覆盖后的当前 tag,清不到这条孤儿 run),就会给"已出图的 view"再叠一层无人停止的扫光。
        cancelShimmer(iv);
        PicassoShimmer.stop(iv);
        // 已成功加载过(会话缓存命中)→ 不再启动骨架屏,直接出图;仅真正加载(慢)才延迟扫光
        boolean cached = sessionLoaded.contains(trimUrl);
        if (!cached) {
            // run 内再校验:仅当该 view 当前仍是本 URL、且实图还没出图(src 仍为空)才启动扫光。
            // 后一条是"孤儿任务"的兜底:view 被复用到新图、新图已经加载完成之后,这条旧 run 若还触发,
            // 就会给已出图的 view 叠一层没人会停的扫光(那个 INFINITE 动画器会一直 tick 并留住视图)。
            Runnable run = () -> {
                if (trimUrl.equals(iv.getTag(TAG_LAST_URL)) && iv.getDrawable() == null) {
                    PicassoShimmer.start(iv);
                }
            };
            iv.setTag(TAG_SHIMMER_RUN, run);
            MAIN.postDelayed(run, SHIMMER_DELAY_MS);
        }
        Picasso.get()
                .load(DefaultConfig.checkReplaceProxy(trimUrl))
                .into(iv, new Callback() {
                    @Override
                    public void onSuccess() {
                        sessionLoaded.add(trimUrl);
                        sessionFailed.remove(trimUrl); // 重试成功:清掉失败记录
                        cancelShimmer(iv);
                        PicassoShimmer.stop(iv);
                    }

                    @Override
                    public void onError(Exception e) {
                        // 加载失败:记入失败窗口(窗口内滑回不再重试)并切到带"图片加载失败"文字的占位
                        sessionFailed.put(trimUrl, System.currentTimeMillis());
                        showFailedPlaceholder(iv);
                    }
                });
    }

    /**
     * 本地图片文件统一入口(下载页剧集海报等):占位/失败语义与 {@link #into(ImageView, String)} 完全一致
     * (占位/失败都画在背景层,src 只放实图),只是图源是本地文件。
     *
     * <p>用 fit()+centerCrop 按控件尺寸解码:RecyclerView 绑定条目先于测量,此时 iv 尺寸为 0,
     * centerCrop 不带正尺寸直接 build 会抛 IllegalStateException;fit() 会等布局完成后再按真实尺寸加载。
     * 本地文件读取很快,故不叠骨架屏扫光。
     */
    public static void intoFile(final ImageView iv, final File file) {
        if (iv == null) return;
        if (file == null || !file.exists() || file.length() <= 0) {
            showFailedPlaceholder(iv);
            iv.setTag(TAG_LAST_URL, null); // 清去重记录:文件补齐后重绑能再次加载
            return;
        }
        String key = file.getAbsolutePath();
        // 同一张本地海报已绑定(状态刷新/多选切换整行重绑):不重载、不闪
        if (key.equals(iv.getTag(TAG_LAST_URL))) return;
        iv.setTag(TAG_LAST_URL, key);
        showLoadingPlaceholder(iv);
        Picasso.get()
                .load(file)
                .fit()
                .centerCrop()
                .into(iv, new Callback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError(Exception e) {
                        iv.setTag(TAG_LAST_URL, null); // 失败:下次重绑允许再试
                        showFailedPlaceholder(iv);
                    }
                });
    }

    private static void cancelShimmer(ImageView iv) {
        Object run = iv.getTag(TAG_SHIMMER_RUN);
        if (run instanceof Runnable) {
            MAIN.removeCallbacks((Runnable) run);
        }
        iv.setTag(TAG_SHIMMER_RUN, null);
    }
}
