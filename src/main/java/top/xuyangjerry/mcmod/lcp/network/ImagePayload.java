package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C 图片数据包：
 * - sender == null: NoneBot 发来的图片
 * - sender != null: 玩家上传的图片，显示为 <Sender> [图片] 格式
 */
public record ImagePayload(byte[] imageData, int width, int height, String sender, String uuid) implements CustomPacketPayload {

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "image");

    public static final CustomPacketPayload.Type<ImagePayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ImagePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.byteArray(8 * 1024 * 1024), ImagePayload::imageData,
                    ByteBufCodecs.INT, ImagePayload::width,
                    ByteBufCodecs.INT, ImagePayload::height,
                    ByteBufCodecs.STRING_UTF8, ImagePayload::sender,
                    ByteBufCodecs.STRING_UTF8, ImagePayload::uuid,
                    ImagePayload::new
            );

    /** 兼容旧调用：不传 uuid 时置空 */
    public ImagePayload(byte[] imageData, int width, int height, String sender) {
        this(imageData, width, height, sender, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
