package com.github.tvbox.osc.player;

import android.content.Context;

import xyz.doikki.videoplayer.player.AndroidMediaPlayerFactory;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

/**
 * 播放内核统一工厂(doikki 内核的 Media3/Android 选择单点化;PlayerHelper/组合根均经本类取内核)。
 * IJK 内核已移除(2026-10 体积优化,参照 AVBox),原 type=1(IJK)回落到 Media3。
 */
public final class PlayerKernels {

    private PlayerKernels() {
    }

    /** doikki 内核工厂:1(存量 IJK 回落)/2=Media3 其它=系统 AndroidMediaPlayer */
    @SuppressWarnings("rawtypes")
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    public static PlayerFactory doikkiFactory(int playerType) {
        switch (playerType) {
            case 1:
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

    /** 渲染视图工厂:0 texture 1 surface(新安装默认，由 PlayConfig 指定) */
    public static RenderViewFactory renderFactory(int renderType) {
        if (renderType == 1) {
            return com.github.tvbox.osc.player.render.SurfaceRenderViewFactory.create();
        }
        return TextureRenderViewFactory.create();
    }
}
