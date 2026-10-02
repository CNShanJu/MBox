package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 直播 RecyclerView 条目自身承载焦点/选中底，状态底与文字都从运行时主题取色。 */
public class ThemeLiveListSelectorContractTest {

    private static String read(String relative) throws Exception {
        File file = new File(relative);
        if (!file.isFile()) file = new File("../app/" + relative);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void liveRowsKeepTheirThemedSelectorPath() throws Exception {
        for (String layout : new String[]{"activity_live.xml", "dialog_all_channel.xml"}) {
            String xml = read("src/main/res/layout/" + layout);
            assertFalse("RecyclerView 不支持 listSelector: " + layout, xml.contains("android:listSelector="));
        }
        String groupAdapter = read("src/main/java/com/github/tvbox/osc/ui/adapter/LiveChannelGroupNewAdapter.java");
        String channelAdapter = read("src/main/java/com/github/tvbox/osc/ui/adapter/LiveChannelItemNewAdapter.java");
        assertTrue(groupAdapter.contains("applyBackground(root, R.drawable.item_bg_selector_left)"));
        assertTrue(channelAdapter.contains("applyBackground(root, R.drawable.item_bg_selector_right)"));
        assertTrue(groupAdapter.contains("root.setSelected("));
        assertTrue(channelAdapter.contains("root.setSelected("));
        assertTrue(read("src/main/res/layout/item_live_channel_group_new.xml")
                .contains("android:duplicateParentState=\"true\""));
        assertTrue(read("src/main/res/layout/item_live_channel_group_new.xml")
                .contains("@color/live_channel_text"));
        assertTrue(read("src/main/res/drawable/item_bg_selector_left.xml")
                .contains("@drawable/bg_r_common_solid_select"));
        assertTrue(read("src/main/res/drawable/item_bg_selector_right.xml")
                .contains("@drawable/bg_r_common_solid_select"));
        String factory = read("src/main/java/com/github/tvbox/osc/theme/ThemeDrawableFactory.java");
        assertTrue("直播行状态底应按配方现造，避免旧 XML 重建回抄编译期圆角",
                factory.contains("R.drawable.item_bg_selector_left")
                        && factory.contains("R.drawable.item_bg_selector_right) return \"live_row_selector\""));
        String recipes = read("src/main/assets/theme/radius/theme_shapes.json");
        assertTrue(recipes.contains("\"live_row_selector\""));
        assertTrue(recipes.contains("\"radius\": \"common_corners\""));
        assertTrue(recipes.contains("\"selected\": { \"fill\": \"btn_select_bg\""));
    }
}
