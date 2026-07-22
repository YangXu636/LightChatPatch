package top.xuyangjerry.mcmod.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChatDraftManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ChatDraftManager() {
    }

    private static Path getDraftsDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("light_chat_patch")
                .resolve("drafts");
    }

    private static Path getDraftFile(String worldId) {
        if (worldId == null) {
            return null;
        }
        return getDraftsDir().resolve("draft_" + worldId + ".json");
    }

    public static String loadDraft() {
        Path file = getDraftFile(ChatHistoryManager.getCurrentWorldId());
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj != null && obj.has("text")) {
                return obj.get("text").getAsString();
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    public static void saveDraft(String text) {
        Path file = getDraftFile(ChatHistoryManager.getCurrentWorldId());
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("text", text != null ? text : "");
            Files.writeString(file, GSON.toJson(obj));
        } catch (IOException e) {
            // ignore
        }
    }

    public static void clearDraft() {
        Path file = getDraftFile(ChatHistoryManager.getCurrentWorldId());
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            // ignore
        }
    }
}
