package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C 消息UUID同步包：服务器为原版玩家消息生成统一UUID后，
 * 通过此Payload发送给所有装mod客户端，客户端在聊天消息列表中
 * 查找内容匹配的消息并注册该UUID，确保回复跳转跨客户端一致。
 *
 * uuid: 服务器生成的消息UUID
 * contentText: 消息纯文本内容（用于在客户端allMessages中匹配对应的GuiMessage）
 */
public record MessageUuidPayload(String uuid, String contentText) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "message_uuid");

    public static final CustomPacketPayload.Type<MessageUuidPayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MessageUuidPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, MessageUuidPayload::uuid,
                    ByteBufCodecs.STRING_UTF8, MessageUuidPayload::contentText,
                    MessageUuidPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
