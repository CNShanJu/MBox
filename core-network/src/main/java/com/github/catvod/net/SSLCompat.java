package com.github.catvod.net;

import android.annotation.SuppressLint;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 兼容套接字工厂(旧 Android TLS1.0/1.1 升级)。
 * <p>
 * 安全约定:本工厂配 {@link #TM}(信任任意证书)只为在 Android 11+ 上对
 * "忽略证书错误"场景放行自签名/证书错误站点;调用方必须在用户显式开启
 * {@code SystemConfig.isIgnoreSslError()}(默认关)时才挂载本工厂,
 * 默认必须使用 OkHttp 系统证书校验,禁止无条件全局关闭 TLS 校验。
 * 本类不再改写 {@code HttpsURLConnection} 的全局默认 SSLSocketFactory,
 * 避免构造函数副作用让全 App 的直连 HTTPS 静默信任任意证书。
 */
public class SSLCompat extends SSLSocketFactory {

    private static String[] cipherSuites;
    private static String[] protocols;
    private SSLSocketFactory factory;

    static {
        try {
            SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            List<String> protocols = new LinkedList<>();
            for (String protocol : socket.getSupportedProtocols()) if (!protocol.toUpperCase().contains("SSL")) protocols.add(protocol);
            SSLCompat.protocols = protocols.toArray(new String[protocols.size()]);
            List<String> allowedCiphers = Arrays.asList("TLS_RSA_WITH_AES_256_GCM_SHA384", "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "TLS_ECHDE_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_3DES_EDE_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA");
            List<String> availableCiphers = Arrays.asList(socket.getSupportedCipherSuites());
            HashSet<String> preferredCiphers = new HashSet<>(allowedCiphers);
            preferredCiphers.retainAll(availableCiphers);
            preferredCiphers.addAll(new HashSet<>(Arrays.asList(socket.getEnabledCipherSuites())));
            SSLCompat.cipherSuites = preferredCiphers.toArray(new String[preferredCiphers.size()]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public SSLCompat() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[]{TM}, null);
            // 不再 setDefaultSSLSocketFactory:类实例只服务挂载它的 OkHttp 客户端,
            // 不得借构造副作用全局放宽 HttpsURLConnection 的证书校验(安全红线)。
            factory = context.getSocketFactory();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return cipherSuites;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return cipherSuites;
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        Socket ssl = factory.createSocket(s, host, port, autoClose);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket ssl = factory.createSocket(host, port);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket ssl = factory.createSocket(host, port, localHost, localPort);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket ssl = factory.createSocket(host, port);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket ssl = factory.createSocket(address, port, localAddress, localPort);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    private void upgradeTLS(SSLSocket ssl) {
        if (protocols != null) ssl.setEnabledProtocols(protocols);
        if (cipherSuites != null) ssl.setEnabledCipherSuites(cipherSuites);
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    public static final X509TrustManager TM = new X509TrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{};
        }
    };
}
