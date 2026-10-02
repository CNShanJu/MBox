package com.github.tvbox.osc.update.github;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.di.AppCompositionRoot;
import com.github.tvbox.osc.update.UpdateManager;
import com.github.tvbox.osc.update.Updater;
import com.github.tvbox.osc.update.UpdaterConfig;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * GitHub Releases 更新实现:从仓库 release 列表取"比当前版本新"的正式发布、匹配 APK 资产并交给
 * {@link UpdateManager} 统一下载(断点续传/暂停继续/全局悬浮圈),安装完成后再触发系统安装器。
 * <p>
 * 取列表而非 {@code releases/latest}:跨版本升级时可以把中间各版本的发版说明一并汇总给用户;
 * 正文中的开发者内容按 {@link ReleaseNotes} 的围栏约定剔除,不进弹窗。
 * <p>
 * 网络请求经 {@link AppCompositionRoot#network()} 提供的共享客户端(不 self-new OkHttpClient),
 * 后台任务复用 {@link HeavyTaskUtil} 共享执行器,回调统一切回主线程。
 */
public class GithubReleaseUpdater implements Updater {

    private static final String GITHUB_API = "https://api.github.com/repos/%s/%s/releases?per_page=%d";

    /** 一次拉取的发布条数(覆盖很久的历史已足够,也避开分页往返) */
    private static final int RELEASE_PAGE_SIZE = 30;

    /** 跨版本升级时最多汇总几个版本的说明(再往后会把最新改动淹掉) */
    private static final int MAX_NOTE_VERSIONS = 5;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String name() {
        return UpdaterConfig.SOURCE_GITHUB;
    }

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    @Override
    public void checkUpdate(final Context context, final Callback cb) {
        if (cb == null) return;
        cb.onCheckStart();
        final String api = String.format(GITHUB_API, UpdaterConfig.getGithubOwner(),
                UpdaterConfig.getGithubRepo(), RELEASE_PAGE_SIZE);
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            UpdateInfo info = null;
            String err = null;
            try {
                String current = AppUtils.getAppVersionName();
                okhttp3.Response resp = null;
                try {
                    OkHttpClient client = AppCompositionRoot.network().general();
                    // 检查更新是后台链路:失败不要弹"网络不可用"整屏页(用户可能正在看本地内容)。
                    // 用请求级标记,不影响共用 general() 客户端的页面/接口链路。
                    Request req = com.github.tvbox.osc.util.NetworkGuardInterceptor.markQuiet(new Request.Builder()
                            .url(api)
                            .header("Accept", "application/vnd.github.v3+json")
                            .build());
                    resp = client.newCall(req).execute();
                    if (!resp.isSuccessful() || resp.body() == null) {
                        err = "检查更新失败: HTTP " + resp.code();
                    } else {
                        info = parseReleases(resp.body().string(), current);
                    }
                } finally {
                    if (resp != null) resp.close();
                }
            } catch (Throwable t) {
                err = "检查更新失败: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final UpdateInfo fi = info;
            final String fe = err;
            post(() -> {
                if (fe != null) cb.onError(fe);
                else cb.onCheckResult(fi);
            });
        });
    }

    /**
     * 解析 release 列表,产出"可下载的最新版 + 到该版的说明汇总"。
     * 无更新的正式发布、或没有任何可安装 APK 资产时返回 null(视为已是最新)。
     */
    private UpdateInfo parseReleases(String json, String current) throws Exception {
        JSONArray list = new JSONArray(json);
        List<Release> newer = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            // 草稿/预发布不面向普通用户(/releases/latest 本就不含它们,口径保持一致)
            if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) continue;
            String tag = r.optString("tag_name", "");
            String version = stripV(tag);
            if (version.isEmpty()) continue;
            // 当前版本非空时,仅保留严格更新的版本(避免降级/同版本重复提示)
            if (current != null && !current.isEmpty() && !isNewerVersion(version, current)) continue;
            Asset apk = pickApk(r.optJSONArray("assets"));
            newer.add(new Release(version, tag, r.optString("body", ""), apk));
        }
        if (newer.isEmpty()) return null;
        // GitHub 按创建时间倒序返回,这里按版本号再排一次(新→旧),不依赖返回顺序
        Collections.sort(newer, (a, b) -> compareVersion(b.version, a.version));
        if (current == null || current.isEmpty()) {
            // 取不到当前版本时无从判断"跨了哪些版本",只按最新版处理
            newer = newer.subList(0, 1);
        }
        Release target = null;
        for (Release r : newer) {
            if (r.apk != null) {
                target = r;
                break;
            }
        }
        if (target == null) return null;

        // 说明只取"不高于下载目标"的版本,避免展示下不到的更新;超出上限即截断
        List<ReleaseNotes.Note> notes = new ArrayList<>();
        boolean truncated = false;
        for (Release r : newer) {
            if (compareVersion(r.version, target.version) > 0) continue;
            if (notes.size() >= MAX_NOTE_VERSIONS) {
                truncated = true;
                break;
            }
            notes.add(new ReleaseNotes.Note(r.version, r.body));
        }

        List<String> candidates = buildDownloadCandidates(
                mirrorDownloadUrl(target.tag, target.apk.name),
                String.format("https://github.com/%s/%s/releases/download/%s/%s",
                        UpdaterConfig.getGithubOwner(), UpdaterConfig.getGithubRepo(), target.tag, target.apk.name));
        if (candidates.isEmpty()) return null;
        return new UpdateInfo(target.version, target.tag, -1, candidates, target.apk.name, target.apk.size,
                ReleaseNotes.aggregate(notes, truncated));
    }

    /** 从资产里挑要安装的 APK:优先非 debug 包,只有 debug 包时兜底用它 */
    private static Asset pickApk(JSONArray assets) {
        if (assets == null) return null;
        Asset fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name", "");
            if (!isApkAsset(n)) continue;
            Asset apk = new Asset(n, a.optLong("size", -1));
            if (!isDebugAsset(n)) return apk;
            if (fallback == null) fallback = apk;
        }
        return fallback;
    }

    /** 一条 release 里与更新有关的字段 */
    private static final class Release {
        final String version;
        final String tag;
        final String body;
        final Asset apk;

        Release(String version, String tag, String body, Asset apk) {
            this.version = version;
            this.tag = tag;
            this.body = body;
            this.apk = apk;
        }
    }

    /** release 资产里的 APK 文件名与大小 */
    private static final class Asset {
        final String name;
        final long size;

        Asset(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    /** 构建下载候选地址:国内镜像优先,其次 GitHub 加速代理,末尾保留直连兜底 */
    private static List<String> buildDownloadCandidates(String mirrorUrl, String directUrl) {
        List<String> list = new ArrayList<>();
        if (directUrl == null || directUrl.trim().isEmpty()) return list;
        String direct = directUrl.trim();
        if (mirrorUrl != null && !mirrorUrl.trim().isEmpty()) {
            list.add(mirrorUrl.trim());
        }
        String proxy = UpdaterConfig.getGithubDownloadProxy();
        if (!proxy.isEmpty()) {
            // 前缀必须以 / 结尾,否则拼出来是 `https://gh-proxy.comhttps://github.com/...`(非法 URL,
            // 该候选恒失败)。默认值自带斜杠,这里是防自定义值漏写。
            String prefix = proxy.endsWith("/") ? proxy : proxy + "/";
            // 拼接形如 https://gh-proxy.com/https://github.com/...
            list.add(prefix + direct);
        }
        list.add(direct);
        return list;
    }

    /**
     * 国内镜像(Gitee 发行版)的附件直链:{@code <镜像前缀><tag>/<apk文件名>}。
     * <p>
     * 镜像的 tag/附件由发版流水线在 GitHub Release 之后补上传,可能还没同步好;此时该候选会失败,
     * 由 {@link com.github.tvbox.osc.update.UpdateManager} 按 {@code apkSize} 校验判失败并自动换下一条,
     * 不会拿错误页去安装。
     */
    private static String mirrorDownloadUrl(String tag, String apkName) {
        String base = UpdaterConfig.getGiteeDownloadBase();
        if (base.isEmpty()) return null;
        String prefix = base.endsWith("/") ? base : base + "/";
        return prefix + tag + "/" + apkName;
    }

    // ------------------------------------------------------------------
    // 下载 + 安装(统一下放 UpdateManager)
    // ------------------------------------------------------------------

    @Override
    public void downloadAndInstall(final Context context, final UpdateInfo info, final Callback cb) {
        // 下载/暂停/继续/取消/断点续传/复用缓存/安装全部收口到 UpdateManager,与 UI 无关,
        // 关闭弹窗不中断;全局悬浮圈(UpdateFloatIndicator)自动展示进度与控制入口。
        UpdateManager.get().start(context, info, cb);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void post(Runnable r) {
        if (r != null) MAIN.post(r);
    }

    /** 去掉 tag 前缀的 v/V(展示用) */
    private static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            return t.substring(1);
        }
        return t;
    }

    private static boolean isApkAsset(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private static boolean isDebugAsset(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("debug");
    }

    /** 版本比较:remote 严格大于 current 时为 true(数值分段比较,忽略非数字前缀) */
    private static boolean isNewerVersion(String remote, String current) {
        return compareVersion(remote, current) > 0;
    }

    /** 数字分段比较两个版本号:a 大于 b 返回正数,相等返回 0,小于返回负数(缺位按 0) */
    private static int compareVersion(String a, String b) {
        int[] x = parseVersion(a);
        int[] y = parseVersion(b);
        int len = Math.max(x.length, y.length);
        for (int i = 0; i < len; i++) {
            int xv = i < x.length ? x[i] : 0;
            int yv = i < y.length ? y[i] : 0;
            if (xv != yv) return xv > yv ? 1 : -1;
        }
        return 0;
    }

    /** 提取版本中的连续数字分段,如 "v3.2.0" -> [3,2,0] */
    private static int[] parseVersion(String s) {
        if (s == null) return new int[0];
        List<Integer> list = new ArrayList<>();
        StringBuilder num = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) {
                num.append(ch);
            } else if (num.length() > 0) {
                list.add(Integer.parseInt(num.toString()));
                num.setLength(0);
            }
        }
        if (num.length() > 0) list.add(Integer.parseInt(num.toString()));
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }
}
