package com.github.tvbox.osc.share.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareAvailability;
import com.github.tvbox.osc.share.ShareCapability;
import com.github.tvbox.osc.share.SharePlatform;
import com.github.tvbox.osc.share.transport.ShareTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输注册表:平台 → 实现 的映射,并<b>按注册顺序表达优先级</b>。
 *
 * <p>顺序即优先级为什么重要(这是"在线平台不可用能提前换掉"的落地机制):
 * 界面要回答"默认用哪个平台导出",最稳的答案不是硬编码 storage.to,而是
 * "按优先级问一圈,第一个能用的就用"({@link #firstAvailable(ShareCapability)})。
 * 于是当 storage.to 哪天挂了/被墙了,只要新平台的实现注册得更靠前(或旧实现把自己报成
 * 不可用 —— 见 {@link ShareTransport#availability()}),默认行为就自动跟着换,
 * <b>不需要改界面、也不需要发版改判断逻辑</b>。
 *
 * <p>线程安全:注册发生在启动装配期,查询主要在 UI 线程。全部方法加锁,
 * 用 {@link LinkedHashMap} 保插入序。
 */
public final class ShareTransportRegistry {

    private final Map<SharePlatform, ShareTransport> byPlatform = new LinkedHashMap<>();

    /** 注册(或替换)某平台的实现;实现应返回自己声明的 {@link ShareTransport#platform()} */
    public synchronized void register(@NonNull ShareTransport transport) {
        if (transport == null) return;
        byPlatform.put(transport.platform(), transport);
    }

    /** 摘掉某平台 */
    public synchronized void unregister(@Nullable SharePlatform platform) {
        if (platform == null) return;
        ShareTransport t = byPlatform.remove(platform);
        if (t != null) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    public synchronized ShareTransport get(@Nullable SharePlatform platform) {
        return platform == null ? null : byPlatform.get(platform);
    }

    /** 已注册平台(按注册顺序 = 优先级) */
    @NonNull
    public synchronized List<SharePlatform> platforms() {
        return new ArrayList<>(byPlatform.keySet());
    }

    /** 具备某能力的平台(按注册顺序;不筛可用性) */
    @NonNull
    public synchronized List<SharePlatform> platformsSupporting(@NonNull ShareCapability capability) {
        List<SharePlatform> out = new ArrayList<>();
        for (Map.Entry<SharePlatform, ShareTransport> e : byPlatform.entrySet()) {
            if (e.getValue().supports(capability)) out.add(e.getKey());
        }
        return out;
    }

    /**
     * 具备某能力<b>且当前可用</b>的平台(按注册顺序)。界面用它决定显示哪些入口;
     * 首个元素就是应当默认选中的那个。
     */
    @NonNull
    public synchronized List<SharePlatform> availablePlatforms(@NonNull ShareCapability capability) {
        List<SharePlatform> out = new ArrayList<>();
        for (Map.Entry<SharePlatform, ShareTransport> e : byPlatform.entrySet()) {
            ShareTransport t = e.getValue();
            if (!t.supports(capability)) continue;
            if (isAvailable(t)) out.add(e.getKey());
        }
        return out;
    }

    /** 按优先级取第一个可用的平台;一个都没有返回 null */
    @Nullable
    public synchronized SharePlatform firstAvailable(@NonNull ShareCapability capability) {
        List<SharePlatform> list = availablePlatforms(capability);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全部平台(含不可用的)的可用性快照,给设置页"平台状态"列表用 */
    @NonNull
    public synchronized Map<SharePlatform, ShareAvailability> availabilitySnapshot() {
        Map<SharePlatform, ShareAvailability> out = new LinkedHashMap<>();
        for (Map.Entry<SharePlatform, ShareTransport> e : byPlatform.entrySet()) {
            out.put(e.getKey(), safeAvailability(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    public synchronized void clear() {
        for (ShareTransport t : byPlatform.values()) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
        byPlatform.clear();
    }

    /** availability() 由实现提供,可能抛(实现有 bug/被 release 后访问):不让它带崩整个注册表 */
    private static boolean isAvailable(@NonNull ShareTransport t) {
        return safeAvailability(t).isAvailable();
    }

    @NonNull
    private static ShareAvailability safeAvailability(@NonNull ShareTransport t) {
        try {
            ShareAvailability a = t.availability();
            return a == null ? ShareAvailability.unavailable("平台未声明可用性") : a;
        } catch (Throwable th) {
            return ShareAvailability.unavailable("平台状态读取失败");
        }
    }
}
