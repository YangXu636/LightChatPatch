package top.xuyangjerry.mcmod.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.Style;
import top.xuyangjerry.mcmod.LightChatPatch;
import top.xuyangjerry.mcmod.client.message.MessageJumpManager;
import top.xuyangjerry.mcmod.config.ChatHistoryView;
import top.xuyangjerry.mcmod.config.LcpConfig;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ChatBoxHistoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<SavedChatMessage>>() {
    }.getType();

    private ChatBoxHistoryManager() {
    }

    private static class SavedChatMessage {
        private long sessionId;
        private String messageUuid;
        private int addedTime;
        private String contentJson;
        private byte[] signature;
        private Integer tagColor;
        private String tagLog;
        private String replyToUuid;

        public SavedChatMessage() {
        }

        public SavedChatMessage(GuiMessage msg, long sessionId) {
            this.sessionId = sessionId;
            this.addedTime = msg.addedTime();
            try {
                JsonElement json = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, msg.content()).getOrThrow();
                this.contentJson = GSON.toJson(json);
            } catch (Exception e) {
                this.contentJson = null;
            }
            if (msg.signature() != null) {
                this.signature = msg.signature().bytes();
            }
            if (msg.tag() != null) {
                this.tagColor = msg.tag().indicatorColor();
                this.tagLog = msg.tag().logTag();
            }
            this.messageUuid = generateStableUuid(addedTime, contentJson);
            this.replyToUuid = top.xuyangjerry.mcmod.client.message.ReplyTracker.getInstance().getOriginalMessageId(msg);
        }

        public GuiMessage toGuiMessage() {
            if (contentJson == null) return null;
            try {
                JsonElement json = JsonParser.parseString(contentJson);
                Component component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
                MessageSignature sig = signature != null ? new MessageSignature(signature) : null;
                GuiMessageTag tag = null;
                if (tagColor != null && tagLog != null) {
                    tag = new GuiMessageTag(tagColor, null, null, tagLog);
                } else if (tagColor != null) {
                    tag = new GuiMessageTag(tagColor, null, null, "");
                }
                return new GuiMessage(addedTime, component, sig, tag);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 基于 addedTime + 内容 JSON 生成稳定的消息 UUID（SHA-1 哈希的前 16 位 hex）。
     * 同一条消息（同会话内相同 tick + 相同内容）生成相同的 UUID，
     * 用于回复跳转等需要跨会话识别同一条消息的场景。
     */
    public static String generateStableUuid(int addedTime, String contentJson) {
        String raw = addedTime + "|" + (contentJson != null ? contentJson : "");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(16, digest.length); i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static Path getHistoryFile(ChatHistoryView view) {
        Path dir = HistoryPaths.getStorageDir(view);
        if (dir == null) {
            return null;
        }
        return dir.resolve("chatbox_history.json");
    }

    public static void saveChatBoxHistory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return;

        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        Path file = getHistoryFile(LcpConfig.getInstance().getChatHistoryView());
        if (file == null) return;

        // addedTime 是 tick 计数，每次进入世界从 0 重新开始
        // 用 sessionId（当前时间戳）区分不同会话的消息
        // 策略：当前会话的消息都用新的 sessionId，旧消息从文件加载时保留原 sessionId
        long currentSessionId = System.currentTimeMillis();

        List<SavedChatMessage> existing = new ArrayList<>();
        if (Files.exists(file)) {
            existing = loadExistingMessages(file);
        }

        // 构建旧消息的内容哈希集合，用于去重
        // 旧消息：sessionId != 0（从旧格式加载的 sessionId 为 0，但仍需保留）
        java.util.Set<String> existingSignatures = new java.util.HashSet<>();
        for (SavedChatMessage old : existing) {
            if (old.contentJson != null) {
                existingSignatures.add(old.addedTime + "|" + old.contentJson);
            }
        }

        List<SavedChatMessage> result = new ArrayList<>();

        // 先加当前 allMessages 中的消息（当前会话 + 之前加载过的历史）
        for (GuiMessage msg : allMessages) {
            SavedChatMessage saved = new SavedChatMessage(msg, currentSessionId);
            String sig = saved.addedTime + "|" + saved.contentJson;
            if (existingSignatures.contains(sig)) {
                // 这条是历史消息（从文件加载的），跳过重复（旧消息已经在 existing 里了）
                continue;
            }
            result.add(saved);
        }

        // 再加旧消息（保持原 sessionId）
        result.addAll(existing);

        // 排序：先按 sessionId 降序（新会话在前），再按 addedTime 降序（同会话内新消息在前）
        result.sort((a, b) -> {
            int s = Long.compare(b.sessionId, a.sessionId);
            if (s != 0) return s;
            return Integer.compare(b.addedTime, a.addedTime);
        });

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(result));
        } catch (IOException e) {
            // ignore
        }
    }

    private static List<SavedChatMessage> loadExistingMessages(Path file) {
        try {
            String json = Files.readString(file);
            List<SavedChatMessage> list = GSON.fromJson(json, LIST_TYPE);
            if (list == null) return new ArrayList<>();
            // 兼容旧格式：为没有 messageUuid 的消息生成稳定 UUID
            for (SavedChatMessage msg : list) {
                if (msg.messageUuid == null) {
                    msg.messageUuid = generateStableUuid(msg.addedTime, msg.contentJson);
                }
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean isLoadable() {
        Path file = getHistoryFile(LcpConfig.getInstance().getChatHistoryView());
        return file != null && Files.exists(file);
    }

    public static void deleteChatBoxHistory() {
        Path file = getHistoryFile(LcpConfig.getInstance().getChatHistoryView());
        if (file != null && Files.exists(file)) {
            try {
                Files.delete(file);
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public static void loadChatBoxHistory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return;

        Path file = getHistoryFile(LcpConfig.getInstance().getChatHistoryView());
        if (file == null || !Files.exists(file)) {
            return;
        }

        List<SavedChatMessage> serialized;
        try {
            String json = Files.readString(file);
            JsonElement root = JsonParser.parseString(json);

            // 格式保护：必须是数组，且第一个元素必须是对象（新格式）
            if (!root.isJsonArray()) {
                backupAndDelete(file, "root is not array");
                return;
            }
            if (!root.getAsJsonArray().isEmpty()) {
                JsonElement first = root.getAsJsonArray().get(0);
                if (!first.isJsonObject()) {
                    backupAndDelete(file, "first element is not object (old format)");
                    return;
                }
            }

            serialized = GSON.fromJson(json, LIST_TYPE);
            if (serialized == null) {
                backupAndDelete(file, "parsed result is null");
                return;
            }
        } catch (Exception e) {
            backupAndDelete(file, "parse error: " + e.getMessage());
            return;
        }

        // 加载所有历史消息，不截断（maxSize 仅控制渲染，不控制存储）
        boolean loadedAny = false;

        ChatComponentAccess access = (ChatComponentAccess) chat;

        // 先排序（按 sessionId 降序，再按 addedTime 降序），再转换
        // 确保 newest-first：新会话在前，同会话内新消息在前
        serialized.sort((a, b) -> {
            int s = Long.compare(b.sessionId, a.sessionId);
            if (s != 0) return s;
            return Integer.compare(b.addedTime, a.addedTime);
        });

        // 收集所有要加载的消息（保留原始 addedTime），再用 storeState/restoreState 一次性恢复
        // 同时建立 saved -> GuiMessage 的映射，用于加载后注册 UUID
        List<SavedChatMessage> savedList = new ArrayList<>();
        List<GuiMessage> messagesToAdd = new ArrayList<>();
        for (SavedChatMessage saved : serialized) {
            GuiMessage msg = saved.toGuiMessage();
            if (msg != null) {
                messagesToAdd.add(msg);
                savedList.add(saved);
            }
        }

        if (!messagesToAdd.isEmpty()) {
            if (restoreChatMessages(chat, messagesToAdd)) {
                loadedAny = true;
            } else {
                // 回退：直接操作 allMessages 列表，将历史消息追加到末尾（最旧位置）
                LightChatPatch.LOGGER.warn("[ChatBoxHistory] restoreState reflection failed, falling back to direct list manipulation");
                List<GuiMessage> allMessages = access.lcp$getAllMessages();
                allMessages.addAll(messagesToAdd);
                // 通过反射调用 refreshTrimmedMessages 重建 trimmedMessages
                try {
                    java.lang.reflect.Method refresh = ChatComponent.class.getDeclaredMethod("refreshTrimmedMessages");
                    refresh.setAccessible(true);
                    refresh.invoke(chat);
                } catch (Exception e) {
                    LightChatPatch.LOGGER.warn("[ChatBoxHistory] refreshTrimmedMessages reflection failed", e);
                }
                loadedAny = true;
            }
        }

        if (loadedAny) {
            Component separator = Component.translatable("light_chat_patch.history.separator")
                    .setStyle(Style.EMPTY.withItalic(true).withColor(0xAAAAAA));
            chat.addMessage(separator);
            // 重置滚动条到末尾，使新加载的历史消息可见
            access.lcp$setChatScrollbarPos(0);

            // 将加载的历史消息的 UUID 注册到 MessageJumpManager，支持回复跳转
            MessageJumpManager jumpManager = MessageJumpManager.getInstance();
            top.xuyangjerry.mcmod.client.message.ReplyTracker replyTracker = top.xuyangjerry.mcmod.client.message.ReplyTracker.getInstance();
            for (int i = 0; i < savedList.size() && i < messagesToAdd.size(); i++) {
                SavedChatMessage saved = savedList.get(i);
                GuiMessage msg = messagesToAdd.get(i);
                if (saved.messageUuid != null && msg != null) {
                    jumpManager.registerHistoricalMessage(saved.messageUuid, msg);
                }
                // 恢复回复关系
                if (saved.replyToUuid != null && msg != null) {
                    replyTracker.registerHistoricalReply(msg, saved.replyToUuid);
                }
            }
        }
    }

    /**
     * 通过 public storeState/restoreState 一次性替换 ChatComponent 的消息列表。
     * 这样可以同时更新 allMessages 和 trimmedMessages（restoreState 内部会调用 refreshTrimmedMessages），
     * 并完整保留所有 GuiMessage 的原始 addedTime。
     */
    private static boolean restoreChatMessages(ChatComponent chat, List<GuiMessage> messagesToAdd) {
        try {
            java.lang.reflect.Method storeState = ChatComponent.class.getMethod("storeState");
            Object state = storeState.invoke(chat);

            Class<?> stateClass = Class.forName("net.minecraft.client.gui.components.ChatComponent$State");
            java.lang.reflect.Field messagesField = stateClass.getDeclaredField("messages");
            messagesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<GuiMessage> currentMessages = (List<GuiMessage>) messagesField.get(state);

            // allMessages 是 newest-first（index 0 = 最新，末尾 = 最旧）
            // 当前消息在前（新），历史消息在后（旧）
            List<GuiMessage> newMessages = new ArrayList<>(currentMessages);
            newMessages.addAll(messagesToAdd);

            messagesField.set(state, newMessages);

            java.lang.reflect.Method restoreState = ChatComponent.class.getMethod("restoreState", stateClass);
            restoreState.invoke(chat, state);
            LightChatPatch.LOGGER.info("[ChatBoxHistory] restoreState success: {} current + {} history = {} total",
                    currentMessages.size(), messagesToAdd.size(), newMessages.size());
            return true;
        } catch (Exception e) {
            LightChatPatch.LOGGER.warn("[ChatBoxHistory] restoreState reflection failed: {}", e.getMessage());
            return false;
        }
    }

    private static void backupAndDelete(Path file, String reason) {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".backup_" + System.currentTimeMillis());
            Files.move(file, backup);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
