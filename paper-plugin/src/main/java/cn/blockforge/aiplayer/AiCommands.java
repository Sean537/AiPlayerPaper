package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public final class AiCommands implements CommandExecutor, TabCompleter {
    private final AiPlayerPlugin plugin;
    public AiCommands(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { sender.sendMessage(plugin.helpText()); return true; }
        // diagnose / report / say 要放给普通玩家：聊天框看不见的时候，正是被影响的那个玩家在报告看得见哪几条
        Set<String> free = Set.of("help", "tell", "status", "delivery");
        boolean needsAdmin = !free.contains(args[0].toLowerCase(Locale.ROOT));
        if (needsAdmin && !sender.hasPermission("aiplayer.admin")) return fail(sender, "你需要管理员权限（aiplayer.admin）。");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> sender.sendMessage(plugin.statusText());
            case "reload" -> { plugin.reloadPlugin(); sender.sendMessage("§a配置、NPC 与行为任务已重新加载。"); }
            case "tell" -> { if (args.length < 2) return fail(sender, "用法：/aiplayer tell <消息>"); plugin.askAi(sender, join(args, 1), "private"); }
            case "say" -> {
                if (args.length < 2) return fail(sender, "用法：/aiplayer say <文字>（让它说这句话，并回报到底有没有送到聊天框）");
                plugin.delivery().selfTest(sender, join(args, 1));
            }
            case "delivery" -> delivery(sender, args);
            case "confirm" -> { if (!plugin.confirm(sender)) fail(sender, "没有待确认的操作。"); }
            case "deny" -> { if (!plugin.deny(sender)) fail(sender, "没有待取消的操作。"); }
            case "source" -> source(sender, args);
            case "channel" -> channel(sender, args);
            case "persona" -> persona(sender, args);
            case "memory" -> memory(sender, args);
            case "skill" -> skill(sender, args);
            case "punish" -> punish(sender, args);
            case "npc" -> npc(sender, args);
            case "brain" -> brain(sender, args);
            case "home" -> home(sender, args);
            case "autocmd" -> autocmd(sender, args);
            case "secret" -> secret(sender, args);
            case "gather" -> gather(sender, args);
            case "build" -> build(sender, args);
            default -> fail(sender, "未知子命令。用 /aiplayer help 查看帮助。");
        }
        return true;
    }

    private void source(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                Map<String, AiPlayerPlugin.ApiSource> sources = plugin.apiSources();
                if (sources.isEmpty()) { fail(sender, "尚未登记任何 API 源。"); return; }
                StringBuilder b = new StringBuilder("§bAPI 源：\n");
                sources.forEach((id, s) -> b.append("§7 ").append(id).append(" [").append(s.type()).append("] ")
                    .append(s.baseUrl()).append(" model=").append(s.model())
                    .append(s.enabled() ? "（启用）" : "（停用）").append('\n'));
                sender.sendMessage(b.toString());
            }
            case "add" -> {
                if (args.length < 6) { fail(sender, "用法：/aiplayer source add <id> <openai|openai_compatible|anthropic|gemini|deepseek|ollama> <模型名> <接口地址>"); return; }
                String type = args[3].toLowerCase(Locale.ROOT);
                if (!Set.of("openai", "openai_compatible", "anthropic", "gemini", "deepseek", "ollama").contains(type)) {
                    fail(sender, "类型必须是 openai/openai_compatible/anthropic/gemini/deepseek/ollama。"); return;
                }
                plugin.setSource(args[2], type, args[5], args[4]);
                sender.sendMessage("§a已登记源 " + args[2] + "，继续用 /aiplayer source key " + args[2] + " <密钥> 设置密钥。");
            }
            case "key" -> {
                if (args.length < 4) return;
                if (plugin.apiSources().get(args[2]) == null) { fail(sender, "源不存在：" + args[2]); return; }
                plugin.setSourceKey(args[2], args[3]);
                plugin.punishments().audit("CONFIG source key set " + args[2] + " by " + sender.getName());
                sender.sendMessage("§a密钥已加密保存，并启用源 " + args[2] + "。");
            }
            case "enable" -> { if (args.length < 3) return; plugin.setSourceEnabled(args[2], true); sender.sendMessage("§a源 " + args[2] + " 已启用。"); }
            case "disable" -> { if (args.length < 3) return; plugin.setSourceEnabled(args[2], false); sender.sendMessage("源 " + args[2] + " 已停用。"); }
            case "remove" -> { if (args.length < 3) return; plugin.removeSource(args[2]); sender.sendMessage("已删除源 " + args[2] + "。"); }
            case "test" -> plugin.testApi(sender, args.length >= 3 ? join(args, 2) : "你好，请只回复四个字：连通正常。");
            default -> fail(sender, "用法：source list|add|key|enable|disable|remove|test");
        }
    }

    private void channel(CommandSender sender, String[] args) {
        if (args.length < 3) { fail(sender, "用法：/aiplayer channel <public|private|mention> <源id>"); return; }
        String ch = args[1].toLowerCase(Locale.ROOT);
        if (!Set.of("public", "private", "mention").contains(ch)) { fail(sender, "频道只能是 public/private/mention。"); return; }
        if (plugin.apiSources().get(args[2]) == null) { fail(sender, "源不存在：" + args[2]); return; }
        plugin.setChannelSource(ch, args[2]);
        sender.sendMessage("§a频道 " + ch + " 现在使用 API 源 " + args[2] + "。");
    }

    /**
     * /aiplayer delivery —— "回复到底有没有送到眼睛里"的诊断台。
     * diagnose 逐条通道打探针，report 让玩家回报看到了哪几条，据此定位是哪个插件在拦。
     */
    private void delivery(CommandSender sender, String[] args) {
        if (args.length < 2) {
            ChatDelivery.HardResult last = plugin.delivery().lastHardResult();
            sender.sendMessage("§7送达方式：" + plugin.getConfig().getString("persona.delivery-mode", "auto")
                + "，兜底样式：" + plugin.getConfig().getString("persona.fallback-style", "bracket")
                + "\n§7最近一次硬送达：" + (last == null ? "（还没有）" : last.describe()));
            sender.sendMessage("§7用法：§fdelivery diagnose [玩家名]｜delivery report <编号>｜delivery retry");
            sender.sendMessage("§7聊天框看不到 AI 说话时，先跑 §fdelivery diagnose §7——它会发 6 条不同通道的探针。");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "diagnose", "diag" -> plugin.delivery().diagnose(sender, args.length >= 3 ? join(args, 2) : null);
            case "report" -> {
                if (args.length < 3) fail(sender, "用法：/aiplayer delivery report <你看到的编号> 例如 1,3,5");
                else plugin.delivery().report(sender, join(args, 2));
            }
            case "retry" -> { plugin.delivery().resetCooldown(); sender.sendMessage("§a已清除玩家频道的冷静期，下一条回复会重新尝试以玩家身份发言（可用 /aiplayer say 立即测试）。"); }
            default -> fail(sender, "未知子命令。用法：delivery diagnose [玩家名] | delivery report <编号> | delivery retry");
        }
    }

    private void persona(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "nickname" -> set(sender, "persona.nickname", join(args, 2), "称呼");
            case "tone" -> set(sender, "persona.tone", join(args, 2), "语气");
            case "traits" -> set(sender, "persona.traits", join(args, 2), "性格");
            case "cooldown" -> { int v = intOr(args, 2, 8); plugin.getConfig().set("persona.cooldown-seconds", v); plugin.saveConfig(); sender.sendMessage("§a回复冷却已设为 " + v + " 秒。"); }
            case "chance" -> { int v = intOr(args, 2, 100); plugin.getConfig().set("persona.public-reply-chance", v); plugin.saveConfig(); sender.sendMessage("§a公开回复概率已设为 " + v + "%（100=逢聊必答，0=只回 @ 它的话）。"); }
            case "asplayer" -> {
                if (args.length < 3 || !List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) { fail(sender, "用法：persona asplayer on|off（on=AI 用真实聊天频道发言）"); return; }
                boolean on = args[2].equalsIgnoreCase("on");
                plugin.getConfig().set("persona.chat-as-player", on); plugin.saveConfig();
                sender.sendMessage("§aAI 公共回复" + (on ? "将以真实玩家聊天形式发出。" : "改用蓝色 [名字] 广播形式。"));
            }
            case "wreply" -> {
                if (args.length < 3 || !List.of("direct", "msg").contains(args[2].toLowerCase(Locale.ROOT))) { fail(sender, "用法：persona wreply direct|msg（msg=用 /msg 回私聊，需服务器有 msg 命令）"); return; }
                plugin.getConfig().set("persona.whisper-reply-mode", args[2].toLowerCase(Locale.ROOT)); plugin.saveConfig();
                sender.sendMessage("§a私聊回复方式已设为 " + args[2].toLowerCase(Locale.ROOT) + "。");
            }
            case "banned" -> { List<String> words = new ArrayList<>(plugin.getConfig().getStringList("persona.banned-words")); if (args.length >= 3) { String w = join(args, 2); if (!words.contains(w)) words.add(w); plugin.getConfig().set("persona.banned-words", words); plugin.saveConfig(); sender.sendMessage("§a禁词「" + w + "」已加入 AI 输出过滤。"); } }
            case "delivery" -> {
                if (args.length < 3) { sender.sendMessage("§7当前送达方式：" + plugin.getConfig().getString("persona.delivery-mode", "auto")
                    + "，兜底样式：" + plugin.getConfig().getString("persona.fallback-style", "bracket")
                    + "\n§7用法：persona delivery auto|asplayer|broadcast|safe（safe=永不走玩家频道，直接硬送达）；persona delivery retry=立刻重新尝试以玩家身份发言"); return; }
                String v = args[2].toLowerCase(Locale.ROOT);
                if (v.equals("retry")) { plugin.delivery().resetCooldown(); sender.sendMessage("§a已清除玩家频道的冷静期，下一条回复会重新尝试以玩家身份发言（可用 /aiplayer say 立即测试）。"); return; }
                if (!List.of("auto", "asplayer", "broadcast", "safe").contains(v)) { fail(sender, "只能是 auto / asplayer / broadcast / safe / retry"); return; }
                plugin.getConfig().set("persona.delivery-mode", v); plugin.saveConfig();
                sender.sendMessage("§a送达方式已设为 " + v + "。" + switch (v) {
                    case "broadcast" -> "（AI 说话不再走玩家聊天频道，聊天框里显示为兜底样式，但保证看得见。）";
                    case "safe" -> "§e（永不走玩家频道，直接逐人硬送达 + 动作栏。聊天插件拦不到它，最稳。）";
                    case "asplayer" -> "（注意：这条路被其他插件吞掉时不会兜底，聊天框可能一个字都看不到。）";
                    default -> "（推荐：会自己确认真的送达了，确认失败立刻改用硬送达。）";
                });
            }
            case "fallback" -> {
                List<String> styles = List.of("bracket", "vanilla", "none", "actionbar", "title", "multi");
                if (args.length < 3 || !styles.contains(args[2].toLowerCase(Locale.ROOT))) { fail(sender, "用法：persona fallback " + String.join("|", styles)); return; }
                plugin.getConfig().set("persona.fallback-style", args[2].toLowerCase(Locale.ROOT)); plugin.saveConfig();
                sender.sendMessage("§a兜底显示样式已设为 " + args[2].toLowerCase(Locale.ROOT) + "。");
                if (args[2].equalsIgnoreCase("actionbar") || args[2].equalsIgnoreCase("title") || args[2].equalsIgnoreCase("multi"))
                    sender.sendMessage("§7该样式会走屏幕中央的动作栏/标题，不受聊天栏被拦的影响；"
                        + "聊天框看不到时先用 §f/aiplayer delivery diagnose §7确认这条通道是通的。");
            }
            case "hardbar" -> {
                if (args.length < 3 || !List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) { fail(sender, "用法：persona hardbar on|off（硬送达时是否附带动作栏）"); return; }
                boolean on = args[2].equalsIgnoreCase("on");
                plugin.getConfig().set("persona.hard-send-actionbar", on); plugin.saveConfig();
                sender.sendMessage("§a硬送达的动作栏附加通道已" + (on ? "开启。" : "关闭。"));
            }
            case "verify" -> { int v = intOr(args, 2, 16); plugin.getConfig().set("persona.verify-delay-ticks", v); plugin.saveConfig(); sender.sendMessage("§a送达确认等待已设为 " + v + " tick（20=1 秒）。"); }
            default -> fail(sender, "用法：persona nickname|tone|traits|cooldown|chance|banned|asplayer|wreply|delivery|fallback|hardbar|verify");
        }
    }

    private void memory(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) { fail(sender, "玩家不在线。"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "show" -> {
                String ctx = plugin.memory().context(target.getUniqueId());
                sender.sendMessage("§b最近记忆：\n§7" + (ctx.isBlank() ? "（暂无）" : ctx)
                    + "\n§b档案：§7" + plugin.memory().brief(target.getUniqueId(), target.getName()));
            }
            case "note" -> {
                if (args.length < 4) { fail(sender, "用法：memory note <玩家> <要记住的事>"); return; }
                plugin.memory().addEvent(target.getUniqueId(), "（管理员备注）" + join(args, 3));
                plugin.memory().mark(target.getUniqueId());
                sender.sendMessage("§a已记进 " + target.getName() + " 的档案（AI 下次说话时就能看到）。");
            }
            case "clear" -> {
                plugin.memory().clear(target.getUniqueId());
                plugin.punishments().audit("MEMORY clear " + target.getName() + " by " + sender.getName());
                sender.sendMessage("§a已清除 " + target.getName() + " 的记忆。");
            }
            default -> fail(sender, "用法：memory show|note|clear <玩家>");
        }
    }

    private void brain(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§7" + plugin.brain().statusLine()
            + "\n§7用法：brain on|off|now|cellsize <格边长>|radius <探索半径格数>"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> { plugin.getConfig().set("brain.enabled", true); plugin.saveConfig(); plugin.brain().start();
                sender.sendMessage("§a自主大脑已开启：它会持续探索、记地图、记玩家、监管聊天。"); }
            case "off" -> { plugin.getConfig().set("brain.enabled", false); plugin.saveConfig(); plugin.brain().stop();
                sender.sendMessage("§a自主大脑已关闭（回到被动聊天模式；想省 token 就关它）。"); }
            case "now" -> { plugin.brain().forceThink(true); sender.sendMessage("§a已强制思考一次，看控制台 [大脑] 日志。"); }
            case "cellsize" -> { int v = intOr(args, 2, 32); plugin.getConfig().set("brain.cell-size", v); plugin.saveConfig();
                plugin.worldMemory().setCellSize(v); sender.sendMessage("§a探索网格边长设为 " + v + " 格。"); }
            case "radius" -> { int v = intOr(args, 2, 10); plugin.getConfig().set("brain.explore-max-cells", v); plugin.saveConfig();
                sender.sendMessage("§a单次探索半径设为 " + v + " 格（" + (v * plugin.worldMemory().cellSize()) + " 格距离）。"); }
            default -> fail(sender, "用法：brain on|off|now|cellsize|radius");
        }
    }

    private void home(CommandSender sender, String[] args) {
        if (args.length < 2) args = new String[]{"home", "show"};
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "show" -> {
                Location home = plugin.worldMemory().home();
                Location bed = plugin.worldMemory().bed();
                sender.sendMessage(home == null ? "§7它还没有家。有 API 时会自己找地盖房；也可以 /aiplayer home rebuild 或 home set <x y z>。"
                    : "§b它的家在 §f" + home.getWorld().getName() + String.format(" (%.0f, %.0f, %.0f)", home.getX(), home.getY(), home.getZ())
                    + (bed == null ? "" : String.format("\n§b出生点（床）：§f%.0f, %.0f, %.0f", bed.getX(), bed.getY(), bed.getZ())));
            }
            case "rebuild" -> { plugin.homeBase().ensureBuiltAsync(true); sender.sendMessage("§a开始重新选址盖房，进度看控制台 [安家]。"); }
            case "goto" -> {
                Location home = plugin.worldMemory().home();
                if (home == null) { fail(sender, "还没有家。"); return; }
                if (plugin.npcs().player() != null) plugin.npcs().player().teleport(home);
                else if (plugin.npcs().entity() != null) plugin.npcs().entity().teleport(home);
                sender.sendMessage("§a已把它传送回家。");
            }
            case "set" -> {
                if (args.length < 5) { fail(sender, "用法：home set <x> <y> <z> [世界名]（在它脚下盖房）"); return; }
                World w = args.length >= 6 ? Bukkit.getWorld(args[5]) : (sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0));
                if (w == null) { fail(sender, "世界不存在。"); return; }
                Location center;
                try {
                    center = new Location(w, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
                } catch (NumberFormatException bad) { fail(sender, "坐标必须是数字。"); return; }
                if (plugin.npcs().player() != null) plugin.npcs().player().teleport(center);
                plugin.homeBase().adoptSite(center, () -> sender.sendMessage("§a已在指定位置盖好房子并把出生点设在那张床上。"));
                sender.sendMessage("§7开始在指定坐标盖房…");
            }
            default -> fail(sender, "用法：home show|rebuild|goto|set <x y z> [世界]");
        }
    }

    private void skill(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (plugin.skills().all().isEmpty()) { fail(sender, "白名单为空。"); return; }
                StringBuilder b = new StringBuilder("§b白名单技能：\n");
                plugin.skills().all().forEach((name, s) -> b.append("§7 ").append(name).append(" → ").append(s.commandTemplate)
                    .append("（权限等级 ").append(s.permissionLevel).append("，冷却 ").append(s.cooldownSeconds).append("s）\n"));
                sender.sendMessage(b.toString());
            }
            case "add" -> {
                if (args.length < 6) return;
                String name = args[2]; if (name.length() > 32) { fail(sender, "技能名过长。"); return; }
                plugin.skills().add(name, join(args, 5), intOr(args, 3, 0), intOr(args, 4, 60));
                plugin.punishments().audit("SKILL add " + name + " by " + sender.getName());
                sender.sendMessage("§a技能 " + name + " 已登记。");
            }
            case "remove" -> { if (args.length < 3) return; sender.sendMessage(plugin.skills().remove(args[2]) ? "§a技能已移除。" : "技能不存在。"); }
            default -> fail(sender, "用法：skill list|add <名称> <权限0-4> <冷却秒> <命令模板>|remove <名称>");
        }
    }

    private void punish(CommandSender sender, String[] args) {
        if (args.length < 3) { fail(sender, "用法：punish <玩家> mute|unmute|kick|ban <分钟> <原因>"); return; }
        String type = args[2].toLowerCase(Locale.ROOT);
        int minutes = args.length >= 4 ? intOr(args, 3, 30) : 30;
        String reason = args.length >= 5 ? join(args, 4) : "管理员处罚";
        plugin.manualPunish(sender, args[1], type, minutes, reason);
        sender.sendMessage("§a已对 " + args[1] + " 执行 " + type + "。");
    }

    private void npc(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (sender instanceof Player p) plugin.npcs().spawnAt(p.getLocation());
                else plugin.npcs().spawn();
                sender.sendMessage(plugin.npcs().online()
                    ? "§aAI 玩家「" + plugin.npcs().name() + "」已进入世界（形态：" + plugin.npcs().modeLabel() + "）。"
                    : "§cAI 玩家创建失败，请查看控制台日志。");
            }
            case "name" -> { if (args.length < 3) return; plugin.npcs().rename(join(args, 2)); sender.sendMessage("§aAI 名称已改为 " + join(args, 2)); }
            case "skin" -> { if (args.length < 3) return; plugin.npcs().setSkinSource(args[2]); sender.sendMessage("§a皮肤来源已设为 " + args[2]); }
            case "method" -> {
                if (args.length < 3) { sender.sendMessage("§7当前进服方式：" + plugin.npcs().modeLabel()
                    + "（配置项 npc.join-method=auto|self-client|kernel|armorstand）"); return; }
                String m = args[2].toLowerCase(Locale.ROOT);
                if (!List.of("auto", "self-client", "kernel", "armorstand").contains(m)) { fail(sender, "方式只能是 auto|self-client|kernel|armorstand"); return; }
                plugin.npcs().setJoinMethod(m);
                sender.sendMessage("§a进服方式已设为 " + m + "，AI 正在重新进服（形态：" + plugin.npcs().modeLabel() + "）。");
            }
            case "move" -> { if (sender instanceof Player p) { plugin.npcs().move(p.getLocation()); sender.sendMessage("§aAI 已移动到执行者位置。"); } else fail(sender, "需要玩家执行。"); }
            case "remove" -> { plugin.npcs().remove(); sender.sendMessage("§aAI 已移除。"); }
            default -> fail(sender, "用法：npc spawn|name|skin|move|remove|method");
        }
    }

    private void gather(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        if (args[1].equalsIgnoreCase("start")) { plugin.behavior().startGather(plugin.getConfig().getInt("behavior.gather.radius", 12)); sender.sendMessage("§aAI 开始采集，执行 /aiplayer gather stop 可停止。"); }
        else if (args[1].equalsIgnoreCase("stop")) { plugin.behavior().stopJob(); sender.sendMessage("§a采集任务已停止。"); }
        else fail(sender, "用法：/aiplayer gather start|stop");
    }

    private void build(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        if (args[1].equalsIgnoreCase("stop")) { plugin.behavior().stopJob(); sender.sendMessage("§a建造任务已停止。"); return; }
        if (!args[1].equalsIgnoreCase("start") || args.length < 3) { fail(sender, "用法：/aiplayer build start <蓝图> [x y z 世界名]"); return; }
        Location origin = plugin.npcs().location();
        if (origin == null) origin = sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        if (args.length >= 6) {
            World world = Bukkit.getWorld(args.length >= 7 ? args[6] : origin.getWorld().getName());
            if (world != null) origin = new Location(world, Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
        }
        if (plugin.loadBlueprint(args[2]).isEmpty()) { fail(sender, "找不到蓝图：" + args[2] + "，请放在 plugins/AiPlayerPaper/blueprints/"); return; }
        plugin.behavior().startBuild(args[2], origin);
        sender.sendMessage("§a开始建造蓝图：" + args[2]);
    }

    private void autocmd(CommandSender sender, String[] args) {
        if (args.length < 2) { fail(sender, "用法：autocmd list|add|remove|prompt|run|exec|on|off"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                boolean en = plugin.getConfig().getBoolean("join-commands.enabled", true);
                List<String> cmds = plugin.getConfig().getStringList("join-commands.commands");
                List<Map<?, ?>> prompts = plugin.getConfig().getMapList("join-commands.prompts");
                StringBuilder b = new StringBuilder("§b进服自动命令：").append(en ? "§a已启用" : "§c已停用")
                    .append("§b（进服 ").append(plugin.getConfig().getLong("join-commands.delay-seconds", 3))
                    .append(" 秒后开始，每 ").append(plugin.getConfig().getLong("join-commands.interval-ms", 800)).append(" 毫秒发一条）\n");
                if (cmds.isEmpty()) b.append("§7固定命令：无\n");
                else { int i = 1; for (String c : cmds) b.append("§7  ").append(i++).append(". /").append(JoinCommandRunner.maskForDisplay(c)).append('\n'); }
                if (prompts.isEmpty()) b.append("§7提示响应：无\n");
                else {
                    int i = 1;
                    for (Map<?, ?> m : prompts) {
                        Object kw = m.get("keywords");
                        Object timeout = m.get("timeout-seconds");
                        Object maxTimes = m.get("max-times");
                        String kws = kw instanceof List<?> l ? String.join("、", l.stream().map(String::valueOf).toList()) : "（无）";
                        b.append("§7  ").append(i++).append(". 收到「").append(kws).append("」→ /")
                            .append(JoinCommandRunner.maskForDisplay(String.valueOf(m.get("send"))))
                            .append("（超时兜底 ").append(timeout == null ? 15 : timeout).append("s，最多 ")
                            .append(maxTimes == null ? 2 : maxTimes).append(" 次）\n");
                    }
                }
                b.append("§7固定命令用 add <命令> 添加；提示响应用 prompt add <关键词1,关键词2> <命令> 添加。");
                sender.sendMessage(b.toString());
            }
            case "add" -> {
                if (args.length < 3) return;
                List<String> cmds = new ArrayList<>(plugin.getConfig().getStringList("join-commands.commands"));
                cmds.add(join(args, 2));
                plugin.getConfig().set("join-commands.commands", cmds); plugin.saveConfig();
                plugin.punishments().audit("AUTOCMD add by " + sender.getName() + " -> /" + JoinCommandRunner.maskForDisplay(join(args, 2)));
                sender.sendMessage("§a已加入固定命令（进服后第 " + cmds.size() + " 条）：" + JoinCommandRunner.maskForDisplay(join(args, 2))
                    + (join(args, 2).contains("${secret:") ? "" : "。含密码建议改用 ${secret:名字} 占位符，先用 /aiplayer secret set 加密保存。"));
            }
            case "remove" -> {
                if (args.length < 3) return;
                List<String> cmds = new ArrayList<>(plugin.getConfig().getStringList("join-commands.commands"));
                if (args[2].equalsIgnoreCase("all")) cmds.clear();
                else {
                    int idx = intOr(args, 2, 0);
                    if (idx < 1 || idx > cmds.size()) { fail(sender, "序号超出范围（1-" + cmds.size() + "）。"); return; }
                    cmds.remove(idx - 1);
                }
                plugin.getConfig().set("join-commands.commands", cmds); plugin.saveConfig();
                sender.sendMessage("§a固定命令已更新，剩 " + cmds.size() + " 条。");
            }
            case "prompt" -> {
                if (args.length < 3) { fail(sender, "用法：prompt add <关键词1,关键词2> <命令> · prompt remove <序号>"); return; }
                List<Map<?, ?>> raw = plugin.getConfig().getMapList("join-commands.prompts");
                List<Map<String, Object>> list = new ArrayList<>();
                for (Map<?, ?> m : raw) { Map<String, Object> c = new LinkedHashMap<>(); m.forEach((k, v) -> c.put(String.valueOf(k), v)); list.add(c); }
                if (args[2].equalsIgnoreCase("add")) {
                    if (args.length < 5) { fail(sender, "用法：prompt add <关键词1,关键词2> <命令>"); return; }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("keywords", Arrays.stream(args[3].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
                    entry.put("send", join(args, 4));
                    entry.put("timeout-seconds", 15);
                    entry.put("max-times", 2);
                    list.add(entry);
                    plugin.getConfig().set("join-commands.prompts", new ArrayList<>(list)); plugin.saveConfig();
                    plugin.punishments().audit("AUTOCMD prompt add by " + sender.getName() + " -> /" + JoinCommandRunner.maskForDisplay(join(args, 4)));
                    sender.sendMessage("§a提示响应已加入（第 " + list.size() + " 条）：收到关键词就发送 /" + JoinCommandRunner.maskForDisplay(join(args, 4)));
                } else if (args[2].equalsIgnoreCase("remove")) {
                    if (args.length < 4) { fail(sender, "用法：prompt remove <序号>"); return; }
                    int idx = intOr(args, 3, 0);
                    if (idx < 1 || idx > list.size()) { fail(sender, "序号超出范围（1-" + list.size() + "）。"); return; }
                    list.remove(idx - 1);
                    plugin.getConfig().set("join-commands.prompts", new ArrayList<>(list)); plugin.saveConfig();
                    sender.sendMessage("§a提示响应已更新，剩 " + list.size() + " 条。");
                } else fail(sender, "用法：prompt add <关键词1,关键词2> <命令> · prompt remove <序号>");
            }
            case "run" -> {
                if (plugin.autoCommands().retryNow()) sender.sendMessage("§a已重新开始进服命令序列（" + plugin.getConfig().getLong("join-commands.delay-seconds", 3) + " 秒后发送）。");
                else fail(sender, "AI 玩家不在线，或没有任何已配置的命令。");
            }
            case "exec" -> {
                if (args.length < 3) return;
                sender.sendMessage(plugin.autoCommands().execAsNpc(join(args, 2), sender.getName())
                    ? "§aAI 玩家已执行：" + JoinCommandRunner.maskForDisplay(join(args, 2))
                    : "§c执行失败：AI 玩家需在线，且 ${secret:名字} 要已用 /aiplayer secret set 设置。");
            }
            case "on" -> { plugin.getConfig().set("join-commands.enabled", true); plugin.saveConfig(); sender.sendMessage("§a进服自动命令已启用。"); }
            case "off" -> { plugin.getConfig().set("join-commands.enabled", false); plugin.saveConfig(); sender.sendMessage("§a进服自动命令已停用。"); }
            default -> fail(sender, "用法：autocmd list|add|remove|prompt|run|exec|on|off");
        }
    }

    private void secret(CommandSender sender, String[] args) {
        if (args.length < 2) { fail(sender, "用法：secret set <名字> <内容> · secret list · secret remove <名字>"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (args.length < 4) return;
                String name = args[2];
                if (!name.matches("[A-Za-z0-9_.\\-]{1,32}")) { fail(sender, "名字只能用字母、数字、下划线、点和减号（1-32 位）。"); return; }
                plugin.setSecret(name, join(args, 3));
                plugin.punishments().audit("SECRET set " + name + " by " + sender.getName());
                sender.sendMessage("§a加密值「" + name + "」已保存。命令里用 ${secret:" + name + "} 引用，例如 /aiplayer autocmd add login ${secret:" + name + "}");
            }
            case "list" -> {
                java.util.Set<String> names = plugin.secretNames();
                sender.sendMessage(names.isEmpty() ? "§7还没有已保存的加密值。" : "§b加密值：§f" + String.join("、", names));
            }
            case "remove" -> {
                if (args.length < 3) return;
                plugin.removeSecret(args[2]);
                plugin.punishments().audit("SECRET remove " + args[2] + " by " + sender.getName());
                sender.sendMessage("§a加密值「" + args[2] + "」已删除。");
            }
            default -> fail(sender, "用法：secret set|list|remove");
        }
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("help", "status", "reload", "tell", "say", "confirm", "deny", "source", "channel", "persona", "memory", "skill", "punish", "npc", "brain", "home", "autocmd", "secret", "gather", "build"), args[0]);
        if (args.length == 2) return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "source" -> filter(List.of("list", "add", "key", "enable", "disable", "remove", "test"), args[1]);
            case "channel" -> filter(List.of("public", "private", "mention"), args[1]);
            case "persona" -> filter(List.of("nickname", "tone", "traits", "cooldown", "chance", "banned", "asplayer", "wreply", "delivery", "fallback", "verify"), args[1]);
            case "memory" -> filter(List.of("show", "note", "clear"), args[1]);
            case "skill" -> filter(List.of("list", "add", "remove"), args[1]);
            case "npc" -> filter(List.of("spawn", "name", "skin", "move", "remove", "method"), args[1]);
            case "brain" -> filter(List.of("on", "off", "now", "cellsize", "radius"), args[1]);
            case "home" -> filter(List.of("show", "rebuild", "goto", "set"), args[1]);
            case "autocmd" -> filter(List.of("list", "add", "remove", "prompt", "run", "exec", "on", "off"), args[1]);
            case "secret" -> filter(List.of("set", "list", "remove"), args[1]);
            case "gather" -> filter(List.of("start", "stop"), args[1]);
            case "build" -> filter(List.of("start", "stop"), args[1]);
            default -> List.of();
        };
        if (args.length == 3 && args[0].equalsIgnoreCase("build")) return plugin.blueprints();
        if (args.length == 3 && args[0].equalsIgnoreCase("persona")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("delivery")) return filter(List.of("auto", "asplayer", "broadcast", "retry"), args[2]);
            if (sub.equals("fallback")) return filter(List.of("bracket", "vanilla", "none"), args[2]);
            if (sub.equals("wreply")) return filter(List.of("direct", "msg"), args[2]);
            if (sub.equals("asplayer")) return filter(List.of("on", "off"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("autocmd") && args[1].equalsIgnoreCase("prompt")) return filter(List.of("add", "remove"), args[2]);
        return List.of();
    }

    private static List<String> filter(List<String> list, String value) { return list.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(value.toLowerCase(Locale.ROOT))).toList(); }
    private static String join(String[] args, int from) { return String.join(" ", Arrays.copyOfRange(args, from, args.length)); }
    private static int intOr(String[] args, int index, int fallback) { try { return Integer.parseInt(args[index]); } catch (Exception e) { return fallback; } }
    private void set(CommandSender sender, String path, String value, String label) { plugin.getConfig().set(path, value); plugin.saveConfig(); sender.sendMessage("§a" + label + "已更新。"); }
    private boolean fail(CommandSender sender, String msg) { sender.sendMessage("§c" + msg); return true; }
}
