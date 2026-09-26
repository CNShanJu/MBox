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

    private PlayerKernels() {
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
