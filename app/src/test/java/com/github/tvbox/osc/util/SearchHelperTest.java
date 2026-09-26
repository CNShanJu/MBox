package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * SearchHelper.splitWords 单测:主搜索/快速搜索词表来源,锁定"原文在首位 + \W+ 分词补词"语义。
 */
public class SearchHelperTest {

    @Test
    public void singleChineseTitle_onlyOriginal() {
        List<String> words = SearchHelper.splitWords("赘婿");
        assertEquals(1, words.size());
        assertEquals("赘婿", words.get(0));
    }

    @Test
    public void latinMultiWord_originalPlusParts() {
        List<String> words = SearchHelper.splitWords("Avengers Endgame");
        assertEquals(Arrays.asList("Avengers Endgame", "Avengers", "Endgame"), words);
    }

    @Test
    public void mixedWithPunctuation_asciiWordsAppended_noEmptyCandidates() {
        // 原文在首位;中文按 \W 切分产生的空串被过滤,仅 ASCII 词补为候选词
        List<String> words = SearchHelper.splitWords("你好 world,2021");
        assertEquals(Arrays.asList("你好 world,2021", "world", "2021"), words);
    }

    @Test
    public void pureAsciiWords_appendedAsCandidates() {
        List<String> words = SearchHelper.splitWords("Hello world,2021!");
        assertEquals(Arrays.asList("Hello world,2021!", "Hello", "world", "2021"), words);
    }

    @Test
    public void emptyText_keepsSingleEmpty() {
        List<String> words = SearchHelper.splitWords("");
        assertEquals(1, words.size());
        assertEquals("", words.get(0));
    }
}
