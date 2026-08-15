package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * S2C：服务端 → 客户端，同步"当前有哪些已安装 LCP mod 的玩家列表"。
 * <p>
 * 客户端 ForwardTargetScreen（转发选人界面）使用此列表来展示"可转发目标"：
 *   未装 mod 的玩家（即使在线）不会出现在列表中，
 *   因为未装 mod 的客户端即使收到 ForwardMessagePayload 也不会渲染转发卡片。
 * <p>
 * 触发时机：
 *   - 玩家刚加入并完成 ClientHello（mod握手）时
 *   - 玩家离开服务端时
 *   - 某个其他玩家的 mod 客户端连接/断开握手时
 */
public record SyncModdedPlayersPayload(List<ModdedPlayerEntry> entries) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "sync_modded_players");

    public static final CustomPacketPayload.Type<SyncModdedPlayersPayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    /** 单个条目：玩家名称、UUID、mod 版本号（版本号用于 UI 提示或未来扩展）。 */
    public record ModdedPlayerEntry(String name, UUID uuid, String version) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ModdedPlayerEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ModdedPlayerEntry::name,
                        UUIDUtil.STREAM_CODEC, ModdedPlayerEntry::uuid,
                        ByteBufCodecs.STRING_UTF8, ModdedPlayerEntry::version,
                        ModdedPlayerEntry::new
                );
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncModdedPlayersPayload> CODEC =
            StreamCodec.composite(
                    ModdedPlayerEntry.CODEC.apply(ByteBufCodecs.list()), SyncModdedPlayersPayload::entries,
                    SyncModdedPlayersPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
