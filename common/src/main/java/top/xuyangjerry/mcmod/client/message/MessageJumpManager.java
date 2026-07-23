package top.xuyangjerry.mcmod.client.message;

import com.google.gson.Gson;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.Mth;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;

import java.util.*;

public final class MessageJumpManager {
    private static final MessageJumpManager INSTANCE = new MessageJumpManager();
    private static final Gson GSON = new Gson();

    private final Map<String, GuiMessage> messageIdMap = new LinkedHashMap<>();
    private static final int MAX_CACHE = 1000;

    private MessageJumpManager() {
    }

    public static MessageJumpManager getInstance() {
        return INSTANCE;
    }

    /**
     * 为消息生成稳定 ID（基于 addedTime + 内容 JSON 的 SHA-1 哈希）。
     * 同一条消息（同会话内相同 tick + 相同内容）生成相同的 ID。
     */
    private String generateStableId(GuiMessage message) {
        String contentJson = null;
        try {
            contentJson = GSON.toJson(
                    ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, message.content()).getOrThrow()
            );
        } catch (Exception ignored) {
        }
        return top.xuyangjerry.mcmod.history.ChatBoxHistoryManager.generateStableUuid(
                message.addedTime(), contentJson);
    }

    /**
     * 将消息加入映射，返回稳定 ID。
     */
    public synchronized String addMessage(GuiMessage message) {
        // 先检查是否已存在（引用相等）
        for (Map.Entry<String, GuiMessage> entry : messageIdMap.entrySet()) {
            if (entry.getValue() == message) {
                return entry.getKey();
            }
        }

        String id = generateStableId(message);
        messageIdMap.put(id, message);
        trimCache();
        return id;
    }

    /**
     * 注册历史消息（从文件加载的）到映射表。
     * 用于退出重进后恢复回复跳转能力。
     */
    public synchronized void registerHistoricalMessage(String uuid, GuiMessage message) {
        if (uuid == null || message == null) return;
        messageIdMap.put(uuid, message);
        trimCache();
    }

    private void trimCache() {
        if (messageIdMap.size() > MAX_CACHE) {
            Iterator<String> it = messageIdMap.keySet().iterator();
            while (messageIdMap.size() > MAX_CACHE && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * 获取消息的稳定 ID。
     */
    public synchronized String getMessageId(GuiMessage message) {
        return addMessage(message);
    }

    /**
     * 根据 ID 查找消息。
     * 先查精确匹配（引用相等），再按内容匹配回退。
     */
    public synchronized GuiMessage findMessageById(String id) {
        if (id == null) return null;
        GuiMessage cached = messageIdMap.get(id);
        if (cached != null) return cached;

        // 缓存中没有，尝试在 allMessages 中按内容匹配
        // （历史消息加载后可能因为引用变化找不到）
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return null;
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        for (GuiMessage msg : allMessages) {
            String msgId = generateStableId(msg);
            if (id.equals(msgId)) {
                // 找到匹配，更新缓存
                messageIdMap.put(id, msg);
                return msg;
            }
        }
        return null;
    }

    /**
     * 跳转到指定 ID 的消息。
     * 返回 false 表示目标消息不在当前列表中。
     */
    public boolean jumpToMessageById(String id) {
        GuiMessage msg = findMessageById(id);
        if (msg == null) return false;
        return jumpToMessage(msg);
    }

    /**
     * 跳转到指定消息。如果消息已在视野内则只返回 true 不滚动；
     * 如果不在视野内则滚动到视野中央并返回 true。
     * 返回 false 表示目标消息已不在当前列表中。
     */
    public boolean jumpToMessage(GuiMessage targetMessage) {
        if (targetMessage == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.getChat() == null) return false;

        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;

        // 使用与转发选择模式一致的定位方式（基于 addedTime）
        int[] range = ChatMessageLocator.getMessageTrimmedRange(targetMessage);
        if (range == null) return false;
        int trimmedStartIndex = range[0];
        int trimmedEndIndex = range[1];

        List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmed.isEmpty()) return false;

        // 计算视野范围
        double chatScale = mc.options.chatScale().get();
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));
        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = Math.max(1, chatHeight / entryHeight);

        int scrollbarPos = access.lcp$getChatScrollbarPos();
        int viewStart = scrollbarPos;
        int viewEnd = scrollbarPos + linesPerPage;

        // 检查目标消息是否已在视野内（有交集即可）
        boolean inView = trimmedEndIndex >= viewStart && trimmedStartIndex < viewEnd;
        if (inView) {
            return true;
        }

        // 不在视野内：滚动到让消息显示在视野中央
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
        }
    }
}
