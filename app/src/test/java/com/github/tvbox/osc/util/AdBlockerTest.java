package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;

/**
 * AdBlocker 名单语义的 JVM 单测(纯逻辑,不触碰 Android 类):
 * 默认名单与源名单分层、切源替换源名单、大小写归一、空值容错。
 *
 * <p>回归背景:旧实现只有一份名单并以 {@code isEmpty()} 当"只初始化一次"的开关,
 * 结果是"第一个源的 ads 永久生效、后续源的 ads 永远加不进来"(切源后拦截名单串源)。
 */
public class AdBlockerTest {

    private static final String DEFAULT_AD = "mimg.0c1q0l.cn";
    private static final String DEFAULT_TRACK = "cnzz.mmstat.com";

    @Before
    public void reset() {
        AdBlocker.clear();
    }

    @Test
    public void defaultHostsStayAcrossSourceSwitches() {
        AdBlocker.ensureDefaultHosts(Arrays.asList(DEFAULT_AD, DEFAULT_TRACK));

        AdBlocker.setSourceHosts(Collections.singletonList("ads.sourcea.com"));
        assertTrue(AdBlocker.isAd("http://ads.sourcea.com/x.js"));

        // 切源:新源名单生效,旧源名单必须消失(这正是旧实现的 bug)
        AdBlocker.setSourceHosts(Collections.singletonList("ads.sourceb.com"));
        assertTrue(AdBlocker.isAd("http://ads.sourceb.com/x.js"));
        assertFalse(AdBlocker.isAd("http://ads.sourcea.com/x.js"));
        // 默认名单不随切源丢失
        assertTrue(AdBlocker.isAd("http://" + DEFAULT_AD + "/a.png"));
        assertTrue(AdBlocker.isAd("http://" + DEFAULT_TRACK + "/a.gif"));
    }

    @Test
    public void sourceWithoutAdsClearsPreviousSourceAds() {
        AdBlocker.ensureDefaultHosts(Collections.singletonList(DEFAULT_AD));
        AdBlocker.setSourceHosts(Collections.singletonList("ads.sourcea.com"));
        assertTrue(AdBlocker.isAd("http://ads.sourcea.com/x.js"));

        AdBlocker.setSourceHosts(null); // 新源配置里没有 ads
        assertFalse(AdBlocker.isAd("http://ads.sourcea.com/x.js"));
        assertTrue(AdBlocker.isAd("http://" + DEFAULT_AD + "/a.png"));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        AdBlocker.ensureDefaultHosts(Arrays.asList("ADS.Example.COM", "  Tracks.Example.NET  "));
        AdBlocker.setSourceHosts(Collections.singletonList("Ad.Source.COM"));

        assertTrue(AdBlocker.isAd("https://ads.example.com/a.js"));
        assertTrue(AdBlocker.isAd("HTTPS://TRACKS.EXAMPLE.NET/A.GIF"));
        assertTrue(AdBlocker.isAd("https://ad.source.com/x"));
    }

    @Test
    public void ensureDefaultHostsIsIdempotent() {
        AdBlocker.ensureDefaultHosts(Collections.singletonList(DEFAULT_AD));
        AdBlocker.ensureDefaultHosts(Collections.singletonList(DEFAULT_AD));
        AdBlocker.ensureDefaultHosts(Collections.singletonList(DEFAULT_AD));
        assertTrue(AdBlocker.isAd("http://" + DEFAULT_AD + "/a.png"));
        assertFalse(AdBlocker.isEmpty());
    }

    @Test
    public void blankAndNullEntriesAreIgnored() {
        AdBlocker.setSourceHosts(Arrays.asList("", "   ", "  NotAnAd.com "));
        assertFalse(AdBlocker.isAd(""));
        assertFalse(AdBlocker.isAd("http://nothing.example/a.js"));
        // 空白项不进名单;"NotAnAd.com" 归一为小写后生效
        assertTrue(AdBlocker.isAd("http://notanad.com/a.js"));
        assertFalse(AdBlocker.isEmpty());

        AdBlocker.clear();
        AdBlocker.setSourceHosts(Arrays.asList("", "   "));
        assertTrue(AdBlocker.isEmpty());
        assertFalse(AdBlocker.isAd(null));
    }

    @Test
    public void preparedSourceHostsStayInactiveUntilPublicationAndCopyInput() {
        AdBlocker.setSourceHosts(Collections.singletonList("old.example"));
        ArrayList<String> input = new ArrayList<>(Collections.singletonList(" NEW.Example "));
        AdBlocker.SourceHosts prepared = AdBlocker.prepareSourceHosts(input);
        input.clear();

        assertTrue(AdBlocker.isAd("https://old.example/ad"));
        assertFalse(AdBlocker.isAd("https://new.example/ad"));

        AdBlocker.replaceSourceHosts(prepared);
        assertFalse(AdBlocker.isAd("https://old.example/ad"));
        assertTrue(AdBlocker.isAd("https://new.example/ad"));
    }
}
