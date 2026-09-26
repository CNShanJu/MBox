package com.github.tvbox.osc.player.api;

import android.content.Context;

import com.github.tvbox.osc.config.PrefsDataStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放器工厂（注册表）：按内核类型创建 {@link PlayerApi} 适配器。
 * <p>
 * 现有 app 自有播放器（EXOmPlayer / IjkMediaPlayer / MyVideoView + VodController 等）
 * 将逐步收敛为各内核的 PlayerApiAdapter 并在此注册；
 * <b>Media3 升级路径 = 新增 Media3Adapter 注册到本工厂，PlayerApi 与所有调用方零改动</b>。
 * 外部播放器（Kodi / MX / Vlc / Reex / RemoteTVBox）走 PlayerHelper.runExternalPlayer 路径，
 * 不实现 PlayerApi（本工厂不注册）。
 */
public final class PlayerFactory {

    /** playType → 适配器提供者（playType 与 PlayerHelper 一致） */
    private static final Map<Integer, Provider> providers = new ConcurrentHashMap<>();

    private PlayerFactory() {
    }

    public interface Provider {
        PlayerApi create(Context context, PlayOptions options, PlayListener listener);
    }

    /** 注册内核适配器（模块 init 时调用；Media3 升级即在此新增注册） */
    public static void register(int playType, Provider provider) {
        if (provider != null) providers.put(playType, provider);
    }

    public static void unregister(int playType) {
        providers.remove(playType);
    }

    /** 按内核类型创建适配器；未注册且非外部播放器返回 null */
    public static PlayerApi create(int playType, Context context, PlayOptions options, PlayListener listener) {
        Provider p = providers.get(playType);
        if (p != null) return p.create(context, options, listener);
        // 外部播放器（Kodi/MX/Vlc/Reex/RemoteTVBox）不在本工厂内，由调用方走 runExternalPlayer
        return null;
    }

    /** 默认内核（用户当前选中：0 系统 / 1 IJK / 2 Exo / 10 MXPlayer 等；key 内联，独立模块不依赖 app 的 HawkConfig） */
    public static int defaultPlayType() {
        return PrefsDataStore.getInt("play_type", 0);
    }
}
