package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.R;

import org.junit.Test;

/** ThemeSweep 只能按资源 id/令牌工作,禁止恢复按颜色值反推语义。 */
public class ThemeSweepMatchTest {

    @Test
    public void registeredViewsUseExplicitTokens() {
        assertEquals("bg_surface", ThemeSweep.backgroundTokenOf(R.id.bottom_nav_surface));
    }

    /**
     * 布局里没有背景的视图,扫描层<b>不许</b>给它补一层底。
     *
     * <p>真机踩过:"我的"页顶部那行应用名标题(fragment_my.xml 的 my_surface_card,只有
     * margin/padding,没有 background)被登记成 bg_large_round_float,换自定义主题时就
     * 凭空多出一块底色。它现在必须是 null —— 真正要跟主题走的那张卡片,是布局自己写了
     * {@code @drawable/bg_large_round_float} 的那一层,由属性注入通道负责。
     */
    @Test
    public void viewsWithoutBackgroundAreNotPaintedByTheSweep() {
        assertNull("my_surface_card 在布局里没有背景,扫描层不许给它铺底",
                ThemeSweep.backgroundTokenOf(R.id.my_surface_card));
    }

    @Test
    public void unknownViewHasNoGuessedMeaning() {
        assertNull(ThemeSweep.backgroundTokenOf(android.R.id.content));
        assertNull(ThemeSweep.backgroundTokenOf(0x7f7fffff));
    }
}
