package com.github.tvbox.osc.cast;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Pure protocol and input-validation rules for the DLNA control point. */
final class DlnaRules {
    static final String RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:";
    static final String TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:";

    private DlnaRules() { }

    static final class Advert {
        final URI location;
        final String usn;
        final boolean renderer;

        Advert(URI location, String usn, boolean renderer) {
            this.location = location;
            this.usn = usn;
            this.renderer = renderer;
        }
    }

    /** Keep general UPnP replies from displacing MediaRenderer replies on busy networks. */
    static final class AdvertCandidates {
        private final LinkedHashMap<String, Advert> renderers = new LinkedHashMap<>();
        private final LinkedHashMap<String, Advert> general = new LinkedHashMap<>();
        private final int maxEach;

        AdvertCandidates(int maxEach) { this.maxEach = maxEach; }

        void add(Advert advert) {
            if (advert == null) return;
            String key = advert.location.toString();
            if (advert.renderer) {
                if (!renderers.containsKey(key) && renderers.size() < maxEach) {
                    general.remove(key);
                    renderers.put(key, advert);
                }
            } else if (!renderers.containsKey(key) && !general.containsKey(key)
                    && general.size() < maxEach) {
                general.put(key, advert);
            }
        }

        int size() { return renderers.size() + general.size(); }

        List<Advert> ordered() {
            ArrayList<Advert> result = new ArrayList<>(size());
            result.addAll(renderers.values());
            result.addAll(general.values());
            return result;
        }
    }

    static final class Description {
        final String id;
        final String name;
        final URI control;
        final String serviceType;

        Description(String id, String name, URI control, String serviceType) {
            this.id = id;
            this.name = name;
            this.control = control;
            this.serviceType = serviceType;
        }
    }

