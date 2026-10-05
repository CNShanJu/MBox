package com.github.tvbox.osc.subtitle;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Objects;

/** Validates subtitle content before passing it to text subtitle parsers. */
public final class SubtitleInputPolicy {
    private static final int PGS_HEADER_LENGTH = 13;
    private static final String UNSUPPORTED_SUP_MESSAGE =
            "当前不支持 SUP 图片字幕，请选择 SRT 或 ASS 字幕";

    private SubtitleInputPolicy() {
    }

    /**
     * Rejects an actual PGS/SUP packet header, regardless of the file name or source.
     * {@code length} is the number of valid bytes in {@code bytes}, so callers can check
     * the first read buffer without loading an entire subtitle file.
     */
    public static void requireSupportedContent(byte[] bytes, int length)
            throws UnsupportedSubtitleFormatException {
        Objects.requireNonNull(bytes, "bytes");
        if (length < 0 || length > bytes.length) {
            throw new IllegalArgumentException("length must be within bytes");
        }
        if (length < PGS_HEADER_LENGTH || bytes[0] != 'P' || bytes[1] != 'G') {
            return;
        }
        int segmentType = bytes[10] & 0xff;
        if (segmentType == 0x14 || segmentType == 0x15 || segmentType == 0x16
                || segmentType == 0x17 || segmentType == 0x80) {
            throw new UnsupportedSubtitleFormatException(UNSUPPORTED_SUP_MESSAGE);
        }
    }

    /**
     * Peeks at the first PGS header without consuming the caller's buffered stream.
     * This can be used before copying or parsing a local or remote subtitle.
     */
    public static void requireSupportedContent(BufferedInputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        input.mark(PGS_HEADER_LENGTH);
        byte[] header = new byte[PGS_HEADER_LENGTH];
        int length = 0;
        try {
            while (length < header.length) {
                int next = input.read();
                if (next == -1) break;
                header[length++] = (byte) next;
            }
            requireSupportedContent(header, length);
        } finally {
            input.reset();
        }
    }

    /** A recognized subtitle format that this text subtitle pipeline cannot render. */
    public static final class UnsupportedSubtitleFormatException extends IOException {
        public UnsupportedSubtitleFormatException(String message) {
            super(message);
        }
    }
}
