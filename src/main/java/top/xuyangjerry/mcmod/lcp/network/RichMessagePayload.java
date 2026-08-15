package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C 富文本消息包：NoneBot 发送的结构化消息（非图片）。
 * messageType: "chat" | "system" | "command"
 * sender: 消息来源名称（如 NoneBot 名称）
 * content: 消息文本内容
 *
 * 注意：字段名不能用 "type"，会与 CustomPacketPayload.type() 方法冲突。
 */
public record RichMessagePayload(String messageType, String sender, String content, String uuid) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "rich_message");

    public static final CustomPacketPayload.Type<RichMessagePayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, RichMessagePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RichMessagePayload::messageType,
                    ByteBufCodecs.STRING_UTF8, RichMessagePayload::sender,
                    ByteBufCodecs.STRING_UTF8, RichMessagePayload::content,
                    ByteBufCodecs.STRING_UTF8, RichMessagePayload::uuid,
                    RichMessagePayload::new
            );

    /** 兼容旧调用：不传 uuid 时置空（客户端会用 contentJson 自行计算） */
    public RichMessagePayload(String messageType, String sender, String content) {
        this(messageType, sender, content, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
