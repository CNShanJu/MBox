package com.github.tvbox.osc.bean;

import java.util.LinkedHashMap;

/**
 * @author pj567
 * @date :2021/3/8
 * @description: 纯模型(在 :common):不再持有 Hawk 副作用;
 * 用户主动选择解码器时由设置层(PlayConfig)负责持久化 HawkConfig.IJK_CODEC。
 */
public class IJKCode {
    private String name;
    private LinkedHashMap<String, String> option;
    private boolean selected;

    public void selected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LinkedHashMap<String, String> getOption() {
        return option;
    }

    public void setOption(LinkedHashMap<String, String> option) {
        this.option = option;
    }
}