package cn.blockforge.aiplayer;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 真·协议自连客户端：插件在本进程里开一个 TCP 连接到服务器自身（默认 127.0.0.1:服务器端口），
 * 按 Java 版 1.21.x 的网络协议完成 握手→登录→配置→游戏 全流程。
 * 服务器看到的是"一个真实玩家从本地连了进来"：进 tab 列表、其它插件按真玩家对待、
 * 走 PlayerJoinEvent，完全不依赖服务端内部类构造，避开"本服务器无法创建真玩家级AI"。
 *
 * 协议细节不靠硬编码数字：
 *  - 协议号来自服务端 SharedConstants.getProtocolVersion()；
 *  - 包数字 ID 通过服务端注册表解析：LoginProtocols / ConfigurationProtocols / GameProtocols 的
 *    ProtocolInfo.details().listPackets((PacketType,int)->…) 遍历，与各 *PacketTypes 静态字段
 *    指向的 PacketType 做同一性匹配——服务端包表变了，这里自动跟着变；
 *  - 包体字节用服务端自己的 StreamCodec 编码（各包类的 STREAM_CODEC 静态字段），
 *    字段布局（如 1.21.6+ LoginStart 的新字段、Input.EMPTY）天然与解码端一致；
 *  - 传输压缩由服务端登录压缩包的阈值驱动，自动 zlib 压缩/解压。
 *
 * 加入世界后，移动/命令/聊天走 Bukkit API（主线程），本类只维持连接：
 * 回保活、确认传送、应答已知数据包表/行为准则、按 1.21.2+ 要求周期发送客户端输入包，
 * 并把服务器发给它的系统消息转给"进服命令"模块（登录服提示响应）。
 */
public final class ProtocolClientPlayer {

    public interface Listener {
        void onPlaying(String name, UUID uuid);
        void onFailed(String name, String reason);
        void onServerMessage(String name, String text);
    }

    public enum State { IDLE, CONNECTING, LOGIN, CONFIG, PLAYING, FAILED, CLOSED }

    private final JavaPlugin plugin;
    private final Listener listener;

    private volatile State state = State.IDLE;
    private volatile String failureReason;
    private volatile UUID serverUuid;

    private String playerName;
    private UUID expectedUuid;
    private String host = "127.0.0.1";
    private int port = 25565;
    private long loginTimeoutMs = 25_000;
    private long tickIntervalMs = 1000;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean running;
    private Thread worker, tickWorker;

