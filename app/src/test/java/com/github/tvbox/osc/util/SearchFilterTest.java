package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** SearchFilter(搜索结果行命中判定,自 FastSearchActivity 抽取)单测:锁定与旧实现一致的匹配语义。 */
public class SearchFilterTest {

    @Test
    public void nullOrEmptyName_notMatch() {
        assertFalse(SearchFilter.matches(null, "abc"));
        assertFalse(SearchFilter.matches("", "abc"));
    }

    @Test
    public void nullOrEmptyQuery_notMatch() {
        assertFalse(SearchFilter.matches("abc", null));
        assertFalse(SearchFilter.matches("abc", ""));
    }

    @Test
    public void spacesOnlyQuery_matchAll_likeLegacy() {
        assertTrue(SearchFilter.matches("任意片名", "   "));
    }

    @Test
    public void multiToken_allTokensMustContain() {
        assertTrue(SearchFilter.matches("复仇者联盟4 终局之战", "复仇者 终局"));
        assertFalse(SearchFilter.matches("复仇者联盟4 终局之战", "复仇者 不存在"));
    }

    @Test
    public void chineseToken_partialContains() {
        assertTrue(SearchFilter.matches("赘婿第一季", "赘婿"));
        assertFalse(SearchFilter.matches("赘婿第一季", "庆余年"));
    }

    @Test
    public void surroundingSpaces_trimmed() {
        assertTrue(SearchFilter.matches("Avengers Endgame", "  Avengers Endgame  "));
        assertTrue(SearchFilter.matches("Avengers Endgame", "  Avengers   Endgame  "));
    }

    @Test
    public void leadingSpaceTokens_emptyTokensAutoPass() {
        // 旧实现把前导空白切出的空 token 按 contains("") 恒真计数,非空词仍须全部命中
        assertTrue(SearchFilter.matches("Avengers Endgame", " Avengers"));
        assertFalse(SearchFilter.matches("Avengers Endgame", " Endgame Avengers2"));
    }
}
