package top.xuyangjerry.mcmod.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.ArrayListDeque;
import top.xuyangjerry.mcmod.LightChatPatch;
import top.xuyangjerry.mcmod.config.ChatHistoryView;
import top.xuyangjerry.mcmod.config.LcpConfig;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ChatHistoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private ChatHistoryManager() {
    }

    public static String getCurrentWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LightChatPatch.LOGGER.debug("[ChatHistory] getCurrentWorldId: level is null");
            return null;
        }
        if (mc.hasSingleplayerServer()) {
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null) {
                String id = "sp_" + sanitize(server.getWorldData().getLevelName());
                LightChatPatch.LOGGER.info("[ChatHistory] Singleplayer world id: {}", id);
                return id;
            }
            LightChatPatch.LOGGER.warn("[ChatHistory] hasSingleplayerServer=true but getSingleplayerServer() is null");
        } else {
            ServerData data = mc.getCurrentServer();
            if (data != null && data.ip != null && !data.ip.isEmpty()) {
                String id = "mp_" + sanitize(data.ip);
                LightChatPatch.LOGGER.info("[ChatHistory] Multiplayer server id: {}", id);
                return id;
            }
            LightChatPatch.LOGGER.warn("[ChatHistory] Multiplayer but getCurrentServer() is null or ip is empty");
        }
        return null;
    }

    public static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path getHistoryDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("light_chat_patch")
                .resolve("history");
    }

    private static Path getHistoryFile(ChatHistoryView view) {
        String filename;
        switch (view) {
            case GLOBAL:
                filename = "global.json";
                break;
            case CURRENT_VERSION:
                filename = "version_" + sanitize(Minecraft.getInstance().getLaunchedVersion()) + ".json";
                break;
            case CURRENT_WORLD:
                String worldId = getCurrentWorldId();
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

    public static List<String> loadHistory(ChatHistoryView view) {
        Path file = getHistoryFile(view);
        if (file == null || !Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file);
            List<String> history = GSON.fromJson(json, LIST_TYPE);
            return history != null ? history : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static void saveHistory(ChatHistoryView view, List<String> history) {
        Path file = getHistoryFile(view);
        if (file == null) {
            LightChatPatch.LOGGER.warn("[ChatHistory] saveHistory: file is null for view {}", view);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(history));
            LightChatPatch.LOGGER.info("[ChatHistory] Saved {} entries to {}", history.size(), file);
        } catch (IOException e) {
            LightChatPatch.LOGGER.error("[ChatHistory] Failed to save history to {}", file, e);
        }
    }

    public static void addMessage(ChatHistoryView view, String message) {
        List<String> history = loadHistory(view);
        if (!history.isEmpty() && history.get(history.size() - 1).equals(message)) {
            return;
        }
        int maxSize = LcpConfig.getInstance().getChatHistoryMaxSize();
        while (history.size() >= maxSize) {
            history.remove(0);
        }
        history.add(message);
        saveHistory(view, history);
    }

    public static void loadToRecentChat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            LightChatPatch.LOGGER.debug("[ChatHistory] loadToRecentChat: mc.gui is null");
            return;
        }
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) {
            LightChatPatch.LOGGER.debug("[ChatHistory] loadToRecentChat: chat is null");
            return;
        }

        ChatHistoryView view = LcpConfig.getInstance().getChatHistoryView();
        boolean preserve = LcpConfig.getInstance().isPreserveChatHistory();
        LightChatPatch.LOGGER.info("[ChatHistory] loadToRecentChat called, view={}, preserve={}", view, preserve);
        Path historyFile = getHistoryFile(view);
        if (historyFile == null) {
            LightChatPatch.LOGGER.warn("[ChatHistory] loadToRecentChat: historyFile is null for view {}", view);
            return;
        }

        List<String> history = loadHistory(view);
        LightChatPatch.LOGGER.info("[ChatHistory] Loaded {} entries from {}", history.size(), historyFile);
        if (history.isEmpty() && !Files.exists(historyFile)) {
            LightChatPatch.LOGGER.info("[ChatHistory] History file does not exist and history is empty, skipping");
            return;
        }

        ArrayListDeque<String> recentChat = chat.getRecentChat();
        int beforeSize = recentChat.size();
        recentChat.clear();
        int maxSize = LcpConfig.getInstance().getChatHistoryMaxSize();
        int start = Math.max(0, history.size() - maxSize);
        for (int i = start; i < history.size(); i++) {
            recentChat.addLast(history.get(i));
        }
        if (recentChat.isEmpty()) {
            recentChat.addLast("");
        }
        LightChatPatch.LOGGER.info("[ChatHistory] recentChat: before={}, after={}", beforeSize, recentChat.size());
    }
}
