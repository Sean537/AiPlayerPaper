package cn.blockforge.generated.paperaipaperaipaper;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/** AI 展示人偶：1.21.11 原版人偶（玩家皮肤渲染）子类，头顶名牌显示状态与心情。 */
public final class AiAvatarEntity extends MannequinEntity {
    public AiAvatarEntity(EntityType<MannequinEntity> type, World world) {
        super(type, world);
    }

    /** 用在线玩家的完整档案（含皮肤纹理）渲染。 */
    public void applyProfile(ServerPlayerEntity source) {
        setApplicableComponent(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(source.getGameProfile()));
    }

    /** 用玩家名渲染（正版服务器会自动解析皮肤）。 */
    public void applyDynamicSkin(String playerName) {
        setApplicableComponent(DataComponentTypes.PROFILE, ProfileComponent.ofDynamic(playerName));
    }

    public void setStatus(Text status) {
        setCustomName(status);
        setCustomNameVisible(true);
    }

    public void turnHeadTo(float targetYaw) {
        setBodyYaw(targetYaw);
        setHeadYaw(targetYaw);
        setYaw(targetYaw);
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }
}
