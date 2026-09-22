package org.bluepowerrobotics.classframe.data;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 时间校准：把"设备时钟错了多久"算成一个秒级偏移，存进 config 的「时间偏移（秒）」。
 *
 * 为什么用偏移而不是改系统时钟：app 没有 WRITE_SETTINGS，也不该动教室机器的系统时间；
 * 现有全部时间逻辑（状态机 / 调度 / 时钟显示）都走"Calendar.getInstance() + 偏移"，
 * 所以只要把偏移算准就能全面生效，改动面最小。
 *
 * 偏好的语义：校准后的时间 = 设备时间 + timeOffsetSeconds（正数 = 设备慢了）。
 * 它与时区无关——NTP / HTTP 给的都是真实 UTC 时刻，差值就是时钟误差本身。
 */
public final class TimeSync {

    private static final String TAG = "TimeSync";
    /** 偏移上限 ±50 年，防止把 Integer.MAX_VALUE 写进去后换算成毫秒时溢出。 */
    private static final long MAX_OFFSET_SECONDS = 50L * 365 * 24 * 3600;

    public interface Callback {
        /** offsetSeconds 为 null 表示失败。 */
        void onResult(Long offsetSeconds, String message);
    }

    private TimeSync() {
    }

    // ------------------------------------------------------------ 时区

    public static TimeZone zone(Context context) {
        try {
            return TimeZone.getTimeZone(Prefs.timeZoneId(context));
        } catch (Exception e) {
            return TimeZone.getTimeZone(Prefs.DEFAULT_TIMEZONE);
        }
    }

    /**
     * 把配置里的时区设为进程默认时区。
     * 所有取时间的地方都是 Calendar.getInstance()，改默认时区即全局生效。
     */
    public static void applyDefaultZone(Context context) {
        try {
            String id = Prefs.timeZoneId(context);
            if (!id.equals(TimeZone.getDefault().getID())) {
                TimeZone.setDefault(TimeZone.getTimeZone(id));
                Logs.i(TAG, "默认时区设为 " + id);
            }
        } catch (Throwable error) {
            Logs.e(TAG, "设置默认时区失败", error);
        }
    }

    public static String[] commonZones() {
        return new String[]{
                "Asia/Shanghai", "Asia/Urumqi", "Asia/Hong_Kong", "Asia/Taipei",
                "Asia/Tokyo", "Asia/Seoul", "Asia/Singapore", "UTC",
                "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles",
        };
    }

    // ------------------------------------------------------------ 计算

