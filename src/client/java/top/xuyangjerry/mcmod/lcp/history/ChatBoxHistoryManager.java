package top.xuyangjerry.mcmod.lcp.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.client.forward.ForwardMessageManager;
import top.xuyangjerry.mcmod.lcp.client.message.ReplyDataManager;
import top.xuyangjerry.mcmod.lcp.client.mixin.ChatComponentAccess;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageCache;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChatBoxHistoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<MessageEntry>>() {
    }.getType();
    private static final int MAX_HISTORY_SIZE = 1000;

    private static String currentSessionId;
    private static boolean loadable = false;

    // 分批加载状态：每 tick 加载一条历史消息，确保 addedTime 唯一
    private static List<MessageEntry> pendingHistory = null;
    private static int pendingHistoryIndex = 0;
    private static final java.util.Map<String, String> pendingContentJsonToUuid = new java.util.HashMap<>();

    private ChatBoxHistoryManager() {
    }

    public static void startNewSession() {
        currentSessionId = Long.toHexString(System.currentTimeMillis());
        LightChatPatch.LOGGER.info("[ChatBoxHistory] New session: {}", currentSessionId);
    }

    public static void loadChatBoxHistory() {
        Path file = getHistoryFile();
        if (file == null || !Files.exists(file)) {
            loadable = false;
            return;
        }

        try {
            String json = Files.readString(file);
            List<MessageEntry> entries = GSON.fromJson(json, LIST_TYPE);
            if (entries == null || entries.isEmpty()) {
                loadable = true;
                return;
            }

            Set<String> seenContentHashes = new HashSet<>();
            List<MessageEntry> unique = new ArrayList<>();
            for (MessageEntry e : entries) {
                if (e.uuid == null) {
                    e.uuid = generateStableUuid(e.sessionId, e.addedTime, e.contentJson);
                }
                if (seenContentHashes.add(hashContent(e.contentJson))) {
                    unique.add(e);
                }
            }

            unique.sort((a, b) -> {
                int cmp = b.sessionId.compareTo(a.sessionId);
                if (cmp != 0) return cmp;
                return Integer.compare(b.addedTime, a.addedTime);
            });

            Collections.reverse(unique);

            // 存入 pending 列表，由 tickLoadHistoryBatch 分批加载
            // 每 tick 加载一条，确保 addedTime（基于 guiTicks）唯一
            pendingHistory = unique;
            pendingHistoryIndex = 0;
            pendingContentJsonToUuid.clear();
            for (MessageEntry e : unique) {
                pendingContentJsonToUuid.put(e.contentJson, e.uuid);
            }
            loadable = true;
            LightChatPatch.LOGGER.info("[ChatBoxHistory] Prepared {} messages for batch loading from {}", unique.size(), file);
        } catch (Exception e) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to load history", e);
            loadable = false;
        }
    }

    /**
     * 一次性加载所有历史消息到聊天栏。
     * 不再依赖 addedTime 唯一性，因为 ChatMessageLocator 已改用 parent() 引用定位消息。
     *
     * @return true 表示还有更多消息待加载，false 表示加载完毕或无待加载消息
     */
    public static boolean tickLoadHistoryBatch() {
        if (pendingHistory == null) {
            return false;
        }
        LightChatPatch.LOGGER.info("[ChatBoxHistory] tickLoadHistoryBatch START, pending={}", pendingHistory.size());

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud.getChat() == null) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] tickLoadHistoryBatch ABORT: gui or chat is null");
            return false;
        }
        ChatComponent chat = mc.gui.hud.getChat();

        int loaded = 0;
        int failed = 0;
        for (int idx = 0; idx < pendingHistory.size(); idx++) {
            MessageEntry e = pendingHistory.get(idx);
            try {
                Component comp = deserializeComponent(e.contentJson);
                if (comp == null) {
                    failed++;
                    LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to deserialize message #{}", idx);
                    continue;
                }
                // 先恢复转发消息（用文本中已有的 FW id 作为 key）
                if (e.forwardPayloadJson != null && !e.forwardPayloadJson.isEmpty()) {
                    String forwardId = ForwardMessageManager.extractId(comp.getString());
                    if (forwardId != null) {
                        try {
                            ForwardMessagePayload payload = ForwardMessagePayload.fromJson(e.forwardPayloadJson);
                            if (payload != null) {
                                ForwardMessageManager.getInstance().restoreForwardMessage(forwardId, payload);
                            }
                        } catch (Exception ex) {
                            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to restore forward message", ex);
                        }
                    }
                }
                // 恢复回复消息数据（注册到 ReplyDataManager 供悬浮预览查询）
                if (e.replyPayloadJson != null && !e.replyPayloadJson.isEmpty()) {
                    try {
                        ReplyMessagePayload replyPayload = ReplyMessagePayload.fromJson(e.replyPayloadJson);
                        if (replyPayload != null) {
                            ReplyDataManager.getInstance().addReplyData(replyPayload);
                        }
                    } catch (Exception ex) {
                        LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to restore reply message", ex);
                    }
                }
                // 再恢复图片（优先用原始 imageId，恢复失败时回退到分配新 id 并替换 tag）
                if (e.imageDataB64 != null && !e.imageDataB64.isEmpty()) {
                    String newTag = restoreImageFromEntry(e);
                    if (newTag != null) {
                        String text = comp.getString();
                        String newText = ClientImageCache.IMAGE_TAG_PATTERN.matcher(text).replaceAll(newTag);
                        if (!newText.equals(text)) {
                            comp = Component.literal(newText).withStyle(comp.getStyle());
                        }
                    }
                }
                chat.addClientSystemMessage(comp);
                loaded++;
            } catch (Exception ex) {
                failed++;
                String preview = e.contentJson != null && e.contentJson.length() > 80
                        ? e.contentJson.substring(0, 80) + "..." : e.contentJson;
                LightChatPatch.LOGGER.error("[ChatBoxHistory] Exception loading message #{} (preview: {})", idx, preview, ex);
            }
        }

        // 所有消息加载完毕，注册 UUID 到 MessageJumpManager
        registerPendingUuids();
        int total = pendingHistory.size();
        // 打印加载统计：前3条和后3条消息内容预览，便于诊断系统消息是否被加载
        StringBuilder preview = new StringBuilder();
        int previewCount = Math.min(3, pendingHistory.size());
        for (int i = 0; i < previewCount; i++) {
            MessageEntry e = pendingHistory.get(i);
            Component c = deserializeComponent(e.contentJson);
            String text = c != null ? c.getString() : "(null)";
            if (text.length() > 60) text = text.substring(0, 60) + "...";
            preview.append("[").append(i).append(":").append(text).append("] ");
        }
        LightChatPatch.LOGGER.info("[ChatBoxHistory] Batch loading complete, {} messages restored (loaded={}, failed={}). First {} msgs: {}",
                total, loaded, failed, previewCount, preview);
        pendingHistory = null;
        pendingHistoryIndex = 0;
        pendingContentJsonToUuid.clear();
        return false;
    }

    /**
     * 是否正在分批加载历史消息。
     */
    public static boolean isBatchLoading() {
        return pendingHistory != null;
    }

    private static void registerPendingUuids() {
        if (pendingContentJsonToUuid.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud.getChat() == null) return;
        ChatComponent chat = mc.gui.hud.getChat();

        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMsgs = access.lcp$getAllMessages();
        for (GuiMessage msg : allMsgs) {
            String contentJson = serializeComponent(msg.content());
            if (contentJson != null && pendingContentJsonToUuid.containsKey(contentJson)) {
                String uuid = pendingContentJsonToUuid.get(contentJson);
                top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager.getInstance()
                        .registerHistoricalMessage(uuid, msg);
            }
        }
    }

    public static void saveChatBoxHistory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.hud.getChat();
        if (chat == null) return;

        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        if (allMessages == null || allMessages.isEmpty()) return;

        if (currentSessionId == null) {
            startNewSession();
        }

        Path file = getHistoryFile();
        if (file == null) return;

        List<MessageEntry> entries = new ArrayList<>();
        Set<String> existingContentHashes = new HashSet<>();
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file);
                List<MessageEntry> existing = GSON.fromJson(json, LIST_TYPE);
                if (existing != null) {
                    for (MessageEntry e : existing) {
                        if (e.uuid == null) {
                            e.uuid = generateStableUuid(e.sessionId, e.addedTime, e.contentJson);
                        }
                        if (existingContentHashes.add(hashContent(e.contentJson))) {
                            entries.add(e);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        int saved = 0;
        for (GuiMessage msg : allMessages) {
            String contentJson = serializeComponent(msg.content());
            if (contentJson == null) continue;
            // 保存所有聊天框消息，不区分来源
            String text = msg.content().getString();
            String contentHash = hashContent(contentJson);
            if (existingContentHashes.add(contentHash)) {
                String uuid = generateStableUuid(currentSessionId, msg.addedTime(), contentJson);
                MessageEntry entry = new MessageEntry(currentSessionId, msg.addedTime(), contentJson, uuid);
                attachImageData(entry, text);
                attachForwardData(entry, text);
                attachReplyData(entry, text);
                entries.add(entry);
                saved++;
            }
        }
        LightChatPatch.LOGGER.info("[ChatBoxHistory] Save stats: allMessages={}, saved={}",
                allMessages.size(), saved);

        if (entries.size() > MAX_HISTORY_SIZE) {
            entries.sort((a, b) -> {
                int cmp = b.sessionId.compareTo(a.sessionId);
                if (cmp != 0) return cmp;
                return Integer.compare(b.addedTime, a.addedTime);
            });
            entries = new ArrayList<>(entries.subList(0, MAX_HISTORY_SIZE));
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(entries));
            LightChatPatch.LOGGER.info("[ChatBoxHistory] Saved {} entries to {}", entries.size(), file);
        } catch (IOException e) {
            LightChatPatch.LOGGER.error("[ChatBoxHistory] Failed to save history", e);
        }
    }

    public static boolean isLoadable() {
        return loadable;
    }

    public static void deleteChatBoxHistory() {
        Path file = getHistoryFile();
        if (file != null && Files.exists(file)) {
            try {
                Files.delete(file);
                LightChatPatch.LOGGER.info("[ChatBoxHistory] Deleted {}", file);
            } catch (IOException e) {
                LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to delete {}", file, e);
            }
        }
        // 清空内存中的聊天消息，防止退出时重新保存
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.hud.getChat() != null) {
            mc.gui.hud.getChat().clearMessages(false);
            LightChatPatch.LOGGER.info("[ChatBoxHistory] Cleared in-memory chat messages");
        }
        // 清空待加载的历史
        pendingHistory = null;
        pendingHistoryIndex = 0;
        pendingContentJsonToUuid.clear();
    }

    /**
     * 生成稳定UUID（跨客户端一致版本）。
     * 重要：不再依赖 sessionId（每个客户端的 sessionId 不同，会导致同一条消息 UUID 不一致），
     * 也不再依赖 addedTime（每个客户端的 guiTicks 独立运行，同一条消息 addedTime 也不相同）。
     * 仅使用 contentJson（消息内容的序列化JSON）生成UUID：
     *   - 对于服务端广播的同一条聊天消息，contentJson 在所有客户端完全相同
     *   - 即使两条内容完全相同的消息（例如重复说同样的话）UUID 相同也没有严重影响
     *     （最多是跳转定位到第一条，在聊天框内消息位置相近，用户体验可接受）
     */
    public static String generateStableUuid(int addedTime, String contentJson) {
        return generateStableUuid(null, addedTime, contentJson);
    }

    public static String generateStableUuid(String sessionId, int addedTime, String contentJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            // 仅使用 contentJson 生成，确保跨客户端一致性
            // （sessionId 每客户端不同，addedTime 也基于本地 guiTicks，两者都不可靠）
            String input = (contentJson != null ? contentJson : "");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(contentJson != null ? contentJson.hashCode() : 0);
        }
    }

    private static String hashContent(String contentJson) {
        if (contentJson == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(contentJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(contentJson.hashCode());
        }
    }

    private static Path getHistoryFile() {
        Path dir = HistoryPaths.getStorageDir(LcpConfig.getInstance().getChatHistoryView());
        if (dir == null) return null;
        return dir.resolve("chatbox_history.json");
    }

    private static String serializeComponent(Component component) {
        try {
            JsonElement element = ComponentSerialization.CODEC
                    .encodeStart(JsonOps.INSTANCE, component)
                    .getOrThrow();
            return GSON.toJson(element);
        } catch (Exception e) {
            return null;
        }
    }

    private static Component deserializeComponent(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            return ComponentSerialization.CODEC
                    .decode(JsonOps.INSTANCE, element)
                    .getOrThrow()
                    .getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    private static void attachImageData(MessageEntry entry, String contentText) {
        int imageId = ClientImageCache.extractImageId(contentText);
        if (imageId < 0) return;
        ClientImageCache.ImageEntry imgEntry = ClientImageCache.getImage(imageId);
        if (imgEntry == null || imgEntry.pngBytes() == null) return;
        entry.originalImageId = imageId;
        entry.imageWidth = imgEntry.width();
        entry.imageHeight = imgEntry.height();
        entry.imageDataB64 = Base64.getEncoder().encodeToString(imgEntry.pngBytes());
    }

    private static void attachForwardData(MessageEntry entry, String contentText) {
        String forwardId = ForwardMessageManager.extractId(contentText);
        if (forwardId == null) return;
        ForwardMessagePayload payload = ForwardMessageManager.getInstance().getForwardMessage(forwardId);
        if (payload == null) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Forward payload not found for id={} when saving", forwardId);
            return;
        }
        try {
            entry.forwardPayloadJson = payload.toJson();
            LightChatPatch.LOGGER.info("[ChatBoxHistory] Saved forward payload for id={}, json length={}", forwardId, entry.forwardPayloadJson.length());
        } catch (Exception ex) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to serialize forward message", ex);
        }
    }

    /**
     * 保存回复消息关联数据：从文本中提取 [Reply #uuid] 标签，
     * 将对应的 ReplyMessagePayload 序列化为 JSON 存入 entry。
     */
    private static void attachReplyData(MessageEntry entry, String contentText) {
        String replyUuid = ReplyDataManager.extractUuid(contentText);
        if (replyUuid == null) return;
        ReplyMessagePayload payload = ReplyDataManager.getInstance().getReplyData(replyUuid);
        if (payload == null) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Reply payload not found for uuid={} when saving", replyUuid);
            return;
        }
        try {
            entry.replyPayloadJson = payload.toJson();
            LightChatPatch.LOGGER.info("[ChatBoxHistory] Saved reply payload for uuid={}, json length={}", replyUuid, entry.replyPayloadJson.length());
        } catch (Exception ex) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to serialize reply message", ex);
        }
    }

    private static String restoreImageFromEntry(MessageEntry entry) {
        if (entry.imageDataB64 == null || entry.imageDataB64.isEmpty()) return null;
        try {
            byte[] pngBytes = Base64.getDecoder().decode(entry.imageDataB64);
            // 优先使用原始 imageId 恢复，文本中已有的 tag 仍然匹配，无需替换
            if (entry.originalImageId >= 0
                    && ClientImageCache.restoreImage(entry.originalImageId, pngBytes,
                            entry.imageWidth, entry.imageHeight)) {
                return null;
            }
            // 回退：分配新的 imageId 并返回新 tag 以便替换文本
            int imageId = ClientImageCache.addImage(pngBytes, entry.imageWidth, entry.imageHeight);
            if (imageId < 0) return null;
            return ClientImageCache.makeTag(imageId);
        } catch (Exception e) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] Failed to restore image from history", e);
            return null;
        }
    }

    private static class MessageEntry {
        String sessionId;
        int addedTime;
        String contentJson;
        String uuid;
        String imageDataB64;
        int imageWidth;
        int imageHeight;
        int originalImageId = -1;
        String forwardPayloadJson;
        String replyPayloadJson;

        MessageEntry() {
        }

        MessageEntry(String sessionId, int addedTime, String contentJson, String uuid) {
            this.sessionId = sessionId;
            this.addedTime = addedTime;
            this.contentJson = contentJson;
            this.uuid = uuid;
        }
    }
}
