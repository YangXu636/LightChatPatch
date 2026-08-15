package top.xuyangjerry.mcmod.lcp.mute;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.network.MutePayload;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 禁言系统（Mute Manager）
 * 服务端管理类：负责禁言状态存储、权限判断、消息拦截。
 *
 * 规则（按用户需求）：
 *   - 禁言范围：全部（所有消息类型：玩家聊天、mod转发消息、图片消息等）
 *   - 持久化：不需要（仅内存存储，服务器重启后清空）
 *   - 提示：被禁言玩家发送消息时，发送系统消息提示
 *   - 操作权限：任何更高OP等级的玩家都可以禁言/解禁更低OP等级的玩家
 *   - 可选原因：禁言时可附带原因
 *   - 时长：支持限时（ticks）和永久（-1），到期自动解禁
 *
 * 实现策略：
 *   - 使用 ConcurrentHashMap<玩家UUID, MuteInfo> 存储禁言状态
 *   - 在 onChatMessage 链路（ServerMessageEvents.ALLOW_CHAT_MESSAGE）中拦截
 *   - 在 C2S Payload 入口（onForwardMessage/onReplyMessage/onMentionMessage/onPlayerImageUpload）中拦截
 *   - 在 QQ→MC 方向不需要拦截（QQ侧自行处理禁言）
 */
public class MuteManager {

    /** 永久禁言的时长标记 */
    public static final int INFINITE = -1;

    /** 禁言信息条目 */
    public static class MuteInfo {
        public final UUID muterUuid;
        public final String muterName;
        public final String reason;
        public final long time;
        public final int durationTicks;
        public final long expireAtMs;

        public MuteInfo(UUID muterUuid, String muterName, String reason, int durationTicks) {
            this.muterUuid = muterUuid;
            this.muterName = muterName != null ? muterName : "未知";
            this.reason = reason != null && !reason.isBlank() ? reason : "";
            this.time = System.currentTimeMillis();
            this.durationTicks = durationTicks;
            this.expireAtMs = durationTicks < 0 ? 0 : System.currentTimeMillis() + (long) durationTicks * 50;
        }

        public boolean isInfinite() {
            return durationTicks < 0;
        }

        public boolean isExpired() {
            return expireAtMs > 0 && System.currentTimeMillis() >= expireAtMs;
        }

        public String durationText() {
            if (isInfinite()) return "永久";
            long seconds = durationTicks / 20;
            if (seconds < 60) return seconds + "秒";
            long minutes = seconds / 60;
            if (minutes < 60) return minutes + "分" + (seconds % 60 > 0 ? (seconds % 60) + "秒" : "");
            long hours = minutes / 60;
            if (hours < 24) return hours + "时" + (minutes % 60 > 0 ? (minutes % 60) + "分" : "");
            long days = hours / 24;
            return days + "天" + (hours % 24 > 0 ? (hours % 24) + "时" : "");
        }
    }

    /** 玩家UUID → 禁言信息。ConcurrentHashMap 保证并发安全。 */
    private static final ConcurrentHashMap<UUID, MuteInfo> mutedPlayers = new ConcurrentHashMap<>();

    private static MinecraftServer server;
    private static boolean initialized = false;

    private MuteManager() {}

    // ==================== 生命周期 ====================

    public static void onServerStarted(MinecraftServer mcServer) {
        server = mcServer;
        mutedPlayers.clear();
        initialized = true;
        LightChatPatch.LOGGER.info("[LCP][Mute] 禁言系统已启动（内存模式，非持久化）");
    }

    public static void onServerStopping(MinecraftServer mcServer) {
        if (initialized) {
            mutedPlayers.clear();
        }
        initialized = false;
        server = null;
    }

    // ==================== OP 等级工具 ====================

