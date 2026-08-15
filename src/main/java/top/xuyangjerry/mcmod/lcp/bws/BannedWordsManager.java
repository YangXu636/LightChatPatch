package top.xuyangjerry.mcmod.lcp.bws;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.network.BannedWordsPayload;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BWS = Banned Words 违禁词系统
 * 服务端词库管理器：负责加载、保存、匹配、过滤、同步词库。
 * 词库持久化到 config/light_chat_patch/banned_words.json，格式为字符串数组。
 *
 * 匹配规则（按用户需求）：
 *   - 匹配范围：仅玩家在聊天框发送的消息（玩家纯聊天内容）
 *   - 大小写：不敏感
 *   - 匹配方式：全词匹配（词边界）
 *   - 不支持正则
 *   - 命中部分替换为 "***"
 *
 * 未安装 mod 的玩家也受影响：因为过滤发生在服务端 onChatMessage 中，
 * 原始 PlayerChatMessage 被替换/修改后再广播，所有客户端都会收到过滤后的消息。
 */
public class BannedWordsManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final String CONFIG_DIR = "light_chat_patch";
    private static final String CONFIG_FILE = "banned_words.json";
    private static final String REPLACEMENT = "***";

    /** 服务端词库：使用小写形式存储，便于大小写不敏感匹配。ConcurrentHashMap 用于快速去重查询。 */
    private static final ConcurrentHashMap<String, Boolean> bannedWordsSet = new ConcurrentHashMap<>();
    /** 保存原始词语（保留用户输入的大小写），用于 list 显示和同步给客户端。 */
    private static final List<String> originalWords = new ArrayList<>();

    private static MinecraftServer server;
    private static boolean initialized = false;

    private BannedWordsManager() {}

    /** 获取服务端实例。 */
    public static MinecraftServer getServer() {
        return server;
    }

    /** 服务端启动时调用：加载词库。 */
    public static void onServerStarted(MinecraftServer mcServer) {
        server = mcServer;
        load();
        initialized = true;
        LightChatPatch.LOGGER.info("[LCP][BWS] 违禁词系统已启动，词库共 {} 条", originalWords.size());
    }

    /** 服务端关闭时调用：保存词库。 */
    public static void onServerStopping(MinecraftServer mcServer) {
        if (initialized) {
            save();
        }
        initialized = false;
        server = null;
    }

    // ==================== 持久化 ====================

    private static Path getConfigPath() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        return gameDir.resolve("config").resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }

    private static void load() {
        Path path = getConfigPath();
        bannedWordsSet.clear();
        originalWords.clear();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                List<String> loaded = GSON.fromJson(json, LIST_TYPE);
                if (loaded != null) {
                    for (String word : loaded) {
                        if (word != null && !word.isBlank()) {
                            addWordInternal(word.trim());
                        }
                    }
                }
            } catch (IOException e) {
                LightChatPatch.LOGGER.warn("[LCP][BWS] 加载违禁词词库失败，使用空词库", e);
            }
        }
        // 空词库或首次加载都保存一次，确保文件存在
        save();
    }

    private static void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            synchronized (originalWords) {
                Files.writeString(path, GSON.toJson(new ArrayList<>(originalWords)));
            }
        } catch (IOException e) {
            LightChatPatch.LOGGER.warn("[LCP][BWS] 保存违禁词词库失败", e);
        }
    }

    // ==================== 词库增删改查（内部） ====================

    private static void addWordInternal(String word) {
        if (word == null || word.isBlank()) return;
        String lower = word.toLowerCase();
        if (!bannedWordsSet.containsKey(lower)) {
            bannedWordsSet.put(lower, Boolean.TRUE);
            synchronized (originalWords) {
                originalWords.add(word);
            }
        }
    }

    /** 添加违禁词。返回 true 表示成功添加，false 表示已存在。 */
    public static boolean addWord(String word) {
        if (word == null || word.isBlank()) return false;
        word = word.trim();
        String lower = word.toLowerCase();
        if (bannedWordsSet.containsKey(lower)) return false;
        addWordInternal(word);
        save();
        LightChatPatch.LOGGER.info("[LCP][BWS] 新增违禁词: {}", word);
        return true;
    }

    /** 删除违禁词（大小写不敏感）。返回 true 表示成功删除。 */
    public static boolean removeWord(String word) {
        if (word == null || word.isBlank()) return false;
        String lower = word.trim().toLowerCase();
        if (!bannedWordsSet.containsKey(lower)) return false;
        bannedWordsSet.remove(lower);
        synchronized (originalWords) {
            originalWords.removeIf(w -> w.equalsIgnoreCase(word.trim()));
        }
        save();
        LightChatPatch.LOGGER.info("[LCP][BWS] 删除违禁词: {}", word);
        return true;
    }

    /** 修改违禁词（大小写不敏感匹配 oldWord）。返回 true 表示修改成功。 */
    public static boolean modifyWord(String oldWord, String newWord) {
        if (oldWord == null || oldWord.isBlank() || newWord == null || newWord.isBlank()) return false;
        String oldLower = oldWord.trim().toLowerCase();
        String newWordTrimmed = newWord.trim();
        String newLower = newWordTrimmed.toLowerCase();
        if (!bannedWordsSet.containsKey(oldLower)) return false;
        if (oldLower.equals(newLower)) return false;
        if (bannedWordsSet.containsKey(newLower)) return false;

        bannedWordsSet.remove(oldLower);
        bannedWordsSet.put(newLower, Boolean.TRUE);
        synchronized (originalWords) {
            int idx = -1;
            for (int i = 0; i < originalWords.size(); i++) {
                if (originalWords.get(i).equalsIgnoreCase(oldWord.trim())) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                originalWords.set(idx, newWordTrimmed);
            } else {
                originalWords.add(newWordTrimmed);
            }
        }
        save();
        LightChatPatch.LOGGER.info("[LCP][BWS] 修改违禁词: {} -> {}", oldWord, newWordTrimmed);
        return true;
    }

    /** 获取词库副本（用于 list 显示和同步给 OP 客户端）。 */
    public static List<String> getAllWords() {
        synchronized (originalWords) {
            return new ArrayList<>(originalWords);
        }
    }

    // ==================== 同步给 OP 客户端 ====================

    private static boolean isOp(ServerPlayer player) {
        return player != null && player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_OWNER);
    }

    /** 向 OP 玩家同步违禁词词库。 */
    public static void syncToPlayerIfOp(ServerPlayer player) {
        if (player == null || !isOp(player)) return;
        syncToPlayer(player);
    }

    /** 向所有在线 OP 玩家同步违禁词词库。 */
    public static void broadcastSyncToOps() {
        if (server == null) return;
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (isOp(p)) {
                    syncToPlayer(p);
                }
            }
        });
    }

    private static void syncToPlayer(ServerPlayer player) {
        try {
            if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, BannedWordsPayload.TYPE)) {
                return;
            }
            List<String> words = getAllWords();
            BannedWordsPayload payload = new BannedWordsPayload(BannedWordsPayload.ACTION_SYNC, words, null, null);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
            LightChatPatch.LOGGER.debug("[LCP][BWS] 同步词库给玩家 {}: {} 条", player.getName().getString(), words.size());
        } catch (Throwable ignore) {
        }
    }

    // ==================== 过滤逻辑 ====================

    /**
     * 对玩家聊天内容进行违禁词过滤。
     * 规则：全词匹配、大小写不敏感、命中部分替换为 ***。
     * "全词匹配"定义：被空白字符或标点符号包围，或位于字符串首尾。
     *
     * @param text 玩家聊天的纯文本
     * @return 过滤后的文本（若无命中则返回原文本引用，避免无意义拷贝）
     */
    public static String filter(String text) {
        if (text == null || text.isEmpty()) return text;
        if (bannedWordsSet.isEmpty()) return text;

        String lowerText = text.toLowerCase();
        // 使用 StringBuilder 构建结果，逐字符扫描
        StringBuilder result = null;  // 懒初始化：无命中时不创建
        int i = 0;
        int len = text.length();

        while (i < len) {
            char c = lowerText.charAt(i);
            // 跳过非单词字符（直接写入）
            if (!isWordChar(c)) {
                if (result != null) result.append(text.charAt(i));
                i++;
                continue;
            }
            // 开始一个单词：记录起始位置
            int wordStart = i;
            // 找到单词结束位置
            while (i < len && isWordChar(lowerText.charAt(i))) {
                i++;
            }
            int wordEnd = i; // 不包含
            String word = lowerText.substring(wordStart, wordEnd);
            // 查找此单词是否在违禁词中
            if (bannedWordsSet.containsKey(word)) {
                // 命中：需要替换
                if (result == null) {
                    // 首次命中：创建 StringBuilder，拷贝前面未命中的部分
                    result = new StringBuilder(len);
                    result.append(text, 0, wordStart);
                }
                result.append(REPLACEMENT);
            } else if (result != null) {
                // 未命中但之前有命中：需要写入此单词
                result.append(text, wordStart, wordEnd);
            }
            // 否则（未命中且之前也未命中）：什么都不做，继续前进
        }

        // 扫尾：若 result 已创建但 i < len（最后是非单词字符的情况？），实际上循环已经处理完所有字符
        // 但为了安全，若 result != null 且长度 < 原文本（因为前面部分未写入的场景？不，上面逻辑已处理）
        return result != null ? result.toString() : text;
    }

    /** 判断字符是否属于"单词字符"（字母、数字、中文等可视作单词组成的字符）。
     *  空白、标点符号、控制字符等视为单词分隔符。 */
    private static boolean isWordChar(char c) {
        // 空白字符
        if (Character.isWhitespace(c)) return false;
        // ASCII 标点符号
        if (c < 0x80) {
            if ((c >= '!' && c <= '/') || (c >= ':' && c <= '@')
                    || (c >= '[' && c <= '`') || (c >= '{' && c <= '~')) {
                return false;
            }
        } else {
            // Unicode 标点/符号类
            int type = Character.getType(c);
            if (type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION
                    || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL
                    || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL) {
                return false;
            }
        }
        return true;
    }

    // ==================== C2S 请求处理（仅日志，不广播） ====================

    /**
     * 处理来自客户端（C2S）的词库更新请求。
     * 仅 OP 玩家可以执行增/删/改操作。
     * 操作结果仅记录服务端日志，不向玩家发送游戏内消息。
     *
     * @param player  发起操作的玩家
     * @param payload 客户端发来的 Payload
     */
    public static void handleClientRequest(ServerPlayer player, BannedWordsPayload payload) {
        if (player == null || payload == null) return;
        if (!player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_OWNER)) {
            LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 尝试执行 BWS 操作但权限不足",
                    player.getName().getString());
            return;
        }

        String action = payload.action();
        if (action == null) {
            LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 发送空操作", player.getName().getString());
            return;
        }

        String playerName = player.getName().getString();
        switch (action) {
            case BannedWordsPayload.ACTION_ADD -> {
                if (payload.word() == null || payload.word().isBlank()) {
                    LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 添加违禁词但词为空", playerName);
                    return;
                }
                boolean ok = addWord(payload.word());
                if (ok) {
                    LightChatPatch.LOGGER.info("[LCP][BWS] 玩家 {} 成功添加违禁词: {}", playerName, payload.word());
                    broadcastSyncToOps();
                } else {
                    LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 添加违禁词失败(已存在): {}", playerName, payload.word());
                }
            }
            case BannedWordsPayload.ACTION_REMOVE -> {
                if (payload.word() == null || payload.word().isBlank()) {
                    LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 删除违禁词但词为空", playerName);
                    return;
                }
                boolean ok = removeWord(payload.word());
                if (ok) {
                    LightChatPatch.LOGGER.info("[LCP][BWS] 玩家 {} 成功删除违禁词: {}", playerName, payload.word());
                    broadcastSyncToOps();
                } else {
                    LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 删除违禁词失败(不存在): {}", playerName, payload.word());
                }
            }
            case BannedWordsPayload.ACTION_MODIFY -> {
                if (payload.word() == null || payload.word2() == null) {
                    LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 修改违禁词参数缺失", playerName);
                    return;
                }
                boolean ok = modifyWord(payload.word(), payload.word2());
                if (ok) {
                    LightChatPatch.LOGGER.info("[LCP][BWS] 玩家 {} 成功修改违禁词: {} -> {}", playerName, payload.word(), payload.word2());
                    broadcastSyncToOps();
                } else {
                    LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 修改违禁词失败: {} -> {}", playerName, payload.word(), payload.word2());
                }
            }
            case BannedWordsPayload.ACTION_LIST -> {
                LightChatPatch.LOGGER.info("[LCP][BWS] 玩家 {} 请求词库列表（共 {} 条）", playerName, originalWords.size());
            }
            default -> {
                LightChatPatch.LOGGER.warn("[LCP][BWS] 玩家 {} 发送未知操作: {}", playerName, action);
            }
        }
    }
}
