package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.DownloadTask
import com.github.tvbox.osc.download.ArchiveItem
import java.io.File
import java.util.Comparator

/**
 * 下载页"聚合分组"纯逻辑(自 DownloadFragment 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * 把下载任务(运行态)与已下载档案(长期)按"来源+剧名"聚合成展示卡,
 * 按每组最早创建时间排序。无 View/Context 依赖,输入输出纯数据,可 JVM 单测。
 */
object DownloadGrouping {

    /** 聚合分组:key = 来源 + 剧名(同剧不同源各自成卡);@JvmField 保留公共字段供 Java 直读 */
    class Group {
        @JvmField
        var key: String? = null

        @JvmField
        var name: String? = null

        @JvmField
        var sourceName: String? = null

        @JvmField
        var tasks: MutableList<DownloadTask> = ArrayList()

        /** 已完成集(档案表长期数据源) */
        @JvmField
        var doneItems: MutableList<ArchiveItem> = ArrayList()
    }

    /** 剧名(兼容旧字段 groupName) */
    @JvmStatic
    fun vodNameOf(t: DownloadTask): String? = t.vodName ?: t.groupName

    /** 任务是否属于该剧(剧名+来源)分组 */
    @JvmStatic
    fun inGroup(t: DownloadTask, name: String, source: String?): Boolean {
        if (name != vodNameOf(t)) return false
        val src = source ?: ""
        return src == (t.sourceName ?: "")
    }

    /**
     * 聚合分组:运行态任务(非 COMPLETED,COMPLETED 走档案) + 已完成且文件存在的档案记录,
     * 按组内最早 createTime/downloadTime 升序。
     */
    @JvmStatic
    fun group(tasks: List<DownloadTask>?, archive: List<ArchiveItem>?): List<Group> {
        val map = LinkedHashMap<String, Group>()
        val firstTime = LinkedHashMap<String, Long>()
        if (tasks != null) {
            for (t in tasks) {
                if (t.state == DownloadTask.STATE_COMPLETED) continue // 已完成走档案
                val src = t.sourceName ?: ""
                val name = vodNameOf(t)
                val key = src + "\u0001" + name
                var g = map[key]
                if (g == null) {
                    g = Group()
                    g.key = key
                    g.name = name
                    g.sourceName = src
                    map[key] = g
                    firstTime[key] = t.createTime
                }
                g.tasks.add(t)
            }
        }
        if (archive != null) {
            for (it in archive) {
                if (it.savePath == null || !File(it.savePath).exists()) continue
                val src = it.sourceName ?: ""
                val name = it.vodName ?: ""
                val key = src + "\u0001" + name
                var g = map[key]
                if (g == null) {
                    g = Group()
                    g.key = key
                    g.name = name
                    g.sourceName = src
                    map[key] = g
                    firstTime[key] = it.downloadTime
                }
                g.doneItems.add(it)
            }
        }
        val groups = ArrayList(map.values)
        groups.sortWith(Comparator.comparingLong { g -> firstTime[g.key] ?: 0L })
        return groups
    }

    /** 该剧(剧名+来源)是否仍存在于聚合:有下载中任务或已下载档案(文件存在) */
    @JvmStatic
    fun isGroupPresent(
        tasks: List<DownloadTask>?,
        archive: List<ArchiveItem>?,
        name: String,
        source: String?
    ): Boolean {
        val wantSrc = source ?: ""
        if (tasks != null) {
            for (t in tasks) {
                if (name != vodNameOf(t)) continue
                if (wantSrc != (t.sourceName ?: "")) continue
                if (t.state != DownloadTask.STATE_COMPLETED) return true
            }
        }
        if (archive != null) {
            for (it in archive) {
                if (name != it.vodName) continue
                if (wantSrc != (it.sourceName ?: "")) continue
                if (it.savePath != null && File(it.savePath).exists()) return true
            }
        }
        return false
    }

    /** 该剧未完成的任务列表(按加入时间排序) */
    @JvmStatic
    fun tasksInGroup(tasks: List<DownloadTask>?, vodName: String, sourceName: String?): List<DownloadTask> {
        val list = ArrayList<DownloadTask>()
        if (tasks != null) {
            for (t in tasks) {
                if (t.state != DownloadTask.STATE_COMPLETED && inGroup(t, vodName, sourceName)) {
                    list.add(t)
                }
            }
        }
        list.sortWith(Comparator.comparingLong { t -> t.createTime })
        return list
    }

    /** 聚合卡副标题:未完成任务数 + 已完成且文件存在的集数(如 "3 个任务 · 已完成 5 集") */
    @JvmStatic
    fun aggregateNote(tasks: List<DownloadTask>?, doneItems: List<ArchiveItem>?): String {
        var running = 0
        if (tasks != null) {
            for (t in tasks) {
                if (t.state != DownloadTask.STATE_COMPLETED) running++
            }
        }
        var done = 0
        if (doneItems != null) {
            for (it in doneItems) {
                if (it.savePath != null && File(it.savePath).exists()) done++
            }
        }
        var note = if (running > 0) "$running 个任务" else ""
        if (done > 0) {
            note = (if (note.isEmpty()) "" else note + " · ") + "已完成 $done 集"
        }
        return note
    }
}