    public static int getOpLevel(ServerPlayer player) {
        if (player == null) return 0;
        if (player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER)) return 4;
        if (player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)) return 3;
        if (player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) return 2;
        if (player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)) return 1;
        return 0;
    }

    public static boolean canMute(ServerPlayer muter, ServerPlayer target) {
        if (muter == null || target == null) return false;
        int muterLevel = getOpLevel(muter);
        int targetLevel = getOpLevel(target);
        return muterLevel > targetLevel;
    }

    // ==================== 禁言/解禁 API ====================

    /**
     * 执行禁言。
     * @param durationTicks 禁言时长（ticks），使用 INFINITE (-1) 表示永久
     */
    public static String mute(ServerPlayer muter, ServerPlayer target, String reason, int durationTicks) {
        if (muter == null || target == null) return "参数错误：玩家不存在";
        if (muter.getUUID().equals(target.getUUID())) return "你不能禁言自己";
        if (!canMute(muter, target)) {
            return "禁言失败：你的OP等级（" + getOpLevel(muter) + "）不足以禁言 " + target.getName().getString() + "（OP等级 " + getOpLevel(target) + "）";
        }

        MuteInfo info = new MuteInfo(muter.getUUID(), muter.getName().getString(), reason, durationTicks);
        mutedPlayers.put(target.getUUID(), info);

        String reasonText = info.reason.isEmpty() ? "" : "，原因：" + info.reason;
        String durationText = info.durationText();
        Component tip = Component.literal("§c你已被玩家 " + info.muterName + " 禁言（" + durationText + "）" + reasonText + "，无法发送任何消息。");
        target.sendSystemMessage(tip);

        LightChatPatch.LOGGER.info("[LCP][Mute] {} (OP{}) 禁言了 {} (OP{})，原因：{}，时长：{}",
                muter.getName().getString(), getOpLevel(muter),
                target.getName().getString(), getOpLevel(target),
                info.reason.isEmpty() ? "(无)" : info.reason,
                durationText);

        broadcastSyncToOps();
        return "已禁言玩家 " + target.getName().getString() + reasonText;
    }

    /**
     * 执行解禁。
     */
    public static String unmute(ServerPlayer muter, ServerPlayer target) {
        if (muter == null || target == null) return "参数错误：玩家不存在";
        if (!canMute(muter, target)) {
            return "解禁失败：你的OP等级（" + getOpLevel(muter) + "）不足以解禁 " + target.getName().getString() + "（OP等级 " + getOpLevel(target) + "）";
        }
        MuteInfo removed = mutedPlayers.remove(target.getUUID());
        if (removed == null) {
            return "玩家 " + target.getName().getString() + " 不在禁言状态中";
        }
        Component tip = Component.literal("§a你已被玩家 " + muter.getName().getString() + " 解禁，可以正常发送消息了。");
        target.sendSystemMessage(tip);
        LightChatPatch.LOGGER.info("[LCP][Mute] {} (OP{}) 解禁了 {} (OP{})",
                muter.getName().getString(), getOpLevel(muter),
                target.getName().getString(), getOpLevel(target));
        broadcastSyncToOps();
        return "已解禁玩家 " + target.getName().getString();
    }

    /** 按 UUID 解禁离线玩家。 */
    public static String unmuteOffline(ServerPlayer muter, UUID targetUuid) {
        if (muter == null) return "参数错误";
        MuteInfo removed = mutedPlayers.remove(targetUuid);
        if (removed == null) {
            return "该玩家不在禁言状态中";
        }
        LightChatPatch.LOGGER.info("[LCP][Mute] {} (OP{}) 解禁了离线玩家 UUID={}",
                muter.getName().getString(), getOpLevel(muter), targetUuid);
        return "已解禁离线玩家（UUID: " + targetUuid.toString().substring(0, 8) + "...)";
    }

    /** 查询玩家是否被禁言（含过期检查）。 */
    public static boolean isMuted(ServerPlayer player) {
        if (player == null) return false;
        MuteInfo info = mutedPlayers.get(player.getUUID());
        if (info == null) return false;
        if (info.isExpired()) {
            mutedPlayers.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /** 获取玩家的禁言信息（未禁言返回 null，含过期检查）。 */
    public static MuteInfo getMuteInfo(ServerPlayer player) {
        if (player == null) return null;
        MuteInfo info = mutedPlayers.get(player.getUUID());
        if (info == null) return null;
        if (info.isExpired()) {
            mutedPlayers.remove(player.getUUID());
            return null;
        }
        return info;
    }

    /**
     * 检查玩家能否发送消息，若被禁言则向玩家发送提示并返回 true。
     */
    public static boolean checkMutedAndTip(ServerPlayer player) {
        if (player == null) return false;
        MuteInfo info = mutedPlayers.get(player.getUUID());
        if (info == null) return false;
        if (info.isExpired()) {
            mutedPlayers.remove(player.getUUID());
            return false;
        }
        String reasonText = info.reason.isEmpty() ? "" : "（原因：" + info.reason + "）";
        String durationText = info.isInfinite() ? "永久" : info.durationText();
        Component tip = Component.literal("§c你当前处于禁言状态（" + durationText + "）" + reasonText + "，无法发送消息。如需解禁，请联系更高OP等级的玩家。");
        player.sendSystemMessage(tip);
        LightChatPatch.LOGGER.info("[LCP][Mute] 拦截被禁言玩家 {} 的消息（禁言者：{}，原因：{}）",
                player.getName().getString(), info.muterName,
                info.reason.isEmpty() ? "(无)" : info.reason);
        return true;
    }

    // ==================== 查询 / Tab 补全支持 ====================

    /** 获取所有被禁言的在线玩家（用于 /lcp mute remove 的 Tab 补全）。 */
    public static Collection<ServerPlayer> getMutedPlayers() {
        if (server == null) return Collections.emptyList();
        List<ServerPlayer> result = new ArrayList<>();
        for (UUID uuid : mutedPlayers.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null && !mutedPlayers.get(uuid).isExpired()) {
                result.add(p);
            }
        }
        return result;
    }

    /** 获取所有被禁言玩家的描述（用于 /lcp mute list）。 */
    public static List<String> getMuteDescriptions() {
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (var entry : mutedPlayers.entrySet()) {
            MuteInfo info = entry.getValue();
            if (info.isExpired()) {
                mutedPlayers.remove(entry.getKey());
                continue;
            }
            count++;
            ServerPlayer p = server != null ? server.getPlayerList().getPlayer(entry.getKey()) : null;
            String targetName;
            if (p != null) {
                targetName = p.getName().getString();
            } else {
                targetName = entry.getKey().toString().substring(0, 8) + "..";
            }
            String reason = info.reason.isEmpty() ? "(无)" : info.reason;
            String duration = info.durationText();
            lines.add("  " + count + ". " + targetName
                    + "（禁言者: " + info.muterName + ", 时长: " + duration + ", 原因: " + reason + "）");
        }
        return lines;
    }

    // ==================== 同步给 OP 客户端 ====================

    private static boolean isOp(ServerPlayer player) {
        return player != null && player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
    }

    /** 向所有在线 OP 玩家发送当前禁言列表同步。 */
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

    /** 向单个玩家（OP）同步禁言列表。 */
    public static void syncToPlayerIfOp(ServerPlayer player) {
        if (isOp(player)) {
            syncToPlayer(player);
        }
    }

    private static void syncToPlayer(ServerPlayer player) {
        try {
            if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, MutePayload.TYPE)) {
                return;
            }
            java.util.List<MutePayload.MuteEntry> entries = new java.util.ArrayList<>();
            for (var entry : mutedPlayers.entrySet()) {
                UUID targetUuid = entry.getKey();
                MuteInfo info = entry.getValue();
                if (info.isExpired()) continue;
                String targetName;
                ServerPlayer tp = server.getPlayerList().getPlayer(targetUuid);
                if (tp != null) {
                    targetName = tp.getName().getString();
                } else {
                    targetName = targetUuid.toString().substring(0, 8) + "..";
                }
                entries.add(new MutePayload.MuteEntry(targetName, targetUuid.toString(), info.muterName, info.reason, info.time));
            }
            MutePayload payload = new MutePayload(MutePayload.ACTION_SYNC, entries, null, null, null);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        } catch (Throwable ignore) {
        }
    }

    // ==================== 来自客户端（C2S）的禁言/解禁请求 ====================

    public static String handleClientRequest(ServerPlayer source, MutePayload payload) {
        if (source == null || payload == null) return "参数错误";
        if (!isOp(source)) return "你没有权限执行此操作（需要 OP）";

        String action = payload.action();
        if (action == null) return "操作类型为空";

        switch (action) {
            case MutePayload.ACTION_MUTE -> {
                if (payload.targetName() == null || payload.targetName().isBlank()) return "目标玩家为空";
                ServerPlayer target = findPlayerByNameOrUuid(payload.targetName(), payload.targetUuid());
                if (target == null) return "目标玩家不存在或不在线：" + payload.targetName();
                return mute(source, target, payload.reason(), INFINITE);
            }
            case MutePayload.ACTION_UNMUTE -> {
                if (payload.targetName() == null || payload.targetName().isBlank()) return "目标玩家为空";
                ServerPlayer target = findPlayerByNameOrUuid(payload.targetName(), payload.targetUuid());
                if (target == null) {
                    if (payload.targetUuid() != null && !payload.targetUuid().isBlank()) {
                        try {
                            UUID uuid = UUID.fromString(payload.targetUuid());
                            MuteInfo removed = mutedPlayers.remove(uuid);
                            if (removed != null) {
                                LightChatPatch.LOGGER.info("[LCP][Mute] {} (OP{}) 解禁了离线玩家 UUID={}",
                                        source.getName().getString(), getOpLevel(source), payload.targetUuid());
                                broadcastSyncToOps();
                                return "已解禁离线玩家（UUID: " + payload.targetUuid().substring(0, 8) + "...)";
                            }
                        } catch (IllegalArgumentException ignore) {}
                    }
                    return "目标玩家不存在或不在禁言状态中";
                }
                return unmute(source, target);
            }
            case MutePayload.ACTION_LIST -> {
                return "查询完成";
            }
            default -> {
                return "未知操作: " + action;
            }
        }
    }

    /** 按名称或 UUID 查找在线玩家。 */
    private static ServerPlayer findPlayerByNameOrUuid(String name, String uuidStr) {
        if (server == null) return null;
        if (uuidStr != null && !uuidStr.isBlank()) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null) return p;
            } catch (IllegalArgumentException ignore) {}
        }
        if (name != null && !name.isBlank()) {
            return server.getPlayerList().getPlayerByName(name);
        }
        return null;
    }
}