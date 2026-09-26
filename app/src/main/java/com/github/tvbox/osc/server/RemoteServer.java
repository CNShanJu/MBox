package com.github.tvbox.osc.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.util.Base64;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.SecureRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver;
    private ArrayList < RequestProcess > getRequestList = new ArrayList < > ();
    private ArrayList < RequestProcess > postRequestList = new ArrayList < > ();

    public static String m3u8Content;

    /**
     * 进程级随机管理令牌:局域网侧对 /upload、/delFile、/delFolder、/newFolder、/action
     * 及目录列表等"管理型"请求必须携带(web 控制台页面运行时由 /token.js 注入),
     * 本机(loopback)请求放行 —— 阻断同网段其它设备未授权读写/删除。
     */
    private final String accessToken = generateToken();

    private static final Gson GSON = new Gson();

    private static String generateToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        addGetRequestProcess();
        addPostRequestProcess();
    }

    /**
     * @param hostname 绑定地址:null/空=全部网卡(局域网可达);"127.0.0.1"=仅本机。
     *                 默认走仅本机绑定(本 App 的所有 clan://localhost、/proxy、/purify.m3u8 均在本机回环),
     *                 局域网共享/远程管理需显式开启 HawkConfig.LAN_SERVER_ENABLE。
     */
    public RemoteServer(String hostname, int port, Context context) {
        super(hostname == null || hostname.isEmpty() ? null : hostname, port);
        mContext = context;
        addGetRequestProcess();
        addPostRequestProcess();
    }

    private void addGetRequestProcess() {
        getRequestList.add(new RawRequestProcess(this.mContext, "/", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/style.css", R.raw.style, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/ui.css", R.raw.ui, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/jquery.js", R.raw.jquery, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/script.js", R.raw.script, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/favicon.ico", R.drawable.app_icon, "image/x-icon"));
    }

    private void addPostRequestProcess() {
        postRequestList.add(new InputRequestProcess(this));
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.isEmpty()) {
            return getRequestList.get(0).doResponse(session, "", null, null);
        }
        String fileName = uri.trim();
        if (fileName.indexOf('?') >= 0) {
            fileName = fileName.substring(0, fileName.indexOf('?'));
        }
        // 动态令牌注入:web 控制台页面通过 <script src="/token.js"> 拿到本次进程的
        // 管理令牌,后续所有管理型 AJAX 自动携带;token.js 禁止缓存(服务重启后令牌变化)
        if (session.getMethod() == Method.GET && fileName.equals("/token.js")) {
            Response tokenResponse = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/javascript",
                    "window.TVBOX_TOKEN='" + accessToken + "';\n");
            tokenResponse.addHeader("Cache-Control", "no-store");
            return tokenResponse;
        }
        if (session.getMethod() == Method.GET) {
            for (RequestProcess process : getRequestList) {
                if (process.isRequest(session, fileName)) {
                    return process.doResponse(session, fileName, session.getParms(), null);
                }
            }
            if (fileName.equals("/proxy")) {
                // 仅本机使用(spider/播放器代理转发),不对局域网开放
                if (!isLoopbackRequest(session)) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                }
                Map<String, String> params = session.getParms();
                params.putAll(session.getHeaders());
                params.put("request-headers", GSON.toJson(session.getHeaders()));
                if (params.containsKey("do")) {
                    Object[] rs = com.github.catvod.crawler.SpiderApi.proxyLocal(params);
                    // jar 代理方法缺失/未加载时 proxyLocal 返回 null(还有异常吞掉的情况),
                    // 直接返回错误响应, 避免 rs[0] 读 null 数组崩溃
                    if (rs == null || rs.length < 2) {
                        return NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                                "text/plain",
                                "proxy unavailable");
                    }
                    //if (rs[0] instanceof Response) {
                    //    return (Response) rs[0];
                    //}
                    int code = (int) rs[0];
                    String mime = (String) rs[1];
                    // 越界防护:原实现 rs==null||length<2 只保证 rs[0]/rs[1], 后面却直接读 rs[2];
                    // 长度不足 3 或响应体为 null 时返回空响应体
                    if (rs.length < 3 || !(rs[2] instanceof InputStream)) {
                        return NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.lookup(code), mime, "");
                    }
                    InputStream stream = (InputStream) rs[2];
                    Response response = NanoHTTPD.newChunkedResponse(
                            NanoHTTPD.Response.Status.lookup(code),
                            mime,
                            stream);
                    if (rs.length > 3) {
                        try {
                            HashMap<String, String> headers = (HashMap<String, String>) rs[3];
                            for (String key : headers.keySet()) {
                                response.addHeader(key, headers.get(key));
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                    return response;
                }
            } else if (fileName.equals("/dns-query")) {
                // DoH 转发仅本机使用
                if (!isLoopbackRequest(session)) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                }
                String name = session.getParms().get("name");
                if (name == null || name.trim().isEmpty()) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "missing name");
                }
                byte[] rs = new byte[0];
                try {
                    // okhttp 4 的 DnsOverHttps 不再提供原始 DNS 报文转发,这里改为返回解析结果文本
                    okhttp3.dnsoverhttps.DnsOverHttps doh = OkGoHelper.getDnsOverHttps();
                    if (doh != null) {
                        List<InetAddress> addresses = doh.lookup(name);
                        if (addresses != null && !addresses.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (InetAddress a : addresses) {
                                if (a == null) continue;
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(a.getHostAddress());
                            }
                            rs = sb.toString().getBytes("UTF-8");
                        }
                    }
                } catch (Throwable th) {
                    rs = new byte[0];
                }
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, new ByteArrayInputStream(rs), rs.length);
            } else if (fileName.equals("/purify.m3u8") || fileName.equals("/m3u8")) {
                // 仅本机播放器代理使用(广告过滤后的清单)。
                // 规范路径是 /purify.m3u8:播放器按路径扩展名判定容器类型,没有 .m3u8 后缀的路径
                // 会被 Exo 判成 Progressive 首播失败;/m3u8 保留兼容旧调用方。
                if (!isLoopbackRequest(session)) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                }
                String content = m3u8Content == null ? "" : m3u8Content;
                Response purify = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                        "application/vnd.apple.mpegurl", content);
                // 名单是全局单槽(每次起播覆盖):禁止播放器缓存,避免 seek/重试拿到上一条清单
                purify.addHeader("Cache-Control", "no-store");
                return purify;
            } else if (fileName.startsWith("/file/")) {
                return serveFileGet(session, fileName.substring(6));
            }
        } else if (session.getMethod() == Method.POST) {
            return serveFilePost(session, fileName);
        }
        //default page: index.html
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    /** GET /file/<rel>: 具体文件字节流不鉴权(本机与局域网 clan:// 播放都依赖);目录列表属于管理功能,需令牌 */
    private Response serveFileGet(IHTTPSession session, String rel) {
        try {
            File root = storageRoot();
            File localFile;
            if (rel == null || rel.trim().isEmpty() || rel.equals(".")) {
                localFile = root;
            } else {
                localFile = resolveUnderRoot(rel);
            }
            if (localFile == null) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
            }
            if (localFile.exists()) {
                if (localFile.isFile()) {
                    return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", new FileInputStream(localFile));
                } else {
                    if (!isAuthorized(session, session.getParms())) {
                        return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root.getAbsolutePath(), rel == null ? "" : rel));
                }
            } else {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File not found!");
            }
        } catch (Throwable th) {
            String msg = th.getMessage();
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, msg == null ? "error" : msg);
        }
    }

    /** POST 处理:解析 body 后统一做管理鉴权,再分发到各处理函数 */
    private Response serveFilePost(IHTTPSession session, String fileName) {
        Map<String, String> files = new HashMap<>();
        try {
            if (session.getHeaders().containsKey("content-type")) {
                String hd = session.getHeaders().get("content-type");
                if (hd != null) {
                    // cuke: 修正中文乱码问题
                    if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                        Matcher matcher = Pattern.compile("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                        String boundary = matcher.find() ? matcher.group(1) : null;
                        if (boundary != null) {
                            session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                        }
                    }
                }
            }
            session.parseBody(files);
        } catch (IOException IOExc) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
        } catch (NanoHTTPD.ResponseException rex) {
            return createPlainTextResponse(rex.getStatus(), rex.getMessage());
        }
        Map<String, String> params = session.getParms();
        if (params == null) params = new HashMap<>();
        // 管理/变更类接口统一鉴权:本机(loopback)放行,局域网侧必须携带进程令牌
        if (fileName.equals("/action") || fileName.equals("/upload")
                || fileName.equals("/newFolder") || fileName.equals("/delFolder") || fileName.equals("/delFile")) {
            if (!isAuthorized(session, params)) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
            }
        }
        for (RequestProcess process : postRequestList) {
            if (process.isRequest(session, fileName)) {
                return process.doResponse(session, fileName, params, files);
            }
        }
        try {
            if (fileName.equals("/upload")) {
                return handleUpload(params, files);
            } else if (fileName.equals("/newFolder")) {
                return handleNewFolder(params);
            } else if (fileName.equals("/delFolder")) {
                return handleDelete(params, true);
            } else if (fileName.equals("/delFile")) {
                return handleDelete(params, false);
            }
        } catch (Throwable th) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
        }
        //default page: index.html(与历史行为一致)
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    /** /upload: 目标目录限定在外部存储根目录内,文件名必须为单段,zip 解压带 Zip Slip 防护 */
    private Response handleUpload(Map<String, String> params, Map<String, String> files) throws IOException {
        File destDir = resolveUnderRoot(params.get("path"));
        if (destDir == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "cannot create target dir");
        }
        for (String k : files.keySet()) {
            if (!k.startsWith("files-")) continue;
            String fn = params.get(k);
            String tmpFile = files.get(k);
            if (fn == null || tmpFile == null) continue;
            String safeName = sanitizeFileName(fn);
            if (safeName == null) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid file name: " + fn);
            }
            File tmp = new File(tmpFile);
            File target = resolveUnderRoot(joinRel(params.get("path"), safeName));
            if (target == null) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid target path");
            }
            if (target.exists() && !target.delete()) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "cannot replace " + safeName);
            }
            if (tmp.exists()) {
                if (safeName.toLowerCase().endsWith(".zip")) {
                    unzip(tmp, destDir);
                } else {
                    FileUtils.copyFile(tmp, target);
                }
            }
            if (tmp.exists()) tmp.delete();
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    /** /newFolder: 目录名必须为单段,目标位置限定在根目录内 */
    private Response handleNewFolder(Map<String, String> params) throws IOException {
        String safeName = sanitizeFileName(params.get("name"));
        if (safeName == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid name");
        }
        File parent = resolveUnderRoot(params.get("path"));
        if (parent == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
        }
        File file = new File(parent, safeName);
        if (!file.exists()) {
            file.mkdirs();
            File flag = new File(file, ".tvbox_folder");
            if (!flag.exists()) flag.createNewFile();
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    /** /delFolder|/delFile: 拒绝删除根目录/空路径,且目标必须位于外部存储根目录内 */
    private Response handleDelete(Map<String, String> params, boolean folder) throws IOException {
        String path = params.get("path");
        if (path == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "missing path");
        }
        String p = path.trim();
        if (p.isEmpty() || p.equals("/") || p.equals(".") || p.equals("..")) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "refuse to delete root");
        }
        File target = resolveUnderRoot(p);
        if (target == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
        }
        File rootCanon = storageRoot().getCanonicalFile();
        if (target.equals(rootCanon)) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "refuse to delete root");
        }
        if (target.exists()) {
            if (folder) {
                FileUtils.recursiveDelete(target);
            } else {
                target.delete();
            }
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    /** 本机回环判断:NanoHTTPD 的 getRemoteIpAddress 返回对端地址文本 */
    private boolean isLoopbackRequest(IHTTPSession session) {
        String ip = session.getRemoteIpAddress();
        return ip != null && (ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("127."));
    }

    /** 管理接口鉴权:本机直连放行;局域网请求必须携带与进程令牌一致的 token(参数或 X-TVBox-Token 请求头) */
    private boolean isAuthorized(IHTTPSession session, Map<String, String> params) {
        if (isLoopbackRequest(session)) return true;
        String token = params == null ? null : params.get("token");
        if (token == null) {
            Map<String, String> headers = session.getHeaders();
            if (headers != null) {
                token = headers.get("x-tvbox-token");
            }
        }
        return token != null && accessToken.equals(token);
    }

    /** 外部存储根目录:所有文件类接口的允许范围 */
    private static File storageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    private static String joinRel(String path, String name) {
        if (path == null || path.trim().isEmpty()) return name;
        return path.trim() + "/" + name;
    }

    /** 校验普通文件名:单段、非 . / ..、不含路径分隔符与 NUL,返回清理后的名字;非法返回 null */
    private static String sanitizeFileName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0) return null;
        return n;
    }

    /**
     * 把相对子路径安全地限定在外部存储根目录内。
     * 拒绝:null、空字符;显式拒绝 ".." 目录穿越与绝对路径;
     * canonical 双重校验可防止符号链接等方式逃出根目录。
     * 注意:入参为空字符串时返回根目录自身(调用方需自行决定是否允许)。
     */
    private File resolveUnderRoot(String relPath) {
        if (relPath == null) return null;
        String p = relPath.trim();
        if (p.indexOf('\0') >= 0) return null;
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        File cur = new File(storageRoot().getAbsolutePath());
        String[] parts = p.split("/");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            cur = new File(cur, part);
        }
        try {
            String rootPath = storageRoot().getCanonicalPath();
            String curPath = cur.getCanonicalPath();
            if (!curPath.equals(rootPath) && !curPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return cur;
        } catch (IOException e) {
            return null;
        }
    }

    public void setDataReceiver(DataReceiver receiver) {
        mDataReceiver = receiver;
    }

    public DataReceiver getDataReceiver() {
        return mDataReceiver;
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration < NetworkInterface > enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration < InetAddress > enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    String fileList(String root, String path) {
        File file = new File(root + "/" + path);
        File[] list = file.listFiles();
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", 0);
        if (path.isEmpty()) {
            info.addProperty("parent", ".");
        } else {
            info.addProperty("parent", file.getParentFile().getAbsolutePath().replace(root + "/", "").replace(root, ""));
        }
        if (list == null || list.length == 0) {
            info.add("files", new JsonArray());
            return info.toString();
        }
        Arrays.sort(list, new Comparator < File > () {@Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && o2.isFile()) return -1;
            return o1.isFile() && o2.isDirectory() ? 1 : o1.getName().compareTo(o2.getName());
        }
        });
        JsonArray result = new JsonArray();
        for (File f: list) {
            if (f.getName().startsWith(".")) {
                if (f.getName().equals(".tvbox_folder")) {
                    info.addProperty("del", 1);
                }
                continue;
            }
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("name", f.getName());
            fileObj.addProperty("path", f.getAbsolutePath().replace(root + "/", ""));
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            fileObj.addProperty("dir", f.isDirectory() ? 1 : 0);
            result.add(fileObj);
        }
        info.add("files", result);
        return info.toString();
    }

    /**
     * 解压 ZIP 到目标目录。
     * Zip Slip 防护:逐条目做 canonical 包含性校验,任何通过 ../ 或绝对路径
     * 越出 destDir 的条目一律拒绝(抛 SecurityException,整体失败,不落盘)。
     * ZipFile 与输入流均用 try-with-resources 确保关闭。
     */
    void unzip(File zipFilePath, File destDir) throws IOException {
        if (destDir == null) {
            throw new IOException("null dest dir");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("cannot create dest dir: " + destDir);
        }
        String destCanonical = destDir.getCanonicalPath();
        try (ZipFile zip = new ZipFile(zipFilePath)) {
            Enumeration<? extends ZipEntry> iter = (Enumeration<? extends ZipEntry>) zip.entries();
            while (iter.hasMoreElements()) {
                ZipEntry entry = iter.nextElement();
                String name = entry.getName();
                if (name == null || name.indexOf('\0') >= 0) {
                    throw new SecurityException("Invalid zip entry name");
                }
                File target = new File(destDir, name);
                String targetCanonical = target.getCanonicalPath();
                if (!targetCanonical.equals(destCanonical)
                        && !targetCanonical.startsWith(destCanonical + File.separator)) {
                    throw new SecurityException("Zip entry escapes target directory: " + name);
                }
                if (entry.isDirectory()) {
                    if (!target.exists()) target.mkdirs();
                    File flag = new File(target, ".tvbox_folder");
                    if (!flag.exists()) flag.createNewFile();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream is = zip.getInputStream(entry)) {
                        extractFile(is, target);
                    }
                }
            }
        }
    }

    void extractFile(InputStream inputStream, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (dst.exists() && !dst.delete()) {
            throw new IOException("cannot replace existing file: " + dst);
        }
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] bytesIn = new byte[2048];
            int len;
            while ((len = inputStream.read(bytesIn)) > 0) {
                bos.write(bytesIn, 0, len);
            }
        }
    }

}