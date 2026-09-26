package com.github.tvbox.osc.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 作者：By hdy
 * 日期：On 2018/12/6
 * 时间：At 21:27
 */
public class HeavyTaskUtil {
    //这里的代码是拿的AsyncTask的源码，作用是创建合理可用的线程池容量
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 3)) + 2;
    private static LinkedBlockingDeque<Runnable> taskQueue = new LinkedBlockingDeque<>(8192);
    private static ExecutorService executorService = new ThreadPoolExecutor(CORE_POOL_SIZE, 6,
            10L, TimeUnit.SECONDS, taskQueue);

    /** 应用级共享串行执行器:适合"必须按提交顺序逐个执行"的后台小任务(如 SP 增量写) */
    private static final ExecutorService serialExecutorService = Executors.newSingleThreadExecutor();

    /**
     * 图片解码专用执行器(Picasso executor):与共享大池隔离,固定 4 线程。
     * 搜索结果/列表大量海报解码不再占用搜索/爬虫用的共享大池,避免几十张图把 6 线程
     * 大池占满后,排队中的各来源搜索请求被图片任务拖慢(改版后搜索慢的主因之一)。
     */
    private static final ExecutorService imageExecutorService = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tvbox-img-decode");
        t.setDaemon(true);
        return t;
    });

    public static void executeNewTask(Runnable command) {
//        Log.d(TAG, "executeNewTask: CPU_COUNT=" + CPU_COUNT + ", CORE_POOL_SIZE=" + CORE_POOL_SIZE);
        executorService.execute(command);
    }

    public static void executeBigTask(Runnable command) {
        executorService.execute(command);
    }

    public static ExecutorService getBigTaskExecutorService() {
        return executorService;
    }

    public static ExecutorService getSerialExecutorService() {
        return serialExecutorService;
    }

    /** 图片解码专用池(Picasso/Glide 等图片库 executor 用;与搜索/爬虫共享池隔离) */
    public static ExecutorService getImageExecutorService() {
        return imageExecutorService;
    }

    public static LinkedBlockingDeque<Runnable> getBigTaskQueue() {
        return taskQueue;
    }


}