    /** 读取当前配置里的偏移；读不到时按 0。 */
    public static int currentOffsetSeconds(Context context) {
        try {
            return ConfigRepository.load(context).timeOffsetSeconds;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 设备时钟现在几点（不校正）。 */
    public static Calendar deviceNow(Context context) {
        Calendar calendar = Calendar.getInstance(zone(context));
        calendar.setTimeInMillis(System.currentTimeMillis());
        return calendar;
    }

    /** 校正后的现在。等价于 OverlayController.now() 的语义。 */
    public static Calendar correctedNow(Context context) {
        Calendar calendar = Calendar.getInstance(zone(context));
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(Calendar.SECOND, currentOffsetSeconds(context));
        return calendar;
    }

    /** 写入偏移（同时写入内存缓存与 config.json）。 */
    public static void applyOffset(Context context, long offsetSeconds) {
        long clamped = Math.max(-MAX_OFFSET_SECONDS, Math.min(MAX_OFFSET_SECONDS, offsetSeconds));
        try {
            // 直接改 config.json 里的那一个字段：与 Python 版、导入导出共用同一份数据，不另立一套
            org.json.JSONObject raw = ConfigRepository.loadRaw(context);
            raw.put("时间偏移（秒）", new org.json.JSONArray().put((int) clamped));
            ConfigRepository.save(context, raw);
            Logs.i(TAG, "时间偏移已写入: " + clamped + " 秒");
        } catch (Exception e) {
            Logs.e(TAG, "写入时间偏移失败", e);
        }
    }

    /** 用"真实当前时刻"覆盖偏移。 */
    public static void applyFromRealNow(Context context, long realNowMillis) {
        long offset = Math.round((realNowMillis - System.currentTimeMillis()) / 1000.0);
        applyOffset(context, offset);
    }

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static String format(long millis, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    public static String describeOffset(int seconds) {
        int abs = Math.abs(seconds);
        int days = abs / 86400;
        int hours = (abs % 86400) / 3600;
        int minutes = (abs % 3600) / 60;
        int rest = abs % 60;
        StringBuilder sb = new StringBuilder();
        sb.append(seconds < 0 ? "设备快了 " : "设备慢了 ").append(abs).append(" 秒");
        if (abs >= 60) {
            sb.append("（");
            if (days > 0) sb.append(days).append(" 天 ");
            if (days > 0 || hours > 0) sb.append(hours).append(" 小时 ");
            sb.append(minutes).append(" 分 ").append(rest).append(" 秒）");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 自动同步

    private static volatile boolean running;
    /** 两次自动同步之间至少间隔多久（启动时、服务启动时都会触发，用它去重）。 */
    private static final long MIN_AUTO_INTERVAL_MS = 10 * 60 * 1000L;
    /** 服务常驻期间的自动重试间隔。 */
    private static final long AUTO_RETRY_INTERVAL_MS = 60 * 60 * 1000L;
    private static volatile long lastAttemptAt;
    private static android.os.Handler autoHandler;

    /**
     * 自动更新的入口（app 启动、服务启动时调用）：
     * 开关关着就不做事，十分钟内重复调用也不重复发请求，失败会在一小时后重试。
     */
    public static synchronized void syncAutoQuietly(Context context) {
        if (!Prefs.autoTime(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < MIN_AUTO_INTERVAL_MS) return;
        lastAttemptAt = now;
        tryAutoSync(context);
        scheduleAutoRetry();
    }

    /**
     * 服务常驻时每小时再试一次。
     * 间隔比"十分钟去重"长得多，所以手动同步或刚启动过的同步不会被打断。
     */
    private static synchronized void scheduleAutoRetry() {
        if (autoHandler == null) {
            autoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        autoHandler.removeCallbacks(retryRunnable);
        autoHandler.postDelayed(retryRunnable, AUTO_RETRY_INTERVAL_MS);
    }

    private static final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            Context context = Logs.context();
            if (context != null && Prefs.autoTime(context)) {
                lastAttemptAt = System.currentTimeMillis();
                tryAutoSync(context);
                scheduleAutoRetry();
            }
        }
    };

    /**
     * 入口：开关打开时才执行，同一个进程内不会并发跑两次。
     * 成功则写回偏移，失败只记日志、保留原值。
     */
    public static void tryAutoSync(final Context context) {
        if (!Prefs.autoTime(context)) return;
        final Context appContext = context.getApplicationContext();
        sync(context, "自动更新", new Callback() {
            @Override
            public void onResult(Long offsetSeconds, String message) {
                if (offsetSeconds == null) {
                    Logs.w(TAG, "自动更新失败: " + message);
                } else {
                    Logs.i(TAG, "自动更新成功: " + message);
                    // 偏移变了要立刻按新时间重排显示/隐藏与下一次唤醒
                    org.bluepowerrobotics.classframe.overlay.OverlayService.refresh(appContext);
                }
            }
        });
    }

    /** 手动触发（界面按钮）。 */
    public static void sync(final Context context, final String reason, final Callback callback) {
        if (running) {
            if (callback != null) callback.onResult(null, "上一次同步还没结束");
            return;
        }
        running = true;
        final Context appContext = context.getApplicationContext();
        final String mode = Prefs.autoTimeMode(appContext);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Long offset = null;
                String message;
                try {
                    long deviceNow = System.currentTimeMillis();
                    long realNow;
                    String used;
                    if ("http".equals(mode)) {
                        HttpResult http = fetchHttp(appContext);
                        realNow = http.millis;
                        used = http.used;
                    } else {
                        realNow = fetchNtp(appContext);
                        used = "ntp://" + Prefs.ntpHost(appContext);
                    }
                    offset = Math.round((realNow - deviceNow) / 1000.0);
                    applyOffset(appContext, offset);
                    message = reason + "成功（" + used + "）："
                            + describeOffset(offset.intValue())
                            + "，校准后 " + format(System.currentTimeMillis() + offset * 1000L,
                            zone(appContext));
                } catch (Exception error) {
                    message = reason + "失败：" + describeError(error);
                    Logs.w(TAG, message);
                }
                running = false;
                if (callback != null) callback.onResult(offset, message);
            }
        }, "time-sync").start();
    }

    private static String describeError(Exception error) {
        String text = error.getClass().getSimpleName();
        String detail = error.getMessage();
        if (detail != null && !detail.isEmpty()) text += ": " + detail;
        return text;
    }

    // ------------------------------------------------------------ NTP

    /** 标准 48 字节 SNTP 客户端，用发送/接收时刻做往返补偿。 */
    static long fetchNtp(Context context) throws Exception {
        String host = Prefs.ntpHost(context).trim();
        int port = 123;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
                host = host.substring(0, colon).trim();
            } catch (NumberFormatException ignored) {
            }
        }

        byte[] request = new byte[48];
        // LI = 0, VN = 4, Mode = 3 (client)
        request[0] = 0x23;

        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(5000);
            InetAddress address = InetAddress.getByName(host);
            long t0 = System.currentTimeMillis();
            socket.send(new DatagramPacket(request, request.length, address, port));

            DatagramPacket response = new DatagramPacket(new byte[48], 48);
            socket.receive(response);
            long t3 = System.currentTimeMillis();

            byte[] data = response.getData();
            long seconds = readUnsignedInt(data, 40);
            long fraction = readUnsignedInt(data, 44);
            if (seconds == 0) throw new IllegalStateException("服务器返回空时间戳");
            long realNow = (seconds - 2208988800L) * 1000L + (fraction * 1000L) / 0x100000000L;

            // 往返补偿：用"服务器时刻 + 单程延迟"估计当前真实时刻
            long roundTrip = Math.max(0, t3 - t0);
            return realNow + roundTrip / 2;
        } finally {
            socket.close();
        }
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    // ------------------------------------------------------------ HTTP

