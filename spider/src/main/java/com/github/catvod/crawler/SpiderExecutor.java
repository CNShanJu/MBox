package com.github.catvod.crawler;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 爬虫执行器：按 sourceKey 分道（striped lanes）——同一源固定落到同一条单线程道，
 * 保证「对同一个 spider 实例 / quickjs 上下文的调用」串行；不同源落到不同道并行执行，
 * 从而消除「一个慢源 head-of-line 阻塞其余全部源」的问题（旧实现是全局单线程）。
 * <p>
 * 并发安全前提：① 每个 {@code JsSpider} 自带独立单线程 executor，其 quickjs 上下文本就
 * 实例内串行；② spider 实例的「创建」在 {@code JsLoader/JarLoader.getSpider} 内加锁串行，
 * 避免并行首次创建时模块缓存文件写入/引擎初始化相互踩踏；③ 调用期共享静态缓存只读。
 * <p>
 * 道数固定（{@link #LANES}），限制同时活跃的 quickjs 上下文数量，避免低端机原生内存峰值过高。
 */
public final class SpiderExecutor {

    /** 并行道数：同一源串行、不同源并行的上限（有界，控制并发 quickjs 上下文数） */
    private static final int LANES = 4;

    private final ExecutorService[] lanes = new ExecutorService[LANES];

    SpiderExecutor() {
        for (int i = 0; i < LANES; i++) {
            final int idx = i;
            lanes[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tvbox-spider-" + idx);
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 按 key 选道：同一 key 恒定同一道（串行），不同 key 分散到不同道（并行） */
    private ExecutorService lane(String key) {
        int h = key == null ? 0 : key.hashCode();
        return lanes[(h & 0x7fffffff) % LANES];
    }

    /** 同步调用（带超时，按 key 分道）；超时/异常返回 null */
    public <T> T call(String key, long timeoutMs, Callable<T> task) {
        if (task == null) return null;
        Future<T> future = lane(key).submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 异步提交（不阻塞，按 key 分道） */
    public void execute(String key, Runnable task) {
        if (task != null) lane(key).execute(task);
    }

    /** 同步调用（无 key，落到 0 号道，保持旧「串行」语义供无源上下文的调用方使用） */
    public <T> T call(long timeoutMs, Callable<T> task) {
        return call(null, timeoutMs, task);
    }

    /** 异步提交（无 key，落到 0 号道） */
    public void execute(Runnable task) {
        execute(null, task);
    }

    /** 暴露 0 号道（供既有以 ExecutorService 方式提交的调用方兼容使用） */
    public ExecutorService asExecutorService() {
        return lanes[0];
    }
}
