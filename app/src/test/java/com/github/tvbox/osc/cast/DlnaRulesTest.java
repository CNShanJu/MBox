package com.github.tvbox.osc.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class DlnaRulesTest {
    private static final URI LOCATION = URI.create("http://192.168.1.10:1400/xml/device.xml");

    @Test public void acceptsRendererOnlyFromItsAdvertisedLanAddress() throws Exception {
        String response = "HTTP/1.1 200 OK\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "LOCATION: " + LOCATION + "\r\n"
                + "USN: uuid:tv-123::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
        DlnaRules.Advert advert = DlnaRules.parseAdvert(response, InetAddress.getByName("192.168.1.10"));
        assertNotNull(advert);
        assertEquals(LOCATION, advert.location);
        assertNull(DlnaRules.parseAdvert(response, InetAddress.getByName("192.168.1.11")));
        assertNull(DlnaRules.parseAdvert(response.replace("MediaRenderer", "MediaServer"),
                InetAddress.getByName("192.168.1.10")));
        assertNull(DlnaRules.parseAdvert(response.replace("192.168.1.10", "127.0.0.1"),
                InetAddress.getByName("127.0.0.1")));
    }

    @Test public void acceptsGeneralSearchAndAliveAnnouncementsBeforeDescriptionFilter() throws Exception {
        InetAddress sender = InetAddress.getByName("192.168.1.10");
        String general = "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n"
                + "LOCATION: " + LOCATION + "\r\nUSN: uuid:gdlna::upnp:rootdevice\r\n\r\n";
        assertNotNull(DlnaRules.parseAdvert(general, sender));
        assertNotNull(DlnaRules.parseAdvert(general.replace("upnp:rootdevice", "ssdp:all"), sender));
        String notify = "NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "NTS: ssdp:alive\r\nLOCATION: " + LOCATION + "\r\nUSN: uuid:gdlna\r\n\r\n";
        assertNotNull(DlnaRules.parseAdvert(notify, sender));
        assertNull(DlnaRules.parseAdvert(notify.replace("ssdp:alive", "ssdp:byebye"), sender));
        assertNull(DlnaRules.parseAdvert(notify, InetAddress.getByName("192.168.1.11")));
    }

    @Test public void rendererReplySurvivesFullGeneralCandidateList() {
        DlnaRules.AdvertCandidates candidates = new DlnaRules.AdvertCandidates(2);
        URI genericA = URI.create("http://192.168.1.11:8181/description.xml");
        URI genericB = URI.create("http://192.168.1.12:8181/description.xml");
        URI renderer = URI.create("http://192.168.1.10:8181/dlna/desc.xml");
        candidates.add(new DlnaRules.Advert(genericA, "a", false));
        candidates.add(new DlnaRules.Advert(genericB, "b", false));
        candidates.add(new DlnaRules.Advert(renderer, "gdlna", true));
        assertEquals(3, candidates.size());
        assertEquals(renderer, candidates.ordered().get(0).location);

        // A later renderer-specific reply upgrades a general reply at the same LOCATION.
        candidates.add(new DlnaRules.Advert(genericA, "a-renderer", true));
        assertEquals(3, candidates.size());
        assertEquals(genericA, candidates.ordered().get(1).location);
    }

    @Test public void manualReceiverAddressUsesLocalDescriptionsOnly() {
        assertEquals(URI.create("http://192.168.1.10:8181/dlna/desc.xml"),
                DlnaRules.manualLocations("192.168.1.10:8181").get(0));
        assertEquals(URI.create("http://192.168.1.10:8181/description.xml"),
                DlnaRules.manualLocations("192.168.1.10:8181").get(1));
        assertEquals(1, DlnaRules.manualLocations(
                "http://192.168.1.10:8181/custom/device.xml").size());
        assertTrue(DlnaRules.manualLocations("8.8.8.8:8181").isEmpty());
        assertTrue(DlnaRules.manualLocations("127.0.0.1:8181").isEmpty());
        assertTrue(DlnaRules.manualLocations("example.com:8181").isEmpty());
        assertTrue(DlnaRules.manualLocations("192.168.1.10").isEmpty());
        assertTrue(DlnaRules.manualLocations("http://192.168.1.10:8181/desc.xml?token=x").isEmpty());
        assertTrue(DlnaRules.manualLocations("http://user@192.168.1.10:8181/desc.xml").isEmpty());
    }

    @Test public void acceptsAvTransportControlOnDescriptionOrigin() throws Exception {
        DlnaRules.Description description = DlnaRules.parseDescription(xml("/MediaRenderer/AVTransport/Control"),
                LOCATION, "uuid:advertised-tv");
        assertNotNull(description);
        assertEquals("uuid:tv-123", description.id);
        assertEquals("Living Room TV", description.name);
        assertEquals(URI.create("http://192.168.1.10:1400/MediaRenderer/AVTransport/Control"),
                description.control);
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", description.serviceType);
    }

    @Test public void rejectsForeignOrCrossPortControlUrls() throws Exception {
        assertNull(DlnaRules.parseDescription(xml("http://example.org/control"), LOCATION, ""));
        assertNull(DlnaRules.parseDescription(xml("http://192.168.1.10:1499/control"), LOCATION, ""));
        assertNull(DlnaRules.parseDescription(xml("http://127.0.0.1:1400/control"), LOCATION, ""));
    }

    @Test public void rejectsDocTypeRatherThanResolvingEntities() throws Exception {
        byte[] hostile = ("<!DOCTYPE root [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]>"
                + "<root><device><friendlyName>&leak;</friendlyName></device></root>")
                .getBytes(StandardCharsets.UTF_8);
        boolean rejected = false;
        try {
            DlnaRules.parseDescription(hostile, LOCATION, "");
        } catch (Exception expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test public void buildsEscapedMetadataAndRejectsLoopbackMedia() {
        URI media = DlnaRules.mediaUri("https://example.org/movie.mp4?token=a&other=b");
        assertNotNull(media);
        assertNull(DlnaRules.mediaUri("http://127.0.0.1:9978/proxy?do=video"));
        assertNull(DlnaRules.mediaUri("http://[::1]:9978/proxy?do=video"));
        assertNull(DlnaRules.mediaUri("file:///sdcard/movie.mp4"));
        String metadata = DlnaRules.metadata("A&B <Episode>", media);
        assertTrue(metadata.contains("A&amp;B &lt;Episode&gt;"));
        assertTrue(metadata.contains("token=a&amp;other=b"));
        assertFalse(metadata.contains("<dc:title>A&B"));
        assertTrue(DlnaRules.metadata("unknown", URI.create("http://192.168.1.10/cast/film.bin"))
                .contains("protocolInfo=\"http-get:*:*:*\""));
    }

    @Test public void seekTargetUsesElapsedHoursMinutesAndSeconds() {
        assertEquals("00:00:00", DlnaRules.seekTarget(0));
        assertEquals("00:00:00", DlnaRules.seekTarget(-1000));
        assertEquals("01:02:03", DlnaRules.seekTarget(3_723_999));
        assertEquals("27:00:00", DlnaRules.seekTarget(97_200_000));
    }

    private static byte[] xml(String controlUrl) {
        String data = "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>Living Room TV</friendlyName><UDN>uuid:tv-123</UDN>"
                + "<serviceList><service>"
                + "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>" + controlUrl + "</controlURL>"
                + "</service></serviceList></device></root>";
        return data.getBytes(StandardCharsets.UTF_8);
    }
}
