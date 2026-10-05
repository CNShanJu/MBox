package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SubtitleInputPolicyTest {
    // First 32 bytes of a real SUP file, captured without copying the 25 MB file.
    private static final byte[] REAL_SUP_HEADER = {
            0x50, 0x47, 0x00, 0x01, 0x5f, (byte) 0xea, 0x00, 0x01,
            0x47, (byte) 0xe5, 0x16, 0x00, 0x13, 0x07, (byte) 0x80, 0x04,
            0x38, 0x10, 0x00, 0x00, (byte) 0x80, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x00, 0x03, 0x70, 0x00, (byte) 0x90
    };

    @Test
    public void rejectsRealSupHeaderWithClearMessage() {
        assertUnsupported(REAL_SUP_HEADER, REAL_SUP_HEADER.length);
    }

    @Test
    public void detectsSupEvenIfBytesArePresentedAsRenamedTextFile() {
        byte[] renamedSrtContent = Arrays.copyOf(REAL_SUP_HEADER, REAL_SUP_HEADER.length);
        assertUnsupported(renamedSrtContent, renamedSrtContent.length);
    }

    @Test
    public void acceptsEachRecognizedPgsSegmentTypeForDetection() {
        for (int type : new int[]{0x14, 0x15, 0x16, 0x17, 0x80}) {
            byte[] bytes = Arrays.copyOf(REAL_SUP_HEADER, REAL_SUP_HEADER.length);
            bytes[10] = (byte) type;
            assertUnsupported(bytes, bytes.length);
        }
    }

    @Test
    public void allowsOrdinaryTextStartingWithPgAndUtf8Bom() throws Exception {
        byte[] startsWithPg = "PG subtitle text\n00:00:01,000 --> 00:00:02,000"
                .getBytes(StandardCharsets.UTF_8);
        SubtitleInputPolicy.requireSupportedContent(startsWithPg, startsWithPg.length);

        byte[] utf8Bom = {
                (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'P', 'G',
                '1', '2', '3', '4', '5', 0x16, 'x', 'y'
        };
        SubtitleInputPolicy.requireSupportedContent(utf8Bom, utf8Bom.length);
    }

    @Test
    public void allowsShortInputAndHonorsValidLength() throws Exception {
        SubtitleInputPolicy.requireSupportedContent(new byte[0], 0);
        SubtitleInputPolicy.requireSupportedContent(new byte[]{'P', 'G'}, 2);
        SubtitleInputPolicy.requireSupportedContent(REAL_SUP_HEADER, 12);
        assertUnsupported(REAL_SUP_HEADER, 13);
    }

    @Test
    public void rejectsInvalidLengthInsteadOfInspectingOutOfBoundsBytes() throws Exception {
        try {
            SubtitleInputPolicy.requireSupportedContent(REAL_SUP_HEADER, -1);
            fail("Negative length should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            SubtitleInputPolicy.requireSupportedContent(REAL_SUP_HEADER, REAL_SUP_HEADER.length + 1);
            fail("Length beyond the buffer should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            SubtitleInputPolicy.requireSupportedContent(null, 0);
            fail("Null input should be rejected");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void bufferedInputHandlesShortReadsAndPreservesText() throws Exception {
        byte[] text = "PG subtitle text\n1\n00:00:01,000 --> 00:00:02,000"
                .getBytes(StandardCharsets.UTF_8);
        InputStream shortReads = new ByteArrayInputStream(text) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
        BufferedInputStream input = new BufferedInputStream(shortReads, 3);
        SubtitleInputPolicy.requireSupportedContent(input);
        assertArrayEquals(text, readAll(input));
    }

    @Test
    public void bufferedInputPreservesBomAndShortContent() throws Exception {
        byte[] bomText = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '1', '\n', 'H', 'i'};
        BufferedInputStream bomInput = new BufferedInputStream(new ByteArrayInputStream(bomText));
        SubtitleInputPolicy.requireSupportedContent(bomInput);
        assertArrayEquals(bomText, readAll(bomInput));

        byte[] shortText = {'P', 'G'};
        BufferedInputStream shortInput = new BufferedInputStream(new ByteArrayInputStream(shortText));
        SubtitleInputPolicy.requireSupportedContent(shortInput);
        assertArrayEquals(shortText, readAll(shortInput));
    }

    @Test
    public void bufferedInputRejectsSupAndResetsAfterException() throws Exception {
        BufferedInputStream input = new BufferedInputStream(
                new ByteArrayInputStream(REAL_SUP_HEADER), 4);
        try {
            SubtitleInputPolicy.requireSupportedContent(input);
            fail("PGS/SUP content should be rejected");
        } catch (SubtitleInputPolicy.UnsupportedSubtitleFormatException expected) {
            assertEquals("当前不支持 SUP 图片字幕，请选择 SRT 或 ASS 字幕", expected.getMessage());
        }
        assertArrayEquals(REAL_SUP_HEADER, readAll(input));
    }

    @Test
    public void bufferedInputRejectsNull() throws Exception {
        try {
            SubtitleInputPolicy.requireSupportedContent((BufferedInputStream) null);
            fail("Null stream should be rejected");
        } catch (NullPointerException expected) {
            assertEquals("input", expected.getMessage());
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void assertUnsupported(byte[] bytes, int length) {
        try {
            SubtitleInputPolicy.requireSupportedContent(bytes, length);
            fail("PGS/SUP content should be rejected");
        } catch (SubtitleInputPolicy.UnsupportedSubtitleFormatException expected) {
            assertTrue(expected instanceof IOException);
            assertEquals("当前不支持 SUP 图片字幕，请选择 SRT 或 ASS 字幕", expected.getMessage());
        }
    }
}