    private static final class HttpResult {
        long millis;
        String used;
    }

    /**
     * 读响应头的 Date（RFC 1123，GMT，秒级精度）。
     * 用户给的是主机/域名 + 端口，很多站点只支持 https（http 会 301），所以失败就换 https 重试；
     * 也允许直接填完整 URL。
     */
    static HttpResult fetchHttp(Context context) throws Exception {
        String host = Prefs.httpTimeHost(context).trim();
        int port = Prefs.httpTimePort(context);
        boolean hasPath = host.contains("/");
        String base = host.startsWith("http://") || host.startsWith("https://")
                ? host
                : "http://" + host + (port == 80 || port == 443 ? "" : ":" + port)
                        + (hasPath ? "/" : "/");

        Exception first = null;
        for (String candidate : candidates(base, host, port)) {
            try {
                HttpResult result = requestDate(candidate);
                if (result != null) return result;
            } catch (Exception error) {
                if (first == null) first = error;
            }
        }
        throw first != null ? first : new IllegalStateException("没有拿到 Date 响应头");
    }

    private static String[] candidates(String base, String host, int port) {
        String http = base;
        String https = base;
        if (base.startsWith("https://")) {
            https = base;
            http = "http://" + base.substring(8);
        } else {
            http = base;
            https = "https://" + base.substring(7);
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return new String[]{base};
        }
        if (http.equals(https)) return new String[]{base};
        return new String[]{http, https};
    }

    private static HttpResult requestDate(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "ClassFrame/1.0");
            int code = connection.getResponseCode();
            String date = connection.getHeaderField("Date");
            if (date == null) {
                // 有些服务器不在 HEAD 上给 Date，用 GET 再试
                connection.disconnect();
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "ClassFrame/1.0");
                code = connection.getResponseCode();
                date = connection.getHeaderField("Date");
                drain(connection);
            }
            if (date == null) {
                throw new IllegalStateException("HTTP " + code + " 没有 Date 头");
            }
            long millis = parseRfc1123(date);
            HttpResult result = new HttpResult();
            result.millis = millis;
            result.used = url + "（HTTP " + code + "）";
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static void drain(HttpURLConnection connection) {
        try {
            InputStream in = connection.getInputStream();
            if (in == null) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > 64 * 1024) break;
            }
            in.close();
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** 严格按 RFC 1123 解析（Date 头格式固定为 GMT）。 */
    static long parseRfc1123(String value) throws Exception {
        SimpleDateFormat format =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return format.parse(value.trim()).getTime();
        } catch (Exception error) {
            throw new IllegalStateException("无法解析 Date 头: " + value);
        }
    }
}
