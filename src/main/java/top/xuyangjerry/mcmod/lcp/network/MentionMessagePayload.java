package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * @提及消息数据包（C2S 和 S2C 双向复用）。
 *
 * C2S: 客户端发送含@的消息（发送者→服务端）
 * S2C: 服务端转发给装mod玩家（客户端渲染高亮@ + 声音提示）
 *
 * 未装mod玩家由服务端发送纯文本系统消息。
 * 被@的装mod玩家收到后额外播放声音提示。
 */
public record MentionMessagePayload(
        String sender,
        String content,
        List<String> mentionedPlayers,
        String uuid
) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "mention_message");

    public static final CustomPacketPayload.Type<MentionMessagePayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MentionMessagePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, MentionMessagePayload::sender,
                    ByteBufCodecs.STRING_UTF8, MentionMessagePayload::content,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), MentionMessagePayload::mentionedPlayers,
                    ByteBufCodecs.STRING_UTF8, MentionMessagePayload::uuid,
                    MentionMessagePayload::new
            );

    /** 兼容旧调用：不传 uuid 时置空 */
    public MentionMessagePayload(String sender, String content, List<String> mentionedPlayers) {
        this(sender, content, mentionedPlayers, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
