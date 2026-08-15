package top.xuyangjerry.mcmod.lcp.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并转发消息数据包（C2S 和 S2C 双向复用）。
 *
 * C2S: 客户端发送转发请求（转发者→目标玩家）
 * S2C: 服务端转发给目标玩家（目标玩家客户端渲染合并转发卡片）
 *
 * 消息列表按时间顺序（早→晚）。
 */
public record ForwardMessagePayload(
        String forwarder,
        String target,
        List<ForwardedMessage> messages,
        String uuid
) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "forward_message");

    public static final CustomPacketPayload.Type<ForwardMessagePayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    /**
     * 问题6：target 字段的特殊值，代表"全体成员"。
     * 放在共享网络包类中，避免客户端/服务端互相引用包。
     */
    public static final String TARGET_ALL_MEMBERS = "@all";

    public static final StreamCodec<RegistryFriendlyByteBuf, ForwardedMessage> MSG_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ForwardedMessage::sender,
                    ByteBufCodecs.STRING_UTF8, ForwardedMessage::content,
                    ByteBufCodecs.INT, ForwardedMessage::addedTime,
                    ForwardedMessage::new
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, ForwardMessagePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ForwardMessagePayload::forwarder,
                    ByteBufCodecs.STRING_UTF8, ForwardMessagePayload::target,
                    MSG_CODEC.apply(ByteBufCodecs.list()), ForwardMessagePayload::messages,
                    ByteBufCodecs.STRING_UTF8, ForwardMessagePayload::uuid,
                    ForwardMessagePayload::new
            );

    /** 兼容旧调用：不传 uuid 时置空 */
    public ForwardMessagePayload(String forwarder, String target, List<ForwardedMessage> messages) {
        this(forwarder, target, messages, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static ForwardMessagePayload fromMessages(String forwarder, String target,
                                                     List<ForwardedMessage> messages) {
        return new ForwardMessagePayload(forwarder, target, new ArrayList<>(messages));
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * 将本 payload 序列化为 JSON 字符串，用于历史持久化。
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化为 ForwardMessagePayload，用于历史恢复。
     */
    public static ForwardMessagePayload fromJson(String json) {
        return GSON.fromJson(json, ForwardMessagePayload.class);
    }

    public record ForwardedMessage(String sender, String content, int addedTime) {
    }
}
