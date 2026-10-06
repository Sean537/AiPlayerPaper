package cn.blockforge.generated.paperaipaperaipaper;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/** 注册 AI 展示人偶实体类型（服务端注册，客户端无需安装模组内容，仅渲染人偶）。 */
public final class ModRegistries {
    public static final RegistryKey<EntityType<?>> AI_AVATAR_KEY =
        RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(GeneratedMod.MOD_ID, "ai_avatar"));

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final EntityType<AiAvatarEntity> AI_AVATAR_TYPE = Registry.register(
        Registries.ENTITY_TYPE,
        AI_AVATAR_KEY,
        (EntityType) EntityType.Builder.<AiAvatarEntity>create(
                (type, world) -> new AiAvatarEntity((EntityType<MannequinEntity>) (EntityType<?>) type, world),
                SpawnGroup.MISC)
            .dimensions(0.6f, 1.95f)
            .maxTrackingRange(64)
            .disableSaving()
            .build(AI_AVATAR_KEY));

    private ModRegistries() { }

    public static void bootstrap() {
        FabricDefaultAttributeRegistry.register(AI_AVATAR_TYPE,
            LivingEntity.createLivingAttributes()
                .add(EntityAttributes.MAX_HEALTH, 20.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.0));
    }
}
