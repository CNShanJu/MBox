package com.github.tvbox.osc.download;

import com.github.tvbox.osc.log.SubType;

/**
 * 下载模块日志小类型（下载模块自持枚举，归属 大类型=下载；code 落库稳定值，label 展示）。
 */
public enum DownloadSubType implements SubType {
    ENQUEUE("enqueue", "入队"),
    RESOLVE("resolve", "解析"),
    PLAYLIST("playlist", "拉列表"),
    SEGMENT("segment", "分片"),
    VERIFY("verify", "校验"),
    REPAIR("repair", "补片"),
    MERGE("merge", "合并"),
    REMUX("remux", "重封装"),
    SAVE("save", "落盘"),
    ARCHIVE("archive", "档案"),
    CLEANUP("cleanup", "清理"),
    CANCEL("cancel", "取消"),
    DELETE("delete", "删除"),
    FAIL("fail", "失败");

    private final String code;
    private final String label;

    DownloadSubType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }
}
