package top.xuyangjerry.mcmod.lcp.client.message;

import com.google.gson.Gson;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.Mth;
import top.xuyangjerry.mcmod.lcp.client.mixin.ChatComponentAccess;

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

    private String generateStableId(GuiMessage message) {
        String contentJson = null;
        try {
            contentJson = GSON.toJson(
                    ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, message.content()).getOrThrow()
            );
        } catch (Exception ignored) {
        }
        return top.xuyangjerry.mcmod.lcp.history.ChatBoxHistoryManager.generateStableUuid(
                message.addedTime(), contentJson);
    }

    public synchronized String addMessage(GuiMessage message) {
        for (Map.Entry<String, GuiMessage> entry : messageIdMap.entrySet()) {
            if (entry.getValue() == message) {
                return entry.getKey();
            }
        }

        String id = generateStableId(message);
        messageIdMap.put(id, message);
        // 日志级别降为 debug，避免刷屏
        String textPreview = message.content() != null ? message.content().getString() : "";
        if (textPreview.length() > 80) textPreview = textPreview.substring(0, 80) + "...";
        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.debug("[LCP][UUID] message registered: uuid={}, preview={}", id, textPreview);
        trimCache();
        return id;
    }

    public synchronized void registerHistoricalMessage(String uuid, GuiMessage message) {
        if (uuid == null || message == null) return;
        messageIdMap.put(uuid, message);
        trimCache();
    }

    /**
     * 注册最近添加到聊天框的消息：从 allMessages 末尾获取最新的 GuiMessage，
     * 用服务器指定的 uuid 注册（不再用 contentJson 自行计算）。
     *
     * 用于 mod 生成消息（RichMessage/Image/Reply/Forward/Mention）：
     * 调用前必须先执行 addClientSystemMessage，确保消息已添加到 allMessages。
     *
     * @param uuid 服务器生成的UUID（payload.uuid）
     * @return true 如果成功找到并注册了消息
     */
    public synchronized boolean registerLastMessage(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud.getChat() == null) return false;
        ChatComponentAccess access = (ChatComponentAccess) mc.gui.hud.getChat();
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        if (allMessages == null || allMessages.isEmpty()) return false;

        // 取最后一条消息（addClientSystemMessage 同步添加到末尾）
        GuiMessage lastMsg = allMessages.get(allMessages.size() - 1);

        // 检查是否已用其他uuid注册过同一个GuiMessage对象
        for (Map.Entry<String, GuiMessage> entry : messageIdMap.entrySet()) {
            if (entry.getValue() == lastMsg) {
                // 已注册：用服务器uuid覆盖（确保跨客户端一致）
                messageIdMap.remove(entry.getKey());
                break;
            }
        }

        messageIdMap.put(uuid, lastMsg);
        String textPreview = lastMsg.content() != null ? lastMsg.content().getString() : "";
        if (textPreview.length() > 80) textPreview = textPreview.substring(0, 80) + "...";
        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.debug(
                "[LCP][UUID] message registered (server uuid): uuid={}, preview={}", uuid, textPreview);
        trimCache();
        return true;
    }

    /**
     * 根据消息文本内容匹配并注册UUID（用于原版玩家消息）。
     * 服务器通过 MessageUuidPayload 发送 uuid + contentText，
     * 客户端在 allMessages 中从最新开始向前搜索内容匹配的消息。
     *
     * @param uuid 服务器生成的UUID
     * @param contentText 消息纯文本（用于匹配）
     * @return true 如果成功匹配并注册
     */
    public synchronized boolean registerByContent(String uuid, String contentText) {
        if (uuid == null || uuid.isEmpty() || contentText == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud.getChat() == null) return false;
        ChatComponentAccess access = (ChatComponentAccess) mc.gui.hud.getChat();
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        if (allMessages == null || allMessages.isEmpty()) return false;

        // 从最新消息开始向前搜索（最多检查最近20条，避免性能问题）
        int searchCount = Math.min(20, allMessages.size());
        for (int i = allMessages.size() - 1; i >= allMessages.size() - searchCount; i--) {
            GuiMessage msg = allMessages.get(i);
            String msgText = msg.content() != null ? msg.content().getString() : "";
            if (contentText.equals(msgText)) {
                // 检查是否已注册
                for (Map.Entry<String, GuiMessage> entry : messageIdMap.entrySet()) {
                    if (entry.getValue() == msg) {
                        messageIdMap.remove(entry.getKey());
                        break;
                    }
                }
                messageIdMap.put(uuid, msg);
                top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.debug(
                        "[LCP][UUID] message registered (by content match): uuid={}, preview={}",
                        uuid, contentText.length() > 80 ? contentText.substring(0, 80) + "..." : contentText);
                trimCache();
                return true;
            }
        }

        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.warn(
                "[LCP][UUID] registerByContent: no matching message found for uuid={}, contentLen={}",
                uuid, contentText.length());
        return false;
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

    public synchronized String getMessageId(GuiMessage message) {
        return addMessage(message);
    }

    public synchronized GuiMessage findMessageById(String id) {
        if (id == null) return null;
        GuiMessage cached = messageIdMap.get(id);
        if (cached != null) {
            return cached;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return null;
        ChatComponent chat = mc.gui.hud.getChat();
        if (chat == null) return null;
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        for (GuiMessage msg : allMessages) {
            String msgId = generateStableId(msg);
            if (id.equals(msgId)) {
                messageIdMap.put(id, msg);
                return msg;
            }
        }
        return null;
    }

    public boolean jumpToMessageById(String id) {
        GuiMessage msg = findMessageById(id);
        if (msg == null) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.warn("[LCP] jumpToMessageById: message not found for id={}", id);
            return false;
        }
        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.info("[LCP] jumpToMessageById: found message, content={}",
                msg.content().getString().substring(0, Math.min(50, msg.content().getString().length())));
        return jumpToMessage(msg);
    }

    public boolean jumpToMessage(GuiMessage targetMessage) {
        if (targetMessage == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud.getChat() == null) return false;

        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;

        int[] range = ChatMessageLocator.getMessageTrimmedRange(targetMessage);
        if (range == null) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.warn("[LCP] jumpToMessage: getMessageTrimmedRange returned null, message may not be in chat");
            return false;
        }
        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.info("[LCP] jumpToMessage: trimmed range=[{}, {}]", range[0], range[1]);
        int trimmedStartIndex = range[0];
        int trimmedEndIndex = range[1];

        List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmed.isEmpty()) return false;

        double chatScale = mc.options.chatScale().get();
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));
        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = Math.max(1, chatHeight / entryHeight);

        int scrollbarPos = access.lcp$getChatScrollbarPos();
        int viewStart = scrollbarPos;
        int viewEnd = scrollbarPos + linesPerPage;

        boolean inView = trimmedEndIndex >= viewStart && trimmedStartIndex < viewEnd;
        if (inView) {
            return true;
        }

        int msgCenter = (trimmedStartIndex + trimmedEndIndex) / 2;
        int targetScroll = msgCenter - linesPerPage / 2;

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