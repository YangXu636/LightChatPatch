package top.xuyangjerry.mcmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.glfw.GLFW;

public class LcpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "light_chat_patch.json";

    private static LcpConfig instance;

    private int chatHistoryMaxSize = 100;
    private ChatHistoryView chatHistoryView = ChatHistoryView.CURRENT_WORLD;
    private boolean enableHoverButtons = true;
    private boolean enableRightClickMenu = true;
    private boolean saveDraftOnClose = true;
    private boolean plusOneSelf = false;
    private int chatMaxVisibleMessages = 100;

    private int sendKey = GLFW.GLFW_KEY_ENTER;
    private int newlineKey = GLFW.GLFW_KEY_KP_ENTER;
    private boolean preserveChatHistory = true;
    private boolean clickReplyToJump = true;
    private boolean rangeSelectToggle = true; // true=反转, false=全选

    public static LcpConfig getInstance() {
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
                LcpConfigData data = GSON.fromJson(json, LcpConfigData.class);
                instance = new LcpConfig();
                instance.chatHistoryMaxSize = data.chatHistoryMaxSize != null ? data.chatHistoryMaxSize : 100;
                instance.chatHistoryView = data.chatHistoryView != null ? ChatHistoryView.fromKey(data.chatHistoryView) : ChatHistoryView.CURRENT_WORLD;
                instance.enableHoverButtons = data.enableHoverButtons != null ? data.enableHoverButtons : true;
                instance.enableRightClickMenu = data.enableRightClickMenu != null ? data.enableRightClickMenu : true;
                instance.saveDraftOnClose = data.saveDraftOnClose != null ? data.saveDraftOnClose : true;
                instance.plusOneSelf = data.plusOneSelf != null ? data.plusOneSelf : false;
                instance.chatMaxVisibleMessages = data.chatMaxVisibleMessages != null ? data.chatMaxVisibleMessages : 100;
                instance.sendKey = data.sendKey != null ? data.sendKey : GLFW.GLFW_KEY_ENTER;
                instance.newlineKey = data.newlineKey != null ? data.newlineKey : GLFW.GLFW_KEY_KP_ENTER;
                instance.preserveChatHistory = data.preserveChatHistory != null ? data.preserveChatHistory : true;
                instance.clickReplyToJump = data.clickReplyToJump != null ? data.clickReplyToJump : true;
                instance.rangeSelectToggle = data.rangeSelectToggle != null ? data.rangeSelectToggle : true;
            } catch (IOException e) {
                instance = new LcpConfig();
            }
        } else {
            instance = new LcpConfig();
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
            LcpConfigData data = new LcpConfigData();
            data.chatHistoryMaxSize = instance.chatHistoryMaxSize;
            data.chatHistoryView = instance.chatHistoryView.getKey();
            data.enableHoverButtons = instance.enableHoverButtons;
            data.enableRightClickMenu = instance.enableRightClickMenu;
            data.saveDraftOnClose = instance.saveDraftOnClose;
            data.plusOneSelf = instance.plusOneSelf;
            data.chatMaxVisibleMessages = instance.chatMaxVisibleMessages;
            data.sendKey = instance.sendKey;
            data.newlineKey = instance.newlineKey;
            data.preserveChatHistory = instance.preserveChatHistory;
            data.clickReplyToJump = instance.clickReplyToJump;
            data.rangeSelectToggle = instance.rangeSelectToggle;
            Files.writeString(configPath, GSON.toJson(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save LightChatPatch config", e);
        }
    }

    /**
     * 获取配置文件路径。延迟计算，仅在实际需要时调用。
     * 使用系统属性 "minecraft.gameDir" 作为回退（由启动器设置）。
     */
    private static Path getConfigPath() {
        Path gameDir = getGameDir();
        return gameDir.resolve("config").resolve(CONFIG_FILE);
    }

    /**
     * 获取游戏目录，优先使用系统属性（启动器设置），回退到 Minecraft.getInstance()。
     * 这确保在模组加载早期（Minecraft 实例未就绪时）也能工作。
     */
    private static Path getGameDir() {
        // 尝试从系统属性获取（Fabric/NeoForge 启动器都会设置）
        String gameDirProp = System.getProperty("minecraft.gameDir");
        if (gameDirProp != null) {
            return Path.of(gameDirProp);
        }

        // 回退到 Minecraft 实例（客户端运行时）
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath();
            }
        } catch (Exception ignored) {
        }

        // 最终回退：当前工作目录
        return Path.of(".").toAbsolutePath();
    }

    public int getChatHistoryMaxSize() {
        return chatHistoryMaxSize;
    }

    public void setChatHistoryMaxSize(int chatHistoryMaxSize) {
        this.chatHistoryMaxSize = Math.max(10, Math.min(1000, chatHistoryMaxSize));
    }

    public ChatHistoryView getChatHistoryView() {
        return chatHistoryView;
    }

    public void setChatHistoryView(ChatHistoryView chatHistoryView) {
        this.chatHistoryView = chatHistoryView;
    }

    public boolean isEnableHoverButtons() {
        return enableHoverButtons;
    }

    public void setEnableHoverButtons(boolean enableHoverButtons) {
        this.enableHoverButtons = enableHoverButtons;
    }

    public boolean isEnableRightClickMenu() {
        return enableRightClickMenu;
    }

    public void setEnableRightClickMenu(boolean enableRightClickMenu) {
        this.enableRightClickMenu = enableRightClickMenu;
    }

    public boolean isSaveDraftOnClose() {
        return saveDraftOnClose;
    }

    public void setSaveDraftOnClose(boolean saveDraftOnClose) {
        this.saveDraftOnClose = saveDraftOnClose;
    }

    public boolean isPlusOneSelf() {
        return plusOneSelf;
    }

    public void setPlusOneSelf(boolean plusOneSelf) {
        this.plusOneSelf = plusOneSelf;
    }

    public int getChatMaxVisibleMessages() {
        return chatMaxVisibleMessages;
    }

    public void setChatMaxVisibleMessages(int chatMaxVisibleMessages) {
        this.chatMaxVisibleMessages = Math.max(10, Math.min(1000, chatMaxVisibleMessages));
    }

    public int getSendKey() {
        return sendKey;
    }

    public void setSendKey(int sendKey) {
        this.sendKey = sendKey;
    }

    public int getNewlineKey() {
        return newlineKey;
    }

    public void setNewlineKey(int newlineKey) {
        this.newlineKey = newlineKey;
    }

    public boolean isPreserveChatHistory() {
        return preserveChatHistory;
    }

    public void setPreserveChatHistory(boolean preserveChatHistory) {
        this.preserveChatHistory = preserveChatHistory;
    }

    public boolean isClickReplyToJump() {
        return clickReplyToJump;
    }

    public void setClickReplyToJump(boolean clickReplyToJump) {
        this.clickReplyToJump = clickReplyToJump;
    }

    public boolean isRangeSelectToggle() {
        return rangeSelectToggle;
    }

    public void setRangeSelectToggle(boolean rangeSelectToggle) {
        this.rangeSelectToggle = rangeSelectToggle;
    }

    private static class LcpConfigData {
        Integer chatHistoryMaxSize;
        String chatHistoryView;
        Boolean enableHoverButtons;
        Boolean enableRightClickMenu;
        Boolean saveDraftOnClose;
        Boolean plusOneSelf;
        Integer chatMaxVisibleMessages;
        Integer sendKey;
        Integer newlineKey;
        Boolean preserveChatHistory;
        Boolean clickReplyToJump;
        Boolean rangeSelectToggle;
    }
}
