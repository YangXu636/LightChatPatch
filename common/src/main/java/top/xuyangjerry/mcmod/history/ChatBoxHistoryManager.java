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
        private int addedTime;
        private String contentJson;
        private byte[] signature;
        private Integer tagColor;
        private String tagLog;

        public SavedChatMessage() {
        }

        public SavedChatMessage(GuiMessage msg) {
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

    private static Path getHistoryDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("light_chat_patch")
                .resolve("chatbox_history");
    }

    private static Path getHistoryFile(ChatHistoryView view) {
        String filename;
        switch (view) {
            case GLOBAL:
                filename = "global.json";
                break;
            case CURRENT_VERSION:
                filename = "version_" + ChatHistoryManager.sanitize(Minecraft.getInstance().getLaunchedVersion()) + ".json";
                break;
            case CURRENT_WORLD:
                String worldId = ChatHistoryManager.getCurrentWorldId();
                if (worldId == null) {
                    return null;
                }
                filename = "world_" + worldId + ".json";
                break;
            default:
                return null;
        }
        return getHistoryDir().resolve(filename);
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

        int maxTime = loadMaxSavedTime(file);

        List<SavedChatMessage> serialized = new ArrayList<>();
        for (GuiMessage msg : allMessages) {
            if (msg.addedTime() > maxTime) {
                serialized.add(new SavedChatMessage(msg));
            }
        }

        if (!serialized.isEmpty()) {
            try {
                Files.createDirectories(file.getParent());
                if (Files.exists(file)) {
                    List<SavedChatMessage> existing = loadExistingMessages(file);
                    existing.addAll(serialized);
                    Files.writeString(file, GSON.toJson(existing));
                } else {
                    Files.writeString(file, GSON.toJson(serialized));
                }
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private static int loadMaxSavedTime(Path file) {
        if (!Files.exists(file)) return 0;
        try {
            String json = Files.readString(file);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return 0;
            JsonElement[] arr = root.getAsJsonArray().asList().toArray(new JsonElement[0]);
            if (arr.length == 0) return 0;
            int max = 0;
            for (JsonElement e : arr) {
                if (e.isJsonObject() && e.getAsJsonObject().has("addedTime")) {
                    int t = e.getAsJsonObject().get("addedTime").getAsInt();
                    if (t > max) max = t;
                }
            }
            return max;
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<SavedChatMessage> loadExistingMessages(Path file) {
        try {
            String json = Files.readString(file);
            return GSON.fromJson(json, LIST_TYPE);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean isLoadable() {
        Path file = getHistoryFile(LcpConfig.getInstance().getChatHistoryView());
        return file != null && Files.exists(file);
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

        int maxSize = LcpConfig.getInstance().getChatMaxVisibleMessages();
        int start = Math.max(0, serialized.size() - maxSize);
        boolean loadedAny = false;

        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        int originalSize = allMessages.size();

        for (int i = start; i < serialized.size(); i++) {
            SavedChatMessage saved = serialized.get(i);
            GuiMessage msg = saved.toGuiMessage();
            if (msg != null) {
                chat.addMessage(msg.content());
                int newIndex = allMessages.size() - 1;
                if (newIndex >= originalSize) {
                    GuiMessage addedMsg = allMessages.get(newIndex);
                    setAddedTime(addedMsg, saved.addedTime);
                }
                loadedAny = true;
            }
        }

        if (loadedAny) {
            Component separator = Component.translatable("light_chat_patch.history.separator")
                    .setStyle(Style.EMPTY.withItalic(true).withColor(0xAAAAAA));
            chat.addMessage(separator);
        }
    }

    private static void setAddedTime(GuiMessage msg, int time) {
        try {
            java.lang.reflect.Field field = GuiMessage.class.getDeclaredField("addedTime");
            field.setAccessible(true);
            field.set(msg, time);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field field = GuiMessage.class.getDeclaredField("f_9631_");
                field.setAccessible(true);
                field.set(msg, time);
            } catch (Exception ignored) {
            }
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