    static Advert parseAdvert(String packet, InetAddress sender) {
        if (packet == null || sender == null || !isLanAddress(sender)) return null;
        String[] lines = packet.split("\\r?\\n");
        if (lines.length == 0) return null;
        boolean response = lines[0].matches("(?i)^HTTP/1\\.[01] 200(?: .*)?$");
        boolean notify = lines[0].matches("(?i)^NOTIFY \\* HTTP/1\\.[01]$");
        if (!response && !notify) return null;
        String location = null;
        String st = null;
        String nt = null;
        String nts = null;
        String usn = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1) continue;
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (value.length() > 2048) return null;
            if ("location".equals(key)) location = value;
            else if ("st".equals(key)) st = value;
            else if ("nt".equals(key)) nt = value;
            else if ("nts".equals(key)) nts = value;
            else if ("usn".equals(key)) usn = value;
        }
        String advertisedType = notify ? nt : st;
        if (notify && !"ssdp:alive".equalsIgnoreCase(nts)) return null;
        if (advertisedType == null || advertisedType.length() > 256) return null;
        boolean renderer = isVersionedType(advertisedType, RENDERER);
        if (!renderer
                && !"upnp:rootdevice".equalsIgnoreCase(advertisedType)
                && !"ssdp:all".equalsIgnoreCase(advertisedType)
                && !advertisedType.toLowerCase(Locale.ROOT).startsWith("uuid:"))
            return null;
        if (location == null) return null;
        try {
            URI uri = new URI(location);
            if (!isHttp(uri) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !matchesSender(uri.getHost(), sender)) return null;
            return new Advert(uri, usn == null ? "" : usn, renderer);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Only a literal private IPv4 receiver can be contacted through manual discovery. */
    static List<URI> manualLocations(String raw) {
        ArrayList<URI> locations = new ArrayList<>();
        if (raw == null) return locations;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 160) return locations;
        try {
            URI uri = new URI(value.startsWith("http://") ? value : "http://" + value);
            String host = uri.getHost();
            int port = uri.getPort();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
                    || port < 1 || port > 65535 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return locations;
            InetAddress address = InetAddress.getByName(host);
            if (!host.equals(address.getHostAddress()) || !isLanAddress(address)) return locations;
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                if (!path.startsWith("/") || uri.getPath().contains("..")) return locations;
                locations.add(uri);
            } else {
                locations.add(new URI("http", null, host, port, "/dlna/desc.xml", null, null));
                locations.add(new URI("http", null, host, port, "/description.xml", null, null));
            }
        } catch (Exception ignored) { }
        return locations;
    }

    static Description parseDescription(byte[] xml, URI location, String usn) throws Exception {
        if (xml == null || xml.length == 0 || xml.length > 128 * 1024 || location == null) return null;
        // Android's DocumentBuilderFactory supports only the namespace/validation JAXP features.
        // Decode strictly and reject DTDs before handing text to its KXml-backed DOM parser:
        // a Reader prevents the parser from reopening any attacker-supplied system identifier.
        String source = decodeXml(xml);
        String upper = source.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw new IllegalArgumentException("DTD is not allowed");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = builder.parse(new InputSource(new StringReader(source)));
        Element root = document.getDocumentElement();
        if (root == null) return null;
        URI base = location;
        String urlBase = directText(root, "URLBase");
        if (urlBase != null && !urlBase.isEmpty()) {
            URI declared = new URI(urlBase);
            if (!sameOrigin(location, declared)) return null;
            base = declared;
        }
        NodeList nodes = document.getElementsByTagNameNS("*", "device");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element device = (Element) nodes.item(i);
            if (!isVersionedType(directText(device, "deviceType"), RENDERER)) continue;
            Element services = directChild(device, "serviceList");
            if (services == null) continue;
            NodeList children = services.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node node = children.item(j);
                if (!(node instanceof Element) || !"service".equals(localName(node))) continue;
                Element service = (Element) node;
                String type = directText(service, "serviceType");
                String path = directText(service, "controlURL");
                if (!isVersionedType(type, TRANSPORT) || path == null || path.isEmpty()) continue;
                URI control = base.resolve(new URI(path)).normalize();
                if (!sameOrigin(location, control) || control.getRawFragment() != null
                        || control.getRawUserInfo() != null) continue;
                String id = directText(device, "UDN");
                if (id == null || id.isEmpty()) id = usn;
                if (id == null || id.isEmpty()) id = control.toString();
                String name = directText(device, "friendlyName");
                if (name == null || name.isEmpty()) name = "DLNA 电视";
                return new Description(clean(id, 256), clean(name, 80), control, type);
            }
        }
        return null;
    }

    static URI mediaUri(String raw) {
        if (raw == null || raw.length() > 8192) return null;
        try {
            URI uri = new URI(raw.trim());
            if (!isHttp(uri) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
            String host = uri.getHost();
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(lowerHost) || "localhost.".equals(lowerHost)
                    || lowerHost.startsWith("127.") || "::1".equals(lowerHost)
                    || "[::1]".equals(lowerHost)
                    || "0:0:0:0:0:0:0:1".equals(lowerHost)
                    || "[0:0:0:0:0:0:0:1]".equals(lowerHost)
                    || "0.0.0.0".equals(lowerHost)) return null;
            return uri;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String soap(String serviceType, String action, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + inner + "</u:" + action + "></s:Body></s:Envelope>";
    }

    static String metadata(String title, URI media) {
        String safeTitle = title == null || title.trim().isEmpty() ? "视频" : clean(title, 256);
        String path = media.getPath() == null ? "" : media.getPath().toLowerCase(Locale.ROOT);
        String mime = path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                : path.endsWith(".mkv") ? "video/x-matroska"
                : path.endsWith(".ts") ? "video/mp2t"
                : path.endsWith(".webm") ? "video/webm"
                : path.endsWith(".avi") ? "video/x-msvideo"
                : path.endsWith(".flv") ? "video/x-flv"
                : path.endsWith(".mp4") || path.endsWith(".m4v") ? "video/mp4" : "*";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>"
                + escape(safeTitle) + "</dc:title><upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + escape(media.toString())
                + "</res></item></DIDL-Lite>";
    }

    static String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String seekTarget(long positionMs) {
        long seconds = Math.max(0, positionMs) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    private static String clean(String raw, int max) {
        String text = raw.replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String decodeXml(byte[] data) throws CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xff) == 0xef && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf) {
            offset = 3;
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xfe && (data[1] & 0xff) == 0xff) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (data.length >= 4 && data[0] == 0 && data[1] == '<') {
            charset = StandardCharsets.UTF_16BE;
        } else if (data.length >= 4 && data[0] == '<' && data[1] == 0) {
            charset = StandardCharsets.UTF_16LE;
        }
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
    }

    private static boolean isVersionedType(String value, String prefix) {
        if (value == null || value.length() <= prefix.length() || value.length() > 128
                || !value.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        for (int i = prefix.length(); i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getPort() <= 65535;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return isHttp(a) && isHttp(b) && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean matchesSender(String host, InetAddress sender) {
        if (host == null || !host.matches("[0-9.]{7,15}")) return false;
        try {
            return InetAddress.getByName(host).equals(sender);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLanAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || first == 100 && second >= 64 && second <= 127;
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element && name.equals(localName(node))) return (Element) node;
        }
        return null;
    }

    private static String directText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) return local;
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
