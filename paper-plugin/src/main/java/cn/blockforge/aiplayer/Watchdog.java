package cn.blockforge.aiplayer;

import java.util.*;

public final class Watchdog {
    private final Map<UUID, Deque<String>> recent = new HashMap<>();
    private static final String[] AD = {"http://", "https://", "www.", "加v", "微信", "qq群", "外挂出售"};
    private static final String[] ABUSE = {"傻逼", "滚蛋", "废物", "智障", "去死", "idiot"};
    public record Verdict(int level, String reason) {}
    public Verdict check(UUID id, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String word : AD) if (lower.contains(word)) return new Verdict(2, "疑似广告");
        for (String word : ABUSE) if (lower.contains(word)) return new Verdict(1, "攻击性用语");
        Deque<String> q = recent.computeIfAbsent(id, k -> new ArrayDeque<>()); q.addLast(lower); while (q.size() > 40) q.removeFirst();
        long same = q.stream().filter(lower::equals).count();
        if (same >= 4) return new Verdict(2, "重复刷屏");
        if (q.size() >= 20) return new Verdict(1, "消息过多");
        return new Verdict(0, "");
    }
}
