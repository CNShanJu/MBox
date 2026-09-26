package com.github.tvbox.osc.spiderapi;

import android.net.Uri;
import android.text.TextUtils;

import java.util.regex.Pattern;

/**
 * 视频地址嗅探工具(无状态):download 等模块经本类判断 URL 是否像可下载的视频地址,
 * 避免它们依赖 :spider 实现内的 DefaultConfig。
 * 说明:判定规则与 spider 的 DefaultConfig.isVideoFormat 保持同一份(两处实现,迁移时同步)。
 */
public final class MediaUrlUtil {

    private static final Pattern SNIFFER_MATCH = Pattern.compile(
            "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a)\\?.*|" +
                    "http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a)|" +
                    "http((?!http).)*?video/tos*|" +
                    "http((?!http).){20,}?/m3u8\\?pt=m3u8.*|" +
                    "http((?!http).)*?default\\.ixigua\\.com/.*|" +
                    "http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*|" +
                    "http.*?/player/m3u8play\\.php\\?url=.*|" +
                    "http.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
                    "http.*?/playlist/m3u8/\\?vid=.*|" +
                    "http.*?\\.php\\?type=m3u8&.*|" +
                    "http.*?/download.aspx\\?.*|" +
                    "http.*?/api/up_api.php\\?.*|" +
                    "https.*?\\.66yk\\.cn.*|" +
                    "http((?!http).)*?netease\\.com/file/.*"
    );

    private MediaUrlUtil() {
    }

    public static boolean isVideoFormat(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        return SNIFFER_MATCH.matcher(url).find();
    }
}
