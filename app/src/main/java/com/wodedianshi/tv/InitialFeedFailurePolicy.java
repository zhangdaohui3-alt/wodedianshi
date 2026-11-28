package com.wodedianshi.tv;

/**
 * Bounds first-build discovery attempts per independently configured feed.
 * A terminal unavailable feed is an inspected outcome, NOT a successful feed.
 * It remains visible in progress diagnostics for future manual/weekly retry.
 */
public final class InitialFeedFailurePolicy {
    private InitialFeedFailurePolicy() {}
    public static final int MAX_FAILURE_ATTEMPTS = 3;

    public static int nextAttempts(int previous) {
        if (previous >= MAX_FAILURE_ATTEMPTS) return MAX_FAILURE_ATTEMPTS;
        return Math.max(0, previous) + 1;
    }

    /** Safe, stable UI reasons: never echo an untrusted response or URL token. */
    public static String userVisibleReason(String message) {
        String reason=message==null?"":message.toLowerCase(java.util.Locale.ROOT);
        if(reason.contains("http 404"))return "HTTP 404：目录不存在";
        if(reason.contains("http 410"))return "HTTP 410：目录已撤销";
        if(reason.contains("http 429"))return "HTTP 429：访问频率受限";
        if(reason.contains("http 401")||reason.contains("http 403"))
            return "目录需要授权或拒绝访问";
        if(reason.contains("timeout")||reason.contains("timed out")
                ||reason.contains("超时"))return "网络连接或读取超时";
        if(reason.contains("ssl")||reason.contains("certificate"))
            return "TLS 或证书验证失败";
        if(reason.contains("m3u")||reason.contains("playlist"))
            return "播放列表格式或解析失败";
        return "网络访问或目录解析失败";
    }

    public static boolean isTerminalUnavailable(String message, int attempts) {
        String reason = message == null ? "" : message;
        // These represent our own scanning limits, not an unavailable provider.
        // Retrying the same full page later is safer than skipping it forever.
        if (reason.contains("本轮候选容量已满") || reason.contains("source candidate capacity"))
            return false;
        // The publisher returned a clear gone response.
        if (reason.contains("HTTP 404") || reason.contains("HTTP 410")) return true;
        return attempts >= MAX_FAILURE_ATTEMPTS;
    }
}
