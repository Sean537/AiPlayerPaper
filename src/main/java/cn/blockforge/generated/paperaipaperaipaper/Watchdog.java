package cn.blockforge.generated.paperaipaperaipaper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 秩序维护：检测刷屏、广告、辱骂、作弊暗示，返回建议处罚等级（0=无，1=提醒，2=禁言，3=踢出，4=临时封禁）。 */
public final class Watchdog {
    private static final String[] AD_HINTS = {"http://", "https://", "www.", ".top", "加v", "加V", "微信", "qq群", "代理", "低价充值", "白嫖", "外挂出售", "折扣码"};
    private static final String[] CHEAT_HINTS = {"透视", "自瞄", "炸服", "刷物品", "开挂", "x-ray", "killaura", "client hack", "hacked client"};
    private static final String[] ABUSE_HINTS = {"傻逼", "滚蛋", "废物", "智障", "去死", "sb ", " idiot", "idiot"};
    private final Map<UUID, Deque<String>> recent = new HashMap<>();

    public record Verdict(int level, String reason) {
        public static final Verdict CLEAN = new Verdict(0, "");
    }

    public Verdict verdict(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String w : CHEAT_HINTS) if (lower.contains(w)) return new Verdict(2, "疑似作弊言论（" + w + "）");
        for (String w : AD_HINTS) if (lower.contains(w)) return new Verdict(2, "疑似广告信息（" + w + "）");
        for (String w : ABUSE_HINTS) if (lower.contains(w)) return new Verdict(1, "攻击性用语（" + w + "）");
        return Verdict.CLEAN;
    }

    /** 刷屏检测：60 秒窗口内相同消息 >=3 次或总消息 >=20 条。 */
    public Verdict flood(UUID id, String message) {
        Deque<String> q = recent.computeIfAbsent(id, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        q.addLast(message.toLowerCase(Locale.ROOT));
        while (q.size() > 40) q.removeFirst();
        int same = 0;
        for (String s : q) if (s.equals(message.toLowerCase(Locale.ROOT))) same++;
        if (same >= 4) return new Verdict(2, "重复刷屏（相同消息 " + same + " 次）");
        if (q.size() >= 20) return new Verdict(1, "短时间内消息过多");
        return Verdict.CLEAN;
    }

    /** 分级动作：1 提醒，2 禁言 5 分钟，3 踢出，4 临时封禁。 */
    public String actionFor(int level) {
        return switch (level) {
            case 1 -> "提醒";
            case 2 -> "禁言 5 分钟";
            case 3 -> "踢出";
            case 4 -> "临时封禁";
            default -> "无";
        };
    }
}
