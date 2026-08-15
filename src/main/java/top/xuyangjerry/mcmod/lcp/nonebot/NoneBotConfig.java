package top.xuyangjerry.mcmod.lcp.nonebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NoneBotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "nonebot.json";

    private static NoneBotConfig instance;

    private boolean enabled = false;
    private int port = 8765;
    private String token = "change-me-please";
    // NoneBot->MC方向：收到的图片按"文件大小（字节）"进行缩放限制，与 LcpConfig.maxImageSize/maxImageSizeUnit 对齐。
    // 默认 10 MB。
    private int maxImageSize = 10;
    private String maxImageSizeUnit = "MB";

    public static NoneBotConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                NoneBotConfigData data = GSON.fromJson(json, NoneBotConfigData.class);
                instance = new NoneBotConfig();
                instance.enabled = data.enabled != null ? data.enabled : false;
                instance.port = data.port != null ? data.port : 8765;
                instance.token = data.token != null ? data.token : "change-me-please";
                instance.maxImageSize = data.maxImageSize != null ? data.maxImageSize : 10;
                instance.maxImageSizeUnit = data.maxImageSizeUnit != null ? data.maxImageSizeUnit : "MB";
            } catch (Exception e) {
                LightChatPatch.LOGGER.warn("Failed to load nonebot.json, using defaults", e);
                instance = new NoneBotConfig();
            }
        } else {
            instance = new NoneBotConfig();
        }
        save();
    }

    public static void save() {
        if (instance == null) {
            return;
        }
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            NoneBotConfigData data = new NoneBotConfigData();
            data.enabled = instance.enabled;
            data.port = instance.port;
            data.token = instance.token;
            data.maxImageSize = instance.maxImageSize;
            data.maxImageSizeUnit = instance.maxImageSizeUnit;
            Files.writeString(configPath, GSON.toJson(data));
        } catch (IOException e) {
            LightChatPatch.LOGGER.error("Failed to save nonebot.json", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = Math.max(1, Math.min(65535, port)); }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getMaxImageSize() { return maxImageSize; }
    public void setMaxImageSize(int maxImageSize) { this.maxImageSize = Math.max(1, maxImageSize); }
    public String getMaxImageSizeUnit() { return maxImageSizeUnit; }
    public void setMaxImageSizeUnit(String maxImageSizeUnit) { this.maxImageSizeUnit = maxImageSizeUnit; }

    /**
     * 计算图片大小上限（字节），按 maxImageSize + maxImageSizeUnit 换算。
     * 支持 B/KB/MB/GB，大小写不敏感。
     */
    public long getMaxImageBytes() {
        long size = Math.max(1L, maxImageSize);
        String unit = maxImageSizeUnit == null ? "MB" : maxImageSizeUnit.toUpperCase();
        switch (unit) {
            case "B": return size;
            case "KB": return size * 1024L;
            case "GB": return size * 1024L * 1024L * 1024L;
            case "MB":
            default:
                return size * 1024L * 1024L;
        }
    }

    private static class NoneBotConfigData {
        Boolean enabled;
        Integer port;
        String token;
        // 按文件大小限制（对齐 LcpConfig.maxImageSize / maxImageSizeUnit）
        Integer maxImageSize;
        String maxImageSizeUnit;
    }
}
