package top.xuyangjerry.mcmod.lcp.client.mute;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.network.MutePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端禁言管理器（Mute Client Manager）
 *
 * 职责：
 *   1. 接收并缓存服务端同步的"当前被禁言玩家列表"（仅 OP 玩家收到）
 *   2. 提供发送 C2S 禁言/解禁请求的方法（供社交界面按钮、右键菜单调用）
 *   3. 提供 isMuted(uuid/name) 查询接口（供 UI 判断当前禁言状态，显示"禁言"/"解禁"按钮文案）
 */
public final class MuteClientManager {

    /** 缓存的禁言列表（从服务端同步） */
    private static final List<MutePayload.MuteEntry> mutedEntries = new ArrayList<>();

    private MuteClientManager() {}

    // ==================== S2C 同步处理 ====================

    /** 处理服务端发来的禁言列表同步（ACTION_SYNC）。 */
    public static void handleSync(MutePayload payload) {
        synchronized (mutedEntries) {
            mutedEntries.clear();
            if (payload.entries() != null) {
                mutedEntries.addAll(payload.entries());
            }
        }
        LightChatPatch.LOGGER.info("[LCP][Mute] 客户端同步禁言列表完成，共 {} 条被禁言玩家",
                payload.entries() == null ? 0 : payload.entries().size());
    }

    // ==================== 状态查询 ====================

    /** 获取禁言列表副本（UI 展示用）。 */
    public static List<MutePayload.MuteEntry> getMutedEntries() {
        synchronized (mutedEntries) {
            return new ArrayList<>(mutedEntries);
        }
    }

    /**
     * 按玩家名查询是否被禁言。
     * @return 被禁言时返回对应的 MuteEntry，否则返回 null。
     */
    public static MutePayload.MuteEntry findByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        synchronized (mutedEntries) {
            for (MutePayload.MuteEntry e : mutedEntries) {
                if (playerName.equals(e.targetName())) return e;
            }
        }
        return null;
    }

    /**
     * 按玩家 UUID 字符串查询是否被禁言。
     * @return 被禁言时返回对应的 MuteEntry，否则返回 null。
     */
    public static MutePayload.MuteEntry findByUuid(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank()) return null;
        synchronized (mutedEntries) {
            for (MutePayload.MuteEntry e : mutedEntries) {
                if (uuidStr.equals(e.targetUuid())) return e;
            }
        }
        return null;
    }

    // ==================== C2S 请求发送 ====================

    /**
     * 发送"禁言玩家"请求（C2S）。
     * @param targetName 目标玩家名（必填）
     * @param targetUuid 目标玩家 UUID 字符串（选填，可为空）
     * @param reason 禁言原因（选填，可为空）
     */
    public static void sendMuteRequest(String targetName, String targetUuid, String reason) {
        if (!checkCanSend("禁言")) return;
        if (targetName == null || targetName.isBlank()) return;

        MutePayload payload = new MutePayload(
                MutePayload.ACTION_MUTE,
                null,
                targetName,
                targetUuid,
                reason == null ? "" : reason
        );
        try {
            ClientPlayNetworking.send(payload);
            LightChatPatch.LOGGER.info("[LCP][Mute] 客户端发送禁言请求: target={}, uuid={}, reason={}",
                    targetName, targetUuid, reason);
        } catch (Throwable t) {
            LightChatPatch.LOGGER.error("[LCP][Mute] 发送禁言请求失败", t);
            tip("发送禁言请求失败：" + t.getMessage());
        }
    }

    /**
     * 发送"解禁玩家"请求（C2S）。
     * @param targetName 目标玩家名（必填）
     * @param targetUuid 目标玩家 UUID 字符串（选填，可为空）
     */
    public static void sendUnmuteRequest(String targetName, String targetUuid) {
        if (!checkCanSend("解禁")) return;
        if (targetName == null || targetName.isBlank()) return;

        MutePayload payload = new MutePayload(
                MutePayload.ACTION_UNMUTE,
                null,
                targetName,
                targetUuid,
                null
        );
        try {
            ClientPlayNetworking.send(payload);
            LightChatPatch.LOGGER.info("[LCP][Mute] 客户端发送解禁请求: target={}, uuid={}", targetName, targetUuid);
        } catch (Throwable t) {
            LightChatPatch.LOGGER.error("[LCP][Mute] 发送解禁请求失败", t);
            tip("发送解禁请求失败：" + t.getMessage());
        }
    }

    private static boolean checkCanSend(String actionName) {
        if (!top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking.isServerModInstalled()) {
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
                Component.literal("[LCP][Mute] " + msg)
                        .withStyle(s -> s.withColor(0xFFFF5555)));
    }
}
