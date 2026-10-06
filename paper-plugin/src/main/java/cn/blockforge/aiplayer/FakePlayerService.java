package cn.blockforge.aiplayer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通过服务端内核（net.minecraft）反射创建"真玩家级"假玩家：
 * 会进 tab 列表、带皮肤、有名字、可被其他插件当玩家对待。
 * 实现参照原版游戏测试的 makeMockServerPlayerInLevel 配方：
 * GameProfile -> CommonListenerCookie.createInitial -> ServerPlayer
 * -> Connection(SERVERBOUND) + EmbeddedChannel -> PlayerList.placeNewPlayer
 */
public final class FakePlayerService {
    private final AiPlayerPlugin plugin;

    private Class<?> clsGameProfile, clsProperty, clsConnection, clsPacketFlow, clsCookie,
            clsClientInformation, clsServerPlayer, clsPlayerList, clsServerLevel, clsMinecraftServer,
            clsCraftWorld, clsEmbeddedChannel, clsChannelHandler, clsKeepAliveIn, clsKeepAliveOut,
            clsSgpli, clsComponent, clsAdventureComponent, clsSystemChat;
    private Class<?> removeReasonType;
    private Method mGetServer, mPlayerList, mGetHandle, mCreateInitial, mPlaceNewPlayer, mRemovePlayer,
            mProfileProps, mCookieClientInfo, mComponentLiteral, mAdventureText, mComponentGetString, mHandleKeepAlive, mKeepAliveGetId,
            mOutboundMessages, mRelease, mPlayerGetHandle, mSystemContent;
    private Field fConnChannel, fSgpliKeepAlive, fPendingQueue, fSystemContent;
    private SystemMessageListener systemMessageListener;
    private Object enumServerbound;
    private boolean initDone, supported;
    private String unsupportedReason;

    private static final class Session {
        Object connection, embeddedChannel, listener;
    }
    private final Map<Player, Session> byPlayer = new HashMap<>();
    private BukkitTask keepAliveTask;

    public FakePlayerService(AiPlayerPlugin plugin) { this.plugin = plugin; }

    public interface SystemMessageListener {
        void onMessage(Player player, String text);
    }

    public void setSystemMessageListener(SystemMessageListener listener) {
        this.systemMessageListener = listener;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private void resolveRemoveMethod() throws NoSuchMethodException {
        Method fallbackOneArg = null;
        Method fallbackVanilla = null;
        Method fallbackAdventure = null;
        for (Method method : clsPlayerList.getMethods()) {
            if (!method.getName().equals("remove")) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(clsServerPlayer)) {
                fallbackOneArg = method;
            } else if (p.length == 2 && p[0].isAssignableFrom(clsServerPlayer)) {
                if (clsAdventureComponent != null && p[1].isAssignableFrom(clsAdventureComponent)) {
                    fallbackAdventure = method;
                } else if (p[1].isAssignableFrom(clsComponent)) {
                    fallbackVanilla = method;
                }
            }
        }
        if (fallbackAdventure != null) {
            mRemovePlayer = fallbackAdventure;
            removeReasonType = clsAdventureComponent;
        } else if (fallbackVanilla != null) {
            mRemovePlayer = fallbackVanilla;
            removeReasonType = clsComponent;
        } else if (fallbackOneArg != null) {
            mRemovePlayer = fallbackOneArg;
            removeReasonType = null;
        } else {
            throw new NoSuchMethodException("PlayerList.remove(ServerPlayer[, Component])");
        }
    }

    private Object removeReason(String reason) throws Exception {
        String text = reason == null ? "" : reason;
        if (removeReasonType == null) return null;
        if (clsAdventureComponent != null && removeReasonType.isAssignableFrom(clsAdventureComponent) && mAdventureText != null) {
            return mAdventureText.invoke(null, text);
        }
        return mComponentLiteral.invoke(null, text);
    }

