package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.NotificationUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ServiceUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.repo.HistoryRepositories;
import com.github.tvbox.osc.databinding.ActivityDetailBinding;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.service.PlayService;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesBottomDialog;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesRightDialog;
import com.github.tvbox.osc.ui.dialog.CastListDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.DownloadDialogCoordinator;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.VideoDetailDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration;
import com.github.tvbox.osc.util.BroadcastUtils;
import com.github.tvbox.osc.util.DetailQuickSearchHelper;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.PipHelper;
import com.github.tvbox.osc.util.ScreenShotListenManager;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseVbActivity<ActivityDetailBinding>
        implements DownloadDialogCoordinator.Host, VideoDetailDialog.Host, PlayFragment.PlaySyncHost {
    private PlayFragment playFragment = null;
    private SourceViewModel sourceViewModel;
    /** 详情页"快速搜索"请求编排(共享线程池 + epoch 去重/暂停;UI 只负责弹窗展示与直喂数据) */
    private DetailQuickSearchHelper quickSearchHelper;
    /** 快速搜索弹窗(当前打开的实例;宿主直调喂数据/收回调,不再经 EventBus) */
    private QuickSearchDialog mQuickSearchDialog;
    /** 下载选择弹窗协调器(底部弹窗 + 全屏右侧抽屉编排;宿主只提供数据/全屏时序/跳转能力) */
    private final DownloadDialogCoordinator downloadDialogCoordinator =
            new DownloadDialogCoordinator(this, this);
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    public SeriesFlagAdapter seriesFlagAdapter;
    public SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    /** 入口(搜索/列表)传入的剧名,详情拉不到名称时用于下载命名 */
    private String mPassedName = "";
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag = "";
    //改为view模式无法自动响应返回键操作,onBackPress时手动dismiss
    private BasePopupView mAllSeriesRightDialog;
    private BasePopupView mAllSeriesBottomDialog;
    /**
     * Home键广播,用于触发后台服务
     */
    private BroadcastReceiver mHomeKeyReceiver;
    /**
     * 是否开启后台播放标记,不在广播开启,onPause根据标记开启
     */
    boolean openBackgroundPlay;

    /**
     * 截屏监听
     */
    ScreenShotListenManager screenShotListenManager;

    @Override
    protected void init() {
        initReceiver();
        initView();
        initViewModel();
        initData();
        initPipHelper();
        // 状态栏不设底色(ImmersionBar 默认 statusBarColor = 0 即透明),沿用 BaseActivity 的全站口径
        // "状态栏透明 + 图标色跟随明暗主题":页面底(窗口底色/背景层)自己透上来,亮色主题下不再是一条黑带。
        // 原来这里写死 statusBarColor(black) + statusBarDarkFont(false)(见 5fbcae95 / dfa53b54:
        // 当初为治"详情页顶部与状态栏重叠",顺手把状态栏刷成了黑底),详情页没有 AppTitleBar,
        // 黑底就成了唯一一条既不跟主题、也不跟页面背景的色带。
        //
        // fitsSystemWindows 走 false(内容顶到状态栏下面)+ 自己按 insets 让位(applySystemBarsPadding):
        // 原先是 true(系统整帧内缩 android.R.id.content),而页面背景层就挂在 content 最底层
        // (PageBackgroundView.attach),于是背景层跟着内缩 —— 状态栏那条没有任何人绘制,露出的是
        // DecorView 的 android:windowBackground(@color/bg_body 纯色),看起来就是"状态栏一条色带";
        // 其它页不内缩,所以那条是背景图。改成自己让位后:背景层铺满整屏(那条=页面背景,与其它页一致),
        // 内容(含预览播放器)位置与内缩时完全一致 —— 不会重新顶到状态栏下面。
        ImmersionBar.with(this)
                .statusBarDarkFont(!Utils.isDarkTheme())
                .navigationBarColor(R.color.white)
                .fitsSystemWindows(false)
                .init();
        applySystemBarsPadding();
        toggleScreenShotListen(true);
    }

    /**
     * 按系统栏 insets 给根布局让位(替代 fitsSystemWindows 的整帧内缩,原因见 {@link #init()} 上方注释)。
     * <p>
     * 用 insets 监听而不是一次性算状态栏高度:全屏预览/横屏隐藏系统栏时 insets 归 0,padding 自动归零;
     * 刘海屏的 displayCutout 一并算进去,横屏侧边被裁的区域也不会被内容压住。
     * <p>
     * 根布局(activity_detail 的根 FrameLayout)自身不带 padding,这里整体接管;页面背景层是它的兄弟
     * (在 content 里更靠下),不受 padding 影响,所以仍铺满整屏。
     */
    private void applySystemBarsPadding() {
        View root = mBinding.getRoot();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            if (v.getPaddingLeft() != bars.left || v.getPaddingTop() != bars.top
                    || v.getPaddingRight() != bars.right || v.getPaddingBottom() != bars.bottom) {
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * 初始化画中画(小窗)辅助器,传入详情页专属钩子;本地播放器可复用同一套逻辑。
     */
    private void initPipHelper() {
        pipHelper = new PipHelper(this, new PipHelper.Callback() {
            @Override
            public boolean isPlaying() {
                return playFragment != null && playFragment.getPlayer() != null
                        && playFragment.getPlayer().isPlaying();
            }

            @Override
            public void togglePlay() {
                if (playFragment != null && playFragment.getController() != null) {
                    playFragment.getController().togglePlay();
                }
            }

            @Override
            public void pause() {
                if (playFragment != null && playFragment.getPlayer() != null) {
                    playFragment.getPlayer().pause();
                }
            }

            @Override
            public void playPrevious() {
                if (playFragment != null) {
                    playFragment.playPrevious();
                }
            }

            @Override
            public void playNext() {
                if (playFragment != null) {
                    playFragment.playNext(false);
                }
            }

            @Override
            public boolean isFullscreen() {
                return fullWindows;
            }

            @Override
            public void enterFullscreen() {
                if (!fullWindows) {
                    toggleFullPreview();
                }
            }

            @Override
            public void exitFullscreen() {
                if (fullWindows) {
                    toggleFullPreview();
                }
            }

            @Override
            public int[] getVideoSize() {
                if (playFragment != null && playFragment.getPlayer() != null) {
                    return playFragment.getPlayer().getVideoSize();
                }
                return null;
            }

            @Override
            public void onClose() {
                playServerSwitch(false);
                finish();
                NotificationUtils.cancelAll();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 兜底暂停:Activity 真正不可见且不在小窗中时暂停播放,防止"关闭小窗/退出页面后后台一直出声"。
        // 例外:后台播放=开启(类型1,onUserLeaveHint 已置 openBackgroundPlay=true)时不暂停,
        // 由 PlayService 继续后台播放;进入小窗时 isInPictureInPictureMode() 为 true 也不会误暂停
        // (走 PipHelper.isInPip:API 26 方法,低版本按"不在小窗"处理,避免 7.x 上 NoSuchMethodError)
        if (!PipHelper.isInPip(this) && !openBackgroundPlay
                && playFragment != null && playFragment.getPlayer() != null) {
            if (playFragment.getPlayer().isPlaying()) {
                playFragment.getController().togglePlay();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 小窗放大回前台:onStart 是比 onResume 更早的"回前台"信号,用于区分放大/点X关闭。
        // 放大时 Activity 会 onStart(清掉小窗会话标记,保持全屏播放);
        // 点X关闭时 Activity 留在后台不会 onStart(标记保持,走点X处理)
        pipHelper.onActivityStarted();
        // 点X关闭带回前台:播放"小窗逐渐放大铺满全屏"的进入动画,替代生硬的系统切换动画
        if (pipHelper.consumePipCloseAnimation()) {
            playPipExpandAnimation();
        }
    }

    /**
     * "小窗放大铺满全屏"进入动画:内容从屏幕下方(小窗常见位置)由小到大、由淡到实铺满。
     */
    private void playPipExpandAnimation() {
        View root = mBinding.getRoot();
        if (root == null) return;
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        root.setPivotX(w / 2f);
        root.setPivotY(h * 0.88f); // 小窗在屏幕下方,从底部放大铺满
        root.setScaleX(0.35f);
        root.setScaleY(0.35f);
        root.setAlpha(0.3f);
        root.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pipHelper.onActivityResumed(); // 点X关闭后带回前台:立即补暂停,消除"先播放一下再暂停"
        openBackgroundPlay = false;
        playServerSwitch(false);
        pipExitByBack = false; // 回到前台,清除返回键退出标记
        navigatingAway = false; // 回到前台,清除应用内跳转标记
        mBinding.ivPrivateBrowsing.postDelayed(NotificationUtils::cancelAll, 800);
    }

    private void initView() {
        mBinding.ivPrivateBrowsing.setVisibility(SystemConfig.isPrivateBrowsing() ? View.VISIBLE : View.GONE);
        mBinding.ivPrivateBrowsing.setOnClickListener(view -> AppBubble.toast("当前为无痕浏览"));
        mBinding.previewPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);

        mBinding.mGridView.setHasFixedSize(true);
        mBinding.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        mBinding.mGridView.addItemDecoration(new LinearSpacingItemDecoration(20, false));

        seriesAdapter = new SeriesAdapter(false);
        mBinding.mGridView.setAdapter(seriesAdapter);
        mBinding.mGridViewFlag.setHasFixedSize(true);
        seriesFlagAdapter = new SeriesFlagAdapter();
        mBinding.mGridViewFlag.setAdapter(seriesFlagAdapter);
        isReverse = false;
        preFlag = "";
        if (showPreview) {
            playFragment = new PlayFragment();
            playFragment.setPlaySyncHost(this);
            getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commit();
            getSupportFragmentManager().beginTransaction().show(playFragment).commitAllowingStateLoss();
        }

        findViewById(R.id.ll_title).setOnClickListener(view -> {
            // VideoDetailDialog 是 SheetResizableBottomPopup(底部可伸缩抽屉):与 AboutDialog/接口历史抽屉
            // 同属底部抽屉族,统一走 bottom(...) —— 它带的 isViewMode(true) + hasNavigationBar(false)
            // 才算"和其它抽屉同款"(原来用 center(...) 只做 asCustom,位置虽对但少了这两个参数)
            DialogCoordinator.bottom(this, new VideoDetailDialog(this, this, vodInfo), 0).show();
        });
        findViewById(R.id.tvDownload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDownloadSeriesDialog();
            }
        });
        mBinding.tvSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                sortSeries();
                updateSortButtonText();
            }
        });
        mBinding.tvCast.setOnClickListener(v -> {
            showCastDialog();
        });
        mBinding.tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = mBinding.tvCollect.getText().toString();
                if ("加入收藏".equals(text)) {
                    com.github.tvbox.osc.repo.HistoryRepositories.collect().save(sourceKey, vodInfo);
                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                            "收藏: " + (vodInfo.name == null ? "?" : vodInfo.name));
                    AppBubble.toast("已加入收藏夹");
                    mBinding.tvCollect.setText("取消收藏");
                } else {
                    com.github.tvbox.osc.repo.HistoryRepositories.collect().delete(sourceKey, vodInfo.id);
                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                            "取消收藏: " + (vodInfo.name == null ? "?" : vodInfo.name));
                    AppBubble.toast("已移除收藏夹");
                    mBinding.tvCollect.setText("加入收藏");
                }
            }
        });

        seriesFlagAdapter.setOnItemClickListener((adapter, view, position) -> {
            chooseFlag(position);
        });

        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                chooseSeries(position, false);
            }
        });

        mBinding.tvAllSeries.setOnClickListener(view -> {
            showAllSeriesDialog();
        });

        mBinding.tvSite.setOnClickListener(view -> {
            // 快速搜索编排已收敛到 DetailQuickSearchHelper(共享线程池+epoch 去重/暂停语义)
            if (mQuickSearchDialog != null && mQuickSearchDialog.isShow()) {
                return; // 已展示中,忽略连点
            }
            quickSearchHelper.startQuickSearch(mVideo.name);
            mQuickSearchDialog = new QuickSearchDialog(DetailActivity.this);
            // 点击行为直调宿主(原 EventBus SELECT/WORD_CHANGE 收口)
            mQuickSearchDialog.setHost(new QuickSearchDialog.Host() {
                @Override
                public void onVideoSelected(Movie.Video video) {
                    loadDetail(video.id, video.sourceKey);
                }

                @Override
                public void onWordChange(String word) {
                    quickSearchHelper.switchSearchWord(word);
                }
            });
            // helper 结果/词表桥到弹窗:只在弹窗展示期间有效(原 EventBus 订阅窗口语义)
            quickSearchHelper.setQuickSearchOutput(new DetailQuickSearchHelper.QuickSearchOutput() {
                @Override
                public void onResults(List<Movie.Video> data) {
                    runOnUiThread(() -> {
                        QuickSearchDialog dialog = mQuickSearchDialog;
                        if (dialog != null && dialog.isShow()) dialog.appendResults(data);
                    });
                }

                @Override
                public void onWords(List<String> words) {
                    runOnUiThread(() -> {
                        QuickSearchDialog dialog = mQuickSearchDialog;
                        if (dialog != null && dialog.isShow()) dialog.updateWords(words);
                    });
                }
            });
            // 弹窗打开:放行被暂停/暂存的源搜索(等价旧 pauseRunnable 续跑)
            quickSearchHelper.onQuickSearchDialogOpened();
            mQuickSearchDialog.setOnDismissListener(dialog -> {
                // 弹窗关闭:暂停后续排队任务(等价旧 shutdownNow 收集 pauseRunnable),断开输出桥
                quickSearchHelper.onQuickSearchDialogClosed();
                quickSearchHelper.setQuickSearchOutput(null);
                mQuickSearchDialog = null;
            });
            mQuickSearchDialog.show();
            // 初始直喂已累计结果/词表(替代原 show 前广播;show 后 onCreate 已建 adapter)
            mQuickSearchDialog.appendResults(new ArrayList<>(quickSearchHelper.getQuickSearchData()));
            mQuickSearchDialog.updateWords(new ArrayList<>(quickSearchHelper.getQuickSearchWords()));
        });
        mBinding.tvChangeLine.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            quickLineChange();
        });
        setLoadSir(mBinding.llLayout);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (openBackgroundPlay) {
            playServerSwitch(true);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 用户切后台(按Home/切走/进最近任务)时的行为,由"后台播放"设置决定:
        //   0 关闭  :不处理,由 onPause/onStop 兜底暂停,进入后台休眠
        //   1 开启  :后台继续播放(前台服务+通知)
        //   2 画中画:播放窗口自动进入小窗模式
        // 按返回键退出播放页(Activity 正在销毁)不算"切后台",排除
        // 应用内跳转(打开下载管理/设置等)也不算"切后台",排除(避免返回时误触小窗/后台播放)
        if (pipExitByBack || navigatingAway || isFinishing()) {
            pipExitByBack = false;
            navigatingAway = false;
            return;
        }
        if (playFragment == null || playFragment.getPlayer() == null || !playFragment.getPlayer().isPlaying()) {
            return;
        }
        int type = PlayConfig.getBackgroundPlayType();
        if (type == 2) {
            pipHelper.enterPip(); // 自动进入小窗
        } else if (type == 1) {
            openBackgroundPlay = true; // onPause 里启动后台播放服务
        }
    }

    private void initReceiver() {
        // 注册广播接收器
        if (mHomeKeyReceiver == null) {
            mHomeKeyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action != null && action.equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                        openBackgroundPlay = PlayConfig.getBackgroundPlayType() == 1 && playFragment.getPlayer() != null && playFragment.getPlayer().isPlaying();
                    }
                }
            };
            BroadcastUtils.registerReceiverNotExported(this, mHomeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }

    /**
     * 排序(倒序/正序切换):反转全集列表(与弹窗/详情页共用同一状态 vodInfo.reverseSort)。
     * 同步刷新详情页选集区排序按钮文字(弹窗/下载抽屉内排序后详情页文字跟随一致)。
     *
     * @return 反转后的状态:true=已倒序(按钮应显示"正序");false=正序(按钮显示"倒序")
     */
    public boolean sortSeries() {
        if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
            vodInfo.reverseSort = !vodInfo.reverseSort;
            isReverse = !isReverse;
            vodInfo.reverse();
            vodInfo.playIndex = (vodInfo.seriesMap.get(vodInfo.playFlag).size() - 1) - vodInfo.playIndex;
            seriesAdapter.notifyDataSetChanged();
        }
        // 详情页选集区按钮文字跟随共用状态(弹窗内排序也同步;原调用点手调 updateSortButtonText 保留无害)
        updateSortButtonText();
        return vodInfo != null && vodInfo.reverseSort;
    }

    /** 当前是否已倒序(供各弹窗倒序按钮文字同步;与 sortSeries 共用同一状态) */
    public boolean isSeriesReversed() {
        return vodInfo != null && vodInfo.reverseSort;
    }

    /** 详情页选集区倒序按钮文字跟随共用状态:已倒序显示"正序",否则"倒序" */
    private void updateSortButtonText() {
        try {
            mBinding.tvSort.setText(isSeriesReversed() ? "正序" : "倒序");
        } catch (Throwable ignored) {
        }
    }

    public void showCastDialog() {

        VodInfo.VodSeries vodSeries = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
        DialogCoordinator.centerMaxWidth(this, new CastListDialog(this, new CastVideo(vodSeries.name
                , TextUtils.isEmpty(playFragment.getFinalUrl()) ? vodSeries.url : playFragment.getFinalUrl())), 360)
                .show();
    }

    public void showAllSeriesDialog() {
        // 按当前方向决定形态: 横屏用右侧抽屉, 竖屏用底部弹层并限制高度(与详情页一致)
        if (ScreenUtils.isLandscape()) {
            // 右侧抽屉:固定宽度 360,与下载右侧抽屉一致,避免线路列表把弹窗撑开;内部有横向 rv 禁拖拽
            // 复用宿主线路/选集 adapter(同一实例共享数据与选中态),倒序经回调共用宿主状态
            mAllSeriesRightDialog = DialogCoordinator.right(this,
                    new AllVodSeriesRightDialog(this, seriesFlagAdapter, seriesAdapter,
                            this::sortSeries, this::isSeriesReversed), 360, false);
            mAllSeriesRightDialog.show();
        } else {
            mAllSeriesBottomDialog = DialogCoordinator.bottomMaxHeight(this,
                    new AllVodSeriesBottomDialog(this, seriesAdapter.getData(), (position, text) -> {
                        chooseSeries(position, false);
                    }, this::sortSeries, this::isSeriesReversed),
                    ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4));
            mAllSeriesBottomDialog.show();
        }
    }

    private void chooseFlag(int position) {
        //新选中的flag
        String newFlag = seriesFlagAdapter.getData().get(position).name;
        if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
            for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {//遍历flag集合
                VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                if (flag.name.equals(vodInfo.playFlag)) {//取消当前播放的选中状态
                    flag.selected = false;
                    seriesFlagAdapter.notifyItemChanged(i);
                    break;
                }
            }
            //新选中的flag
            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
            flag.selected = true;
            //清除上一个线路集数的选中状态
            List<VodInfo.VodSeries> currentSeriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (currentSeriesList.size() > vodInfo.playIndex) {//有效集数
                currentSeriesList.get(vodInfo.playIndex).selected = false;
            }
            vodInfo.playFlag = newFlag;
            seriesFlagAdapter.notifyItemChanged(position);
            refreshList();
        }
    }

    private void chooseSeries(int position, boolean reloadWithChangeLine) {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            boolean reload = false;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                seriesAdapter.getData().get(j).selected = false;
                seriesAdapter.notifyItemChanged(j);
            }
            //解决倒叙不刷新
            if (vodInfo.playIndex != position) {
                seriesAdapter.getData().get(position).selected = true;
                seriesAdapter.notifyItemChanged(position);
                vodInfo.playIndex = position;

                reload = true;
            }
            //解决当前集不刷新的BUG
            if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                reload = true;
            }

            seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
            seriesAdapter.notifyItemChanged(vodInfo.playIndex);

            //选集全屏 想选集不全屏的注释下面一行
            if (!showPreview || reload || reloadWithChangeLine) {
                jumpToPlay();
            }
        }
    }

    private void jumpToPlay() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (previewVodInfo == null) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    oos.writeObject(vodInfo);
                    oos.flush();
                    oos.close();
                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
                    previewVodInfo = (VodInfo) ois.readObject();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (previewVodInfo != null) {
                previewVodInfo.playerCfg = vodInfo.playerCfg;
                previewVodInfo.playFlag = vodInfo.playFlag;
                previewVodInfo.playIndex = vodInfo.playIndex;
                previewVodInfo.seriesMap = vodInfo.seriesMap;
//                    bundle.putSerializable("VodInfo", previewVodInfo);
                App.getInstance().setVodInfo(previewVodInfo);
            }
            playFragment.setData(bundle);

            //定位选集
            mBinding.mGridView.scrollToPosition(vodInfo.playIndex);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        int seriesSize = vodInfo.seriesMap.get(vodInfo.playFlag).size();
        if (seriesSize > 0 && seriesSize <= vodInfo.playIndex) {//当前集数大于新选线路的总集数,设置为最后一集
            vodInfo.playIndex = seriesSize - 1;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            boolean canSelect = true;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                if (vodInfo.seriesMap.get(vodInfo.playFlag).get(j).selected) {
                    canSelect = false;
                    break;
                }
            }
            if (canSelect)
                vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).selected = true;
        }
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        quickSearchHelper = new DetailQuickSearchHelper(sourceViewModel);
        // quick 搜索批次结果直调(替代历史 EventBus 快搜批次通道;回调线程不保证主线程,切主线程喂快搜聚合)
        sourceViewModel.setQuickSearchBatchListener(data ->
                runOnUiThread(() -> quickSearchHelper.handleQuickSearchResult(data)));
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    showSuccess();
                    mVideo = absXml.movie.videoList.get(0);
                    vodInfo = new VodInfo();
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;

                    mBinding.tvName.setText(TextUtils.isEmpty(mVideo.name) ? "暂无信息" : mVideo.name);
                    String srcName = "";
                    SourceBean detailSource = com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(mVideo.sourceKey);
                    if (detailSource != null) srcName = detailSource.getName();
                    mBinding.tvSite.setText("来源：" + (TextUtils.isEmpty(srcName) ? "未知" : srcName));

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {//线路
                        mBinding.llLayout.setVisibility(View.VISIBLE);
                        mBinding.mGridViewFlag.setVisibility(View.VISIBLE);
                        mBinding.mGridView.setVisibility(View.VISIBLE);
                        mBinding.rlLineHeader.setVisibility(View.VISIBLE);
                        mBinding.llSeriesHeader.setVisibility(View.VISIBLE);
                        mBinding.mEmptyPlaylist.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = HistoryRepositories.history().get(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }
                        updateSortButtonText(); // 历史恢复的倒序状态同步到详情页按钮文字

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
//                        setTextShow(tvPlayUrl, "播放地址：", vodInfo.seriesMap.get(vodInfo.playFlag).get(0).url);
                        //设置线路数据
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mBinding.mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        if (showPreview) {
                            jumpToPlay();
                            mBinding.previewPlayerPlace.setVisibility(View.VISIBLE);
                            mBinding.previewPlayer.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {//空布局:无剧集可播 —— 整页只留"暂无播放数据"空态,其余组件(标题/来源/操作栏/线路/选集/预览播放器)全部不显示
                        mBinding.llLayout.setVisibility(View.GONE);
                        mBinding.mEmptyPlaylist.setVisibility(View.VISIBLE);
                        // 占位区(260dp)与内层播放器都在 llLayout / 根层,这里显式收起避免留空白带
                        mBinding.previewPlayerPlace.setVisibility(View.GONE);
                        mBinding.previewPlayer.setVisibility(View.GONE);
                    }
                } else {
                    showEmpty(emptyTipForSource());
                    mBinding.previewPlayerPlace.setVisibility(View.GONE);
                    mBinding.previewPlayer.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * 「跟随系统」翻明暗时的重建:本页正在播(预览播放器)就先不重建 —— 翻明暗不该把正在看的片子
     * 重建掉;那次重建会记下来,等本页空闲回到前台时由 {@code BaseActivity.onResume} 补做。
     */
    @Override
    protected boolean allowRecreateOnNightChange() {
        try {
            return playFragment == null || playFragment.getPlayer() == null
                    || !playFragment.getPlayer().isPlaying();
        } catch (Throwable th) {
            return true;
        }
    }

    /**
     * 详情拉不到时的空态文案:能说清原因就说原因,说不清才退回默认「暂无数据」。
     * <p>
     * 动机(线上实例):订阅里某个站点声明的类在其 jar 里并不存在(如 csp_XPathGuard),
     * 该源每次调用只会拿到 SpiderNull → 详情页永远空白,用户看到的和"这个片没资源"一模一样,
     * 无从判断是源坏了还是 App 坏了。原因由 :spider 侧登记(SpiderFaults),
     * 这里只经契约读;源已从订阅里消失(订阅换了/被删)也单独给个说法。
     */
    private String emptyTipForSource() {
        String reason = com.github.tvbox.osc.spiderapi.SpiderFaultProviders.unavailableReason(sourceKey);
        if (!TextUtils.isEmpty(reason)) return reason;
        if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(sourceKey) == null) {
            return "该源已不在当前订阅中（可能已被移除或改名）";
        }
        return null;
    }

    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            // 入口(搜索/列表等)传过来的剧名,用于下载命名兜底
            mPassedName = bundle.getString("vodName", "");
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        if (vid != null) {
            vodId = vid;
            sourceKey = key;
            showLoading();
            sourceViewModel.getDetail(sourceKey, vodId);
            boolean isVodCollect = com.github.tvbox.osc.repo.HistoryRepositories.collect().isSaved(sourceKey, vodId);
            if (isVodCollect) {
                mBinding.tvCollect.setText("取消收藏");
            } else {
                mBinding.tvCollect.setText("加入收藏");
            }
        }
    }

    /** PlaySyncHost:选集切换同步(预览播放器→详情选集高亮/历史) */
    @Override
    public void onEpisodeSelected(int index) {
        if (vodInfo == null || vodInfo.seriesMap == null) return;
        Object seriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
        if (seriesList == null || index < 0) return;
        for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
            seriesAdapter.getData().get(j).selected = false;
            seriesAdapter.notifyItemChanged(j);
        }
        if (index >= vodInfo.seriesMap.get(vodInfo.playFlag).size()) return;
        seriesAdapter.getData().get(index).selected = true;
        seriesAdapter.notifyItemChanged(index);
        vodInfo.playIndex = index;
        insertVod(sourceKey, vodInfo);
    }

    /** PlaySyncHost:播放配置变更回写(详情页缓存配置并保存历史) */
    @Override
    public void onPlayerCfgChanged(JSONObject cfg) {
        if (vodInfo == null || cfg == null) return;
        vodInfo.playerCfg = cfg.toString();
        insertVod(sourceKey, vodInfo);
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        if (SystemConfig.isPrivateBrowsing()) {//无痕浏览
            return;
        }
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        com.github.tvbox.osc.repo.HistoryRepositories.history().save(sourceKey, vodInfo);
    }

    /** 详情页不再注册 EventBus:选集/配置经 PlayFragment.PlaySyncHost 屏内直调,快搜批次经监听直调 */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        sourceViewModel.setQuickSearchBatchListener(null); // 断开 quick 结果直调,防悬垂
        if (playFragment != null) playFragment.setPlaySyncHost(null); // 断开屏内直调
        // 页面销毁:解除后台播放服务对共享播放视图的借用。只摘引用、不停服务 ——
        // 后台播放是用户显式开启的能力,"宿主销毁"不等于"停止播放";
        // 但不摘掉的话服务会继续操作一张随本页销毁的视图(通知栏/锁屏按钮打在死视图上)
        PlayService.onHostDestroyed(this);
        pipHelper.setReceiverEnabled(false);
        super.onDestroy();
        // 注销广播接收器
        if (mHomeKeyReceiver != null) {
            unregisterReceiver(mHomeKeyReceiver);
            mHomeKeyReceiver = null;
        }

        // 作废未启动的快速搜索任务、清空暂停队列(共享线程池不可关闭);断开弹窗与输出桥
        try {
            if (quickSearchHelper != null) {
                quickSearchHelper.release();
                quickSearchHelper.setQuickSearchOutput(null);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        if (mQuickSearchDialog != null) {
            mQuickSearchDialog = null;
        }
        HttpClient.cancel("fenci");
        HttpClient.cancel("detail");
        HttpClient.cancel("quick_search");
        toggleScreenShotListen(false);
    }

    @Override
    public void onBackPressed() {
        if (mAllSeriesRightDialog != null && mAllSeriesRightDialog.isShow()) {
            mAllSeriesRightDialog.dismiss();
            return;
        }
        if (mAllSeriesBottomDialog != null && mAllSeriesBottomDialog.isShow()) {
            mAllSeriesBottomDialog.dismiss();
            return;
        }
        if (playFragment.hideAllDialogSuccess()) {//fragment有弹窗隐藏并拦截返回
            return;
        }
        if (fullWindows) {
            toggleFullPreview();
            mBinding.mGridView.requestFocus();
            return;
        }
        pipExitByBack = true; // 返回键真正退出播放页,onUserLeaveHint 里排除(不算"切后台")
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // preview
    VodInfo previewVodInfo = null;
    boolean showPreview = SystemConfig.isShowPreview();
    ; // true 开启 false 关闭
    boolean fullWindows = false;
    /** 用户按返回键退出播放页的标记(onUserLeaveHint 里排除,避免"返回退出"被当成"切后台") */
    private boolean pipExitByBack = false;
    /** 应用内跳转(如打开下载管理/设置等)标记:onUserLeaveHint 里排除,避免"应用内导航"被当成"切后台"触发小窗/后台播放 */
    private boolean navigatingAway = false;
    /** 画中画(小窗)通用辅助器,封装进入/退出小窗逻辑,详情页与本地播放器复用 */
    private PipHelper pipHelper;

    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    public void toggleFullPreview() {
        if (windowsPreview == null) {
            windowsPreview = mBinding.previewPlayer.getLayoutParams();
        }
        if (windowsFull == null) {//全屏尺寸
            windowsFull = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        fullWindows = !fullWindows;

        //交由fragment处理播放器全屏逻辑
        playFragment.changedLandscape(fullWindows);
        //activity处理预览尺寸(全屏/非全屏预览)
        mBinding.previewPlayer.setLayoutParams(fullWindows ? windowsFull : windowsPreview);
        mBinding.mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mBinding.mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);

        //全屏下禁用详情页几个按键的焦点 防止上键跑过来
        mBinding.tvSort.setFocusable(!fullWindows);
        mBinding.tvCollect.setFocusable(!fullWindows);
        toggleSubtitleTextSize();
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        // 预览播放器与详情页同屏,直调取代 EventBus TYPE_SUBTITLE_SIZE_CHANGE 广播
        if (playFragment != null) {
            playFragment.applySubtitleTextSize(subtitleTextSize);
        }
    }

    // ------------------------------------------------------------------
    // 下载弹窗协调器 Host 实现 + 兼容入口(编排已下沉 DownloadDialogCoordinator)
    // ------------------------------------------------------------------

    @Override
    public VodInfo currentVodInfo() {
        return vodInfo;
    }

    @Override
    public String downloadVodName() {
        String vodName = vodInfo.name;
        if (TextUtils.isEmpty(vodName)) {
            vodName = mPassedName;
        }
        if (TextUtils.isEmpty(vodName)) {
            CharSequence title = mBinding.tvName.getText();
            vodName = title == null ? "" : title.toString().trim();
            if ("暂无信息".equals(vodName)) vodName = "";
        }
        return vodName;
    }

    @Override
    public PlayFragment currentPlayFragment() {
        return playFragment;
    }

    @Override
    public boolean isFullscreen() {
        return fullWindows;
    }

    @Override
    public boolean isLandscape() {
        return ScreenUtils.isLandscape();
    }

    /** 全屏下先退全屏,再延迟打开底部下载弹窗(宿主负责退全屏 + postDelayed 时序) */
    @Override
    public void exitFullscreenThenOpenBottom() {
        toggleFullPreview();
        // 等退全屏布局稳定后再弹窗,避免弹窗与全屏切换动画叠加(修复:返回时全屏/抽屉残留)
        mBinding.previewPlayer.postDelayed(downloadDialogCoordinator::showDownloadSeriesDialogInner, 250);
    }

    @Override
    public void openDownloadManager() {
        // 应用内跳转:标记避免 onUserLeaveHint 误判为"切后台"触发小窗/后台播放(修复:返回时全屏播放)
        navigatingAway = true;
        jumpActivity(DownloadActivity.class);
    }

    @Override
    public void toast(String msg) {
        AppBubble.toast(msg);
    }

    @Override
    public void runOnUi(Runnable action) {
        runOnUiThread(action);
    }

    /** 打开"选择下载剧集"弹窗:网格多选 + 开始下载/下载管理(编排见协调器) */
    public void showDownloadSeriesDialog() {
        downloadDialogCoordinator.showDownloadSeriesDialog();
    }

    /** 全屏控制栏"下载"按钮:右侧下载抽屉(编排见协调器;仅全屏触发) */
    public void showDownloadDialogInFullscreen() {
        downloadDialogCoordinator.showDownloadDialogInFullscreen();
    }
    /**
     * 画中画模式(小窗):进入小窗。逻辑封装在 PipHelper,详情页/本地播放器复用。
     */
    public void enterPip() {
        pipHelper.enterPip();
        playFragment.getController().hideBottom();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 兜底:本设备上点X关闭不触发 onPictureInPictureModeChanged(false),只触发配置变化,
        // 由 PipHelper 统一延迟判断"放大/点X关闭"
        pipHelper.onConfigurationChanged();
    }

    /**
     * 后台播放服务开关,开启时注册操作广播,关闭时注销
     */
    private void playServerSwitch(boolean open) {
        if (open) {
            VodInfo.VodSeries vod = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
            PlayService.start(playFragment.getPlayer(), vodInfo.name + "&&" + vod.name);
            pipHelper.setReceiverEnabled(true);
        } else {
            if (ServiceUtils.isServiceRunning(PlayService.class)) {
                PlayService.stop();
                pipHelper.setReceiverEnabled(false);
            }
        }
    }

    public String getCurrentVodUrl() {
        return playFragment == null ? "" : playFragment.getFinalUrl();
    }

    public void quickLineChange() {
        List<VodInfo.VodSeriesFlag> flags = seriesFlagAdapter.getData();
        if (flags.size() > 1) {
            int currentIndex = 0;
            for (int i = 0; i < flags.size(); i++) {
                if (flags.get(i).selected) {
                    currentIndex = i;
                }
            }
            currentIndex += 1;
            if (currentIndex >= flags.size()) {
                currentIndex = 0;
            }
            mBinding.mGridViewFlag.smoothScrollToPosition(currentIndex);
            chooseFlag(currentIndex);
            mBinding.mGridView.postDelayed(() -> chooseSeries(vodInfo.playIndex, true), 300);
        }
    }

    public void showParseRoot(boolean show, ParseAdapter adapter) {
        mBinding.rvParse.setAdapter(adapter);
        int defaultIndex = 0;
        for (int i = 0; i < adapter.getData().size(); i++) {
            if (adapter.getData().get(i).isDefault()) {
                defaultIndex = i;
                break;
            }
        }
        if (defaultIndex != 0) {
            mBinding.rvParse.scrollToPosition(defaultIndex);
        }
        mBinding.parseRoot.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toggleScreenShotListen(boolean open) {
        if (open){
            if (screenShotListenManager == null){
                screenShotListenManager = ScreenShotListenManager.newInstance(this);
            }
            screenShotListenManager.setListener(imagePath -> {

                if (playFragment.getPlayer().isInPlaybackState())return;

                // 截图后弹"跳转到哪个 App":统一走公共选择弹窗(SelectDialog)。
                // 原来用 XPopup 内置的 asCenterList —— 它的面板底/文字色与**圆角(库内固定 15dp)**都来自
                // 库内样式,不吃主题文件;自定义主题下它就是一块"外来"的底,圆角也跟全站对不上
                // (users 口径:"抽屉圆角还是不对 / 我怎么设置圆角都不生效")。
                // SelectDialog 是 app 的公共居中列表:面板走 bg_dialog(= 主题圆角档),行样式与
                // "选择音轨 / 内置字幕 / 主题"同款 —— 全站弹窗的圆角从此只有一个来源。
                final String[] labels = {"跳转阿狸", "跳转优汐", "跳转夸父", "关闭"};
                final String[][] targets = {
                        {"com.alicloud.databox", "com.alicloud.databox.launcher.splash.SplashActivity"},
                        {"com.UCMobile", "com.uc.browser.InnerUCMobile"},
                        {"com.quark.browser", "com.ucpro.MainActivity"},
                        null,
                };
                SelectDialog<String> dialog = new SelectDialog<>(this);
                dialog.setTip("打开方式");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        dialog.dismiss();
                        if (pos < 0 || pos >= targets.length || targets[pos] == null) return; // 「关闭」
                        try {
                            startActivity(new Intent().setComponent(new ComponentName(targets[pos][0], targets[pos][1])));
                        } catch (Exception e) {
                            AppBubble.toast("未找到应用");
                        }
                    }

                    @Override
                    public CharSequence getDisplay(String val) {
                        return val == null ? "" : val;
                    }
                }, SelectDialogAdapter.stringDiff, java.util.Arrays.asList(labels), -1);
                // -1 = 动作列表:4 项都没有"默认选中",每一行都点得动(选择列表才会跳过已选项)
                DialogCoordinator.center(this, dialog).show();
            });
            screenShotListenManager.startListen();
        }else {
            if (screenShotListenManager != null) {
                screenShotListenManager.stopListen();
            }
        }
    }
}