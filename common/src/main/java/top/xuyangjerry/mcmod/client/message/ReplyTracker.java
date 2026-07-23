package top.xuyangjerry.mcmod.client.message;

import net.minecraft.client.GuiMessage;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 追踪回复消息与其原始消息的关联。
 * 当玩家发送回复时，通过 pendingReply 机制等待服务端回环消息，
 * 匹配成功后建立 GuiMessage -> originalMessageId 的映射。
 */
public final class ReplyTracker {
    private static final ReplyTracker INSTANCE = new ReplyTracker();

    private final Set<GuiMessage> processedMessages = Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakHashMap<GuiMessage, String> replyToOriginal = new WeakHashMap<>();
    private PendingReply pendingReply = null;

    private ReplyTracker() {
    }

    public static ReplyTracker getInstance() {
        return INSTANCE;
    }

    /**
     * 发送回复前调用，设置待匹配的回复信息。
     */
    public synchronized void setPendingReply(PendingReply pending) {
        this.pendingReply = pending;
    }

    /**
     * 当检测到新消息时调用。如果匹配 pendingReply，则建立关联。
     */
    public synchronized void onNewMessage(GuiMessage message, String messageId) {
        if (!processedMessages.add(message)) {
            return; // 已处理过
        }

        PendingReply pending = this.pendingReply;
        if (pending == null) {
            return;
        }

        // 超时清理（10秒）
        if (System.currentTimeMillis() - pending.createdAt > 10000L) {
            this.pendingReply = null;
            return;
        }

        // 通过 MessageInfo 提取消息内容（chat.type.text 的 args[1]）
        MessageInfo info = MessageInfo.from(message);
        if (info != null && pending.replyText.equals(info.getContent())) {
            replyToOriginal.put(message, pending.originalMessageId);
            this.pendingReply = null;
        }
    }

    /**
     * 获取回复消息对应的原消息 ID。
     *
     * @return originalMessageId，如果不是回复消息则返回 null
     */
    public synchronized String getOriginalMessageId(GuiMessage replyMessage) {
        return replyToOriginal.get(replyMessage);
    }

    public synchronized void clear() {
        processedMessages.clear();
        replyToOriginal.clear();
        pendingReply = null;
    }

    public static class PendingReply {
        public final String originalMessageId;
        public final String originalSender;
        public final String originalContent;
        public final String replyText;
        public final long createdAt;

        public PendingReply(String originalMessageId, String originalSender,
                            String originalContent, String replyText) {
            this.originalMessageId = originalMessageId;
            this.originalSender = originalSender;
            this.originalContent = originalContent;
            this.replyText = replyText;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
