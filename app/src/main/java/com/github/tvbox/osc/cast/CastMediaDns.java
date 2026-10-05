package com.github.tvbox.osc.cast;

import com.github.tvbox.osc.server.LanCastRelayRules;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Dns;

/** DNS policy used by one cast relay's actual OkHttp connections. */
public final class CastMediaDns implements Dns {
    private static final int MAX_PINNED_HOSTS = 256;

    private final Dns delegate;
    private final String selectedHost;
    private final boolean selectedLocalSource;
    private final String playlistOriginHost;
    private final boolean playlistOriginLocalSource;
    private final Map<String, List<InetAddress>> pinned = new LinkedHashMap<>();

    CastMediaDns(Dns delegate, String selectedHost) {
        this(delegate, selectedHost, null);
    }

    public CastMediaDns(Dns delegate, String selectedHost, String playlistOriginHost) {
        if (delegate == null) throw new IllegalArgumentException("delegate == null");
        this.delegate = delegate;
        this.selectedHost = normalize(selectedHost);
        this.selectedLocalSource = explicitLocalSource(this.selectedHost);
        this.playlistOriginHost = normalize(playlistOriginHost);
        this.playlistOriginLocalSource = explicitLocalSource(this.playlistOriginHost);
    }

    @Override public synchronized List<InetAddress> lookup(String hostname) throws UnknownHostException {
        String host = normalize(hostname);
        List<InetAddress> cached = pinned.get(host);
        if (cached != null) return cached;
        if (host.isEmpty() || pinned.size() >= MAX_PINNED_HOSTS)
            throw new UnknownHostException("Cast media DNS host limit");
        List<InetAddress> resolved = delegate.lookup(hostname);
        ArrayList<InetAddress> accepted = new ArrayList<>();
        boolean selected = host.equals(selectedHost) && selectedLocalSource
                || host.equals(playlistOriginHost) && playlistOriginLocalSource;
        for (InetAddress address : resolved) {
            if (LanCastRelayRules.isPublicAddress(address)
                    || selected && selectedPrivateAddress(host, address))
                accepted.add(address);
        }
        if (accepted.isEmpty()) throw new UnknownHostException("Cast media DNS resolved to a restricted address");
        List<InetAddress> stable = Collections.unmodifiableList(accepted);
        pinned.put(host, stable);
        return stable;
    }

    private static boolean selectedPrivateAddress(String host, InetAddress address) {
        if ("localhost".equals(host)) return address.isLoopbackAddress();
        if (host.endsWith(".local") || host.endsWith(".home.arpa")
                || host.endsWith(".lan"))
            return !address.isLoopbackAddress() && !address.isAnyLocalAddress()
                    && !LanCastRelayRules.isPublicAddress(address);
        // A numeric IP literal cannot be rebound by DNS. OkHttp normally skips Dns for it;
        // this branch keeps the explicit local-source exception when a resolver is consulted.
        return !LanCastRelayRules.isPublicAddress(address);
    }

    private static boolean explicitLocalSource(String host) {
        if ("localhost".equals(host)) return true;
        if (host.endsWith(".local") || host.endsWith(".home.arpa")
                || host.endsWith(".lan")) return true;
        boolean numeric = host.indexOf(':') >= 0
                || host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
        return numeric && LanCastRelayRules.isRestrictedIpLiteral(host);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String host = raw.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
