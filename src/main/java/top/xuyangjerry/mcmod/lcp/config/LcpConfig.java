package top.xuyangjerry.mcmod.lcp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

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
    private boolean rangeSelectToggle = true;
    private ChatHistoryView draftHistoryView = ChatHistoryView.CURRENT_WORLD;
    private int chatMaxLength = 256;
    private int replyTruncateThreshold = 50;
    private int replyTruncateHead = 15;
    private int replyTruncateTail = 10;
    private int maxImageSize = 10;
    private String maxImageSizeUnit = "MB";
    // 问题2：QQ -> MC 聊天消息的颜色（ARGB，默认亮绿色 0xFF55FF55）
    private int qqMessageColor = 0xFF55FF55;

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
                instance.draftHistoryView = data.draftHistoryView != null ? ChatHistoryView.fromKey(data.draftHistoryView) : ChatHistoryView.CURRENT_WORLD;
                instance.chatMaxLength = data.chatMaxLength != null ? data.chatMaxLength : 256;
                instance.replyTruncateThreshold = data.replyTruncateThreshold != null ? data.replyTruncateThreshold : 50;
                instance.replyTruncateHead = data.replyTruncateHead != null ? data.replyTruncateHead : 15;
                instance.replyTruncateTail = data.replyTruncateTail != null ? data.replyTruncateTail : 10;
                instance.maxImageSize = data.maxImageSize != null ? data.maxImageSize : 10;
                instance.maxImageSizeUnit = data.maxImageSizeUnit != null ? data.maxImageSizeUnit : "MB";
                instance.qqMessageColor = data.qqMessageColor != null ? data.qqMessageColor : 0xFF55FF55;
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
            data.draftHistoryView = instance.draftHistoryView.getKey();
            data.chatMaxLength = instance.chatMaxLength;
            data.replyTruncateThreshold = instance.replyTruncateThreshold;
            data.replyTruncateHead = instance.replyTruncateHead;
            data.replyTruncateTail = instance.replyTruncateTail;
            data.maxImageSize = instance.maxImageSize;
            data.maxImageSizeUnit = instance.maxImageSizeUnit;
            data.qqMessageColor = instance.qqMessageColor;
            Files.writeString(configPath, GSON.toJson(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save LightChatPatch config", e);
        }
    }

    private static Path getConfigPath() {
        Path gameDir = getGameDir();
        return gameDir.resolve("config").resolve(CONFIG_FILE);
    }

    private static Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
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

    public ChatHistoryView getDraftHistoryView() {
        return draftHistoryView;
    }

    public void setDraftHistoryView(ChatHistoryView draftHistoryView) {
        this.draftHistoryView = draftHistoryView;
    }

    public int getChatMaxLength() {
        return chatMaxLength;
    }

    public void setChatMaxLength(int chatMaxLength) {
        this.chatMaxLength = Math.max(256, Math.min(32767, chatMaxLength));
    }

    public int getReplyTruncateThreshold() {
        return replyTruncateThreshold;
    }

    public void setReplyTruncateThreshold(int replyTruncateThreshold) {
        this.replyTruncateThreshold = Math.max(10, Math.min(256, replyTruncateThreshold));
    }

    public int getReplyTruncateHead() {
        return replyTruncateHead;
    }

    public void setReplyTruncateHead(int replyTruncateHead) {
        this.replyTruncateHead = Math.max(3, Math.min(100, replyTruncateHead));
    }

    public int getReplyTruncateTail() {
        return replyTruncateTail;
    }

    public void setReplyTruncateTail(int replyTruncateTail) {
        this.replyTruncateTail = Math.max(3, Math.min(100, replyTruncateTail));
    }

    public int getMaxImageSize() {
        return maxImageSize;
    }

    public void setMaxImageSize(int maxImageSize) {
        this.maxImageSize = Math.max(1, maxImageSize);
    }

    public String getMaxImageSizeUnit() {
        return maxImageSizeUnit;
    }

    public void setMaxImageSizeUnit(String maxImageSizeUnit) {
        this.maxImageSizeUnit = maxImageSizeUnit;
    }

    /** QQ -> MC 消息颜色（ARGB），比如 0xFF55FF55 是不透明的亮绿色 */
    public int getQqMessageColor() {
        return qqMessageColor;
    }

    /** 设置 QQ -> MC 消息颜色（ARGB），并强制不透明 alpha=255 */
    public void setQqMessageColor(int qqMessageColor) {
        this.qqMessageColor = 0xFF000000 | qqMessageColor;
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
        String draftHistoryView;
        Integer chatMaxLength;
        Integer replyTruncateThreshold;
        Integer replyTruncateHead;
        Integer replyTruncateTail;
        Integer maxImageSize;
        String maxImageSizeUnit;
        Integer qqMessageColor;
    }
}