package cn.blockforge.generated.paperaipaperaipaper;

import net.fabricmc.api.ModInitializer;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class GeneratedMod implements ModInitializer {
    public static final String MOD_ID = "paper_ai_paper_ai_paper";
    public static final RegistryKey<Item> GENERATED_ITEM_KEY =
        RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "generated_item"));
    public static final Item GENERATED_ITEM = Registry.register(
        Registries.ITEM,
        GENERATED_ITEM_KEY,
        new Item(new Item.Settings().registryKey(GENERATED_ITEM_KEY))
    );

    @Override
    public void onInitialize() {
        // 先绑定单例并注册实体，再连接服务端生命周期事件。
        AiPlayerModule module = new AiPlayerModule();
        AiPlayerModule.bind(module);
        ModRegistries.bootstrap();

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(module::start);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> module.stop());

        // 秩序维护：禁言 / 刷屏 / 广告 / 辱骂 / 作弊暗示（返回 false 拦截消息）
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(
            (message, sender, params) -> module.beforeChat(sender, message.getSignedContent()));

        // 记忆与 AI 回复（含 @提及与概率回复）
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register(
            (message, sender, params) -> module.handleChat(sender, message.getSignedContent()));

        // 临时封禁在连接时拦截；同时加载该玩家记忆
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
            (handler, sender, server) -> module.onJoin(handler.player));

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> module.tick());

        AiCommands.register();
    }
}
