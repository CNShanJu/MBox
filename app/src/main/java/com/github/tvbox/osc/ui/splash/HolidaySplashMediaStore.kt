package com.github.tvbox.osc.ui.splash

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.calendar.HolidayCatalog
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.OkGoHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

/**
 * 节日开屏海报的本地缓存与后台预取。
 *
 * 启动安全策略（用户明确要求"图片加载不出来不能影响启动"）：
 * 1. 开屏选择阶段**只查本地文件**，不发起任何网络请求；没有缓存就当没有节日图，直接走默认开屏；
 * 2. 网络下载全部在后台共享执行器里做，失败只记日志，不弹窗、不重试、不阻塞首屏；
 * 3. 下载落盘前按文件头校验图片类型，图床返回 HTML 错误页时不会把垃圾当成海报缓存下来；
 * 4. 缓存按「id + 链接哈希」命名，换了图床链接会自动重新下载，旧文件随后清理。
 */
internal class HolidaySplashMediaStore(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, CACHE_DIR)

    @Volatile
    private var items: Map<String, HolidaySplashMediaCatalog.Item>? = null

    @Volatile
    private var prefetchStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 记录"今天该用哪张已缓存海报"，供下次冷启动在目录加载前直接命中。 */
    private val prefs by lazy {
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 启动极快时（首页秒开，开屏往往不到 1 秒）节日目录根本来不及加载，
     * 这里返回上次预取记下的"今日海报"：只读一份 SharedPreferences + 一次文件检查，
     * 不读 assets、不解析 JSON、不发起网络，保证开屏第一帧就能出图。
     */
    fun todayCachedPoster(): HolidayPosterSource? {
        val savedDate = prefs.getString(KEY_DATE, null) ?: return null
        if (savedDate != dayKey(Calendar.getInstance())) return null
        val path = prefs.getString(KEY_PATH, null) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() < HolidaySplashMediaCatalog.MIN_IMAGE_BYTES) return null
        return HolidayPosterSource(
            imagePath = path,
            anchorX = prefs.getFloat(KEY_ANCHOR_X, HolidaySplashMediaCatalog.DEFAULT_ANCHOR_X),
            anchorY = prefs.getFloat(KEY_ANCHOR_Y, HolidaySplashMediaCatalog.DEFAULT_ANCHOR_Y)
        )
    }

    /** 当天是否有可用的节日海报素材定义（不代表已缓存）。 */
    fun hasMedia(entry: HolidayCatalog.Entry): Boolean = items()?.containsKey(entry.id) == true

    /** 预览/调试页用：按素材表声明顺序返回全部条目。 */
    fun mediaEntries(): List<HolidaySplashMediaCatalog.Item> = items()?.values?.toList().orEmpty()

    /** 指定条目的本地缓存路径；未缓存或文件损坏返回 null。可在主线程调用（只做文件长度检查）。 */
    fun cachedImagePath(item: HolidaySplashMediaCatalog.Item): String? {
        val file = cacheFile(item)
        if (isUsableCache(file)) return file.absolutePath
        // 兼容旧命名（无版本号）：改名复用，避免升级后把已下载的海报全部重下。
        val legacy = File(cacheDir, legacyCacheName(item))
        if (isUsableCache(legacy) && legacy.renameTo(file)) return file.absolutePath
        return null
    }

    /**
     * 返回已缓存海报的本地路径与它自己的裁切锚点；没有素材、没有缓存或文件损坏时返回 null。
     * 可在主线程调用：只做一次内存查表和文件长度检查。
     */
    fun posterFor(entry: HolidayCatalog.Entry): HolidayPosterSource? {
        val item = items()?.get(entry.id) ?: return null
        val path = cachedImagePath(item) ?: return null
        return HolidayPosterSource(path, item.anchorX, item.anchorY)
    }

    /**
     * 预取海报：**当天命中的那几张先下**，完成立刻回调（让开屏有机会在下完后切上海报），
     * 再补未来 windowDays 天内会用到的其他素材。每个进程只调度一次。
     */
    fun prefetchUpcoming(
        catalog: HolidayCatalog,
        today: Calendar,
        windowDays: Int = PREFETCH_WINDOW_DAYS,
        onTodayReady: (() -> Unit)? = null
    ) {
        if (prefetchStarted) return
        prefetchStarted = true
        HeavyTaskUtil.executeBigTask {
            try {
                runPrefetch(catalog, today, windowDays, onTodayReady)
            } catch (error: Throwable) {
                LogStore.fail(Category.SYSTEM, "节日开屏: 预取中止，原因=${error.javaClass.simpleName}")
            }
        }
    }

    private fun runPrefetch(
        catalog: HolidayCatalog,
        today: Calendar,
        windowDays: Int,
        onTodayReady: (() -> Unit)?
    ) {
        val known = items() ?: return
        val todayItems = LinkedHashMap<String, HolidaySplashMediaCatalog.Item>()
        val upcomingItems = LinkedHashMap<String, HolidaySplashMediaCatalog.Item>()
        val cursor = today.clone() as Calendar
        for (offset in 0 until windowDays.coerceAtLeast(1)) {
            val matches = catalog.getMatchingHolidays(
                cursor.get(Calendar.YEAR),
                cursor.get(Calendar.MONTH) + 1,
                cursor.get(Calendar.DAY_OF_MONTH)
            )
            for (entry in matches) {
                val item = known[entry.id] ?: continue
                // 今天的单独排前面先下：开屏正在展示，先到就能先切。
                if (offset == 0) {
                    todayItems[item.id] = item
                } else if (!upcomingItems.containsKey(item.id)) {
                    upcomingItems[item.id] = item
                }
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (todayItems.isEmpty() && upcomingItems.isEmpty()) return
        val client = OkGoHelper.getImageClient() ?: OkGoHelper.getDefaultClient() ?: return
        var downloaded = 0
        for (item in todayItems.values) {
            if (downloadIfNeeded(client, item)) downloaded++
        }
        // 当天的落盘后立即通知上层：开屏若还在，就能把默认动画换成节日海报。
        if (onTodayReady != null) mainHandler.post(onTodayReady)
        for (item in upcomingItems.values) {
            if (downloadIfNeeded(client, item)) downloaded++
        }
        if (downloaded > 0) {
            LogStore.log(Category.SYSTEM, "节日开屏: 已缓存 $downloaded 张开屏海报")
        }
        pruneUnused(known)
        rememberTodayPoster(catalog, today, known)
    }

    /**
     * 记下"今天优先级最高、且已缓存"的那张海报。
     * 下次冷启动开屏不再等目录，直接用这条记录取文件，避免首页秒开时开屏一闪而过、海报来不及切换。
     */
    private fun rememberTodayPoster(
        catalog: HolidayCatalog,
        today: Calendar,
        known: Map<String, HolidaySplashMediaCatalog.Item>
    ) {
        val chosen = try {
            catalog.getMatchingHolidays(
                today.get(Calendar.YEAR),
                today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH)
            )
                .sortedByDescending { it.displayPriority }
                .mapNotNull { known[it.id] }
                .firstOrNull { cachedImagePath(it) != null }
        } catch (error: Throwable) {
            null
        }
        val path = chosen?.let { cachedImagePath(it) }
        val editor = prefs.edit().putString(KEY_DATE, dayKey(today))
        if (chosen != null && path != null) {
            editor.putString(KEY_PATH, path)
            // 锚点跟图走：下次冷启动直接按这张图的标题位置裁切。
            editor.putFloat(KEY_ANCHOR_X, chosen.anchorX)
            editor.putFloat(KEY_ANCHOR_Y, chosen.anchorY)
        } else {
            editor.remove(KEY_PATH)
        }
        editor.apply()
    }

    private fun dayKey(calendar: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    private fun downloadIfNeeded(
        client: OkHttpClient,
        item: HolidaySplashMediaCatalog.Item
    ): Boolean {
        val target = cacheFile(item)
        if (target.isFile && target.length() >= HolidaySplashMediaCatalog.MIN_IMAGE_BYTES) return false
        return download(client, item, target)
    }

    private fun download(
        client: OkHttpClient,
        item: HolidaySplashMediaCatalog.Item,
        target: File
    ): Boolean {
        val temp = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(item.url)
                .header("Accept", "image/*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogStore.fail(Category.SYSTEM, "节日开屏: ${item.name} 下载失败 HTTP ${response.code}")
                    return false
                }
                val body = response.body
                if (body == null) return false
                if (body.contentLength() > MAX_IMAGE_BYTES) {
                    LogStore.fail(Category.SYSTEM, "节日开屏: ${item.name} 图片过大，已跳过")
                    return false
                }
                var valid = false
                FileOutputStream(temp).use { output ->
                    body.byteStream().use { input ->
                        val header = ByteArray(HEADER_SIZE)
                        var filled = 0
                        while (filled < HEADER_SIZE) {
                            val read = input.read(header, filled, HEADER_SIZE - filled)
                            if (read <= 0) break
                            filled += read
                        }
                        val extension = HolidaySplashMediaCatalog.sniffImageExtension(header, filled)
                        if (extension == null) {
                            LogStore.fail(Category.SYSTEM, "节日开屏: ${item.name} 返回的不是图片")
                            return false
                        }
                        output.write(header, 0, filled)
                        input.copyTo(output, COPY_BUFFER_BYTES)
                        valid = true
                    }
                }
                if (!valid || temp.length() < HolidaySplashMediaCatalog.MIN_IMAGE_BYTES) return false
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) return false
                }
                true
            }
        } catch (error: Throwable) {
            LogStore.fail(Category.SYSTEM, "节日开屏: ${item.name} 下载异常，原因=${error.javaClass.simpleName}")
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** 清掉不属于当前素材表的旧文件（链接变更后留下的历史缓存）。 */
    private fun pruneUnused(known: Map<String, HolidaySplashMediaCatalog.Item>) {
        val keep = known.values.map { cacheFile(it).name }.toHashSet()
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep && !file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    /**
     * 缓存文件名 = id + 素材版本 + 链接哈希：
     * 换图床链接或调大 `version` 都会生成新文件名，旧文件在下次预取结束时被清掉。
     */
    private fun cacheFile(item: HolidaySplashMediaCatalog.Item): File =
        File(cacheDir, "${item.id}-v${item.version}-${shortHash(item.url)}.img")

    /** 旧版命名（无版本号），仅用于升级时改名复用。 */
    private fun legacyCacheName(item: HolidaySplashMediaCatalog.Item): String =
        "${item.id}-${shortHash(item.url)}.img"

    private fun isUsableCache(file: File): Boolean =
        file.isFile && file.length() >= HolidaySplashMediaCatalog.MIN_IMAGE_BYTES

    private fun items(): Map<String, HolidaySplashMediaCatalog.Item>? {
        items?.let { return it }
        synchronized(this) {
            items?.let { return it }
            val loaded = try {
                val json = appContext.assets.open(ASSET).use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(4096)
                        val builder = StringBuilder()
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            builder.append(buffer, 0, read)
                            if (builder.length > HolidaySplashMediaCatalog.MAX_CHARS) {
                                throw IllegalArgumentException("holiday media asset too large")
                            }
                        }
                        builder.toString()
                    }
                }
                HolidaySplashMediaCatalog.parse(json).associateBy { it.id }
            } catch (error: Throwable) {
                LogStore.fail(Category.SYSTEM, "节日开屏: 素材表加载失败，改用默认开屏，原因=" +
                    error.javaClass.simpleName)
                emptyMap()
            }
            items = loaded
            return loaded
        }
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(8)
        for (index in 0 until 4) {
            builder.append(String.format("%02x", digest[index]))
        }
        return builder.toString()
    }

    private companion object {
        const val ASSET = "splash/holiday_media.json"
        const val CACHE_DIR = "splash_media"
        const val PREF_NAME = "holiday_splash_media"
        const val KEY_DATE = "today_date"
        const val KEY_PATH = "today_poster_path"
        const val KEY_ANCHOR_X = "today_anchor_x"
        const val KEY_ANCHOR_Y = "today_anchor_y"

        /** 预取窗口：覆盖未来一个月的节气，节气当天必然已就绪。 */
        const val PREFETCH_WINDOW_DAYS = 30

        /** 单张海报体积上限，防止异常链接把磁盘写满。 */
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val HEADER_SIZE = 12
        const val COPY_BUFFER_BYTES = 16 * 1024
    }
}
