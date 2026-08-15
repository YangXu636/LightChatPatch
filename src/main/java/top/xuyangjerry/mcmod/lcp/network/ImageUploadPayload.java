package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S 玩家上传图片数据包：客户端将玩家发送的图片（PNG 字节数组）发送给服务端。
 * 服务端收到后转发给 NoneBot（WebSocket）和其他安装了 mod 的客户端。
 */
public record ImageUploadPayload(byte[] imageData, int width, int height) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "image_upload");

    public static final CustomPacketPayload.Type<ImageUploadPayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.byteArray(8 * 1024 * 1024), ImageUploadPayload::imageData,
                    ByteBufCodecs.INT, ImageUploadPayload::width,
                    ByteBufCodecs.INT, ImageUploadPayload::height,
                    ImageUploadPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
