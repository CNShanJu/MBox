package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 圆角自检文本的判据(纯 JVM)。
 *
 * <p>它存在的意义:圆角是**编译期资源**,而 AS 点 Run 的部署链路不带资源 ——
 * "改了圆角没生效"这件事只能靠"包内值 vs 文件值"的对比说清楚,所以这两句判据必须准:
 * 一致就别说"不一致"(免得误导用户去重装),不一致必须点名是哪一档。
 */
public class RadiusCheckTest {

    private static Map<String, String> all(String value) {
        Map<String, String> m = new HashMap<>();
        for (String k : RadiusCheck.KEYS) m.put(k, value);
        return m;
    }

    @Test
    public void sameValuesReportConsistent() {
        String text = RadiusCheck.describe(all("12dp"), all("12dp"));
        assertTrue("两边一样时必须说一致:" + text, text.contains("一致"));
        assertTrue("一致时不该出现不一致结论:" + text, !text.contains("不一致"));
    }

    @Test
    public void mismatchNamesTheKeyAndTellsToInstallFully() {
        Map<String, String> compiled = all("12dp");
        Map<String, String> file = all("12dp");
        file.put("radius_widget_btn", "6dp");
        String text = RadiusCheck.describe(compiled, file);
        assertTrue("必须点名是哪一档不一致:" + text, text.contains("radius_widget_btn"));
        assertTrue("必须给出「完整安装」的处置口径:" + text, text.contains("完整安装"));
        assertTrue("要同时给出包内值与文件值:" + text,
                text.contains("包内12dp") && text.contains("文件6dp"));
    }

    @Test
    public void keyMissingFromFileCountsAsMismatch() {
        Map<String, String> compiled = all("12dp");
        Map<String, String> file = all("12dp");
        file.remove("common_corners");
        String text = RadiusCheck.describe(compiled, file);
        assertTrue("文件里缺这一档要报出来(说明包与文件对不上):" + text, text.contains("common_corners"));
        assertTrue(text.contains("不一致"));
    }

    @Test
    public void missingFromPackageIsReported() {
        Map<String, String> compiled = new HashMap<>();
        Map<String, String> file = all("12dp");
        String text = RadiusCheck.describe(compiled, file);
        assertTrue("包内取不到该 dimen 时要报告:" + text, text.contains("不一致"));
    }
}
