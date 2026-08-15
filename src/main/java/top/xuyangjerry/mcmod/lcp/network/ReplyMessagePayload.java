package top.xuyangjerry.mcmod.lcp.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 回复消息数据包（C2S 和 S2C 双向复用）。
 *
 * C2S: 客户端发送回复请求（回复者→服务端）
 * S2C: 服务端转发给装mod玩家（客户端渲染内联回复格式）
 *
 * 未装mod玩家由服务端发送纯文本系统消息。
 */
public record ReplyMessagePayload(
        String sender,
        String originalSender,
        String originalContent,
        String replyContent,
        String originalMessageUuid,
        String replyMessageUuid
) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "reply_message");

    public static final CustomPacketPayload.Type<ReplyMessagePayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ReplyMessagePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ReplyMessagePayload::sender,
                    ByteBufCodecs.STRING_UTF8, ReplyMessagePayload::originalSender,
                    ByteBufCodecs.STRING_UTF8, ReplyMessagePayload::originalContent,
                    ByteBufCodecs.STRING_UTF8, ReplyMessagePayload::replyContent,
                    ByteBufCodecs.STRING_UTF8, ReplyMessagePayload::originalMessageUuid,
                    ByteBufCodecs.STRING_UTF8, ReplyMessagePayload::replyMessageUuid,
                    ReplyMessagePayload::new
            );

    /** 兼容旧调用：不传 replyMessageUuid 时置空 */
    public ReplyMessagePayload(String sender, String originalSender, String originalContent,
                               String replyContent, String originalMessageUuid) {
        this(sender, originalSender, originalContent, replyContent, originalMessageUuid, "");
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 将本 payload 序列化为 JSON 字符串，用于历史持久化。
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化为 ReplyMessagePayload，用于历史恢复。
     */
    public static ReplyMessagePayload fromJson(String json) {
        return GSON.fromJson(json, ReplyMessagePayload.class);
    }
}

