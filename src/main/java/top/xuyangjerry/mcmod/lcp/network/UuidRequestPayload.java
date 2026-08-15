package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S 客户端→服务端 UUID 请求包：
 * 客户端需要 UUID 时（如玩家聊天消息、系统消息等），将【发送者 + 纯文本】
 * 发送给服务端，由服务端用统一规则生成 UUID 并通过 MessageUuidPayload 回传。
 *
 * sender: 发送者名称（玩家名或系统标识）
 * contentText: 消息纯文本内容
 */
public record UuidRequestPayload(String sender, String contentText) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "uuid_request");

    public static final CustomPacketPayload.Type<UuidRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, UuidRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, UuidRequestPayload::sender,
            ByteBufCodecs.STRING_UTF8, UuidRequestPayload::contentText,
            UuidRequestPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}