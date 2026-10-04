package com.github.tvbox.osc.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

public class DetailDownloadStyleTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void downloadHasNoRippleButKeepsItsStyleAndTouchArea() throws Exception {
        File file = new File("src/main/res/layout/activity_detail.xml");
        if (!file.isFile()) file = new File("app", file.getPath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("*");
        Element download = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if ("@+id/tvDownload".equals(element.getAttributeNS(ANDROID, "id"))) {
                download = element;
                break;
            }
        }
        if (download == null) throw new AssertionError("Missing download button");
        assertEquals("@null", download.getAttributeNS(ANDROID, "background"));
        assertFalse(download.hasAttributeNS(ANDROID, "foreground"));
        assertEquals("@style/TextButton", download.getAttribute("style"));
        assertEquals("@dimen/dp_40", download.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("10dp", download.getAttributeNS(ANDROID, "paddingHorizontal"));
        assertEquals("@drawable/ic_download_18", download.getAttributeNS(ANDROID, "drawableRight"));
    }
}
