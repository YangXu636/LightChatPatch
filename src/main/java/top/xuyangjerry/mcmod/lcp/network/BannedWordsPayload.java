package top.xuyangjerry.mcmod.lcp.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * BWS = Banned Words 违禁词系统网络包
 *
 * 双向使用：
 *   S2C：服务端 → OP 玩家客户端，ACTION_SYNC 同步完整词库
 *   C2S：OP 玩家客户端 → 服务端，ACTION_ADD/REMOVE/MODIFY 增删改词库
 *
 * 字段：
 *   action：操作类型（SYNC / ADD / REMOVE / MODIFY / LIST）
 *   words：  ACTION_SYNC 时携带完整词库；其他动作可为 null
 *   word：   ADD/REMOVE 时的词语，或 MODIFY 时的旧词
 *   word2：  MODIFY 时的新词
 */
public record BannedWordsPayload(
        String action,
        List<String> words,
        String word,
        String word2
) implements CustomPacketPayload {

    // 操作类型常量
    public static final String ACTION_SYNC = "sync";     // S2C：同步完整词库
    public static final String ACTION_ADD = "add";       // C2S：添加
    public static final String ACTION_REMOVE = "remove"; // C2S：删除
    public static final String ACTION_MODIFY = "modify"; // C2S：修改
    public static final String ACTION_LIST = "list";     // 预留（客户端本地即可查看）

    public static final Identifier PAYLOAD_ID =
            Identifier.fromNamespaceAndPath("light_chat_patch", "banned_words");

    public static final CustomPacketPayload.Type<BannedWordsPayload> TYPE =
            new CustomPacketPayload.Type<>(PAYLOAD_ID);

    /**
     * 自定义 StreamCodec：List 可能为 null（C2S 动作不带词库），
     * 使用 int 前缀：-1 表示 null，>=0 表示列表长度。
     * 空字符串表示该 String 字段为 null。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BannedWordsPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                // action
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.action() != null ? payload.action() : "");
                // words: -1 = null, >=0 = list size
                List<String> w = payload.words();
                if (w == null) {
                    buf.writeInt(-1);
                } else {
                    buf.writeInt(w.size());
                    for (String s : w) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, s != null ? s : "");
                    }
                }
                // word
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.word() != null ? payload.word() : "");
                // word2
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.word2() != null ? payload.word2() : "");
            },
            (buf) -> {
                String action = ByteBufCodecs.STRING_UTF8.decode(buf);
                int listLen = buf.readInt();
                List<String> words = null;
                if (listLen >= 0) {
                    words = new ArrayList<>(listLen);
                    for (int i = 0; i < listLen; i++) {
                        words.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                    }
                }
                String w1 = ByteBufCodecs.STRING_UTF8.decode(buf);
                String w2 = ByteBufCodecs.STRING_UTF8.decode(buf);
                return new BannedWordsPayload(
                        action.isEmpty() ? null : action,
                        words,
                        w1.isEmpty() ? null : w1,
                        w2.isEmpty() ? null : w2
                );
            }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