    private boolean init() {
        if (initDone) return supported;
        initDone = true;
        try {
            ClassLoader cl = plugin.getClass().getClassLoader();
            clsGameProfile = Class.forName("com.mojang.authlib.GameProfile", true, cl);
            clsProperty = Class.forName("com.mojang.authlib.properties.Property", true, cl);
            clsConnection = Class.forName("net.minecraft.network.Connection", true, cl);
            clsPacketFlow = Class.forName("net.minecraft.network.protocol.PacketFlow", true, cl);
            clsCookie = Class.forName("net.minecraft.server.network.CommonListenerCookie", true, cl);
            clsClientInformation = Class.forName("net.minecraft.server.level.ClientInformation", true, cl);
            clsServerPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", true, cl);
            clsPlayerList = Class.forName("net.minecraft.server.players.PlayerList", true, cl);
            clsServerLevel = Class.forName("net.minecraft.server.level.ServerLevel", true, cl);
            clsMinecraftServer = Class.forName("net.minecraft.server.MinecraftServer", true, cl);
            clsCraftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld", true, cl);
            clsEmbeddedChannel = Class.forName("io.netty.channel.embedded.EmbeddedChannel", true, cl);
            clsChannelHandler = Class.forName("io.netty.channel.ChannelHandler", true, cl);
            clsKeepAliveIn = Class.forName("net.minecraft.network.protocol.common.ClientboundKeepAlivePacket", true, cl);
            clsKeepAliveOut = Class.forName("net.minecraft.network.protocol.common.ServerboundKeepAlivePacket", true, cl);
            clsSgpli = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl", true, cl);
            clsComponent = Class.forName("net.minecraft.network.chat.Component", true, cl);
            try {
                clsAdventureComponent = Class.forName("net.kyori.adventure.text.Component", true, cl);
                mAdventureText = clsAdventureComponent.getMethod("text", String.class);
            } catch (Throwable ignored) {
                clsAdventureComponent = null;
                mAdventureText = null;
            }

            try {
                clsSystemChat = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket", true, cl);
                fSystemContent = findField(clsSystemChat, "content");
                try { mSystemContent = clsSystemChat.getMethod("content"); } catch (NoSuchMethodException ignored) {}
                mComponentGetString = clsComponent.getMethod("getString");
            } catch (Throwable t) {
                clsSystemChat = null;
                plugin.getLogger().warning("无法监听发给 AI 的服务器消息，提示响应功能不可用：" + t.getClass().getSimpleName());
            }

            mGetServer = Bukkit.getServer().getClass().getMethod("getServer");
            mPlayerList = clsMinecraftServer.getMethod("getPlayerList");
            mGetHandle = clsCraftWorld.getMethod("getHandle");
            mPlayerGetHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer", true, cl).getMethod("getHandle");
            mCreateInitial = clsCookie.getMethod("createInitial", clsGameProfile, boolean.class);
            mCookieClientInfo = clsCookie.getMethod("clientInformation");
            mPlaceNewPlayer = clsPlayerList.getMethod("placeNewPlayer", clsConnection, clsServerPlayer, clsCookie);
            resolveRemoveMethod();
            mProfileProps = clsGameProfile.getMethod("properties");
            mComponentLiteral = clsComponent.getMethod("literal", String.class);
            mHandleKeepAlive = clsSgpli.getMethod("handleKeepAlive", clsKeepAliveOut);
            mKeepAliveGetId = clsKeepAliveIn.getMethod("getId");
            mOutboundMessages = clsEmbeddedChannel.getMethod("outboundMessages");
            mRelease = Class.forName("io.netty.util.ReferenceCountUtil", true, cl).getMethod("release", Object.class);

            fConnChannel = clsConnection.getField("channel");
            fSgpliKeepAlive = findField(clsSgpli, "keepAlive");
            if (fSgpliKeepAlive != null) fPendingQueue = fSgpliKeepAlive.getType().getField("pendingKeepAlives");

            for (Object e : clsPacketFlow.getEnumConstants()) {
                if (((Enum<?>) e).name().equals("SERVERBOUND")) { enumServerbound = e; break; }
            }
            supported = true;
        } catch (Throwable t) {
            supported = false;
            unsupportedReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().warning("内核反射方式不可用（服务端内部类签名与本插件不匹配），自动改用其他进服方式：" + unsupportedReason);
        }
        return supported;
    }

    public boolean supported() { return init(); }
    public String unsupportedReason() { return unsupportedReason; }

    /** 让 AI 以玩家身份进入世界。texturesB64 为 Mojang 纹理属性值（base64 JSON），可为 null。 */
    public Player join(String name, UUID uuid, String texturesB64, Location location, GameMode mode) {
        if (!init()) return null;
        try {
            Object server = mGetServer.invoke(Bukkit.getServer());
            Object playerList = mPlayerList.invoke(server);
            World world = location.getWorld();
            Object worldHandle = mGetHandle.invoke(world);

            Object profile = clsGameProfile.getConstructor(UUID.class, String.class).newInstance(uuid, name);
            if (texturesB64 != null && !texturesB64.isBlank()) {
                Object props = mProfileProps.invoke(profile);
                Object prop = clsProperty.getConstructor(String.class, String.class).newInstance("textures", texturesB64);
                props.getClass().getMethod("put", Object.class, Object.class).invoke(props, "textures", prop);
            }
            Object cookie = mCreateInitial.invoke(null, profile, false);
            Object clientInfo = mCookieClientInfo.invoke(cookie);

            Object player = clsServerPlayer
                    .getConstructor(clsMinecraftServer, clsServerLevel, clsGameProfile, clsClientInformation)
                    .newInstance(server, worldHandle, profile, clientInfo);

            Object connection = clsConnection.getConstructor(clsPacketFlow).newInstance(enumServerbound);
            Object handlers = Array.newInstance(clsChannelHandler, 0);
            Object embedded = clsEmbeddedChannel.getConstructor(clsChannelHandler.arrayType()).newInstance(handlers);
            fConnChannel.set(connection, embedded);
            try {
                Class<?> localAddress = Class.forName("io.netty.channel.local.LocalAddress", true, plugin.getClass().getClassLoader());
                clsConnection.getField("address").set(connection, localAddress.getConstructor(String.class).newInstance("aiplayer"));
            } catch (Throwable ignored) {}

            mPlaceNewPlayer.invoke(playerList, connection, player, cookie);

            Player bukkit = Bukkit.getPlayer(uuid);
            if (bukkit == null) {
                plugin.getLogger().severe("假玩家已加入但未能找回 Bukkit 对象，服务端版本可能不兼容。");
                return null;
            }
            if (mode != null) bukkit.setGameMode(mode);
            bukkit.teleport(location);

            Session s = new Session();
            s.connection = connection; s.embeddedChannel = embedded;
            try { s.listener = clsServerPlayer.getField("connection").get(player); } catch (Throwable ignored) {}
            byPlayer.put(bukkit, s);
            startKeepAlivePump();
            return bukkit;
        } catch (Throwable t) {
            Throwable root = t; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            plugin.getLogger().severe("创建真玩家级 AI 失败：" + root);
            return null;
        }
    }

