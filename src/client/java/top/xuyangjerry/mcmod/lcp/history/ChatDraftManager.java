package top.xuyangjerry.mcmod.lcp.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import top.xuyangjerry.mcmod.lcp.config.ChatHistoryView;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;

public final class ChatDraftManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ChatDraftManager() {
    }

    private static Path getDraftFile(ChatHistoryView view) {
        Path dir = HistoryPaths.getStorageDir(view);
        if (dir == null) {
            return null;
        }
        return dir.resolve("draft.json");
    }

    public static String loadDraft() {
        Path file = getDraftFile(LcpConfig.getInstance().getDraftHistoryView());
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
        }
        return null;
    }

    public static void saveDraft(String text) {
        Path file = getDraftFile(LcpConfig.getInstance().getDraftHistoryView());
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("text", text != null ? text : "");
            Files.writeString(file, GSON.toJson(obj));
        } catch (IOException e) {
        }
    }

    public static void clearDraft() {
        Path file = getDraftFile(LcpConfig.getInstance().getDraftHistoryView());
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
        }
    }
}
