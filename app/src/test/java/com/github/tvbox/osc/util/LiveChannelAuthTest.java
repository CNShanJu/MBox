package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** LiveChannelAuth(直播分组密码门禁纯逻辑)单测 */
public class LiveChannelAuthTest {

    private static LiveChannelGroup group(int idx, String password) {
        LiveChannelGroup g = new LiveChannelGroup();
        g.setGroupIndex(idx);
        g.setGroupPassword(password);
        return g;
    }

    @Test
    public void isPasswordConfirmed_matchesIndex() {
        List<Integer> confirmed = new ArrayList<>();
        confirmed.add(0);
        confirmed.add(3);
        assertTrue(LiveChannelAuth.isPasswordConfirmed(confirmed, 0));
        assertTrue(LiveChannelAuth.isPasswordConfirmed(confirmed, 3));
        assertFalse(LiveChannelAuth.isPasswordConfirmed(confirmed, 1));
        assertFalse(LiveChannelAuth.isPasswordConfirmed(null, 0));
    }

    @Test
    public void needInputPassword_onlyWhenProtectedAndUnconfirmed() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, "1234"));
        groups.add(group(1, ""));   // 无密码
        List<Integer> confirmed = new ArrayList<>();
        confirmed.add(0);
        assertFalse(LiveChannelAuth.needInputPassword(groups, confirmed, 0)); // 已确认
        assertTrue(LiveChannelAuth.needInputPassword(groups, new ArrayList<>(), 0)); // 未确认且设密
        assertFalse(LiveChannelAuth.needInputPassword(groups, new ArrayList<>(), 1)); // 无密码组
    }

    @Test
    public void visibleChannels_authorizedReturnsSameList() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, "1234"));
        ArrayList<LiveChannelItem> channels = new ArrayList<>();
        LiveChannelItem c = new LiveChannelItem();
        c.setChannelName("央视1");
        channels.add(c);
        groups.get(0).setLiveChannels(channels);

        // 未确认:空表
        List<LiveChannelItem> denied = LiveChannelAuth.visibleChannels(groups, new ArrayList<>(), 0, channels);
        assertTrue(denied.isEmpty());
        // 已确认:原引用
        List<Integer> confirmed = new ArrayList<>();
        confirmed.add(0);
        assertSame(channels, LiveChannelAuth.visibleChannels(groups, confirmed, 0, channels));
        assertEquals("央视1", channels.get(0).getChannelName());
    }

    @Test
    public void visibleChannels_unprotectedGroupPassesThrough() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, ""));
        ArrayList<LiveChannelItem> channels = new ArrayList<>();
        groups.get(0).setLiveChannels(channels);
        assertSame(channels, LiveChannelAuth.visibleChannels(groups, new ArrayList<>(), 0, channels));
    }

    @Test
    public void firstOpenGroup_returnsFirstUnprotected() {
        List<LiveChannelGroup> groups = new ArrayList<>();
        groups.add(group(0, "1234"));
        groups.add(group(1, ""));
        groups.add(group(2, "pwd"));
        assertEquals(1, LiveChannelAuth.firstOpenGroup(groups));
        assertEquals(-1, LiveChannelAuth.firstOpenGroup(new ArrayList<>()));
        assertEquals(-1, LiveChannelAuth.firstOpenGroup(null));
    }

    // ── LiveChannelNav: 组/频道上下导航(3 组:0/2 无密码各 3 频道,1 加密) ──
    // channelCount: group0=3, group1(加密) 无频道, group2=2; 加密组 group1 不入可见频道数
    private static java.util.function.IntUnaryOperator CH =
            g -> g == 1 ? 0 : (g == 2 ? 2 : 3);
    private static java.util.function.IntPredicate LOCKED =
            g -> g == 1;

    @Test
    public void next_withinGroupIncrementsAndWraps() {
        // 组内 0→1→2→回卷(非跨组越界仍留本组)
        int[] r = LiveChannelNav.next(1, 3, CH, LOCKED, 0, 1, true);
        assertEquals(0, r[0]);
        assertEquals(2, r[1]);
    }

    @Test
    public void next_crossGroupSkipsLocked() {
        // group0 最后一频道 +1 → 越过加密 group1 到 group2 频道 0
        int[] r = LiveChannelNav.next(1, 3, CH, LOCKED, 0, 2, true);
        assertEquals(2, r[0]);
        assertEquals(0, r[1]);
    }

    @Test
    public void next_previousWrapsToTailOfPreviousOpenGroup() {
        // group2 频道 0 的 -1 → group0 最后一频道(2),跳过加密 group1
        int[] r = LiveChannelNav.next(-1, 3, CH, LOCKED, 2, 0, true);
        assertEquals(0, r[0]);
        assertEquals(2, r[1]);
    }

    @Test
    public void next_noCrossGroupKeepsGroup() {
        int[] r = LiveChannelNav.next(1, 3, CH, LOCKED, 0, 2, false);
        assertEquals(0, r[0]);
        assertEquals(0, r[1]); // 组内回卷
    }

    @Test
    public void next_allLockedStaysOnCurrentGroup() {
        // 全部加密:跨组无目标 → 兜底停当前组(防死循环)
        int[] r = LiveChannelNav.next(1, 1, CH, LOCKED, 0, 2, true);
        assertEquals(0, r[0]);
    }
}
