package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 聚合搜索翻页记账单测(纯 JVM,不碰真机):
 * 覆盖"还有下一页 / 空页即到底 / 没回包即到底 / 一轮结束判定 / 复位"这些页面直接依赖的语义。
 */
public class SearchPagingStateTest {

    private static final String SITE = "maccms_jiejie";
    private static final String CMS = "xiaohz";

    @Test
    public void firstPage_recordsPageAndTotal() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 1560);
        assertEquals(1, st.pageOf(SITE));
        assertEquals(1560, st.totalOf(SITE));
        assertTrue(st.hasMore());
        assertEquals(Collections.singletonList(SITE), st.keysWithMore());
        // 总页数非法(0/负/缺省)按 1:只有一页就不再翻
        st.recordFirstPage(CMS, 0);
        assertEquals(1, st.totalOf(CMS));
        assertFalse(st.keysWithMore().contains(CMS));
    }

    /** 见过的来源按各自页码推进;没见过的按第 1 页 / 1 页(不误判成"可以翻") */
    @Test
    public void unknownSource_isSinglePage() {
        SearchPagingState st = new SearchPagingState();
        assertEquals(1, st.pageOf("没见过的源"));
        assertEquals(1, st.totalOf("没见过的源"));
        assertFalse(st.hasMore());
        // 空 key 不记账
        st.recordFirstPage("", 10);
        st.recordFirstPage(null, 10);
        assertFalse(st.hasMore());
    }

    /** 正常翻页:一路翻到总页数后 hasMore=false */
    @Test
    public void pagingUntilLastPage() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 3);

        assertTrue(st.beginRound(st.keysWithMore()));
        assertTrue("发起后本轮应处于进行中", st.inRound());
        assertTrue("全部来源回包后本轮结束", st.reply(SITE, 2, true, 3));
        assertEquals(2, st.pageOf(SITE));
        assertTrue(st.finishRound());
        assertTrue(st.hasMore());

        assertTrue(st.beginRound(st.keysWithMore()));
        assertTrue(st.reply(SITE, 3, true, 3));
        st.finishRound();
        assertEquals(3, st.pageOf(SITE));
        assertFalse("到最后一页就没有下一页了", st.hasMore());
    }

    /** 空页 = 该源到底:即使响应里的 pagecount 还很大,也不再重复请求同一页 */
    @Test
    public void emptyPage_marksSourceDone() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 1560);   // 站点分页虚高,实际翻两页就没了

        st.beginRound(st.keysWithMore());
        assertTrue(st.reply(SITE, 2, false, 1560));
        assertFalse("空页应把该源标记到底", st.finishRound());
        assertFalse(st.hasMore());
        assertEquals(2, st.pageOf(SITE));
        assertEquals(2, st.totalOf(SITE));
    }

    /** 一轮里没回包的来源按到底处理:不能让页面永远"加载中"(请求超时/异常走这里) */
    @Test
    public void missingReply_marksSourceDone() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 5);
        st.recordFirstPage(CMS, 5);

        assertTrue(st.beginRound(st.keysWithMore()));
        assertFalse("两个源都回了才算本轮结束", st.reply(SITE, 2, true, 5));
        // CMS 一直没回包:强制收尾时应把 CMS 按到底处理;本轮 SITE 有新增,故返回 true
        assertTrue(st.finishRound());
        assertTrue("还有一个源能翻", st.hasMore());
        assertFalse("CMS 已被标记到底", st.keysWithMore().contains(CMS));
        assertEquals(Arrays.asList(SITE), st.keysWithMore());
    }

    /** 乱序回包 / 页码不回退 / 过期批次不干扰本轮计数 */
    @Test
    public void staleOrOutOfOrderReplies() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 4);

        st.beginRound(st.keysWithMore());
        // 过期来源(不在本轮)回包:不记账、不减计数
        assertFalse(st.reply("别的源", 2, true, 4));
        assertTrue(st.inRound());
        // 本轮该源回了个更小的页码(乱序):页码不后退
        assertTrue(st.reply(SITE, 2, true, 4));
        st.finishRound();
        assertEquals(2, st.pageOf(SITE));
        assertTrue(st.hasMore());
    }

    /** 没有可翻来源时不发起(避免空转轮次) */
    @Test
    public void beginRound_withoutCandidates() {
        SearchPagingState st = new SearchPagingState();
        assertFalse(st.beginRound(null));
        assertFalse(st.beginRound(Collections.<String>emptyList()));
        st.recordFirstPage(SITE, 1);
        assertFalse(st.beginRound(st.keysWithMore()));
        assertFalse(st.inRound());
    }

    /** 多来源各自独立翻页:一个到底不影响另一个继续翻 */
    @Test
    public void multiSource_independentPaging() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 10);
        st.recordFirstPage(CMS, 2);
        List<String> keys = st.keysWithMore();
        assertEquals(2, keys.size());

        st.beginRound(keys);
        assertTrue(!st.reply(SITE, 2, true, 10));
        assertTrue(st.reply(CMS, 2, true, 2));
        st.finishRound();

        assertEquals(2, st.pageOf(SITE));
        assertEquals(2, st.pageOf(CMS));
        assertTrue(st.hasMore());
        assertEquals(Arrays.asList(SITE), st.keysWithMore());
    }

    /** 复位:新一轮搜索从零开始 */
    @Test
    public void reset_clearsEverything() {
        SearchPagingState st = new SearchPagingState();
        st.recordFirstPage(SITE, 9);
        st.beginRound(st.keysWithMore());
        st.reply(SITE, 2, true, 9);
        st.finishRound();

        st.reset();
        assertFalse(st.hasMore());
        assertTrue(st.keysWithMore().isEmpty());
        assertFalse(st.inRound());
        assertEquals(1, st.pageOf(SITE));
        assertEquals(1, st.totalOf(SITE));
    }
}
