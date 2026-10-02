package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

public class HistorySourceBindingTest {
    @Test
    public void identicalSourceKeyFromDifferentSubscriptionsDoesNotMatch() {
        VodInfo record = new VodInfo();
        record.sourceKey = "site_a";
        HistorySourceBinding.stamp(record, "https://one.example/订阅.json");

        assertTrue(HistorySourceBinding.matchesSubscription(record, "https://one.example/订阅.json"));
        assertFalse(HistorySourceBinding.matchesSubscription(record, "https://two.example/订阅.json"));
    }

    @Test
    public void legacyRecordWithoutFingerprintRetainsSourceKeyCompatibility() {
        VodInfo record = new VodInfo();
        record.sourceKey = "site_a";

        assertTrue(HistorySourceBinding.matchesSubscription(record, "https://current.example/config.json"));
    }
}
