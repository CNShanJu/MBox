package com.github.tvbox.osc.util;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** A QR handoff for one local MBox server. The host validates it before connecting. */
public final class LanPairQr {
    private static final String SCHEME = "mbox";
    private static final String HOST = "lan-pair";
    private static final int MAX_PAYLOAD_LENGTH = 2048;

    private LanPairQr() { }

    public static final class Pair {
        public final String address;
        public final String code;

        private Pair(String address, String code) {
            this.address = address;
            this.code = code;
        }
    }

    public static String encode(String address, String code) {
        Pair pair = validPair(address, code);
        if (pair == null) throw new IllegalArgumentException("Invalid local server address or pairing code");
        return SCHEME + "://" + HOST + "?address=" + urlEncode(pair.address)
                + "&code=" + urlEncode(pair.code);
    }

    @Nullable
    public static Pair decode(String raw) {
        if (raw == null || raw.length() > MAX_PAYLOAD_LENGTH) return null;
        try {
            URI uri = new URI(raw.trim());
            if (!SCHEME.equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getRawFragment() != null || uri.getRawPath() == null
                    || !uri.getRawPath().isEmpty() || uri.getRawUserInfo() != null) return null;
            String query = uri.getRawQuery();
            if (query == null) return null;
            String address = null;
            String code = null;
            for (String part : query.split("&", -1)) {
                int equals = part.indexOf('=');
                if (equals <= 0) return null;
                String key = part.substring(0, equals);
                String value = URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8.name());
                if ("address".equals(key) && address == null) address = value;
                else if ("code".equals(key) && code == null) code = value;
                else return null;
            }
            return validPair(address, code);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Bitmap bitmap(String payload, int sizePx) throws WriterException {
        if (decode(payload) == null) throw new IllegalArgumentException("Invalid pairing payload");
        int size = Math.max(128, Math.min(sizePx, 1024));
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    @Nullable
    private static Pair validPair(String address, String code) {
        if (address == null || code == null || address.length() > 512 || !code.matches("[0-9]{8}")) return null;
        try {
            URI uri = new URI(address.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !LanAddressRules.isPrivateIpv4(uri.getHost()) || uri.getPort() < 1
                    || uri.getPort() > 65535 || !"/".equals(uri.getRawPath())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) return null;
            return new Pair(address.trim(), code);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