    private int compressionThreshold = -1;
    private int packetsSeen;          // 收到过的包数（登录失败时用于定位）
    private int lastPacketId = -1;    // 最后一个收到的包 ID
    private boolean debugLog;
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);

    /* ---------------- 反射：服务端自身的协议注册表 ---------------- */

    private ClassLoader cl;
    private boolean refDone, refOk;
    private String refWhy;

    private int protocolVersion = -1;
    private Method mUnpooledBuf;
    private Constructor<?> fbbCtor;
    private Method mCodecEncode;
    private Method mReadable, mGetBytes, mReaderIndex;
    private Class<?> packetTypeCls, visitorItf, customAnswerPayloadItf;

    private final Map<String, Class<?>> clsCache = new HashMap<>();
    private final Map<String, Object> staticCache = new HashMap<>();
    private final Map<String, Object> protocolInfoCache = new HashMap<>();
    private final Map<String, Integer> idCache = new HashMap<>();

    private String tickKey;
    private boolean loggedNoTick, loggedNoClientInfo;

    private static final String LOGIN_TYPES = "net.minecraft.network.protocol.login.LoginPacketTypes";
    private static final String COMMON_TYPES = "net.minecraft.network.protocol.common.CommonPacketTypes";
    private static final String CONFIG_TYPES = "net.minecraft.network.protocol.configuration.ConfigurationPacketTypes";
    private static final String GAME_TYPES = "net.minecraft.network.protocol.game.GamePacketTypes";
    private static final String LOGIN_PROTOCOLS = "net.minecraft.network.protocol.login.LoginProtocols";
    private static final String CONFIG_PROTOCOLS = "net.minecraft.network.protocol.configuration.ConfigurationProtocols";
    private static final String GAME_PROTOCOLS = "net.minecraft.network.protocol.game.GameProtocols";

    private static final Function<Object, Object> IDENTITY = x -> x;

    public ProtocolClientPlayer(JavaPlugin plugin, Listener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    public State state() { return state; }
    public String failureReason() { return failureReason; }
    public UUID serverUuid() { return serverUuid; }
    public boolean supported() { return initReflect(); }
    public String unsupportedReason() { return refWhy; }

    private boolean initReflect() {
        if (refDone) return refOk;
        refDone = true;
        try {
            cl = plugin.getClass().getClassLoader();

            Class<?> sc = Class.forName("net.minecraft.SharedConstants", true, cl);
            protocolVersion = (int) sc.getMethod("getProtocolVersion").invoke(null);

            packetTypeCls = Class.forName("net.minecraft.network.protocol.PacketType", true, cl);
            visitorItf = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor", true, cl);

            Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled", true, cl);
            mUnpooledBuf = unpooled.getMethod("buffer");
            Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf", true, cl);
            Class<?> fbb = Class.forName("net.minecraft.network.FriendlyByteBuf", true, cl);
            fbbCtor = fbb.getConstructor(byteBuf);
            mReadable = byteBuf.getMethod("readableBytes");
            mReaderIndex = byteBuf.getMethod("readerIndex");
            mGetBytes = byteBuf.getMethod("getBytes", int.class, byte[].class);

            Class<?> codec = Class.forName("net.minecraft.network.codec.StreamCodec", true, cl);
            mCodecEncode = codec.getMethod("encode", Object.class, Object.class);

            try {
                customAnswerPayloadItf = Class.forName("net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload", true, cl);
            } catch (ClassNotFoundException ignored) {}

            for (String c : new String[]{LOGIN_TYPES, COMMON_TYPES, CONFIG_TYPES, GAME_TYPES,
                                         LOGIN_PROTOCOLS, CONFIG_PROTOCOLS, GAME_PROTOCOLS}) {
                try { Class.forName(c, true, cl); } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("缺少协议注册表 " + c + "（服务端太老或非 Mojang 映射）");
                }
            }
            String[] mustKeys = {"pkt.login.start", "pkt.login.ack", "pkt.config.ack", "pkt.keepalive.out",
                                 "pkt.play.teleport.confirm", "pkt.play.input", "pkt.config.clientinfo"};
            StringBuilder missing = new StringBuilder();
            for (String key : mustKeys) if (packetCls(key) == null) missing.append(' ').append(key);
            if (missing.length() > 0) throw new IllegalStateException("服务端缺少协议包类:" + missing);

            if (id("LOGIN", false, LOGIN_TYPES, "CLIENTBOUND_LOGIN_FINISHED") < 0
                || id("CONFIG", false, CONFIG_TYPES, "CLIENTBOUND_FINISH_CONFIGURATION") < 0
                || id("PLAY", false, COMMON_TYPES, "CLIENTBOUND_KEEP_ALIVE") < 0) {
                throw new IllegalStateException("无法从服务端注册表解析关键包 ID");
            }
            refOk = true;
        } catch (Throwable t) {
            refOk = false;
            refWhy = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return refOk;
    }

    /** 服务端包类候选名（版本间挪包的全部列出，取第一个存在的）。 */
    private static final Map<String, String[]> PKG_CANDIDATES = new HashMap<>();
    static {
        String LOGIN = "net.minecraft.network.protocol.login.", CONF = "net.minecraft.network.protocol.configuration.",
               COMMON = "net.minecraft.network.protocol.common.", GAME = "net.minecraft.network.protocol.game.";
        PKG_CANDIDATES.put("pkt.login.start", new String[]{LOGIN + "ServerboundHelloPacket"});
        PKG_CANDIDATES.put("pkt.login.ack", new String[]{LOGIN + "ServerboundLoginAcknowledgedPacket"});
        PKG_CANDIDATES.put("pkt.query.answer", new String[]{LOGIN + "ServerboundCustomQueryAnswerPacket", COMMON + "ServerboundCustomQueryAnswerPacket"});
        PKG_CANDIDATES.put("pkt.login.acked.additions", new String[]{LOGIN + "ServerboundAcknowledgedAdditionsPacket"});
        PKG_CANDIDATES.put("pkt.config.clientinfo", new String[]{COMMON + "ServerboundClientInformationPacket", CONF + "ServerboundClientInformationPacket"});
        PKG_CANDIDATES.put("pkt.config.ack", new String[]{
            CONF + "ServerboundFinishConfigurationPacket", CONF + "ServerboundConfigurationAcknowledgedPacket",
            COMMON + "ServerboundFinishConfigurationPacket", COMMON + "ServerboundConfigurationAcknowledgedPacket"});
        PKG_CANDIDATES.put("pkt.config.knownpacks", new String[]{CONF + "ServerboundSelectKnownPacks"});
        PKG_CANDIDATES.put("pkt.config.conduct", new String[]{CONF + "ServerboundAcceptCodeOfConductPacket"});
        PKG_CANDIDATES.put("pkt.keepalive.out", new String[]{COMMON + "ServerboundKeepAlivePacket"});
        PKG_CANDIDATES.put("pkt.play.teleport.confirm", new String[]{GAME + "ServerboundAcceptTeleportationPacket"});
        PKG_CANDIDATES.put("pkt.play.input", new String[]{GAME + "ServerboundPlayerInputPacket"});
        // 1.21.x 叫 StatusOnly；旧映射曾有 StatusOnGround，两个候选名都兼容。
        PKG_CANDIDATES.put("pkt.play.move.status", new String[]{GAME + "ServerboundMovePlayerPacket$StatusOnly",
                                                                GAME + "ServerboundMovePlayerPacket$StatusOnGround"});
        PKG_CANDIDATES.put("pkt.play.move.pos", new String[]{GAME + "ServerboundMovePlayerPacket$Pos"});
        PKG_CANDIDATES.put("pkt.play.move.posrot", new String[]{GAME + "ServerboundMovePlayerPacket$PosRot"});
    }

    /** 发送语义键 → [协议状态, PacketTypes 类, 字段名]。 */
    private static final Map<String, String[]> SWITCH_KEYS = new HashMap<>();
    static {
        SWITCH_KEYS.put("pkt.login.start", new String[]{"LOGIN", LOGIN_TYPES, "SERVERBOUND_HELLO"});
        SWITCH_KEYS.put("pkt.login.ack", new String[]{"LOGIN", LOGIN_TYPES, "SERVERBOUND_LOGIN_ACKNOWLEDGED"});
        SWITCH_KEYS.put("pkt.query.answer", new String[]{"LOGIN", LOGIN_TYPES, "SERVERBOUND_CUSTOM_QUERY_ANSWER"});
        SWITCH_KEYS.put("pkt.login.acked.additions", new String[]{"LOGIN", LOGIN_TYPES, "SERVERBOUND_ACKNOWLEDGED_ADDITIONS"});
        SWITCH_KEYS.put("pkt.config.clientinfo", new String[]{"CONFIG", COMMON_TYPES, "SERVERBOUND_CLIENT_INFORMATION"});
        SWITCH_KEYS.put("pkt.config.ack", new String[]{"CONFIG", CONFIG_TYPES, "SERVERBOUND_FINISH_CONFIGURATION"});
        SWITCH_KEYS.put("pkt.config.knownpacks", new String[]{"CONFIG", CONFIG_TYPES, "SERVERBOUND_SELECT_KNOWN_PACKS"});
        SWITCH_KEYS.put("pkt.config.conduct", new String[]{"CONFIG", CONFIG_TYPES, "SERVERBOUND_ACCEPT_CODE_OF_CONDUCT"});
        SWITCH_KEYS.put("pkt.keepalive.out", new String[]{"KEEPALIVE", COMMON_TYPES, "SERVERBOUND_KEEP_ALIVE"});
        SWITCH_KEYS.put("pkt.play.teleport.confirm", new String[]{"PLAY", GAME_TYPES, "SERVERBOUND_ACCEPT_TELEPORTATION"});
        SWITCH_KEYS.put("pkt.play.input", new String[]{"PLAY", GAME_TYPES, "SERVERBOUND_PLAYER_INPUT"});
        SWITCH_KEYS.put("pkt.play.move.status", new String[]{"PLAY", GAME_TYPES, "SERVERBOUND_MOVE_PLAYER_STATUS_ONLY"});
    }

    private Class<?> packetCls(String key) {
        if (clsCache.containsKey(key)) return clsCache.get(key);
        Class<?> found = null;
        for (String name : PKG_CANDIDATES.getOrDefault(key, new String[0])) {
            try { found = Class.forName(name, true, cl); break; } catch (ClassNotFoundException ignored) {}
        }
        clsCache.put(key, found);
        return found;
    }

    private Object staticField(String clsName, String fieldName) {
        String ck = "sf:" + clsName + "." + fieldName;
        if (staticCache.containsKey(ck)) return staticCache.get(ck);
        Object v = null;
        try {
            Class<?> c = Class.forName(clsName, true, cl);
            Field f = c.getField(fieldName);
            f.setAccessible(true);
            v = f.get(null);
        } catch (Throwable ignored) {}
        staticCache.put(ck, v);
        return v;
    }

    /** 某协议状态下 PacketType 静态字段对应的数字 ID；取不到返回 -1（并缓存）。 */
    private int id(String state, boolean serverbound, String typesCls, String typeField) {
        String ck = state + (serverbound ? ":S:" : ":C:") + typeField;
        if (idCache.containsKey(ck)) return idCache.get(ck);
        int found = -1;
        try {
            Object target = staticField(typesCls, typeField);
            if (target != null) found = walkProtocol(state, serverbound, target);
        } catch (Throwable ignored) {}
        idCache.put(ck, found);
        return found;
    }

    /** 按方向+字段名片段模糊找 PacketType（处理 1.21.x 小版本字段名微调）。 */
    private int idFuzzy(String state, boolean serverbound, String typesCls, String fieldPrefix, String contains) {
        String ck = state + (serverbound ? ":S:" : ":C:") + "#" + typesCls + "#" + contains;
        if (idCache.containsKey(ck)) return idCache.get(ck);
        int found = -1;
        try {
            Class<?> c = Class.forName(typesCls, true, cl);
            Object target = null;
            for (Field f : c.getFields()) {
                if (!f.getType().equals(packetTypeCls) || !Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().startsWith(fieldPrefix)) continue;
                if (contains != null && !f.getName().toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) continue;
                target = f.get(null);
                break;
            }
            if (target != null) found = walkProtocol(state, serverbound, target);
        } catch (Throwable ignored) {}
        idCache.put(ck, found);
        return found;
    }

    /** details().listPackets(visitor)：找 target PacketType 在该协议注册表里的数字 ID。 */
    private int walkProtocol(String state, boolean serverbound, Object targetPacketType) throws Exception {
        Object info = protocolInfo(state, serverbound);
        if (info == null) return -1;
        Object details = null;
        for (Method m : info.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getName().equals("details")) {
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                details = m.invoke(info);
                break;
            }
        }
        if (details == null) return -1;
        Method list = null;
        for (Method m : details.getClass().getMethods()) {
            if (m.getParameterCount() == 1 && m.getName().equals("listPackets")) { list = m; break; }
        }
        if (list == null) return -1;
        // 实现类常是包内私有匿名类（ProtocolInfoBuilder$N），不 setAccessible 会 IllegalAccessException。
        try { list.setAccessible(true); } catch (Throwable ignored) {}
        final int[] out = {-1};
        Object visitor = Proxy.newProxyInstance(cl, new Class<?>[]{visitorItf}, (proxy, m, args) -> {
            if (args != null && args.length == 2 && args[1] instanceof Integer
                && (args[0] == targetPacketType || targetPacketType.equals(args[0]))) {
                out[0] = (Integer) args[1];
            }
            return null;
        });
        list.invoke(details, visitor);
        return out[0];
    }

    /** 返回协议模板（模板的 details() 才是 1.21.11 真实包表）；旧版本再退回已绑定信息。 */
    private Object protocolInfo(String state, boolean serverbound) {
        String ck = state + (serverbound ? "S" : "C");
        if (protocolInfoCache.containsKey(ck)) return protocolInfoCache.get(ck);
        Object out = null;
        try {
            String cls = state.equals("LOGIN") ? LOGIN_PROTOCOLS : state.equals("CONFIG") ? CONFIG_PROTOCOLS : GAME_PROTOCOLS;
            // 1.21.11 的 SimpleUnboundProtocol 实现 DetailsProvider，包 ID 在模板 details() 中。
            out = staticField(cls, serverbound ? "SERVERBOUND_TEMPLATE" : "CLIENTBOUND_TEMPLATE");
            if (out == null) {
                // 兼容把 details() 直接放在 ProtocolInfo 上的旧实现。
                out = staticField(cls, serverbound ? "SERVERBOUND" : "CLIENTBOUND");
            }
            if (out == null) {
                Object template = staticField(cls, serverbound ? "SERVERBOUND_TEMPLATE" : "CLIENTBOUND_TEMPLATE");
                if (template != null) {
                    for (Method m : template.getClass().getMethods()) {
                        if (!m.getName().equals("bind") || m.getParameterCount() != 1) continue;
                        try { out = m.invoke(template, IDENTITY); break; } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) { out = null; }
        protocolInfoCache.put(ck, out);
        return out;
    }

    /** GameProtocols$Context 之类模板绑定时要的上下文对象，尽量给个默认实例；给不出返回 null。 */
    private Object contextInstance(String protocolCls) {
        try {
            Class<?> ctx = Class.forName(protocolCls + "$Context", true, cl);
            if (ctx.isEnum()) { Object[] es = ctx.getEnumConstants(); return es.length > 0 ? es[0] : null; }
            try { return ctx.getMethod("createDefault").invoke(null); } catch (Throwable ignored) {}
            for (Constructor<?> c : ctx.getConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts.length == 0) return c.newInstance();
                if (pts.length == 1 && pts[0] == boolean.class) return c.newInstance(false);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ---------------- 帧与编码 ---------------- */

    private void writeVarInt(ByteArrayOutputStream b, int v) {
        do { int t = v & 0x7F; v >>>= 7; if (v != 0) t |= 0x80; b.write(t); } while (v != 0);
    }

    private void writeString(ByteArrayOutputStream b, String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(b, u.length);
        b.write(u, 0, u.length);
    }

    private void writeFrame(byte[] idAndBody) throws java.io.IOException {
        byte[] payload = idAndBody;
        if (compressionThreshold >= 0 && idAndBody.length >= compressionThreshold) {
            byte[] comp;
            synchronized (deflater) {
                deflater.reset();
                deflater.setInput(idAndBody);
                deflater.finish();
                ByteArrayOutputStream z = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                while (!deflater.finished()) { int n = deflater.deflate(buf); z.write(buf, 0, n); }
                comp = z.toByteArray();
            }
            ByteArrayOutputStream cb = new ByteArrayOutputStream();
            writeVarInt(cb, idAndBody.length);
            cb.write(comp, 0, comp.length);
            payload = cb.toByteArray();
        }
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        writeVarInt(hdr, payload.length);
        synchronized (writeLock) {
            out.write(hdr.toByteArray());
            out.write(payload);
            out.flush();
        }
    }

    private void sendRaw(int id, byte[] body) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, id);
        b.write(body, 0, body.length);
        writeFrame(b.toByteArray());
    }

    private byte[] encodeBody(Object packet) throws Exception {
        Field f = packet.getClass().getField("STREAM_CODEC");
        Object codec = f.get(null);
        Object buf = fbbCtor.newInstance(mUnpooledBuf.invoke(null));
        mCodecEncode.invoke(codec, buf, packet);
        int len = (int) mReadable.invoke(buf);
        int idx = (int) mReaderIndex.invoke(buf);
        byte[] arr = new byte[len];
        mGetBytes.invoke(buf, idx, arr);
        return arr;
    }

    /** 发送 serverbound 包。keepalive 在 CONFIG/PLAY 的 ID 不同，由当前 state 决定。 */
    private void sendPacket(String key, Object packet) throws Exception {
        String[] map = SWITCH_KEYS.get(key);
        if (map == null) throw new IllegalStateException("未知包键 " + key);
        int id;
        if ("KEEPALIVE".equals(map[0])) id = id(stateStr(), true, map[1], map[2]);
        else id = id(map[0], true, map[1], map[2]);
        if (id < 0) throw new IllegalStateException("无法解析服务端包 ID：" + key);
        byte[] body = encodeBody(packet);
        if (debugLog) plugin.getLogger().info("[自连调试] 发送 state=" + stateStr() + " " + key + " 包ID=" + id
            + " 正文=" + hex(body));
        sendRaw(id, body);
    }

    private String stateStr() {
        if (state == State.CONFIG) return "CONFIG";
        if (state == State.LOGIN) return "LOGIN";
        return "PLAY";
    }

    /* ---------------- 构造服务端包对象 ---------------- */

    private Object construct(String key, Object... prefs) {
        Class<?> cls = packetCls(key);
        if (cls == null) return null;
        // 无字段的标记包（LoginAcknowledged/FinishConfiguration）record 规范构造器是私有的，
        // 必须把 declared 构造器也纳入扫描。
        List<Constructor<?>> ctors = new ArrayList<>();
        ctors.addAll(Arrays.asList(cls.getConstructors()));
        try {
            for (Constructor<?> dc : cls.getDeclaredConstructors()) {
                boolean dup = false;
                for (Constructor<?> pc : ctors) {
                    if (Arrays.equals(pc.getParameterTypes(), dc.getParameterTypes())) { dup = true; break; }
                }
                if (!dup) ctors.add(dc);
            }
        } catch (Throwable ignored) {}
        for (Constructor<?> ctor : ctors) {
            try {
                ctor.setAccessible(true);
                Class<?>[] pts = ctor.getParameterTypes();
                Object[] args = new Object[pts.length];
                List<Object> pool = new ArrayList<>();
                for (Object p : prefs) if (p != null) pool.add(p);
                boolean ok = true;
                for (int i = 0; i < pts.length && ok; i++) {
                    Object v = null;
                    for (int j = 0; j < pool.size(); j++) {
                        if (box(pts[i]).isAssignableFrom(box(pool.get(j).getClass()))) { v = pool.remove(j); break; }
                    }
                    if (v == null) v = typeDefault(pts[i]);
                    if (v == null) { ok = false; break; }
                    args[i] = coerce(v, pts[i]);
                }
                if (ok) return ctor.newInstance(args);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object coerce(Object v, Class<?> target) {
        if (!(v instanceof Number n)) return v;
        if (target == int.class || target == Integer.class) return n.intValue();
        if (target == long.class || target == Long.class) return n.longValue();
        if (target == float.class || target == Float.class) return n.floatValue();
        if (target == double.class || target == Double.class) return n.doubleValue();
        if (target == short.class || target == Short.class) return n.shortValue();
        if (target == byte.class || target == Byte.class) return n.byteValue();
        return v;
    }

    private Object typeDefault(Class<?> t) {
        Class<?> b = box(t);
        if (b == String.class) return "en_us";
        if (b == boolean.class) return false;
        if (b == char.class) return ' ';
        if (b == byte.class) return (byte) 0;
        if (b == short.class) return (short) 0;
        if (b == int.class) return 0;
        if (b == long.class) return 0L;
        if (b == float.class) return 0f;
        if (b == double.class) return 0d;
        if (b == UUID.class) return expectedUuid != null ? expectedUuid : new UUID(0, 0);
        if (b == byte[].class) return new byte[0];
        if (b == BitSet.class) return new BitSet();
        if (b == List.class || b == Collection.class || b == Iterable.class) return new ArrayList<>();
        if (b == Set.class) return new HashSet<>();
        if (b == Map.class) return new HashMap<>();
        if (t.isEnum()) { Object[] es = t.getEnumConstants(); return es != null && es.length > 0 ? es[0] : null; }
        return tryDefault(t, "createDefault", "DEFAULT", "EMPTY", "IDLE");
    }

    private Object tryDefault(Class<?> t, String methodName, String... fieldNames) {
        try {
            for (Method m : t.getMethods()) {
                if (m.getParameterCount() == 0 && Modifier.isStatic(m.getModifiers()) && m.getName().equals(methodName)
                    && t.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null);
                }
            }
        } catch (Throwable ignored) {}
        for (String fn : fieldNames) {
            try {
                Field f = t.getField(fn);
                if (Modifier.isStatic(f.getModifiers()) && t.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == boolean.class) return Boolean.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == char.class) return Character.class;
        return t;
    }

    /* ---------------- 主循环 ---------------- */

    public boolean start(String name, UUID offlineUuid) {
        if (!initReflect()) return false;
        if (running) return true;
        this.playerName = name;
        this.expectedUuid = offlineUuid;
        this.state = State.CONNECTING;
        this.failureReason = null;
        this.serverUuid = null;
        this.tickKey = null;
        this.loggedNoTick = false;
        this.loggedNoClientInfo = false;
        this.compressionThreshold = -1;

        host = plugin.getConfig().getString("self-client.address", "127.0.0.1");
        port = resolvePort();
        if (port <= 0) { fail("找不到服务器监听端口（可在 config.yml 的 self-client.port 指定）"); return false; }
        loginTimeoutMs = Math.max(5, plugin.getConfig().getLong("self-client.login-timeout-seconds", 25)) * 1000L;
        debugLog = plugin.getConfig().getBoolean("self-client.debug-log", false);
        packetsSeen = 0; lastPacketId = -1;
        tickIntervalMs = Math.max(200, plugin.getConfig().getLong("self-client.tick-interval-ms", 1000));

        Boolean online = onlineMode();
        if (Boolean.TRUE.equals(online) && !plugin.getConfig().getBoolean("self-client.force", false)) {
            fail("服务器 online-mode=true：正版验证需要 Mojang 登录凭证，自连客户端拿不到。请保持 online-mode=false（或用 self-client.force 强行尝试）");
            return false;
        }

        running = true;
        worker = new Thread(this::run, "AiPlayer-SelfClient-" + name);
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public void close() {
        running = false;
        Thread t = tickWorker;
        if (t != null) t.interrupt();
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
    }

    private void run() {
        long deadline = System.currentTimeMillis() + loginTimeoutMs;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            if (debugLog) plugin.getLogger().info("[自连调试] TCP 已连接 " + host + ":" + port
                + "，握手 protocolVersion=" + protocolVersion + "（服务端 SharedConstants 给出的值）");
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, protocolVersion);
            writeString(hs, host);
            hs.write((port >> 8) & 0xFF); hs.write(port & 0xFF);
            writeVarInt(hs, 2); // next state: login
            writeFrame(hs.toByteArray());
            state = State.LOGIN;

            // 1.21.9+ 的 ServerboundHelloPacket 多了第二个字段 profileId（玩家 UUID）；
            // 老版本只有一个 name。construct 按参数类型挑值，多传一个 UUID 在老版本上会被忽略。
            UUID helloUuid = expectedUuid != null ? expectedUuid
                : java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
            Object loginStart = construct("pkt.login.start", playerName, helloUuid);
            if (loginStart == null) { fail("无法构造登录开始包（服务端包类结构不认识）"); return; }
            sendPacket("pkt.login.start", loginStart);

            int successId = id("LOGIN", false, LOGIN_TYPES, "CLIENTBOUND_LOGIN_FINISHED");
            int disconnectLoginId = id("LOGIN", false, LOGIN_TYPES, "CLIENTBOUND_LOGIN_DISCONNECT");
            int queryLoginId = id("LOGIN", false, LOGIN_TYPES, "CLIENTBOUND_CUSTOM_QUERY");
            int compressionId = id("LOGIN", false, LOGIN_TYPES, "CLIENTBOUND_LOGIN_COMPRESSION");
            int requiredAddId = idFuzzy("LOGIN", false, LOGIN_TYPES, "CLIENTBOUND", "ADDITION");
            int finishId = id("CONFIG", false, CONFIG_TYPES, "CLIENTBOUND_FINISH_CONFIGURATION");
            int disconnectCfgId = id("CONFIG", false, COMMON_TYPES, "CLIENTBOUND_DISCONNECT");
            int keepInCfgId = id("CONFIG", false, COMMON_TYPES, "CLIENTBOUND_KEEP_ALIVE");
            int knownPacksId = id("CONFIG", false, CONFIG_TYPES, "CLIENTBOUND_SELECT_KNOWN_PACKS");
            int conductId = idFuzzy("CONFIG", false, CONFIG_TYPES, "CLIENTBOUND", "CODE_OF_CONDUCT");
            int keepInId = id("PLAY", false, COMMON_TYPES, "CLIENTBOUND_KEEP_ALIVE");
            int teleportInId = id("PLAY", false, GAME_TYPES, "CLIENTBOUND_PLAYER_POSITION");
            if (teleportInId < 0) teleportInId = idFuzzy("PLAY", false, GAME_TYPES, "CLIENTBOUND", "POSITION");
            int sysChatId = id("PLAY", false, GAME_TYPES, "CLIENTBOUND_SYSTEM_CHAT");
            if (sysChatId < 0) sysChatId = idFuzzy("PLAY", false, GAME_TYPES, "CLIENTBOUND", "SYSTEM_CHAT");
            int playerChatId = id("PLAY", false, GAME_TYPES, "CLIENTBOUND_PLAYER_CHAT");
            if (playerChatId < 0) playerChatId = idFuzzy("PLAY", false, GAME_TYPES, "CLIENTBOUND", "PLAYER_CHAT");
            int disconnectPlayId = id("PLAY", false, COMMON_TYPES, "CLIENTBOUND_DISCONNECT");
            int reconfigId = idFuzzy("PLAY", false, GAME_TYPES, "CLIENTBOUND", "START_CONFIGURATION");
            UUID myUuid = expectedUuid;
            boolean knownPacksAnswered = false;

            DataInputStream d = new DataInputStream(in);
            while (running) {
                if (state != State.PLAYING && System.currentTimeMillis() > deadline) {
                    fail("登录流程超时（" + loginTimeoutMs / 1000 + " 秒未进入游戏状态）");
                    return;
                }
                int len = readVarInt(d);
                if (len <= 0 || len > 2_097_152) { fail("数据包长度异常：" + len); return; }
                byte[] raw = new byte[len];
                d.readFully(raw);
                byte[] data = compressionThreshold >= 0 ? decompress(raw) : raw;
                DataInputStream hdr = new DataInputStream(new ByteArrayInputStream(data));
                int id = readVarInt(hdr);
                int bodyOff = varIntSizeAt(data, 0);
                packetsSeen++; lastPacketId = id;
                if (debugLog) plugin.getLogger().info("[自连调试] 收到 state=" + state + " 包ID=" + id + " 长度=" + data.length);

                if (state == State.LOGIN) {
                    if (compressionId >= 0 && id == compressionId) {
                        compressionThreshold = readFirstVarInt(data, bodyOff);
                    } else if (id == successId) {
                        UUID u = parseUuid(tryReadString(data, bodyOff));
                        if (u != null) myUuid = u;
                        serverUuid = myUuid;
                        Object ack = construct("pkt.login.ack");
                        if (ack == null) { fail("无法构造登录确认包"); return; }
                        sendPacket("pkt.login.ack", ack);
                        state = State.CONFIG;
                        sendClientInformation(); // TCP 顺序保证服务端先处理 ack
                    } else if (disconnectLoginId >= 0 && id == disconnectLoginId) {
                        fail("服务器拒绝登录：" + flattenJson(guessString(data)));
                        return;
                    } else if (queryLoginId >= 0 && id == queryLoginId) {
                        answerQuery(data, bodyOff);
                    } else if (requiredAddId >= 0 && id == requiredAddId) {
                        Object ackAdd = construct("pkt.login.acked.additions");
                        if (ackAdd != null) try { sendPacket("pkt.login.acked.additions", ackAdd); } catch (Throwable ignored) {}
                    }
                } else if (state == State.CONFIG) {
                    if (disconnectCfgId >= 0 && id == disconnectCfgId) { fail("配置阶段被断开：" + flattenJson(guessString(data))); return; }
                    if (keepInCfgId >= 0 && id == keepInCfgId) answerKeepAlive(data, bodyOff);
                    if (knownPacksId >= 0 && id == knownPacksId && !knownPacksAnswered) {
                        knownPacksAnswered = true;
                        Object kp = construct("pkt.config.knownpacks");
                        if (kp != null) try { sendPacket("pkt.config.knownpacks", kp); } catch (Throwable ignored) {}
                    }
                    if (conductId >= 0 && id == conductId) {
                        Object co = construct("pkt.config.conduct");
                        if (co != null) try { sendPacket("pkt.config.conduct", co); } catch (Throwable ignored) {}
                    }
                    if (id == finishId) {
                        Object ack = construct("pkt.config.ack");
                        if (ack == null) { fail("无法构造配置完成确认包"); return; }
                        sendPacket("pkt.config.ack", ack);
                        state = State.PLAYING;
                        startTickWorker();
                        listener.onPlaying(playerName, myUuid);
                    }
                } else if (state == State.PLAYING) {
                    if (keepInId >= 0 && id == keepInId) {
                        answerKeepAlive(data, bodyOff);
                    } else if (teleportInId >= 0 && id == teleportInId) {
                        Object confirm = construct("pkt.play.teleport.confirm", readFirstVarInt(data, bodyOff));
                        if (confirm != null) try { sendPacket("pkt.play.teleport.confirm", confirm); } catch (Throwable ignored) {}
                    } else if (sysChatId >= 0 && id == sysChatId) {
                        String text = flattenJson(tryReadString(data, bodyOff));
                        if (text != null && !text.isEmpty()) listener.onServerMessage(playerName, text);
                    } else if (playerChatId >= 0 && id == playerChatId
                               && plugin.getConfig().getBoolean("chat.sniff-signed-chat", true)) {
                        // 签名聊天包的正文是 NBT 编码的组件，不做完整解析；
                        // 提取其中可读文字片段足够 WhisperHook 认出"×× 对你说：…"这类格式。
                        String text = guessString(java.util.Arrays.copyOfRange(data, bodyOff, data.length));
                        if (text != null && !text.isEmpty()) listener.onServerMessage(playerName, text);
                    } else if (disconnectPlayId >= 0 && id == disconnectPlayId) {
                        running = false;
                        state = State.CLOSED;
                        plugin.getLogger().info("[自连] 服务器断开了 AI 客户端：" + flattenJson(guessString(data)));
                        return;
                    } else if (reconfigId >= 0 && id == reconfigId) {
                        state = State.CONFIG; // 服务器要求重新配置
                    }
                }
            }
        } catch (EOFException e) {
            fail(state == State.PLAYING ? "连接被服务器关闭（可能被踢或服务器停机）"
                : "登录过程中连接中断（当时处于 " + state + " 阶段，共收到 " + packetsSeen
                    + " 个包，最后一个包 ID=" + lastPacketId
                    + "。可打开 self-client.debug-log 看完整握手过程；常见原因：服务器已满、名称被占用、白名单/风控拦截）");
        } catch (java.net.SocketTimeoutException e) {
            fail("60 秒没收到服务器任何数据，连接疑似挂死");
        } catch (java.io.IOException e) {
            if (running) fail("网络错误：" + e.getMessage());
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            running = false;
            if (tickWorker != null) { tickWorker.interrupt(); tickWorker = null; }
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        }
    }

    private byte[] decompress(byte[] frame) throws java.io.IOException {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(frame));
            int uncompressedLen = readVarInt(d);
            if (uncompressedLen == 0) {
                byte[] rest = new byte[d.available()];
                d.readFully(rest);
                return rest;
            }
            if (uncompressedLen > 2_097_152) throw new java.io.IOException("解压后数据过大");
            byte[] comp = new byte[d.available()];
            d.readFully(comp);
            Inflater inflater = new Inflater();
            inflater.setInput(comp);
            byte[] res = new byte[uncompressedLen];
            int got = 0;
            try {
                while (got < uncompressedLen && !inflater.finished()) {
                    int n = inflater.inflate(res, got, uncompressedLen - got);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    got += n;
                }
            } finally { inflater.end(); }
            if (got != uncompressedLen) throw new java.io.IOException("zlib 解压不完整：" + got + "/" + uncompressedLen);
            return res;
        } catch (DataFormatException e) {
            throw new java.io.IOException("zlib 解压失败：" + e.getMessage());
        }
    }

    private synchronized void startTickWorker() {
        if (tickWorker != null) return;
        tickWorker = new Thread(() -> {
            while (running && state == State.PLAYING) {
                sendClientTick();
                try { Thread.sleep(tickIntervalMs); } catch (InterruptedException ignored) { break; }
            }
        }, "AiPlayer-SelfClient-Tick-" + playerName);
        tickWorker.setDaemon(true);
        tickWorker.start();
    }

    /* ---------------- 小工具 ---------------- */

    private void fail(String reason) {
        failureReason = reason;
        state = State.FAILED;
        running = false;
        listener.onFailed(playerName, reason);
    }

    private void sendClientInformation() {
        try {
            Object info = construct("pkt.config.clientinfo");
            if (info != null) { sendPacket("pkt.config.clientinfo", info); return; }
        } catch (Throwable ignored) {}
        if (!loggedNoClientInfo) {
            loggedNoClientInfo = true;
            plugin.getLogger().info("[自连] 构造 ClientInformation 失败，跳过（服务端会用默认值）");
        }
    }

    private int resolvePort() {
        int p = plugin.getConfig().getInt("self-client.port", 0);
        if (p > 0) return p;
        try {
            Object server = plugin.getServer().getClass().getMethod("getServer").invoke(plugin.getServer());
            try { return (int) server.getClass().getMethod("getPort").invoke(server); } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        try {
            java.nio.file.Path f = java.nio.file.Paths.get("server.properties");
            if (java.nio.file.Files.isRegularFile(f)) {
                for (String line : java.nio.file.Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.startsWith("server-port=")) return Integer.parseInt(line.substring(12).trim());
                }
            }
        } catch (Throwable ignored) {}
        return 25565;
    }

    private Boolean onlineMode() {
        try {
            Object server = plugin.getServer().getClass().getMethod("getServer").invoke(plugin.getServer());
            Object props = server.getClass().getMethod("getProperties").invoke(server);
            return (Boolean) props.getClass().getMethod("getOnlineMode").invoke(props);
        } catch (Throwable t) { return null; }
    }

    private int readFirstVarInt(byte[] data, int off) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
            d.skipBytes(off);
            return readVarInt(d);
        } catch (Throwable t) { return 0; }
    }

    private void answerKeepAlive(byte[] data, int bodyOff) {
        try {
            long idv;
            if (data.length - bodyOff == 8) {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
                d.skipBytes(bodyOff);
                idv = d.readLong();
            } else {
                idv = readFirstVarInt(data, bodyOff);
            }
            Object pkt = construct("pkt.keepalive.out", idv);
            if (pkt != null) sendPacket("pkt.keepalive.out", pkt);
        } catch (Throwable t) {
            plugin.getLogger().warning("[自连] 回复保活失败：" + t);
        }
    }

    private void answerQuery(byte[] data, int bodyOff) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
            d.skipBytes(bodyOff);
            int queryId = readVarInt(d);
            String channel = tryReadString(data, bodyOff + varIntSizeAt(data, bodyOff));
            if (channel == null || (!channel.contains("brand") && !channel.contains("Brand"))) return;
            if (customAnswerPayloadItf == null) return;
            final String brand = "aiplayer-paper";
            Object payloadProxy = Proxy.newProxyInstance(cl, new Class<?>[]{customAnswerPayloadItf}, (p, m, args) -> {
                if (m.getName().equals("write") && args != null && args.length == 1) {
                    try {
                        Object buf = args[0];
                        byte[] u = brand.getBytes(StandardCharsets.UTF_8);
                        Method wi = buf.getClass().getMethod("writeVarInt", int.class);
                        wi.invoke(buf, u.length);
                        Method wb = buf.getClass().getMethod("writeBytes", byte[].class);
                        wb.invoke(buf, (Object) u);
                    } catch (Throwable ignored) {}
                }
                return null;
            });
            Object answer = construct("pkt.query.answer", queryId, payloadProxy);
            if (answer != null) sendPacket("pkt.query.answer", answer);
        } catch (Throwable ignored) {}
    }

    private int varIntSizeAt(byte[] data, int off) {
        int pos = off, count = 0;
        while (pos < data.length && count < 5) { byte b = data[pos++]; count++; if ((b & 0x80) == 0) break; }
        return count;
    }

    private int readVarInt(DataInputStream d) throws java.io.IOException {
        int value = 0, pos = 0;
        while (true) {
            int b = d.read();
            if (b == -1) throw new EOFException("连接结束");
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) return value;
            pos += 7;
            if (pos > 35) throw new java.io.IOException("varint 溢出");
        }
    }

    private String tryReadString(byte[] data, int off) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
            d.skipBytes(off);
            int len = readVarInt(d);
            if (len < 0 || len > data.length - off) return null;
            byte[] u = new byte[len];
            d.readFully(u);
            return new String(u, StandardCharsets.UTF_8);
        } catch (Throwable t) { return null; }
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String v = s.trim();
            if (v.length() == 32) v = v.replaceFirst("(?i)(.{8})(.{4})(.{4})(.{4})(.+)", "$1-$2-$3-$4-$5");
            return UUID.fromString(v);
        } catch (Throwable t) { return null; }
    }

    private String guessString(byte[] data) {
        try {
            String s = new String(data, StandardCharsets.UTF_8);
            int i = s.indexOf('{'), j = s.lastIndexOf('}');
            if (i >= 0 && j > i) return s.substring(i, j + 1);
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < s.length(); k++) {
                char ch = s.charAt(k);
                if (ch >= 0x20 && ch < 0x7F || ch >= 0x4E00 && ch <= 0x9FA5) b.append(ch);
            }
            return b.toString();
        } catch (Throwable t) { return ""; }
    }

    /** 聊天 JSON 组件 → 纯文本（收集 text/content 字段）。 */
    /** 调试用：把包正文转成十六进制（最多 96 字节）。 */
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < 96; i++) sb.append(String.format("%02x ", b[i]));
        return sb.toString().trim();
    }

    static String flattenJson(String json) {
        if (json == null || json.isEmpty()) return "";
        String s = json.trim();
        if (!s.startsWith("{") && !s.startsWith("[")) {
            if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            return unescape(s);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int key = s.indexOf("\"text\":", i);
            int key2 = s.indexOf("\"content\":", i);
            if (key < 0 && key2 < 0) break;
            int start = (key >= 0 && (key2 < 0 || key < key2)) ? key + 7 : key2 + 10;
            while (start < s.length() && (s.charAt(start) == ' ' || s.charAt(start) == '"')) start++;
            if (start >= s.length()) break;
            int end = stringEnd(s, start);
            if (end < 0) break;
            out.append(unescape(s.substring(start, end)));
            i = end;
        }
        if (out.length() == 0) {
            int t = s.indexOf("\"translate\":\"");
            if (t >= 0) { int st = t + 13, en = stringEnd(s, st); if (en > st) out.append(s, st, en); }
        }
        return out.toString();
    }

    private static int stringEnd(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> b.append('\n');
                    case 'r' -> { }
                    case 't' -> b.append('\t');
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } catch (Exception ignored) {}
                        }
                    }
                    default -> b.append(n);
                }
            } else b.append(c);
        }
        return b.toString();
    }

    /** play 状态"客户端滴答"：1.21.2+ 服务端会踢长时间不发输入/移动包的玩家。 */
    private void sendClientTick() {
        try {
            if (tickKey == null) {
                for (String key : new String[]{"pkt.play.input", "pkt.play.move.status", "pkt.play.move.pos", "pkt.play.move.posrot"}) {
                    if (packetCls(key) == null) continue;
                    if (construct(key) != null) { tickKey = key; break; }
                }
                if (tickKey == null) {
                    if (!loggedNoTick) {
                        loggedNoTick = true;
                        plugin.getLogger().warning("[自连] 找不到可构造的移动包，若 AI 被服务器踢（missing client tick packet）请反馈服务端版本");
                    }
                    return;
                }
            }
            Object pkt = construct(tickKey);
            if (pkt != null) sendPacket(tickKey, pkt);
        } catch (Throwable t) {
            if (!loggedNoTick) { loggedNoTick = true; plugin.getLogger().warning("[自连] 客户端滴答发送失败：" + t); }
        }
    }
}
