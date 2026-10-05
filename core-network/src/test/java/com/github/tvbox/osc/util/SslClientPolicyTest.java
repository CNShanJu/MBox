package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.catvod.net.SSLCompat;
import com.github.tvbox.osc.config.PrefsDataStore;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.OkHttpClient;

public class SslClientPolicyTest {
    @Test
    public void trustExceptionAppliesToAnyHostOnlyWhileEnabled() throws Exception {
        synchronized (PrefsDataStore.class) {
            Field cacheField = PrefsDataStore.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            Object previous = cacheField.get(null);
            ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
            try {
                cacheField.set(null, cache);
                Method configure = OkGoHelper.class.getDeclaredMethod("setOkHttpSsl", OkHttpClient.Builder.class);
                configure.setAccessible(true);
                OkHttpClient baseline = new OkHttpClient.Builder().build();
                SSLSocketFactory globalDefault = HttpsURLConnection.getDefaultSSLSocketFactory();

                cache.put("ignore_ssl_error", true);
                OkHttpClient.Builder enabledBuilder = new OkHttpClient.Builder();
                configure.invoke(null, enabledBuilder);
                OkHttpClient enabled = enabledBuilder.build();
                assertTrue(enabled.sslSocketFactory() instanceof SSLCompat);
                assertTrue(enabled.hostnameVerifier().verify("first.example", null));
                assertTrue(enabled.hostnameVerifier().verify("unrelated.example", null));
                assertSame(globalDefault, HttpsURLConnection.getDefaultSSLSocketFactory());

                cache.put("ignore_ssl_error", false);
                OkHttpClient.Builder disabledBuilder = new OkHttpClient.Builder();
                configure.invoke(null, disabledBuilder);
                OkHttpClient disabled = disabledBuilder.build();
                assertEquals(baseline.hostnameVerifier().getClass(), disabled.hostnameVerifier().getClass());
                assertEquals(baseline.sslSocketFactory().getClass(), disabled.sslSocketFactory().getClass());
            } finally {
                cacheField.set(null, previous);
            }
        }
    }
}
