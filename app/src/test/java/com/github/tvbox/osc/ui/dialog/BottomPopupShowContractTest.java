package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「底部弹窗能直接 show()」这条链路的源码级绊线(纯 JVM;真机行为由用户人工验证)。
 *
 * <p>钉住的是一个已经崩过的坑(日志页「错误日志 → 日期抽屉」一点即崩):
 * XPopup 的 {@code popupInfo} 只由 {@code XPopup.Builder} 绑定,而 {@code BasePopupView.show()}
 * 对它做硬校验 —— 公共弹窗组件照文档直接 {@code new BottomListDialog(...).show()} 时 popupInfo 还是 null,
 * 于是抛 {@code IllegalArgumentException: popupInfo is null, if your popup object is reused...}。
 * 修法是把自绑定收口到底部弹窗基类({@link AppBottomPopupView#show()}),本测试钉住这条收口:
 * 基类兜底一旦被删掉/挪走,所有 Bottom 系弹窗(ApiHistoryDialog/TextTipDialog/BottomListDialog 等)
 * 的直呼 {@code show()} 会再次集体崩,而编译器不会拦 —— 所以只能靠这条绊线。
 */
public class BottomPopupShowContractTest {

    private static final String BASE = "src/main/java/com/github/tvbox/osc/ui/dialog/AppBottomPopupView.java";
    private static final String BASE_ALT = "../app/" + BASE;
    private static final String DIALOG_DIR = "src/main/java/com/github/tvbox/osc/ui/dialog";
    private static final String DIALOG_DIR_ALT = "../app/" + DIALOG_DIR;
    private static final String BASE_CLASS = "AppBottomPopupView.java";

    private static String read(String... relativeCandidates) throws Exception {
        for (String c : relativeCandidates) {
            File f = new File(c);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new AssertionError("找不到源文件:" + relativeCandidates[0]);
    }

    /** 基类的 show() 必须"未绑定先补绑定":popupInfo==null → 经 Builder 绑定后再 super.show() */
    @Test
    public void baseBindsPopupInfoBeforeFirstShow() throws Exception {
        String src = read(BASE, BASE_ALT);
        int i = src.indexOf("public BasePopupView show()");
        assertTrue("底部弹窗基类不再覆写 show():直呼 show() 的弹窗会抛 popupInfo is null", i > 0);
        String body = src.substring(i);
        int nullCheck = body.indexOf("popupInfo == null");
        assertTrue("基类 show() 没有先判 popupInfo 是否为 null:" + body, nullCheck > 0);
        // 兜底要真的绑定弹壳(经协调器,壳参数与全站底部弹窗同一套),不能只是提前 return
        String bind = body.substring(nullCheck);
        int coordinator = bind.indexOf("DialogCoordinator.bottom(");
        assertTrue("未绑定时要经 DialogCoordinator.bottom 绑定后展示:" + bind, coordinator > 0);
        assertTrue("未绑定时不能越过兜底直接往上抛:", bind.indexOf("super.show()") > coordinator);
        assertTrue("已绑定的调用点仍要原样走 super.show():", body.contains("super.show()"));
    }

    /**
     * 底部弹窗不许绕过 {@link AppBottomPopupView} 直接继承 XPopup 的 BottomPopupView:
     * 绕过去就丢了统一底/限高/横屏限宽,也丢了上面那条 show() 兜底 —— 直呼 show() 的坑会原样复现。
     * (AppBottomPopupView 自己当然要继承它,只豁免这一个文件。)
     */
    @Test
    public void bottomDialogsGoThroughTheRepoBase() throws Exception {
        File dir = new File(DIALOG_DIR);
        if (!dir.exists()) dir = new File(DIALOG_DIR_ALT);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java") || name.endsWith(".kt"));
        assertTrue("找不到弹窗目录,绊线要跟着改", files != null && files.length > 0);

        // 只看"类声明头"(class 名到第一个 '{' 之间):import 行、注释里提到 XPopup 基类都不算
        Pattern decl = Pattern.compile("class\\s+(\\w+)([^{]*)\\{");
        Pattern baseType = Pattern.compile("(?:extends|:)\\s*([\\w.]*BottomPopupView)\\b");
        List<String> offenders = new ArrayList<>();
        for (File f : files) {
            if (BASE_CLASS.equals(f.getName())) continue;
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = decl.matcher(src);
            while (m.find()) {
                Matcher t = baseType.matcher(m.group(2));
                while (t.find()) {
                    String type = t.group(1);
                    // 继承本仓底部基类(含 SheetResizableBottomPopup 这条)是正路,只有直接吃 XPopup 基类才算绕过
                    if (type.endsWith("AppBottomPopupView")) continue;
                    offenders.add(f.getName() + " → " + m.group(1) + " extends " + type);
                }
            }
        }
        assertTrue("这些底部弹窗绕过了 AppBottomPopupView(丢了统一底/限高,也没有 show() 兜底):" + offenders,
                offenders.isEmpty());
    }
}