    /** 让 AI 离开世界（等价于玩家正常退出，其他插件会收到退服事件）。 */
    public void disconnect(Player player, String reason) {
        if (!supported) return;
        byPlayer.remove(player);
        try {
            Object server = mGetServer.invoke(Bukkit.getServer());
            Object playerList = mPlayerList.invoke(server);
            Object nms = mPlayerGetHandle.invoke(player);
            if (mRemovePlayer.getParameterCount() == 1) {
                mRemovePlayer.invoke(playerList, nms);
            } else {
                mRemovePlayer.invoke(playerList, nms, removeReason(reason));
            }
        } catch (Throwable t) {
            try {
                player.kick(net.kyori.adventure.text.Component.text(reason == null ? "" : reason));
            } catch (Throwable t2) {
                plugin.getLogger().warning("移除假玩家失败：" + t);
            }
        }
        if (byPlayer.isEmpty()) stopKeepAlivePump();
    }

    /* ---------- 心跳泵：替假玩家回复保活包，防止被判定超时踢出 ---------- */

    private void startKeepAlivePump() {
        if (keepAliveTask == null) keepAliveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 20L, 10L);
    }
    private void stopKeepAlivePump() {
        if (keepAliveTask != null) { keepAliveTask.cancel(); keepAliveTask = null; }
    }

    private void pump() {
        for (Map.Entry<Player, Session> e : new ArrayList<>(byPlayer.entrySet())) {
            Player p = e.getKey(); Session s = e.getValue();
            if (p == null || !p.isOnline()) { byPlayer.remove(p); continue; }
            try {
                Object outbound = mOutboundMessages.invoke(s.embeddedChannel);
                Collection<?> queue = (Collection<?>) outbound;
                List<Object> snapshot = new ArrayList<>(queue);
                queue.clear();
                Object listener = s.listener != null ? s.listener : mPlayerGetHandle.invoke(p).getClass().getField("connection").get(mPlayerGetHandle.invoke(p));
                for (Object msg : snapshot) {
                    try {
                        if (clsKeepAliveIn.isInstance(msg)) {
                            long id = (long) mKeepAliveGetId.invoke(msg);
                            Object reply = clsKeepAliveOut.getConstructor(long.class).newInstance(id);
                            mHandleKeepAlive.invoke(listener, reply);
                        } else {
                            if (clsSystemChat != null && clsSystemChat.isInstance(msg) && systemMessageListener != null) {
                                String text = systemMessageText(msg);
                                if (text != null && !text.isBlank()) systemMessageListener.onMessage(p, text);
                            }
                            if (msg != null && mRelease != null) {
                                try { mRelease.invoke(null, msg); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                // 双保险：清空未确认保活记录，防止 keepalive timeout
                if (listener != null && fSgpliKeepAlive != null && fPendingQueue != null) {
                    try {
                        Object ka = fSgpliKeepAlive.get(listener);
                        if (ka != null) ((Collection<?>) fPendingQueue.get(ka)).clear();
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        if (byPlayer.isEmpty()) stopKeepAlivePump();
    }

    private String systemMessageText(Object packet) {
        try {
            Object component = fSystemContent != null ? fSystemContent.get(packet)
                : mSystemContent != null ? mSystemContent.invoke(packet) : null;
            return component == null || mComponentGetString == null ? null : (String) mComponentGetString.invoke(component);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 构造 Mojang 纹理属性值（base64 JSON）。slim=true 时使用细手臂模型。 */
    public static String textureValueFromUrl(String skinUrl, boolean slim) {
        String safeUrl = skinUrl.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + safeUrl + "\""
                + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        return java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
