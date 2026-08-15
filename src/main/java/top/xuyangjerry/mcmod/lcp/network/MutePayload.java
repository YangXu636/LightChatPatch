package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import top.xuyangjerry.mcmod.lcp.fabric.LightChatPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 禁言系统双向网络 Payload（MutePayload）
 *
 * 支持双向：
 *   C2S：OP 玩家从客户端 GUI / 右键菜单 发起禁言/解禁请求
 *   S2C：服务端向所有 OP 玩家同步当前禁言列表
 *
 * action 取值：
 *   "sync"    (S2C)：服务端同步完整禁言列表给 OP 客户端，entries 非空
 *   "mute"    (C2S)：客户端请求禁言目标玩家，targetName/targetUuid/reason 非空（reason 可空）
 *   "unmute"  (C2S)：客户端请求解禁目标玩家，targetName/targetUuid 非空
 *   "list"    (C2S)：客户端请求列表（服务端收到后立即发 sync 回包，可留空由 sync 周期/触发式处理）
 */
public record MutePayload(
        String action,
        List<MuteEntry> entries,
        String targetName,
        String targetUuid,
        String reason
) implements CustomPacketPayload {

    public static final String ACTION_SYNC = "sync";
    public static final String ACTION_MUTE = "mute";
    public static final String ACTION_UNMUTE = "unmute";
    public static final String ACTION_LIST = "list";

    /** 禁言列表条目（用于 S2C 同步） */
    public record MuteEntry(
            String targetName,    // 被禁言玩家名
            String targetUuid,    // 被禁言玩家 UUID 字符串
            String muterName,     // 执行禁言的玩家名
            String reason,        // 禁言原因（可空）
            long time             // 禁言时间戳（System.currentTimeMillis）
    ) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MuteEntry> CODEC = StreamCodec.of(
                (buf, e) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.targetName() != null ? e.targetName() : "");
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.targetUuid() != null ? e.targetUuid() : "");
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.muterName() != null ? e.muterName() : "");
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.reason() != null ? e.reason() : "");
                    buf.writeLong(e.time());
                },
                (buf) -> new MuteEntry(
                        readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf)),
                        readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf)),
                        readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf)),
                        readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf)),
                        buf.readLong()
                )
        );

        private static String readStrOrNull(String s) {
            return (s != null && s.isEmpty()) ? null : s;
        }
    }

    public static final CustomPacketPayload.Type<MutePayload> TYPE =
            new CustomPacketPayload.Type<>(LightChatPatch.id("mute"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MutePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.action() != null ? payload.action() : "");
                // entries list：-1 表示 null，>=0 表示长度
                List<MuteEntry> list = payload.entries();
                buf.writeInt(list == null ? -1 : list.size());
                if (list != null) {
                    for (MuteEntry e : list) {
                        MuteEntry.CODEC.encode(buf, e);
                    }
                }
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.targetName() != null ? payload.targetName() : "");
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.targetUuid() != null ? payload.targetUuid() : "");
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.reason() != null ? payload.reason() : "");
            },
            (buf) -> {
                String action = ByteBufCodecs.STRING_UTF8.decode(buf);
                int listLen = buf.readInt();
                List<MuteEntry> entries = null;
                if (listLen >= 0) {
                    entries = new ArrayList<>(listLen);
                    for (int i = 0; i < listLen; i++) {
                        entries.add(MuteEntry.CODEC.decode(buf));
                    }
                }
                String targetName = readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf));
                String targetUuid = readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf));
                String reason = readStrOrNull(ByteBufCodecs.STRING_UTF8.decode(buf));
                return new MutePayload(
                        action.isEmpty() ? null : action,
                        entries,
                        targetName,
                        targetUuid,
                        reason
                );
            }
    );

    private static String readStrOrNull(String s) {
        return (s != null && s.isEmpty()) ? null : s;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
