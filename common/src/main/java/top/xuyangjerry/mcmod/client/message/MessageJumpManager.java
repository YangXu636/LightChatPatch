package top.xuyangjerry.mcmod.client.message;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.Mth;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;

import java.util.*;

public final class MessageJumpManager {
    private static final MessageJumpManager INSTANCE = new MessageJumpManager();

    private final Map<String, GuiMessage> messageIdMap = new LinkedHashMap<>();
    private long messageCounter = 0;

    private MessageJumpManager() {
    }

    public static MessageJumpManager getInstance() {
        return INSTANCE;
    }

    /**
     * 将消息加入本地列表，返回分配的唯一 ID。
     * 如果消息已存在（引用相等），返回已有 ID。
     */
    public synchronized String addMessage(GuiMessage message) {
        // 先检查是否已存在
        for (Map.Entry<String, GuiMessage> entry : messageIdMap.entrySet()) {
            if (entry.getValue() == message) {
                return entry.getKey();
            }
        }

        String id = generateMessageId(message);
        messageIdMap.put(id, message);

        int maxCache = 500;
        if (messageIdMap.size() > maxCache) {
            Iterator<String> it = messageIdMap.keySet().iterator();
            while (messageIdMap.size() > maxCache && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return id;
    }

    private String generateMessageId(GuiMessage message) {
        String sender = null;
        MessageInfo info = MessageInfo.from(message);
        if (info != null) {
            sender = info.getSender();
        }
        long timestamp = System.currentTimeMillis();
        messageCounter++;
        return String.format("%d_%d_%s", timestamp, messageCounter,
                sender != null ? sender.hashCode() : 0);
    }

    /**
     * 获取消息 ID。如果消息不在本地列表中，会自动添加。
     */
    public synchronized String getMessageId(GuiMessage message) {
        return addMessage(message);
    }

    public synchronized GuiMessage findMessageById(String id) {
        return messageIdMap.get(id);
    }

    /**
     * 跳转到指定消息。如果消息已在视野内则只返回 true 不滚动；
     * 如果不在视野内则滚动到视野中央并返回 true。
     * 返回 false 表示目标消息已不在当前列表中。
     */
    public boolean jumpToMessage(GuiMessage targetMessage) {
        if (targetMessage == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return false;

        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();

        if (allMessages.isEmpty() || trimmed.isEmpty()) return false;

        // 1. 在 allMessages 中找到目标消息的索引（引用相等）
        int messageIndex = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i) == targetMessage) {
                messageIndex = i;
                break;
            }
        }
        if (messageIndex < 0) return false;

        // 2. 计算该消息在 trimmedMessages 中的起始和结束行索引
        int trimmedStartIndex = -1;
        int trimmedEndIndex = -1;
        int msgCount = 0;
        for (int i = 0; i < trimmed.size(); i++) {
            if (msgCount == messageIndex) {
                if (trimmedStartIndex < 0) {
                    trimmedStartIndex = i;
                }
            }
            if (trimmed.get(i).endOfEntry()) {
                if (msgCount == messageIndex) {
                    trimmedEndIndex = i;
                    break;
                }
                msgCount++;
            }
        }
        if (trimmedStartIndex < 0 || trimmedEndIndex < 0) return false;

        // 3. 计算视野范围
        double chatScale = mc.options.chatScale().get();
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));
        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = Math.max(1, chatHeight / entryHeight);

        int scrollbarPos = access.lcp$getChatScrollbarPos();
        int viewStart = scrollbarPos;
        int viewEnd = scrollbarPos + linesPerPage;

        // 4. 检查目标消息是否已在视野内（有交集即可）
        boolean inView = trimmedEndIndex >= viewStart && trimmedStartIndex < viewEnd;
        if (inView) {
            return true; // 已在视野内，无需滚动
        }

        // 5. 不在视野内：滚动到让消息显示在视野中央
        int msgCenter = (trimmedStartIndex + trimmedEndIndex) / 2;
        int targetScroll = msgCenter - linesPerPage / 2;

        // 边界限制
        int maxScroll = Math.max(0, trimmed.size() - linesPerPage);
        targetScroll = Math.max(0, Math.min(targetScroll, maxScroll));

        access.lcp$setChatScrollbarPos(targetScroll);
        return true;
    }

    public void clear() {
        synchronized (this) {
            messageIdMap.clear();
            messageCounter = 0;
        }
    }
}
