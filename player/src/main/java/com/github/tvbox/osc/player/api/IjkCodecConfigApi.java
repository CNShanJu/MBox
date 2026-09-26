package com.github.tvbox.osc.player.api;

import com.github.tvbox.osc.bean.IJKCode;

import java.util.List;

/**
 * IJK 解码配置契约（改进.txt 播放器收口：播放内核/设置侧读取解码组配置）。
 * <p>
 * 订阅内容里可携带 ijk 解码配置（组名+选项表）；未成功拉取时回退内置默认组。
 * 具体持有在 app 组合根（桥接 :spider ApiConfig 现有实现），播放内核与 UI 只依赖本接口。
 */
public interface IjkCodecConfigApi {

    /** 全部解码组（订阅携带或内置默认；其中恰有一组 selected） */
    List<IJKCode> getIjkCodes();

    /** 当前选中解码组（按 Hawk IJK_CODEC 名匹配，未命名回退默认） */
    IJKCode getCurrentIJKCode();

    /** 按组名取解码组；不存在返回 null */
    IJKCode getIJKCodec(String name);
}
