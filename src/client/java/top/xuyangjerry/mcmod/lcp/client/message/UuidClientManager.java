package top.xuyangjerry.mcmod.lcp.client.message;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking;
import top.xuyangjerry.mcmod.lcp.network.UuidRequestPayload;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端 UUID 请求管理器
 *
 * 当客户端需要统一 UUID 时（如玩家聊天消息、系统消息等），
 * 通过此类向服务端发送 UuidRequestPayload，服务端计算 UUID 后
 * 通过 MessageUuidPayload 回传，客户端在 handleMessageUuid 中注册。
 *
 * 同时支持同步等待（通过 CompletableFuture），方便调用方在需要 UUID 时等待结果。
 */
public final class UuidClientManager {

    /** 待完成的 UUID 请求（key = contentText, value = Future） */
    private static final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    private UuidClientManager() {}

    /**
     * 请求服务端生成 UUID（同步等待模式）。
     * 调用方发送请求后，可通过返回的 Future 异步获取结果。
     *
     * @param sender      发送者名称
     * @param contentText 消息纯文本
     * @return CompletableFuture，完成时返回服务端生成的 UUID
     */
    public static CompletableFuture<String> requestUuid(String sender, String contentText) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (contentText == null || contentText.isEmpty()) {
            future.complete(null);
            return future;
        }

        // 避免重复请求：同一文本的请求合并
        synchronized (pendingRequests) {
            CompletableFuture<String> existing = pendingRequests.get(contentText);
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            pendingRequests.put(contentText, future);
        }

        if (!ClientNetworking.isServerModInstalled()) {
            future.complete(null);
            pendingRequests.remove(contentText);
            return future;
        }

        try {
            ClientPlayNetworking.send(new UuidRequestPayload(
                    sender != null ? sender : "", contentText));
        } catch (Throwable t) {
            future.complete(null);
            pendingRequests.remove(contentText);
        }

        return future;
    }

    /**
     * 由 handleMessageUuid 回调：当服务端返回 UUID 时，完成对应的 Future。
     */
    public static void completeRequest(String contentText, String uuid) {
        if (contentText == null || contentText.isEmpty()) return;
        CompletableFuture<String> future = pendingRequests.remove(contentText);
        if (future != null) {
            future.complete(uuid);
        }
    }

    /**
     * 由 handleMessageUuid 回调：服务端回传 UUID 但未等待请求时，
     * 直接在 MessageJumpManager 中注册（现有流程）。
     */
    public static void registerUuid(String uuid, String contentText) {
        MessageJumpManager.getInstance().registerByContent(uuid, contentText);
    }
}