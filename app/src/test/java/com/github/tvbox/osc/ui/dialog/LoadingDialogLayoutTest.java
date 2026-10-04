package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

public class LoadingDialogLayoutTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private static Document layout(String name) throws Exception {
        File file = new File("src/main/res/layout/" + name + ".xml");
        if (!file.isFile()) file = new File("app", file.getPath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Element view(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (("@+id/" + id).equals(element.getAttributeNS(ANDROID, "id"))) return element;
        }
        throw new AssertionError("Missing view: " + id);
    }

    @Test
    public void animationAndStatusShareOneStableThemePanel() throws Exception {
        Document document = layout("dialog_loading");
        Element panel = document.getDocumentElement();
        Element status = view(document, "loading_status_panel");
        assertEquals("280dp", panel.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@drawable/bg_dialog", panel.getAttributeNS(ANDROID, "background"));
        assertSame(panel, view(document, "lottie_loading").getParentNode());
        assertSame(panel, status.getParentNode());
        assertEquals("", status.getAttributeNS(ANDROID, "background"));
        assertSame(status, view(document, "tv_loading_msg").getParentNode());
        assertSame(status, view(document, "btn_loading_cancel").getParentNode());
    }

    @Test
    public void longHintsWrapWithinThePanelWithoutTruncation() throws Exception {
        Element message = view(layout("dialog_loading"), "tv_loading_msg");
        assertEquals("match_parent", message.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("wrap_content", message.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("", message.getAttributeNS(ANDROID, "singleLine"));
        assertEquals("", message.getAttributeNS(ANDROID, "ellipsize"));
        assertEquals("@color/text_foreground", message.getAttributeNS(ANDROID, "textColor"));
    }

    @Test
    public void cancelUsesTheExistingDialogButtonStyleAndHeight() throws Exception {
        Element cancel = view(layout("dialog_loading"), "btn_loading_cancel");
        Element confirmCancel = view(layout("dialog_confirm"), "tv_cancel");
        assertEquals(confirmCancel.getAttribute("style"), cancel.getAttribute("style"));
        assertEquals(confirmCancel.getAttributeNS(ANDROID, "layout_height"),
                cancel.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("gone", cancel.getAttributeNS(ANDROID, "visibility"));
    }
}
