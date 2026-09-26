package com.github.tvbox.osc.log;

import org.json.JSONObject;

/**
 * 限定对象：由 {@link LogStore#register(Category, Class)} 返回，模块持有。
 * 调用时大类型自动注入（不传不丢），小类型传枚举值（编译期类型安全）。
 * <ul>
 *   <li>success/fail/info/warn/debug 的参数均不含大类型；</li>
 *   <li>fail() 必须携带失败描述（原因必填），异常重载自动打印 类型+消息+堆栈；</li>
 *   <li>时间戳由 LogStore 自动打，业务不传。</li>
 * </ul>
 */
public interface CategoryLogger<T extends Enum<T> & SubType> {

    /** 成功事件（result=SUCCESS，级别 INFO） */
    void success(T subType, String detail, JSONObject extras);

    /** 失败事件（result=FAILURE，级别 ERROR；detail 即失败原因，必填） */
    void fail(T subType, String detail, JSONObject extras);

    /** 失败事件 + 异常（自动打印 异常类型: 消息 + 堆栈） */
    void fail(T subType, String detail, Throwable t, JSONObject extras);

    /** 中性事件（result=INFO，级别 INFO） */
    void info(T subType, String detail, JSONObject extras);

    /** 中性告警（result=INFO，级别 WARN，如"可重试失败"） */
    void warn(T subType, String detail, JSONObject extras);

    /** 调试（result=INFO，级别 DEBUG，仅在最低级别 ≤ DEBUG 时落库） */
    void debug(T subType, String detail, JSONObject extras);
}
