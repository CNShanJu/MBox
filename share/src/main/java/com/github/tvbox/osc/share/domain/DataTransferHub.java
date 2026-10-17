package com.github.tvbox.osc.share.domain;

import android.content.Context;

import com.github.tvbox.osc.share.ShareArchives;
import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.ShareManifest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 数据域导入导出的统一调度器。
 *
 * <p>各数据域（订阅源 / 直播源 / 主题 / 设置 / 历史 / prefs / room / subs 等）由上层实现
 * {@link DataDomain} 并注册进来；局域网配置导入、本地备份还原、在线分享等所有传输链路都只
 * 面向本调度器收发归档，不再各自实现"逐域导出/合并/覆盖/清除"。
 *
 * <p>领域逻辑（序列化格式、去重规则、存储位置）仍由各数据域自己管理 —— 某个域的数据格式
 * 变了，只需改那个域的实现，所有链路自动生效。
 *
 * <p>归档格式：{@code manifest.json}（{@link ShareManifest}）+ 每个数据域一个条目文件
 * （{@link DataDomain#entryName()}）。打包/解压复用 {@link ShareArchives}（Zip Slip、大小
 * 与条目数检查）。
 */
public final class DataTransferHub {

    private static final long MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L;      // 1 GB
    private static final long MAX_UNPACKED_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GB

    private final Context context;
    private final Map<String, DataDomain> domains = new LinkedHashMap<>();
    private ImportProtection protection;

    public DataTransferHub(Context context) {
        this.context = context.getApplicationContext();
        // 默认保护：快照 + 锁 + 事务 + 回滚（原配置导入 applyWithRollback 语义），下游可重写。
        this.protection = new DefaultImportProtection(this.context);
    }

    /** 注册数据域；重复 id 且非同一实例时报错（避免覆盖他人实现）。 */
    public void register(DataDomain domain) {
        if (domain == null) throw new IllegalArgumentException("domain == null");
        DataDomain existed = domains.putIfAbsent(domain.id(), domain);
        if (existed != null && existed != domain) {
            throw new IllegalArgumentException("数据域重复注册: " + domain.id());
        }
    }

    public DataDomain domain(String id) {
        return domains.get(id);
    }

    /** 已注册的数据域 id（按注册顺序）。 */
    public Set<String> registeredIds() {
        return new LinkedHashSet<>(domains.keySet());
    }

    public boolean contains(String id) {
        return domains.containsKey(id);
    }

    /** 替换导入保护实现（备份还原等链路换成自己的快照/回滚）；传 null 表示不做保护。 */
    public void setProtection(ImportProtection protection) {
        this.protection = protection;
    }

    /**
     * 导出选定数据域为归档 ZIP。
     *
     * @param ids      选定的数据域 id
     * @param fileName 归档文件名（区分配置包 / 备份包，写入清单供导入侧路由）
     * @param skipEmpty 为 {@code true} 时跳过 {@link DataDomain#hasData()} 为 false 的域
     *                  （备份语义：没有数据就不产出条目）；{@code false} 时所有选定域都导出
     * @return 调用方拥有的临时 ZIP（用完自行删除）
     */
    public File exportArchive(Set<String> ids, String fileName, boolean skipEmpty) throws IOException {
        Set<String> selected = requireDomains(ids);
        File work = createWorkDir();
        File archive = null;
        try {
            Map<String, File> entries = new LinkedHashMap<>();
            List<String> exported = new ArrayList<>();
            for (String id : selected) {
                DataDomain domain = domains.get(id);
                if (skipEmpty && !domain.hasData()) continue;
                File target = new File(work, domain.entryName());
                domain.exportTo(target);
                if (!target.isFile() || target.length() > domain.maxBytes()) {
                    throw new IOException("数据域导出产物无效或过大: " + id);
                }
                entries.put(domain.entryName(), target);
                exported.add(id);
            }
            if (exported.isEmpty()) throw new IOException("没有可导出的数据");
            ShareManifest manifest = new ShareManifest(
                    ShareManifest.SCHEMA_CURRENT, fileName, 0, "",
                    System.currentTimeMillis(), appVersionName(), 0, exported);
            entries.put(ShareManifest.ENTRY_MANIFEST, writeManifest(work, manifest));
            archive = File.createTempFile("share_data_", ".zip", context.getCacheDir());
            ShareArchives.zip(entries, archive);
            if (archive.length() > MAX_ARCHIVE_BYTES) throw new IOException("数据包超过上限");
            return archive;
        } catch (IOException error) {
            if (archive != null) archive.delete();
            throw error;
        } finally {
            clean(work);
        }
    }

    /**
     * 导入归档：按 {@link ImportMode} 分发到各数据域。
     *
     * @param archive 归档 ZIP
     * @param ids     允许导入的数据域 id（合并模式=用户勾选；覆盖模式=本机全部备份域）
     * @param mode    合并（保留现有）或覆盖（先清空）
     * @param hook    通用流程前后的定制化钩子，可为 {@code null}
     * @return 实际导入的域 id（调用方用于结果文案）
     */
    public List<String> importArchive(File archive, Set<String> ids, ImportMode mode,
                                      TransferHook hook) throws IOException {
        Set<String> selected = requireDomains(ids);
        if (archive == null || !archive.isFile() || archive.length() > MAX_ARCHIVE_BYTES) {
            throw new IOException("数据包无效或过大");
        }
        ShareManifest manifest = ShareArchives.manifest(archive);
        if (manifest == null || !manifest.isReadableByCurrentVersion()
                || manifest.domains().isEmpty()
                || !selected.containsAll(manifest.domains())) {
            throw new IOException("数据包清单无效或包含未选中的数据域");
        }
        try {
            ShareArchives.check(archive, manifest);
        } catch (ShareException error) {
            throw new IOException(error.getMessage(), error);
        }
        File work = createWorkDir();
        try {
            ShareArchives.extract(archive, work, MAX_ARCHIVE_BYTES, MAX_UNPACKED_BYTES);
            validateEntries(work, manifest.domains());
            return dispatch(work, selected, mode, manifest, hook, null);
        } finally {
            clean(work);
        }
    }

    /**
     * 从调用方已校验的解压目录导入（备份等"自有归档格式"的链路用）。
     *
     * <p>归档的读取、格式校验与解压由调用方按自己的格式完成 —— 例如备份包自带严格清单
     * （逐条目 SHA-256、Room 预校验），旧版本备份格式也在这条路上继续可还原；本方法只负责
     * 统一调度：钩子 → 导入保护 → 逐域分发，与 {@link #importArchive} 走完全相同的分发逻辑。
     *
     * @param work        已解压的归档内容目录（由调用方持有与清理）
     * @param manifest    与目录内容对应的清单（清单内的数据域条目必须已存在于 work 中）
     * @param ids         允许导入的数据域 id
     * @param mode        合并（保留现有）或覆盖（先清空）
     * @param hook        通用流程前后的定制化钩子，可为 {@code null}
     * @param protection 本次使用的导入保护，{@code null} 表示用调度器默认实现
     * @return 实际导入的域 id（调用方用于结果文案）
     */
    public List<String> importValidated(File work, ShareManifest manifest, Set<String> ids,
                                        ImportMode mode, TransferHook hook,
                                        ImportProtection protection) throws IOException {
        Set<String> selected = requireDomains(ids);
        if (work == null || !work.isDirectory()) throw new IOException("导入目录无效");
        if (manifest == null || !manifest.isReadableByCurrentVersion()
                || manifest.domains().isEmpty()
                || !selected.containsAll(manifest.domains())) {
            throw new IOException("数据包清单无效或包含未选中的数据域");
        }
        for (String id : manifest.domains()) {
            if (!new File(work, domains.get(id).entryName()).isFile()) {
                throw new IOException("数据域条目缺失: " + id);
            }
        }
        return dispatch(work, selected, mode, manifest, hook, protection);
    }

    /** 钩子 → 导入保护 → 逐域分发（所有导入链路统一的尾部）。 */
    private List<String> dispatch(File work, Set<String> selected, ImportMode mode,
                                  ShareManifest manifest, TransferHook hook,
                                  ImportProtection protection) throws IOException {
        TransferSession session = new TransferSession(selected, mode, false);
        if (hook != null) hook.before(session);
        boolean success = false;
        try {
            ImportProtection guard = protection != null ? protection : this.protection;
            ImportProtection.ImportAction action = () -> distribute(work, selected, mode, manifest);
            List<String> imported = guard != null ? guard.runProtected(session, action) : action.run();
            success = true;
            return imported;
        } finally {
            if (hook != null) hook.after(session, success);
        }
    }

    /** 逐域分发：合并模式对清单内域 merge；覆盖模式对清单内域 overwrite、清单外域 clear。 */
    private List<String> distribute(File work, Set<String> selected, ImportMode mode,
                                    ShareManifest manifest) throws IOException {
        List<String> imported = new ArrayList<>();
        for (String id : selected) {
            DataDomain domain = domains.get(id);
            boolean present = manifest.domains().contains(id);
            int count;
            if (mode == ImportMode.OVERWRITE) {
                if (present) {
                    count = domain.overwriteFrom(new File(work, domain.entryName()));
                } else {
                    domain.clearData();
                    count = 0;
                }
            } else if (present) {
                count = domain.mergeFrom(new File(work, domain.entryName()));
            } else {
                continue;
            }
            imported.add(domain.label() + " " + count + " 项");
        }
        return imported;
    }

    private Set<String> requireDomains(Set<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) throw new IOException("请至少选择一个数据域");
        for (String id : ids) {
            if (!domains.containsKey(id)) throw new IOException("未知数据域: " + id);
        }
        return new LinkedHashSet<>(ids);
    }

    private void validateEntries(File work, List<String> expectedIds) throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        expected.add(ShareManifest.ENTRY_MANIFEST);
        for (String id : expectedIds) expected.add(domains.get(id).entryName());
        File[] files = work.listFiles();
        if (files == null) throw new IOException("数据包解压失败");
        if (files.length != expected.size()) throw new IOException("数据包条目不完整或包含额外文件");
        for (File file : files) {
            if (!file.isFile() || !expected.contains(file.getName())) {
                throw new IOException("数据包包含未知条目");
            }
        }
    }

    private File writeManifest(File dir, ShareManifest manifest) throws IOException {
        File file = new File(dir, ShareManifest.ENTRY_MANIFEST);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private File createWorkDir() throws IOException {
        File dir = new File(context.getCacheDir(), "share_data_" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("无法建立临时目录");
        return dir;
    }

    private String appVersionName() {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void clean(File dir) {
        try {
            String cache = context.getCacheDir().getCanonicalPath() + File.separator;
            if (!dir.getCanonicalPath().startsWith(cache)
                    || !dir.getName().startsWith("share_data_")) {
                return;
            }
            cleanChildren(dir);
        } catch (IOException ignored) {
        }
    }

    private static void cleanChildren(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) cleanChildren(child);
        file.delete();
    }
}
