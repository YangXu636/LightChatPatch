package top.xuyangjerry.mcmod.lcp.client.message;

import net.minecraft.client.multiplayer.chat.GuiMessage;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

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

    public synchronized void setPendingReply(PendingReply pending) {
        this.pendingReply = pending;
    }

    public synchronized void onNewMessage(GuiMessage message, String messageId) {
        if (!processedMessages.add(message)) {
            return;
        }

        PendingReply pending = this.pendingReply;
        if (pending == null) {
            return;
        }

        if (System.currentTimeMillis() - pending.createdAt > 10000L) {
            this.pendingReply = null;
            return;
        }

        MessageInfo info = MessageInfo.from(message);
        if (info != null && pending.replyText.equals(info.getContent())) {
            replyToOriginal.put(message, pending.originalMessageId);
            this.pendingReply = null;
        }
    }

    public synchronized String getOriginalMessageId(GuiMessage replyMessage) {
        return replyToOriginal.get(replyMessage);
    }

    public synchronized void registerHistoricalReply(GuiMessage replyMessage, String originalMessageId) {
        if (replyMessage == null || originalMessageId == null) return;
        processedMessages.add(replyMessage);
        replyToOriginal.put(replyMessage, originalMessageId);
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