package top.xuyangjerry.mcmod.lcp.client.bws;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking;
import top.xuyangjerry.mcmod.lcp.network.BannedWordsPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BWS = Banned Words 违禁词系统客户端管理器
 * 负责：
 *   1. 缓存服务端同步的词库（OP 玩家才会收到）
 *   2. 提供 C2S 请求接口（增/删/改）
 *   3. 状态查询（是否为 OP、服务端是否已装 mod）
 */
public final class BannedWordsClientManager {

    /** 服务端同步的完整词库（仅 OP 玩家有）。使用小写形式做 key，便于快速判断。 */
    private static final ConcurrentHashMap<String, Boolean> bannedWordsSet = new ConcurrentHashMap<>();
    /** 原始词语列表（保留用户输入的大小写），用于 UI 显示。 */
    private static final List<String> originalWords = Collections.synchronizedList(new ArrayList<>());
    /** 是否已接收到服务端同步的词库（null=未同步过, true/false=已同步且状态）。 */
    private static volatile Boolean synced = null;
    /** 上一次操作的结果消息，用于 UI 回显。 */
    private static volatile String lastResult = null;

    private BannedWordsClientManager() {}

    // ==================== 服务端同步处理 ====================

    /**
     * 处理服务端发来的 S2C 同步 Payload。
     * 仅 ACTION_SYNC 会携带完整词库。
     */
    public static void handleSync(BannedWordsPayload payload) {
        if (payload == null) return;
        List<String> words = payload.words();
        bannedWordsSet.clear();
        originalWords.clear();
        if (words != null) {
            for (String w : words) {
                if (w != null && !w.isBlank()) {
                    bannedWordsSet.put(w.toLowerCase(), Boolean.TRUE);
                    originalWords.add(w);
                }
            }
        }
        synced = true;
        LightChatPatch.LOGGER.info("[LCP][BWS] 客户端收到词库同步: {} 条", originalWords.size());
    }

    // ==================== C2S 请求 ====================

    /**
     * 发送添加违禁词请求。
     */
    public static void sendAddRequest(String word) {
        if (!checkCanSend("添加违禁词")) return;
        ClientPlayNetworking.send(new BannedWordsPayload(
                BannedWordsPayload.ACTION_ADD, null, word, null));
    }

    /**
     * 发送删除违禁词请求。
     */
    public static void sendRemoveRequest(String word) {
        if (!checkCanSend("删除违禁词")) return;
        ClientPlayNetworking.send(new BannedWordsPayload(
                BannedWordsPayload.ACTION_REMOVE, null, word, null));
    }

    /**
     * 发送修改违禁词请求。
     */
    public static void sendModifyRequest(String oldWord, String newWord) {
        if (!checkCanSend("修改违禁词")) return;
        ClientPlayNetworking.send(new BannedWordsPayload(
                BannedWordsPayload.ACTION_MODIFY, null, oldWord, newWord));
    }

    /**
     * 发送 list 请求（仅触发服务端重新同步）。
     */
    public static void sendListRequest() {
        if (!checkCanSend("查询违禁词")) return;
        ClientPlayNetworking.send(new BannedWordsPayload(
                BannedWordsPayload.ACTION_LIST, null, null, null));
    }

    /**
     * 检查是否可以发送请求（服务端 mod 是否已装 + 玩家是否已连接）。
     */
    private static boolean checkCanSend(String actionName) {
        if (!ClientNetworking.isServerModInstalled()) {
            tip("服务端未安装 LCP mod，无法使用" + actionName + "功能");
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            tip("未连接到服务器，无法" + actionName);
            return false;
        }
        return true;
    }

    private static void tip(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud == null) return;
        mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[LCP][BWS] " + msg)
                        .withStyle(s -> s.withColor(0xFFFF5555)));
    }

    // ==================== 状态查询 ====================

    /** 获取当前缓存的词库（返回副本，避免并发修改问题）。 */
    public static List<String> getCachedWords() {
        synchronized (originalWords) {
            return new ArrayList<>(originalWords);
        }
    }

    /** 是否已接收到服务端词库同步。 */
    public static boolean isSynced() {
        return Boolean.TRUE.equals(synced);
    }

    /** 获取上次操作结果。 */
    public static String getLastResult() {
        return lastResult;
    }

    /** 设置操作结果（供网络层调用）。 */
    public static void setLastResult(String result) {
        lastResult = result;
    }

    // ==================== 乐观更新（UI 立即刷新，服务端同步作为最终权威） ====================

    /** 乐观添加：发送请求后立即更新本地缓存，等服务端同步时会覆盖。 */
    public static void optimisticAdd(String word) {
        if (word == null || word.isBlank()) return;
        String lower = word.toLowerCase();
        if (!bannedWordsSet.containsKey(lower)) {
            bannedWordsSet.put(lower, Boolean.TRUE);
            synchronized (originalWords) {
                originalWords.add(word.trim());
            }
        }
    }

    /** 乐观删除：发送请求后立即更新本地缓存。 */
    public static void optimisticRemove(String word) {
        if (word == null || word.isBlank()) return;
        String lower = word.trim().toLowerCase();
        bannedWordsSet.remove(lower);
        synchronized (originalWords) {
            originalWords.removeIf(w -> w.equalsIgnoreCase(word.trim()));
        }
    }

    /** 乐观修改：发送请求后立即更新本地缓存。 */
    public static void optimisticModify(String oldWord, String newWord) {
        if (oldWord == null || oldWord.isBlank() || newWord == null || newWord.isBlank()) return;
        String oldLower = oldWord.trim().toLowerCase();
        String newLower = newWord.trim().toLowerCase();
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
                originalWords.set(idx, newWord.trim());
            } else {
                originalWords.add(newWord.trim());
            }
        }
    }
}