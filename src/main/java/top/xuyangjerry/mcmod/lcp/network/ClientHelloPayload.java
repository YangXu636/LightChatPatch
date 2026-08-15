package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S 客户端握手包：客户端发送给服务端，告知已安装本 mod。
 * 服务端通过是否收到此包来判断客户端是否安装了 mod，
 * 从而决定发送原版聊天消息还是自定义数据包。
 */
public record ClientHelloPayload(String version) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "client_hello");

    public static final CustomPacketPayload.Type<ClientHelloPayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientHelloPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientHelloPayload::version,
                    ClientHelloPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
