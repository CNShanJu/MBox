package com.github.tvbox.osc.ui.adapter;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.SPUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.PicassoLoad;
import com.github.tvbox.osc.util.Utils;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class LocalVideoAdapter extends BaseQuickAdapter<VideoInfo, BaseViewHolder> {

    public interface OnSelectedCountListener {
        void onSelectedCount(int count);
    }

    OnSelectedCountListener mOnSelectedCountListener;
    private boolean selectMode = false;

    // ---- 选中计数独立维护(替代 convert 内 O(n²) 全量统计) ----
    // 只在勾选状态真正变化的条目上增减,仅当计数变化时才回调监听器
    private int selectedCount = 0;
    /** 最近一次同步时各条目的勾选态快照(按对象同一性比对),用于增量 diff */
    private final Map<VideoInfo, Boolean> checkedStateSnapshot = new IdentityHashMap<>();
    /** 最近一次已通知监听器的计数;初始 -1 保证首次同步必然通知一次(等价旧实现首次绑定即回调) */
    private int lastNotifiedCount = -1;

    // 视频帧缓存:path -> Bitmap(LRU 上限 64),避免滚动/刷新反复取帧
    private static final int FRAME_CACHE_MAX = 64;
    /** 无集数/来源的行(本地视频列表)标题最多几行:下方是空白区,给到 3 行才不浪费(见 item_local_video.xml) */
    private static final int NAME_MAX_LINES_ROOMY = 3;
    private final Map<String, Bitmap> frameCache = new LinkedHashMap<String, Bitmap>(FRAME_CACHE_MAX, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > FRAME_CACHE_MAX;
        }
    };
    // 取帧走模块级共享执行器(AGENTS §六.6:UI/页面不得自建线程池)。
    // 原实现是自建单线程池("tvbox-video-frame"),与 util/LocalVideoFrameLoader 里那个同功能池重复;
    // 统一用 HeavyTaskUtil 的图片执行器(多线程,列表滚动时取帧更快);
    // "取消/过期"语义由下面的 TAG_KEY_PATH 复用自检承担(holder 滚走/复用后回调直接丢弃)。
    private static final ExecutorService FRAME_EXECUTOR = HeavyTaskUtil.getImageExecutorService();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 行内 tag key:异步取帧回调校验 holder 是否已复用到其它条目
    private static final int TAG_KEY_PATH = 0x6d000001;
    private static final int TAG_KEY_VIEWS = 0x6d000002;

    public LocalVideoAdapter() {
        super(R.layout.item_local_video);
    }

    @Override
    protected void convert(BaseViewHolder helper, VideoInfo item) {
        // 主标题:剧名(下载完成项);本地视频列表无剧名用文件名
        String name = item.getVodName();
        if (TextUtils.isEmpty(name)) name = item.getDisplayName();
        TextView tvName = helper.getView(R.id.tv_name);
        tvName.setText(name);
        // 主文字色与主题一致(text_foreground=text_main);播放过的剧集标题置灰(主题二级色)
        tvName.setTextColor(ContextCompat.getColor(mContext,
                item.isPlayed() ? R.color.text_sub_foreground : R.color.text_foreground));

        // 集数行:第N集 -> "集数：N";其它集名 -> "集数：<名>";无集数(单文件电影等)隐藏
        TextView tvEpisode = helper.getView(R.id.tv_episode);
        String ep = item.getEpisodeName();
        if (!TextUtils.isEmpty(ep)) {
            if (ep.matches("第\\d+集")) {
                tvEpisode.setText("集数：" + ep.substring(1, ep.length() - 1));
            } else {
                tvEpisode.setText("集数：" + ep);
            }
            tvEpisode.setVisibility(View.VISIBLE);
        } else {
            tvEpisode.setVisibility(View.GONE);
        }

        // 来源行
        TextView tvSource = helper.getView(R.id.tv_source);
        if (!TextUtils.isEmpty(item.getSourceName())) {
            tvSource.setText(item.getSourceName());
            tvSource.setVisibility(View.VISIBLE);
        } else {
            tvSource.setVisibility(View.GONE);
        }

        // 标题行数:本地视频这类"只有文件名 + 文件大小"的行(集数/来源都隐藏)中间是本行的空白区,
        // 让文件名吃到 3 行,别再单行省略(布局里 tv_name 已按 bottom_toTopOf(tv_video_size) 兜底);
        // 下载完成那种 剧名+集数+来源 的行必须保持单行 —— 标题一撑高就会把下面两行挤出 120dp 的行高。
        boolean hasSubRows = tvEpisode.getVisibility() == View.VISIBLE
                || tvSource.getVisibility() == View.VISIBLE;
        int wantLines = hasSubRows ? 1 : NAME_MAX_LINES_ROOMY;
        // TextView.setMaxLines 不判等、每次都 requestLayout,列表复用时会白刷一遍布局,故先比一下
        if (tvName.getMaxLines() != wantLines) tvName.setMaxLines(wantLines);

        helper.setText(R.id.tv_video_size, formatSize(item.getSize()));

        // 时长/进度:总长>0 才显示;总长未知时隐藏(异步取帧后会补上),杜绝 "00:02/00:00"
        ProgressBar progressBar = helper.getView(R.id.progressBar);
        TextView tvDuration = helper.getView(R.id.tv_duration);
        long duration = item.getDuration();
        if (duration <= 0) {
            long cache = SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).getLong(item.getPath(), -1);
            if (cache > 0) duration = cache;
        }
        long progressPlayed = SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).getLong(item.getPath(), -1);
        if (duration > 0) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setMax((int) duration);
            if (progressPlayed > 0 && progressPlayed < duration) {
                tvDuration.setText(Utils.stringForTime(progressPlayed) + "/" + Utils.stringForTime(duration));
                progressBar.setProgress((int) progressPlayed);
            } else {
                tvDuration.setText(Utils.stringForTime(duration));
                progressBar.setProgress(0);
            }
        } else {
            tvDuration.setText("");
            progressBar.setVisibility(View.INVISIBLE);
            progressBar.setProgress(0);
        }

        // 封面:视频内容截图(异步 MediaMetadataRetriever 取关键帧,第1秒画面避免黑帧;顺带补时长)。
        // 取帧前/取不到统一走背景层的统一占位(PicassoLoad → PosterPlaceholderDrawable):
        // 灰底 + 居中图标按 156×88dp 封面槽自适应铺满 —— 以前把方形占位塞 src,配 fitCenter 会缩成
        // 一个居中小方块(四周留白),配 centerCrop 又会被放大裁掉,两种都不对。
        ImageView iv = helper.getView(R.id.iv);
        iv.setTag(TAG_KEY_PATH, item.getPath());
        iv.setTag(TAG_KEY_VIEWS, new Object[]{tvDuration, progressBar});
        Bitmap cached = frameCache.get(item.getPath());
        if (cached != null) {
            iv.setImageBitmap(cached);
        } else {
            PicassoLoad.showLoadingPlaceholder(iv);
            loadFrameAsync(iv, item.getPath());
        }

        CheckBox cb = helper.getView(R.id.cb);
        cb.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        cb.setChecked(item.isChecked());
        // 注:选中计数由 notifyDataSetChanged/setItemChecked/recomputeSelectedCount 增量维护,
        // 不再在此处全量遍历 getData() 统计(O(n²) 已移除)
    }

    /** 后台线程取视频帧(第1秒画面)+ 总时长;完成后回主线程,holder 未复用才更新 */
    private void loadFrameAsync(final ImageView iv, final String path) {
        FRAME_EXECUTOR.execute(() -> {
            long dur = 0;
            Bitmap bmp = null;
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(path);
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    try {
                        dur = Long.parseLong(d);
                    } catch (Throwable ignored) {
                    }
                }
                // 取第 1 秒画面(避开黑屏/灰屏首帧),CLOSEST_SYNC 保证拿到关键帧;
                // 拿不到再退到第 0 秒 / 任意可用帧 —— 短片头、非常规容器(部分 ts/合并流)只有这些兜底能出帧
                bmp = mmr.getFrameAtTime(1000 * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) {
                    bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (bmp == null) {
                    bmp = mmr.getFrameAtTime();
                }
            } catch (Throwable th) {
                bmp = null;
            } finally {
                try {
                    if (mmr != null) mmr.release();
                } catch (Throwable ignored) {
                }
            }
            final Bitmap fb = bmp;
            final long fdur = dur;
            mainHandler.post(() -> {
                // holder 复用校验:path 不匹配说明该行已滚走/复用,不更新
                if (!path.equals(iv.getTag(TAG_KEY_PATH))) return;
                if (fb != null) {
                    frameCache.put(path, fb);
                    iv.setImageBitmap(fb);
                } else {
                    // 取不到帧:保持统一占位(灰底 + 居中图标,不写"加载失败"—— 本地视频/下载完成列表没有失败语义)
                    PicassoLoad.showLoadingPlaceholder(iv);
                }
                // 时长补写 SP + 刷新该行时长文本/进度条
                if (fdur > 0) {
                    SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).put(path, fdur);
                    Object[] views = (Object[]) iv.getTag(TAG_KEY_VIEWS);
                    if (views != null && views[0] instanceof TextView) {
                        TextView tvDuration = (TextView) views[0];
                        ProgressBar pb = (ProgressBar) views[1];
                        long played = SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).getLong(path, -1);
                        if (pb != null) {
                            pb.setVisibility(View.VISIBLE);
                            pb.setMax((int) fdur);
                        }
                        if (played > 0 && played < fdur) {
                            tvDuration.setText(Utils.stringForTime(played) + "/" + Utils.stringForTime(fdur));
                            if (pb != null) pb.setProgress((int) played);
                        } else {
                            tvDuration.setText(Utils.stringForTime(fdur));
                            if (pb != null) pb.setProgress(0);
                        }
                    }
                }
            });
        });
    }

    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        // 进/出多选模式也会触发全表重绑,先做一次增量同步保证计数正确
        syncSelectedCount();
        notifyDataSetChanged();
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void setOnSelectCountListener(OnSelectedCountListener listener) {
        mOnSelectedCountListener = listener;
    }

    /**
     * 数据或勾选态发生任何变化后,调用本方法做一次增量同步(仅对勾选态真变化的条目 ±1)。
     * 说明:BRVAH 2.9.45 的 notifyDataSetChanged() 是 final,无法覆写拦截,
     * 因此计数入口收敛为本方法 + {@link #setItemChecked} / {@link #selectAll} /
     * {@link #cancelAllSelection} / {@link #recomputeSelectedCount},
     * 调用方在相应动作点调用即可,convert() 内不再做任何全量统计。
     */
    public void syncSelection() {
        syncSelectedCount();
    }

    /** 增量同步:把当前列表与上次快照逐条比对,只对勾选态变化的条目增减计数(整体换数据/删除也正确吸收) */
    private void syncSelectedCount() {
        List<VideoInfo> data = getData();
        IdentityHashMap<VideoInfo, Boolean> next = new IdentityHashMap<>(Math.max(16, data.size() * 2));
        int delta = 0;
        for (VideoInfo item : data) {
            boolean checked = item.isChecked();
            Boolean last = checkedStateSnapshot.get(item);
            if (last == null) {
                if (checked) delta++;            // 新增/重挂条目:按当前勾选态计入
            } else if (last.booleanValue() != checked) {
                delta += checked ? 1 : -1;       // 只有勾选态真正变化才增减
            }
            next.put(item, checked);
        }
        // 已不在列表中的旧条目(删除/整表替换):原本勾选的要一并减掉
        for (Map.Entry<VideoInfo, Boolean> entry : checkedStateSnapshot.entrySet()) {
            if (!next.containsKey(entry.getKey()) && entry.getValue().booleanValue()) {
                delta--;
            }
        }
        checkedStateSnapshot.clear();
        checkedStateSnapshot.putAll(next);
        selectedCount += delta;
        if (selectedCount < 0) selectedCount = 0; // 防御:正常流程不会出现负数
        // 仅在计数发生变化(或首次同步)时回调监听器,避免旧实现里每次绑定行都回调
        if (selectedCount != lastNotifiedCount) {
            lastNotifiedCount = selectedCount;
            notifySelectedCount();
        }
    }

    private void notifySelectedCount() {
        if (mOnSelectedCountListener != null) {
            mOnSelectedCountListener.onSelectedCount(selectedCount);
        }
    }

    /** 全选:所有条目置勾选,一次性同步计数并刷新 */
    public void selectAll() {
        List<VideoInfo> data = getData();
        if (data != null) {
            for (VideoInfo item : data) {
                if (item != null) item.setChecked(true);
            }
        }
        syncSelectedCount();
        notifyDataSetChanged();
    }

    /** 取消全选:所有条目取消勾选,一次性同步计数并刷新 */
    public void cancelAllSelection() {
        List<VideoInfo> data = getData();
        if (data != null) {
            for (VideoInfo item : data) {
                if (item != null) item.setChecked(false);
            }
        }
        syncSelectedCount();
        notifyDataSetChanged();
    }

    /**
     * 勾选切换统一入口(供调用方在点击处调用,替代“外部改 isChecked + 全量 notifyDataSetChanged”):
     * 计数即时增减,只刷新该行;仅在计数变化时回调监听器。若数据未变化则直接返回。
     */
    public void setItemChecked(VideoInfo item, boolean checked) {
        if (item == null || item.isChecked() == checked) return; // 状态未变:无需处理
        item.setChecked(checked);
        selectedCount += checked ? 1 : -1;
        if (selectedCount < 0) selectedCount = 0;
        checkedStateSnapshot.put(item, checked); // 同步快照,避免后续 notifyDataSetChanged diff 重复增减
        if (selectedCount != lastNotifiedCount) {
            lastNotifiedCount = selectedCount;
            notifySelectedCount();
        }
        int index = getData().indexOf(item); // VideoInfo 未重写 equals,indexOf 即按对象同一性
        if (index >= 0) {
            notifyItemChanged(index);
        } else {
            notifyDataSetChanged();
        }
    }

    /** 数据整体变化后调用一次,强制按当前列表重算计数并同步监听器(正常 setNewData/删除路径无需额外调用) */
    public void recomputeSelectedCount() {
        checkedStateSnapshot.clear();
        selectedCount = 0;
        lastNotifiedCount = Integer.MIN_VALUE; // 强制本次重算后回调一次,避免界面残留旧状态
        syncSelectedCount();
    }

    /** 当前选中条目数 */
    public int getSelectedCount() {
        return selectedCount;
    }

    /** 文件大小:MB/KB 去小数点(224.113MB -> 224MB),GB 保留两位小数(1.25GB) */
    private static String formatSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return (size / 1024) + "KB";
        if (size < 1024L * 1024 * 1024) return (size / 1024 / 1024) + "MB";
        return String.format("%.2fGB", size / 1024.0 / 1024.0 / 1024.0);
    }
}
