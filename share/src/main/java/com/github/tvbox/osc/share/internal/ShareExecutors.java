package com.github.tvbox.osc.share.internal;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模块级共享执行器(AGENTS §六:后台任务必须用模块级共享执行器,不得每轮 new 线程池)。
 *
 * <p>为什么本模块不能复用 :app 的 {@code HeavyTaskUtil}:那是 :app 的东西,而 :share 不得
 * 依赖 :app(见门禁)。所以本模块自带一份,语义照抄:
 * <ul>
 *   <li><b>模块级、进程级唯一</b>:不随操作创建/销毁。导出是低频动作(用户点一次),
 *       但上传/下载是长任务,池子跟着操作起落只会让线程反复创建;</li>
 *   <li><b>固定 2 线程</b>:上传与下载不会真的并发很多(用户就是在等一个结果),
 *       给大了只是多占内存与带宽竞争;</li>
 *   <li><b>守护线程 + 有意义的名字</b>:不阻止 JVM 退出;线程名带 {@code mbox-share}
 *       便于抓 ANR/线程转储时认出这是谁的活;</li>
 *   <li><b>不做 shutdown</b>:模块级执行器在进程内常驻;真要收口只走
 *       {@code ShareFacade} 的取消/释放,不要去关池子(关了以后没法再用,
 *       而"用户关了页面又点一次导出"是常态)。</li>
 * </ul>
 *
 * <p>取消不靠 {@code shutdownNow}(它无法中断 OkHttp 的阻塞读,还会连带杀掉无关任务),
 * 靠 epoch 自检 + OkHttp {@code Call.cancel()}(见 {@link BaseTransport} 与各实现)。
 */
public final class ShareExecutors {

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, "mbox-share-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private ShareExecutors() {
    }

    /** IO 执行器:上传、下载、归档读写都走它 */
    @NonNull
    public static ExecutorService io() {
        return IO;
    }
}
