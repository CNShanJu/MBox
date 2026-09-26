package com.github.tvbox.osc.player;

import android.content.Context;

import com.github.tvbox.osc.bean.IJKCode;

import xyz.doikki.videoplayer.exo.ExoMediaPlayerFactory;
import xyz.doikki.videoplayer.player.AndroidMediaPlayerFactory;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

/**
 * 播放内核统一工厂（改进.txt 播放器收口：doikki 内核的 IJK/Exo/Android 选择与 IJK so 加载单点化；
 * PlayerHelper/组合根均经本类取内核，后续 Media3 升级只替换这里，UI/控制器零改动）。
 */
public final class PlayerKernels {

    /**
     * 本次播放生效的 IJK 解码档。
     * <p>
     * 为什么要有它:IjkMediaPlayer 在创建时把 codec 存成实例字段(快照),而播放面板切档位只改
     * per-vod 配置 + 重播 —— 重播走的是 {@code mMediaPlayer.reset() + setOptions()},**播放器实例并不会
     * 重建**,于是那份快照永远停在首次创建时的值,面板切档位等于没生效。
     * 这里由 {@link com.github.tvbox.osc.util.PlayerHelper} 在每次应用播放配置(含面板切换后的重播)时刷新,
     * IjkMediaPlayer.setOptions() 每次读它 —— 既让面板立刻生效,又保留"老剧沿用自己那份 per-vod 解码档"的语义。
     */
    private static volatile IJKCode sCurrentCodec;

    private PlayerKernels() {
    }

    /** 记录本次播放的解码档(PlayerHelper 应用播放配置时调用) */
    public static void setCurrentCodec(IJKCode codec) {
        sCurrentCodec = codec;
    }

    /** 本次播放的解码档;未记录时返回 null,由调用方回退到全局设置 */
    public static IJKCode currentCodec() {
        return sCurrentCodec;
    }

    /** doikki 内核工厂:1=IJK(带解码配置) 2=Exo 其它=系统 AndroidMediaPlayer;返回 null 表示未知类型 */
    @SuppressWarnings("rawtypes")
    public static PlayerFactory doikkiFactory(int playerType, IJKCode codec) {
        switch (playerType) {
            case 1:
                return new PlayerFactory<com.github.tvbox.osc.player.IjkMediaPlayer>() {
                    @Override
                    public com.github.tvbox.osc.player.IjkMediaPlayer createPlayer(Context context) {
                        return new com.github.tvbox.osc.player.IjkMediaPlayer(context, codec);
                    }
                };
            case 2:
                return new PlayerFactory<com.github.tvbox.osc.player.EXOmPlayer>() {
                    @Override
                    public com.github.tvbox.osc.player.EXOmPlayer createPlayer(Context context) {
                        return new com.github.tvbox.osc.player.EXOmPlayer(context);
                    }
                };
            default:
                return AndroidMediaPlayerFactory.create();
        }
    }

    /** IJK 动态库首次加载(幂等由库内部保证;失败静默) */
    public static void ensureIjkLibrariesLoaded() {
        try {
            tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new tv.danmaku.ijk.media.player.IjkLibLoader() {
                @Override
                public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                    try {
                        System.loadLibrary(s);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 渲染视图工厂:0 texture(默认) 1 surface */
    public static RenderViewFactory renderFactory(int renderType) {
        if (renderType == 1) {
            return com.github.tvbox.osc.player.render.SurfaceRenderViewFactory.create();
        }
        return TextureRenderViewFactory.create();
    }
}
