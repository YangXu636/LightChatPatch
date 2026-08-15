package top.xuyangjerry.mcmod.lcp.nonebot;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ImagePayload;
import top.xuyangjerry.mcmod.lcp.network.MentionMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.MessageUuidPayload;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.RichMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.SyncModdedPlayersPayload;

import top.xuyangjerry.mcmod.lcp.bws.BannedWordsManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoneBotServerManager {
    private static NoneBotWebSocketServer webSocketServer;
    /**
     * 问题4②：升级为 Map<玩家UUID, Mod版本号>，不再只是 Set<UUID>。
     * 这样服务端既能判断"谁装了mod"，也能把 mod 版本号一并同步给客户端，
     * 便于转发选人界面中显示（或在未来做版本兼容）。
     */
    private static final java.util.Map<UUID, String> moddedPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    private static MinecraftServer server;
    /**
     * 防回环标记：NoneBot 远程命令执行期间设为 true，
     * onGameMessage 检查此标记跳过转发，避免命令产生的系统消息回环。
     */
    private static volatile boolean commandExecuting = false;
    /**
     * 防回环标记：QQ→MC 消息处理期间设为 true，
     * onGameMessage 检查此标记跳过转发，避免 QQ 消息显示后触发系统消息回环。
     * （MC 26.2 中 player.sendSystemMessage 可能触发 GAME_MESSAGE 事件）
     */
    private static volatile boolean messageProcessing = false;
    /**
     * 消息序列号（sessionID）：每次为消息生成 UUID 时递增，溢出回 0。
     * 作为 UUID 生成输入的一部分，保证同一毫秒内多条消息 UUID 不同。
     * 使用 32 位无符号整数语义（long 存储，掩码 0xFFFFFFFFL）。
     */
    private static long messageSequence = 0L;

    /**
     * 服务端版构造 <玩家名> 标签。
     * 当 senderDisplayName 对应在线 MC 玩家时，自动附加 SHOW_ENTITY 悬浮事件（EntityType=PLAYER），
     * 使客户端能在右键菜单中识别该玩家并提供禁言/解禁选项。
     * QQ 昵称等非 MC 玩家名则回退为纯文本 LiteralContents。
     */
    private static Component buildSenderTag(MinecraftServer mcServer, String senderDisplayName) {
        if (senderDisplayName == null || senderDisplayName.isEmpty()) {
            return Component.literal("<>");
        }
        // 尝试从 MC 玩家列表中查找
        if (mcServer != null) {
            ServerPlayer player = mcServer.getPlayerList().getPlayerByName(senderDisplayName);
            if (player != null) {
                UUID playerUuid = player.getUUID();
                HoverEvent.EntityTooltipInfo tooltipInfo = new HoverEvent.EntityTooltipInfo(
                        EntityTypes.PLAYER, playerUuid, Optional.of(player.getDisplayName()));
                HoverEvent showEntity = new HoverEvent.ShowEntity(tooltipInfo);
                return Component.literal("<" + senderDisplayName + ">")
                        .withStyle(Style.EMPTY.withHoverEvent(showEntity));
            }
        }
        return Component.literal("<" + senderDisplayName + ">");
    }

    /**
     * 统一 UUID 生成规则：SHA-1(发送者 + contentJson + sessionID + 时间戳) 取前8字节转16进制。
     * - 发送者：玩家名或 QQ 昵称等
     * - contentJson：消息显示文本（getString）
     * - sessionID：消息序列号（每次生成UUID时递增，溢出回0）
     * - 时间戳：System.currentTimeMillis()
     * 返回 16 字符的十六进制字符串（如 "a1b2c3d4e5f67890"）。
     */
    private static synchronized String generateMessageUuid(String sender, String content) {
        return generateMessageUuidInternal(sender, content);
    }

    /** 公共入口：供外部（如 UUID 请求 Payload 处理）调用生成消息 UUID。 */
    public static String generateMessageUuidPublic(String sender, String content) {
        return generateMessageUuidInternal(sender, content);
    }

    private static synchronized String generateMessageUuidInternal(String sender, String content) {
        long seq = messageSequence;
        messageSequence = (messageSequence + 1) & 0xFFFFFFFFL; // 32位无符号，溢出回0
        long timestamp = System.currentTimeMillis();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            String input = (sender != null ? sender : "") + "|"
                    + (content != null ? content : "") + "|"
                    + seq + "|" + timestamp;
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            LightChatPatch.LOGGER.warn("[LCP] SHA-1 unavailable, using hashCode fallback", e);
            return Integer.toHexString((sender + content + seq + timestamp).hashCode());
        }
    }

    /**
     * QQ→MC 方向：MC 端处理完 QQ 消息后，回传生成的 mc_uuid 给 Python 端。
     * 映射由且仅由 Python 端维护，Java 端不再本地查表。
     * sourceMessageId 为通用平台消息ID（不局限于QQ），由 Python 端传入。
     */
    private static void sendMcUuidAck(String sourceMessageId, String mcUuid) {
        if (webSocketServer == null || !webSocketServer.hasConnectedClients()) return;
        if (sourceMessageId == null || sourceMessageId.isEmpty()) return;
        if (mcUuid == null || mcUuid.isEmpty()) return;
        webSocketServer.broadcast(NoneBotMessage.mcUuidAck(sourceMessageId, mcUuid));
        LightChatPatch.LOGGER.info("[LCP][映射] QQ→MC 回传UUID: mc_uuid={}, source_message_id={}", mcUuid, sourceMessageId);
    }

    public static void onServerStarted(MinecraftServer mcServer) {
        server = mcServer;
        NoneBotConfig config = NoneBotConfig.getInstance();
        if (config.isEnabled()) {
            // 重启保护：如果已经存在 WS Server（异常重入），先停止旧实例再启动新实例
            if (webSocketServer != null) {
                try {
                    webSocketServer.stop();
                } catch (Throwable ignore) {
                }
                webSocketServer = null;
            }
            webSocketServer = new NoneBotWebSocketServer(config.getPort(), config.getToken());
            webSocketServer.setMessageHandler(new NoneBotMessageHandler(mcServer));
            webSocketServer.start();
        }
    }

    public static void onServerStopping(MinecraftServer mcServer) {
        if (webSocketServer != null) {
            webSocketServer.stop();
            webSocketServer = null;
        }
        moddedPlayers.clear();
    }

    public static void onChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound boundChatType) {
        String playerName = sender.getName().getString();

        // 问题2：使用 boundChatType.decorate(message.decoratedContent()) 得到玩家聊天框中看到的完整显示组件，
        // 再 getString() 得到已翻译/已解析的纯文本。这等同于客户端 GuiMessage.content().getString()。
        // MC 26.2 中 ChatType.Bound.decorate 接受 Component 参数（非 PlayerChatMessage）。
        Component fullDisplayComponent;
        try {
            fullDisplayComponent = boundChatType.decorate(message.decoratedContent());
        } catch (Throwable ignore) {
            fullDisplayComponent = Component.literal("<" + playerName + "> " + message.decoratedContent().getString());
        }
        String fullDisplayText = fullDisplayComponent.getString();

        // BWS = Banned Words：玩家聊天消息文本进行违禁词过滤（全词匹配+大小写不敏感+替换为***）。
        // 这里过滤后用于 UUID 生成、mod 客户端 UUID 同步、以及 QQ 群转发。
        // 注意：服务端原版聊天消息的显示过滤（面向未装 mod 玩家）由 LightChatPatch.registerServerEvents
        // 中注册的 ALLOW_CHAT_MESSAGE 拦截器负责，以确保所有玩家都能看到过滤后的内容。
        String filteredText = BannedWordsManager.filter(fullDisplayText);

        // 问题6：为原版玩家消息生成统一UUID（基于发送端时间戳+内容哈希），
        // 通过 MessageUuidPayload 发送给所有装mod客户端，确保回复跳转跨客户端一致。
        // 未装mod客户端不接收此Payload（不影响其正常聊天）。
        // 使用过滤后的文本生成UUID，避免UUID与显示内容不一致。
        String mcUuid = generateMessageUuid(playerName, filteredText);
        LightChatPatch.LOGGER.info("[LCP][MC→QQ] 普通聊天: player={}, mc_uuid={}, 原文本len={}, 过滤后len={}, 过滤后预览={}",
                playerName, mcUuid,
                fullDisplayText.length(), filteredText.length(),
                filteredText.length() > 80 ? filteredText.substring(0, 80) + "..." : filteredText);

        String finalText = filteredText;
        if (server != null && finalText != null && !finalText.isEmpty()) {
            server.execute(() -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (isClientModded(p)) {
                        ServerPlayNetworking.send(p, new MessageUuidPayload(mcUuid, finalText));
                    }
                }
            });
        }

        if (webSocketServer == null || !webSocketServer.hasConnectedClients()) return;

        if (filteredText.startsWith("/")) {
            webSocketServer.broadcast(NoneBotMessage.event("player_command", playerName, filteredText));
        } else {
            // 发送过滤后的显示文本，Python 端会 strip_player_prefix 后重新拼接 <Player> 前缀
            webSocketServer.broadcast(NoneBotMessage.chat(playerName, filteredText, mcUuid));
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (webSocketServer != null && webSocketServer.hasConnectedClients()) {
            webSocketServer.broadcast(NoneBotMessage.event("player_join", player.getName().getString(), null));
        }
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        moddedPlayers.remove(player.getUUID());
        // 问题4②：有玩家离开 → 同步列表给所有剩余 mod 客户端
        if (server != null) {
            server.execute(NoneBotServerManager::broadcastModdedPlayersSync);
        }
        if (webSocketServer != null && webSocketServer.hasConnectedClients()) {
            webSocketServer.broadcast(NoneBotMessage.event("player_leave", player.getName().getString(), null));
        }
    }

    public static void onClientHello(ServerPlayer player, String version) {
        String normalizedVersion = (version == null || version.isEmpty()) ? "unknown" : version;
        moddedPlayers.put(player.getUUID(), normalizedVersion);
        LightChatPatch.LOGGER.info("[LCP.NoneBot] Client mod detected: {} (version {})", player.getName().getString(), normalizedVersion);
        // 发送 S2C 确认 Payload：客户端收到后会标记 serverModInstalled=true，
        // 避免超时后误判服务端未装 mod 导致转发/回复等按钮不可用
        ServerPlayNetworking.send(player, new RichMessagePayload("handshake", "", ""));
        // 问题4②：有新 mod 客户端握手 → 立即同步最新"有mod玩家列表"给所有 mod 客户端（含刚连上的这个）
        if (server != null) {
            server.execute(NoneBotServerManager::broadcastModdedPlayersSync);
        }
    }

    /**
     * 问题4②：向所有已连接的 mod 客户端广播"当前在线且有mod的玩家列表"。
     * 需要在 mcServer.execute() 主线程中调用。
     */
    private static void broadcastModdedPlayersSync() {
        if (server == null) return;
        java.util.List<SyncModdedPlayersPayload.ModdedPlayerEntry> entries = new java.util.ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String version = moddedPlayers.get(player.getUUID());
            if (version != null) {
                entries.add(new SyncModdedPlayersPayload.ModdedPlayerEntry(
                        player.getName().getString(),
                        player.getUUID(),
                        version
                ));
            }
        }
        SyncModdedPlayersPayload payload = new SyncModdedPlayersPayload(entries);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (moddedPlayers.containsKey(player.getUUID())) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /**
     * 玩家上传图片处理：转发给 NoneBot（WebSocket）和其他客户端。
     * - 给 NoneBot：发送 player_image 协议消息（含 base64）
     * - 给带 mod 的客户端：发送 ImagePayload（含原始 PNG 字节）
     * - 给未带 mod 的客户端：发送 <Player> [图片] 文本提示
     * - 给发送者自己：发送 ImagePayload，让其在本地聊天框看到图片
     */
    public static void onPlayerImageUpload(ServerPlayer player, byte[] imageData, int width, int height) {
        if (imageData == null || imageData.length == 0) return;

        String playerName = player.getName().getString();
        LightChatPatch.LOGGER.info("[LCP.NoneBot] Player {} uploaded image ({}x{}, {} bytes)",
                playerName, width, height, imageData.length);

        // 转发给 NoneBot 和其他玩家：统一放到 server.execute，避免阻塞网络线程
        if (server != null) {
            server.execute(() -> {
                // 生成MC图片消息UUID（用于回复跳转）
                String imageUuid = generateMessageUuid(playerName, "[Image]" + width + "x" + height);
                LightChatPatch.LOGGER.info("[LCP][MC→QQ] 玩家图片: player={}, mc_uuid={}, size={}x{}, bytes={}",
                        playerName, imageUuid, width, height, imageData.length);

                // 1. 转发给 NoneBot（通过 WebSocket）—— 在服务端主线程做 base64 编码
                if (webSocketServer != null && webSocketServer.hasConnectedClients()) {
                    String base64 = Base64.getEncoder().encodeToString(imageData);
                    NoneBotMessage imgMsg = NoneBotMessage.playerImage(playerName, base64, width, height);
                    // 携带 MC UUID，供 Python 端建立 MC↔QQ 映射
                    imgMsg.setMcMessageUuid(imageUuid);
                    webSocketServer.broadcast(imgMsg);
                }

                // 2. 转发给所有在线玩家（包括发送者自己，以便在聊天框看到图片）
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (isClientModded(p)) {
                        ServerPlayNetworking.send(p, new ImagePayload(imageData, width, height, playerName, imageUuid));
                    } else {
                        // 未装mod客户端：用实体选择器（若玩家在线）+ 纯文本回退
                        Component imageTip = buildSenderTag(server, playerName)
                                .copy()
                                .append(Component.literal(" [图片] (" + width + "x" + height + ")"));
                        p.sendSystemMessage(imageTip);
                    }
                }
            });
        }
    }

    /**
     * 处理玩家转发消息请求。
     * - 目标玩家安装了 mod：发送 ForwardMessagePayload，客户端渲染合并转发卡片
     * - 目标玩家未安装 mod：逐条发送 /msg 风格的文本消息
     * - 自己转发给自己时只发送一份
     */
    /** 处理玩家转发消息请求（问题6：新增全体成员转发 + MC↔QQ 同步） */
    public static void onForwardMessage(ServerPlayer sender, ForwardMessagePayload payload) {
        if (payload.messages() == null || payload.messages().isEmpty()) return;

        String senderName = sender.getName().getString();
        String targetName = payload.target();
        int msgCount = payload.messages().size();
        LightChatPatch.LOGGER.info("[LCP] {} forwarded {} messages to target={}",
                senderName, msgCount, targetName);

        if (server == null) return;

        // 问题6：目标为"全体成员"时：
        //  1. MC 端：给所有在线玩家（含未装mod）都发送一份
        //  2. QQ 端：同步转发消息格式到 QQ 群（如果 WS 已连接）
        boolean forwardToAll = ForwardMessagePayload.TARGET_ALL_MEMBERS.equals(targetName);

        // 生成MC转发消息UUID（用于回复跳转），在lambda外部定义以便后续QQ转发也能使用
        String forwardUuid = generateMessageUuid(senderName, "[Forward]" + msgCount + "msgs");
        LightChatPatch.LOGGER.info("[LCP][MC→QQ] 全体转发: sender={}, mc_uuid={}, target={}, msgCount={}",
                senderName, forwardUuid, targetName, msgCount);

        server.execute(() -> {
            List<ServerPlayer> allTargets = new java.util.ArrayList<>();

            if (forwardToAll) {
                // 全体成员：收集所有在线玩家
                allTargets.addAll(server.getPlayerList().getPlayers());
            } else {
                // 单个目标
                ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
                if (target != null) allTargets.add(target);
            }

            for (ServerPlayer target : allTargets) {
                boolean targetModded = isClientModded(target);
                boolean isSelf = sender.getUUID().equals(target.getUUID());

                if (targetModded) {
                    ServerPlayNetworking.send(target,
                            new ForwardMessagePayload(senderName, target.getName().getString(),
                                    payload.messages(), forwardUuid));
                } else if (!isSelf) {
                    sendForwardAsText(target, senderName, payload);
                }
                // 否则：未装mod + 自己 = 不重复发（自己在游戏聊天里已经看到原文了）
            }

            // 问题6：转发者自己也收到一份确认回显（仅非"全体成员"情况；全体情况下：上面循环已经把自己包含在allTargets）
            if (!forwardToAll && isClientModded(sender)) {
                boolean isSelfTarget = targetName.equals(sender.getName().getString());
                if (!isSelfTarget) {
                    ServerPlayNetworking.send(sender,
                            new ForwardMessagePayload(senderName, targetName, payload.messages(), forwardUuid));
                }
            }
        });

        // 问题6：转发至 QQ 群（"全体成员" 或者 配置项要求同步）
        // 此处"全体成员"始终同步；单玩家转发按配置可扩展（目前仅同步全体→QQ群）
        if (forwardToAll && webSocketServer != null && webSocketServer.hasConnectedClients()) {
            try {
                java.util.List<java.util.Map<String, Object>> msgs = new java.util.ArrayList<>();
                for (ForwardMessagePayload.ForwardedMessage fm : payload.messages()) {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("sender", fm.sender());
                    m.put("content", fm.content());
                    m.put("time", fm.addedTime());
                    msgs.add(m);
                }
                NoneBotMessage forwardMsg = NoneBotMessage.event(
                        "forward_chat_all", senderName,
                        new com.google.gson.Gson().toJson(msgs));
                // 携带 MC UUID，供 Python 端建立 MC↔QQ 映射
                forwardMsg.setMcMessageUuid(forwardUuid);
                webSocketServer.broadcast(forwardMsg);
            } catch (Throwable t) {
                LightChatPatch.LOGGER.warn("[LCP.NoneBot] Failed to forward all-members msg to QQ", t);
            }
        }
    }

    private static void sendForwardAsText(ServerPlayer target, String forwarder, ForwardMessagePayload payload) {
        // 灰色分割线（Minecraft 颜色代码 §7 = 灰色），首尾两条"-------"
        Component divider = Component.literal("§7-------").withStyle(s -> s.withColor(0xFFAAAAAA));
        target.sendSystemMessage(divider);
        target.sendSystemMessage(Component.literal("[" + forwarder + " 转发的聊天记录]"));
        int count = 0;
        for (ForwardMessagePayload.ForwardedMessage msg : payload.messages()) {
            target.sendSystemMessage(Component.literal(msg.sender() + " " + msg.content()));
            count++;
            if (count >= 20) {
                target.sendSystemMessage(Component.literal("... (共 " + payload.messages().size() + " 条)"));
                break;
            }
        }
        target.sendSystemMessage(divider);
    }

    /**
     * 处理玩家回复消息请求。
     * - 未装mod玩家：发送纯文本系统消息（» <Player> 回复内容 格式）
     * - 装mod玩家：发送 ReplyMessagePayload，客户端渲染内联格式
     * - NoneBot：格式化文本转发到QQ群（含回复映射）
     * - 回复者自己也收到 ReplyMessagePayload（用于回显）
     *
     * 问题10：MC→QQ 回复格式
     *   1. 建立 回复消息本身 的 MC UUID → QQ message_id 映射（让未来QQ回复这条时能反查到MC）
     *   2. 若被回复的原始消息有映射（MC→QQ或QQ→MC），则把 target_qq_message_id 带上
     *      让 Python 端能发送 QQ 原生 MessageSegment.reply 引用原始QQ消息
     *   3. 完整传递 originalContent（被回复消息的完整文本）给 Python 端，
     *      即使找不到 QQ message_id，也能在 QQ 群里降级显示"回复 xxx：内容 \n 新内容"
     */
    public static void onReplyMessage(ServerPlayer sender, ReplyMessagePayload payload) {
        String senderName = sender.getName().getString();
        LightChatPatch.LOGGER.info("[LCP] {} replied to {} (originalUuid={})",
                senderName, payload.originalSender(), payload.originalMessageUuid());

        if (server == null) return;

        // ===== MC→QQ：建立回复消息的UUID映射并转发 =====
        // 生成 这条回复消息本身 的 MC UUID（用于 未来 QQ 用户回复这条时 建立反向映射）
        String replyMcUuid = generateMessageUuid(senderName, "[Reply]" + (payload.replyContent() != null ? payload.replyContent() : ""));
        LightChatPatch.LOGGER.info("[LCP][MC→QQ] 玩家回复: sender={}, mc_uuid={}, original_mc_uuid={}, original_sender={}",
                senderName, replyMcUuid, payload.originalMessageUuid(), payload.originalSender());

        if (webSocketServer != null && webSocketServer.hasConnectedClients()) {
            String originalUuid = payload.originalMessageUuid();
            // 映射由 Python 端统一维护：Java 端不再本地查表，
            // 直接把 original_mc_uuid 传给 Python，由 Python 反查 MC_TO_QQ_MAP 获取 target_qq_message_id
            NoneBotMessage replyMsg = NoneBotMessage.replyChat(
                    senderName,
                    payload.replyContent(),
                    replyMcUuid,
                    null, // target_qq_message_id 由 Python 端反查映射后填充
                    payload.originalSender(),
                    payload.originalContent() != null ? payload.originalContent() : "",
                    originalUuid);
            webSocketServer.broadcast(replyMsg);
        }

        // 构造携带 replyMessageUuid 的新 Payload 发给装mod客户端
        ReplyMessagePayload serverPayload = new ReplyMessagePayload(
                payload.sender(), payload.originalSender(), payload.originalContent(),
                payload.replyContent(), payload.originalMessageUuid(), replyMcUuid);

        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean isModded = isClientModded(player);
                boolean isSelf = player.getUUID().equals(sender.getUUID());

                if (isModded) {
                    ServerPlayNetworking.send(player, serverPayload);
                } else if (!isSelf) {
                    String truncatedOriginal = truncateReplyContent(payload.originalContent());
                    String text = "» <" + senderName + "> " + payload.replyContent();
                    player.sendSystemMessage(Component.literal(text));
                }
            }
        });
    }

    /**
     * 处理玩家@消息请求。
     * - 装mod玩家：发送 MentionMessagePayload，客户端渲染高亮@ + 声音提示
     * - 未装mod玩家：发送纯文本系统消息
     * - NoneBot：转发原始消息到QQ群
     *
     * @全体 特殊处理：mentionedPlayers 中包含 "全体" 标记时，
     * 所有装mod玩家（除发送者）都会收到声音提示。
     */
    public static void onMentionMessage(ServerPlayer sender, MentionMessagePayload payload) {
        String senderName = sender.getName().getString();
        String content = payload.content();
        List<String> mentions = payload.mentionedPlayers();

        // @全体 权限检查：需要最低 OP 权限
        boolean hasAll = mentions != null && mentions.contains("全体");
        if (hasAll && !isOp(sender)) {
            sender.sendSystemMessage(Component.literal("§c你没有权限使用 @全体 功能（需要 OP 权限）"));
            LightChatPatch.LOGGER.info("[LCP] {} attempted @all without permission", senderName);
            return;
        }

        LightChatPatch.LOGGER.info("[LCP] {} sent mention message (mentions: {})",
                senderName, mentions);

        if (server == null) return;

        // 生成 MC 端消息 UUID（用于 MC↔QQ 回复映射和回复跳转）
        String mentionUuid = "mc_mention_" + senderName.hashCode() + "_" + System.currentTimeMillis()
                + "_" + (System.nanoTime() & 0xFFFF);

        // 转发给 NoneBot（通过 WebSocket）—— 只发送纯内容，<Player> 前缀由 Python 端拼接
        if (webSocketServer != null && webSocketServer.hasConnectedClients()) {
            webSocketServer.broadcast(NoneBotMessage.chat(senderName, content, mentionUuid));
        }

        // 构造携带 uuid 的新 Payload 发给装mod客户端
        MentionMessagePayload serverPayload = new MentionMessagePayload(
                payload.sender(), payload.content(), payload.mentionedPlayers(), mentionUuid);

        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isClientModded(player)) {
                    // 装mod玩家：发 Payload（携带uuid），客户端渲染高亮@ + 声音提示
                    ServerPlayNetworking.send(player, serverPayload);
                } else {
                    // 未装mod玩家：发纯文本系统消息
                    String text = "<" + senderName + "> " + content;
                    player.sendSystemMessage(Component.literal(text));
                }
            }
        });
    }

    public static boolean isClientModded(ServerPlayer player) {
        return moddedPlayers.containsKey(player.getUUID());
    }

    /**
     * 检查玩家是否有 OP 权限。
     * MC 26.2 移除了 ServerPlayer.getPermissionLevel() 和 CommandSourceStack.hasPermission(int)，
     * 改用新的 PermissionSet + Permission 系统。
     * 这里使用 Permissions.COMMANDS_MODERATOR 判断权限等级 >= 1（最低 OP 权限）。
     * 单人游戏开启作弊时，权限等级为 2（或更高），同样满足条件。
     */
    private static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
    }

    /**
     * 截断回复内容：当被回复内容超过阈值时，只保留头尾部分，中间用省略号代替。
     * 仅用于未装mod玩家的纯文本显示，装mod玩家通过 Payload 获取完整内容。
     */
    private static String truncateReplyContent(String content) {
        if (content == null || content.isEmpty()) return "";
        LcpConfig config = LcpConfig.getInstance();
        int threshold = config.getReplyTruncateThreshold();
        if (content.length() <= threshold) return content;
        int head = config.getReplyTruncateHead();
        int tail = config.getReplyTruncateTail();
        if (content.length() <= head + tail) return content;
        return content.substring(0, head) + "..." + content.substring(content.length() - tail);
    }

    public static void onGameMessage(MinecraftServer mcServer, Component message, boolean overlay) {
        if (webSocketServer == null || !webSocketServer.hasConnectedClients()) return;
        // 防回环：跳过 NoneBot 远程命令执行期间产生的系统消息
        if (commandExecuting) return;
        // 防回环：跳过 QQ→MC 消息处理期间产生的系统消息
        if (messageProcessing) return;

        String msgString = message.getString();
        String playerName = extractPlayerNameFromContent(msgString);

        // 系统消息 UUID 生成：使用 "System" 作为发送者标识
        String systemUuid = generateMessageUuid("System", msgString);
        LightChatPatch.LOGGER.info("[LCP][SysMsg] 系统消息UUID: uuid={}, contentLen={}, preview={}",
                systemUuid, msgString.length(),
                msgString.length() > 80 ? msgString.substring(0, 80) + "..." : msgString);

        // 同步系统消息 UUID 给所有 mod 客户端（通过 MessageUuidPayload 按内容匹配注册）
        if (mcServer != null && systemUuid != null) {
            mcServer.execute(() -> {
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (isClientModded(p)) {
                        ServerPlayNetworking.send(p, new MessageUuidPayload(systemUuid, msgString));
                    }
                }
            });
        }

        String rawJson = message.toString();
        if (rawJson.contains("\"chat.type.advancement.")) {
            String advancementType = extractAdvancementType(rawJson);
            // 问题8：去掉 [系统] 前缀，避免Python端再次拼接导致双重[系统][系统]
            // 仅传原始内容，Python端 system_message 分支会自己加 [系统] 前缀
            webSocketServer.broadcast(NoneBotMessage.event("player_advancement", playerName, (advancementType + "|" + msgString)));
            return;
        }

        if (rawJson.contains("\"death.")) {
            // 问题8：去掉 [系统] 前缀
            webSocketServer.broadcast(NoneBotMessage.event("player_death", playerName, msgString));
            return;
        }

        // 问题8：去掉 [系统] 前缀，避免双重前缀
        webSocketServer.broadcast(NoneBotMessage.event("system_message", playerName != null ? playerName : "server", msgString));
    }

    private static String extractPlayerNameFromContent(String content) {
        int start = content.indexOf("<");
        int end = content.indexOf(">");
        if (start >= 0 && end > start) {
            return content.substring(start + 1, end);
        }
        return null;
    }

    private static String extractAdvancementType(String rawJson) {
        int start = rawJson.indexOf("chat.type.advancement.");
        if (start >= 0) {
            int end = rawJson.indexOf("\"", start);
            if (end > start) {
                return rawJson.substring(start + "chat.type.advancement.".length(), end);
            }
        }
        return "unknown";
    }

    private static class NoneBotMessageHandler implements NoneBotWebSocketServer.MessageHandler {
        private final MinecraftServer mcServer;

        NoneBotMessageHandler(MinecraftServer server) {
            this.mcServer = server;
        }

        @Override
        public void onMessage(NoneBotMessage message) {
            switch (message.getType()) {
                case "send_message" -> handleSendMessage(message);
                case "send_message_with_media" -> handleSendMessageWithMedia(message);
                case "send_image" -> handleSendImage(message);
                case "command" -> handleCommand(message);
                case "event" -> handleEventMessage(message);
                default -> LightChatPatch.LOGGER.warn("[LCP.NoneBot] Unknown message type: {}", message.getType());
            }
        }

        /**
         * 处理 NoneBot (Python) 端发来的 event 类型消息，目前支持：
         *   forward_chat_qq_to_mc：问题6 —— QQ 群内合并转发消息 → 同步至 MC，按每条/嵌套逐级生成转发卡片
         */
        private void handleEventMessage(NoneBotMessage message) {
            String event = message.getEvent();
            if (event == null || event.isEmpty()) return;
            String detail = message.getDetail();
            String sender = message.getPlayer() != null ? message.getPlayer() : "QQ";

            if ("forward_chat_qq_to_mc".equals(event)) {
                handleForwardQqToMc(sender, detail);
            } else {
                LightChatPatch.LOGGER.info("[LCP.NoneBot] Received unhandled event: {}", event);
            }
        }

        /**
         * 问题6：QQ→MC 合并转发消息。
         * detail 是 JSON 字符串，格式：
         *   {"forwarder": "昵称", "messages": [{sender, content, time}, ...], "index": 1, "total": 1, "source_qq_message_id": "xx"}
         * 对每个 QQ forward 段（含嵌套已在 Python 端展开）都生成一个 ForwardMessagePayload，
         * 发送给所有安装 mod 的玩家（玩家端用 ForwardCardRenderer 显示漂亮的合并转发卡片）；
         * 未装 mod 的玩家降级为纯文本。
         */
        private void handleForwardQqToMc(String forwarder, String detail) {
            if (detail == null || detail.isEmpty()) return;
            LightChatPatch.LOGGER.info("[LCP][QQ→MC] 收到合并转发: forwarder={}, detailLen={}", forwarder, detail.length());
            List<ForwardMessagePayload.ForwardedMessage> messages = new java.util.ArrayList<>();
            String sourceQqId = null;
            try {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(detail).getAsJsonObject();
                String resolvedForwarder = root.has("forwarder") && root.get("forwarder").isJsonPrimitive()
                        ? root.get("forwarder").getAsString() : forwarder;
                sourceQqId = root.has("source_message_id") && root.get("source_message_id").isJsonPrimitive()
                        ? root.get("source_message_id").getAsString() : null;
                if (root.has("messages") && root.get("messages").isJsonArray()) {
                    for (com.google.gson.JsonElement el : root.get("messages").getAsJsonArray()) {
                        if (!el.isJsonObject()) continue;
                        com.google.gson.JsonObject obj = el.getAsJsonObject();
                        // 补足缺失的 sender 字段
                        String s = obj.has("sender") && obj.get("sender").isJsonPrimitive()
                                ? obj.get("sender").getAsString() : "未知";
                        if (s.isEmpty()) s = "未知";
                        String c = obj.has("content") ? obj.get("content").getAsString() : "";
                        int t = obj.has("time") && obj.get("time").isJsonPrimitive()
                                ? obj.get("time").getAsInt() : 0;
                        messages.add(new ForwardMessagePayload.ForwardedMessage(s, c, t));
                    }
                }
                if (!resolvedForwarder.equals(forwarder)) {
                    forwarder = resolvedForwarder;
                }
            } catch (Exception e) {
                LightChatPatch.LOGGER.warn("[LCP.NoneBot] Failed to parse forward_chat_qq_to_mc detail: {}", detail, e);
                return;
            }
            if (messages.isEmpty()) return;

            // QQ来源的转发者包装为 [nickname] 格式，让客户端识别为QQ消息并应用配置色
            if (!forwarder.startsWith("[")) {
                forwarder = "[" + forwarder + "]";
            }
            final String fwd = forwarder;
            final List<ForwardMessagePayload.ForwardedMessage> finalMsgs = messages;
            final String finalSourceQqId = sourceQqId;
            mcServer.execute(() -> {
                messageProcessing = true;
                try {
                    // mcUuid 必须与传给 ForwardMessagePayload 的 uuid 一致，
                    // 这样 MC 客户端回复此转发消息时拿到的 originalMessageUuid 才能反查到 qq_message_id
                    String mcUuid = generateMessageUuid(fwd, "[Forward]" + finalMsgs.size() + "msgs");
                    // 回传 mc_uuid 给 Python，由 Python 建立映射
                    sendMcUuidAck(finalSourceQqId, mcUuid);
                    for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                        if (isClientModded(player)) {
                            ServerPlayNetworking.send(player,
                                    new ForwardMessagePayload(fwd, player.getName().getString(), finalMsgs, mcUuid));
                        } else {
                            // 未装mod：纯文本"------- [昵称 转发的聊天记录]"
                            sendForwardAsText(player, fwd,
                                    new ForwardMessagePayload(fwd, player.getName().getString(), finalMsgs, mcUuid));
                        }
                    }
                    LightChatPatch.LOGGER.info("[LCP] QQ->MC 合并转发：发送者={}, 条数={}", fwd, finalMsgs.size());
                } finally {
                    messageProcessing = false;
                }
            });
        }

        private void handleSendMessage(NoneBotMessage message) {
            String msg = message.getMessage();
            if (msg == null || msg.isEmpty()) return;

            String qqSender = message.getSender();
            String replyToSender = message.getReplyToSender();
            String replyToContent = message.getReplyToContent();
            String replyToQqMessageId = message.getReplyToQqMessageId();
            String sourceQqMessageId = message.getSourceMessageId();
            // 问题7：优先使用 Python 端直接传过来的 MC UUID（当被回复消息原始是 MC→QQ 时命中）
            String replyToMcMessageUuid = message.getReplyToMcMessageUuid();

            // 问题9：添加日志，记录收到的回复字段，辅助诊断QQ→MC回复格式问题
            LightChatPatch.LOGGER.info("[LCP.NoneBot] handleSendMessage: sender={}, msgLen={}, replyToSender={}, replyToContentLen={}, replyToQqId={}, sourceQqId={}",
                    qqSender, msg.length(), replyToSender,
                    replyToContent != null ? replyToContent.length() : 0,
                    replyToQqMessageId, sourceQqMessageId);

            // 问题1：QQ->MC消息前缀改为 [QQname] 而非 <QQname>
            // 把 QQ 昵称用方括号包裹后传给客户端 sender 字段；客户端 buildSenderTag 会用 <> 或 [] 原样显示吗？
            // 实际客户端逻辑：sender 会被 buildSenderTag("<name>") 再次包装。所以这里把 senderDisplay 改为 "[QQname]" 字符串，
            // 客户端 buildSenderTag 会得到 "<[QQname]>" 吗？不行。需要区分 QQ 和 MC。
            // 最直接方案：在这里直接不包，改 senderDisplay 为 "[QQname]"（不含 <>），客户端 buildSenderTag 包装为 "<[QQname]>"？也不对。
            // 换方案：在服务器端不使用 buildSenderTag（buildSenderTag本来就只返回 "<X>"），
            // 而是把 sender 直接传 "[QQname]" 文本，客户端改为 sender 本身有 "[" 时不额外包 <>。
            // 更简单：这里直接把 senderDisplay 拼接为 "[xxx]"，然后在未装mod用户处不包 <>，客户端侧修改 buildSenderTag 使其对含 [] 的输入直接输出输入。
            String senderDisplay = (qqSender != null && !qqSender.isEmpty()) ? ("[" + qqSender + "]") : null;

            final String finalSourceQqMessageId = sourceQqMessageId;
            final String finalReplyToMcMessageUuid = replyToMcMessageUuid;
            final String finalReplyToQqMessageId = replyToQqMessageId;
            final String finalReplyToSender = replyToSender;
            final String finalReplyToContent = replyToContent;
            final String finalSenderDisplay = senderDisplay;
            final String finalMsg = msg;

            mcServer.execute(() -> {
                // 设置防回环标记：QQ→MC 消息处理期间产生的系统消息不会被 onGameMessage 转发
                messageProcessing = true;
                try {
                    if (finalReplyToSender != null && !finalReplyToSender.isEmpty()
                            && finalReplyToContent != null && !finalReplyToContent.isEmpty()) {
                        // 被回复消息的 originalUuid：优先用 Python 直传的 MC_UUID
                        String originalUuid;
                        if (finalReplyToMcMessageUuid != null && !finalReplyToMcMessageUuid.isEmpty()) {
                            originalUuid = finalReplyToMcMessageUuid;
                        } else if (finalReplyToQqMessageId != null && !finalReplyToQqMessageId.isEmpty()) {
                            originalUuid = generateMessageUuid(finalReplyToSender, "[OrigReply]" + finalReplyToQqMessageId);
                        } else {
                            originalUuid = generateMessageUuid(finalReplyToSender, "[OrigReply]");
                        }
                        // 生成这条回复消息自身的UUID（用于被别人回复时定位）
                        String replyUuid = generateMessageUuid(finalSenderDisplay, "[Reply]" + finalMsg);
                        // 回传 mc_uuid 给 Python，由 Python 建立映射
                        sendMcUuidAck(finalSourceQqMessageId, replyUuid);
                        ReplyMessagePayload replyPayload = new ReplyMessagePayload(
                                finalSenderDisplay != null ? finalSenderDisplay : "", finalReplyToSender, finalReplyToContent, finalMsg,
                                originalUuid, replyUuid);

                        for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                            if (isClientModded(player)) {
                                ServerPlayNetworking.send(player, replyPayload);
                            } else {
                                // 未装mod玩家：用 <发送者> 格式，但 senderDisplay 已是 [QQname] 时直接用
                                String senderTag = finalSenderDisplay != null ? finalSenderDisplay + " " : "";
                                String text = senderTag + "» 回复 " + finalReplyToSender + "：" + finalReplyToContent + " | " + finalMsg;
                                player.sendSystemMessage(Component.literal(text));
                            }
                        }
                    } else {
                        // 普通消息：无 sender 时不加前缀，只显示内容
                        // 生成QQ→MC消息的UUID（用于MC玩家回复此消息时定位）
                        String richUuid = generateMessageUuid(finalSenderDisplay, finalMsg);
                        // 回传 mc_uuid 给 Python，由 Python 建立映射
                        sendMcUuidAck(finalSourceQqMessageId, richUuid);
                        for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                            if (isClientModded(player)) {
                                // 装mod玩家：发 RichMessagePayload（携带uuid），客户端 handleRichMessage 会根据 sender 是否为空决定是否加 <昵称>
                                ServerPlayNetworking.send(player, new RichMessagePayload("chat",
                                        finalSenderDisplay != null ? finalSenderDisplay : "", finalMsg, richUuid));
                            } else {
                                // 未装mod玩家：有sender显示 [昵称] 内容，无sender只显示内容
                                String text = finalSenderDisplay != null ? finalSenderDisplay + " " + finalMsg : finalMsg;
                                player.sendSystemMessage(Component.literal(text));
                            }
                        }
                    }
                } finally {
                    messageProcessing = false;
                }
            });
        }

        private void handleSendImage(NoneBotMessage message) {
            String base64 = message.getBase64();
            if (base64 == null || base64.isEmpty()) return;

            String qqSender = message.getSender();
            String sourceQqMessageId = message.getSourceMessageId();
            // 问题1：QQ->MC前缀改为 [QQname]
            String senderDisplay = (qqSender != null && !qqSender.isEmpty()) ? ("[" + qqSender + "]") : null;
            LightChatPatch.LOGGER.info("[LCP][QQ→MC] 收到纯图片: sender={}, source_id={}", qqSender, sourceQqMessageId);

            NoneBotConfig config = NoneBotConfig.getInstance();
            long maxBytes = config.getMaxImageBytes();
            String[] processed = ImageUtils.processImage(base64, maxBytes);
            if (processed == null) return;

            byte[] imageData = Base64.getDecoder().decode(processed[0]);
            int finalWidth = Integer.parseInt(processed[1]);
            int finalHeight = Integer.parseInt(processed[2]);

            mcServer.execute(() -> {
                messageProcessing = true;
                try {
                    // 生成QQ→MC图片消息的UUID
                    String imageUuid = generateMessageUuid(senderDisplay, "[Image]" + finalWidth + "x" + finalHeight);
                    // 回传 mc_uuid 给 Python，由 Python 建立映射
                    sendMcUuidAck(sourceQqMessageId, imageUuid);
                    for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                        if (isClientModded(player)) {
                            // 装mod玩家：发 ImagePayload（sender 为空时客户端只显示 [图片]，携带uuid）
                            ServerPlayNetworking.send(player, new ImagePayload(imageData, finalWidth, finalHeight,
                                    senderDisplay != null ? senderDisplay : "", imageUuid));
                        } else {
                            // 未装mod玩家：有sender显示 <昵称> [图片]，无sender只显示 [图片]
                            if (senderDisplay != null) {
                                Component imageTip = buildSenderTag(mcServer, senderDisplay)
                                        .copy()
                                        .append(Component.literal(" [图片]"));
                                player.sendSystemMessage(imageTip);
                            } else {
                                player.sendSystemMessage(Component.literal("[图片]"));
                            }
                        }
                    }
                } finally {
                    messageProcessing = false;
                }
            });
        }

        /**
         * 问题8：QQ->MC 文本+图片合并为一条消息。
         * payload 格式：{"type":"send_message_with_media","message":"...","sender":"QQ昵称",
         *                "reply_to_sender":"...","reply_to_content":"...",
         *                "reply_to_qq_message_id":"...","source_qq_message_id":"...",
         *                "images":[{"base64":"...","width":100,"height":100}]}
         *
         * 对装mod玩家：先发送 RichMessagePayload/ReplyMessagePayload（文字+发送者），
         *   随后紧跟着发送 ImagePayload（每条带相同 sender + 统一 prefixUuid 前缀），
         *   客户端 ChatHistoryManager 会把同 prefixUuid 的所有条目（文字+图）组装成一条复合消息显示。
         * 未装mod玩家：降级为 [昵称] 消息内容 [图片1] [图片2] 一条系统消息（图用 [图片] 占位）。
         */
        private void handleSendMessageWithMedia(NoneBotMessage message) {
            String msg = message.getMessage();
            java.util.List<NoneBotMessage.ImageData> images = message.getImages();
            if ((msg == null || msg.isEmpty()) && (images == null || images.isEmpty())) return;

            String qqSender = message.getSender();
            String replyToSender = message.getReplyToSender();
            String replyToContent = message.getReplyToContent();
            String replyToQqMessageId = message.getReplyToQqMessageId();
            String sourceQqMessageId = message.getSourceMessageId();
            // 问题7：优先使用 Python 端直传的 MC UUID，reply 分支与 handleSendMessage 保持一致策略
            String replyToMcMessageUuid = message.getReplyToMcMessageUuid();

            String senderDisplay = (qqSender != null && !qqSender.isEmpty()) ? ("[" + qqSender + "]") : null;
            LightChatPatch.LOGGER.info("[LCP][QQ→MC] 收到图文混合: sender={}, source_qq_id={}, hasReply={}, imgCount={}, msgLen={}",
                    qqSender, sourceQqMessageId,
                    replyToSender != null && !replyToSender.isEmpty(),
                    images != null ? images.size() : 0,
                    msg != null ? msg.length() : 0);

            // 处理所有图片：每个压缩一次
            java.util.List<byte[]> imageBytesList = new java.util.ArrayList<>();
            java.util.List<int[]> imageSizes = new java.util.ArrayList<>();
            if (images != null && !images.isEmpty()) {
                NoneBotConfig config = NoneBotConfig.getInstance();
                long maxBytes = config.getMaxImageBytes();
                for (NoneBotMessage.ImageData img : images) {
                    if (img == null || img.getBase64() == null || img.getBase64().isEmpty()) continue;
                    String[] processed = ImageUtils.processImage(img.getBase64(), maxBytes);
                    if (processed == null) continue;
                    try {
                        byte[] bytes = Base64.getDecoder().decode(processed[0]);
                        int w = Integer.parseInt(processed[1]);
                        int h = Integer.parseInt(processed[2]);
                        imageBytesList.add(bytes);
                        imageSizes.add(new int[]{w, h});
                    } catch (Exception ignored) {
                    }
                }
            }

            final boolean hasReply = replyToSender != null && !replyToSender.isEmpty()
                    && replyToContent != null && !replyToContent.isEmpty();
            final String finalMsg = msg == null ? "" : msg;
            final String finalSenderDisplay = senderDisplay;
            final String finalReplyToSender = replyToSender;
            final String finalReplyToContent = replyToContent;
            final String finalReplyToQqMessageId = replyToQqMessageId;
            final String finalSourceQqMessageId = sourceQqMessageId;
            final String finalReplyToMcMessageUuid = replyToMcMessageUuid;

            mcServer.execute(() -> {
                messageProcessing = true;
                try {
                    // 复合消息的 groupUuid（文字 + 所有图片同属一组）
                    // groupUuid 就是客户端回复此消息时拿到的 originalMessageUuid
                    String groupUuid = generateMessageUuid(finalSenderDisplay, "[Group]" + finalMsg);

                    java.util.List<ServerPlayer> players = mcServer.getPlayerList().getPlayers();

                    // 2. 文字部分：和 handleSendMessage 基本一致
                    if (!finalMsg.isEmpty() || !hasReply) {
                        if (hasReply) {
                            String originalUuid;
                            // 优先使用 Python 端直传的 MC_UUID，其次 fallback 到统一生成
                            if (finalReplyToMcMessageUuid != null && !finalReplyToMcMessageUuid.isEmpty()) {
                                originalUuid = finalReplyToMcMessageUuid;
                            } else if (finalReplyToQqMessageId != null && !finalReplyToQqMessageId.isEmpty()) {
                                originalUuid = generateMessageUuid(finalReplyToSender, "[OrigReply]" + finalReplyToQqMessageId);
                            } else {
                                originalUuid = generateMessageUuid(finalReplyToSender, "[OrigReply]");
                            }
                            String replyUuid = generateMessageUuid(finalSenderDisplay, "[Reply]" + finalMsg);
                            // 回传 mc_uuid 给 Python，由 Python 建立映射
                            sendMcUuidAck(finalSourceQqMessageId, replyUuid);
                            ReplyMessagePayload replyPayload = new ReplyMessagePayload(
                                    finalSenderDisplay != null ? finalSenderDisplay : "",
                                    finalReplyToSender, finalReplyToContent, finalMsg,
                                    originalUuid, replyUuid);
                            for (ServerPlayer player : players) {
                                if (isClientModded(player)) {
                                    // 客户端需支持 groupUuid，但 ReplyMessagePayload 暂不支持，
                                    // 这里直接用 ReplyMessagePayload 显示文字回复
                                    ServerPlayNetworking.send(player, replyPayload);
                                } else {
                                    String senderTag = finalSenderDisplay != null ? finalSenderDisplay + " " : "";
                                    String text = senderTag + "» 回复 " + finalReplyToSender + "：" + finalReplyToContent
                                            + " | " + finalMsg;
                                    player.sendSystemMessage(Component.literal(text));
                                }
                            }
                        } else if (!finalMsg.isEmpty()) {
                            String richUuid = groupUuid;
                            // 回传 mc_uuid 给 Python，由 Python 建立映射
                            sendMcUuidAck(finalSourceQqMessageId, richUuid);
                            for (ServerPlayer player : players) {
                                if (isClientModded(player)) {
                                    ServerPlayNetworking.send(player, new RichMessagePayload("chat",
                                            finalSenderDisplay != null ? finalSenderDisplay : "", finalMsg, richUuid));
                                } else {
                                    String text = finalSenderDisplay != null ? finalSenderDisplay + " " + finalMsg : finalMsg;
                                    player.sendSystemMessage(Component.literal(text));
                                }
                            }
                        }
                    }

                    // 3. 图片部分：每条图片作为单独 ImagePayload 发送，携带 sender 让客户端显示 [QQname] 前缀
                    if (!imageBytesList.isEmpty()) {
                        for (int i = 0; i < imageBytesList.size(); i++) {
                            byte[] imgBytes = imageBytesList.get(i);
                            int[] size = imageSizes.get(i);
                            String imgUuid = groupUuid + "_img_" + i;
                            for (ServerPlayer player : players) {
                                if (isClientModded(player)) {
                                    ServerPlayNetworking.send(player, new ImagePayload(imgBytes, size[0], size[1],
                                            finalSenderDisplay != null ? finalSenderDisplay : "",
                                            imgUuid));
                                }
                            }
                        }
                    }

                    // 4. 未装mod玩家：将 文字 + [图片1] [图片2] 合并为一条系统消息（若消息为空但有图片，只发 [图片] 前缀）
                    if (!imageBytesList.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        if (finalSenderDisplay != null) sb.append(finalSenderDisplay).append(' ');
                        if (!finalMsg.isEmpty()) sb.append(finalMsg);
                        for (int i = 0; i < imageBytesList.size(); i++) {
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                            sb.append("[图片]");
                        }
                        if (sb.length() == 0) sb.append("[图片]");
                        String fallbackText = sb.toString();
                        for (ServerPlayer player : players) {
                            if (!isClientModded(player)) {
                                player.sendSystemMessage(Component.literal(fallbackText));
                            }
                        }
                    }
                } finally {
                    messageProcessing = false;
                }
            });
        }

        private void handleCommand(NoneBotMessage message) {
            String command = message.getCommand();
            if (command == null || command.isEmpty()) return;

            LightChatPatch.LOGGER.info("[LCP.NoneBot] Remote command received: {}", command);

            mcServer.execute(() -> {
                // 设置防回环标记：命令执行期间产生的系统消息不会被 onGameMessage 转发给 NoneBot
                commandExecuting = true;
                try {
                    mcServer.getCommands().performPrefixedCommand(
                            mcServer.createCommandSourceStack(), command);
                } finally {
                    commandExecuting = false;
                }
            });
        }

        @Override
        public void onClientConnected() {
            LightChatPatch.LOGGER.info("[LCP.NoneBot] NoneBot client connected");
        }

        @Override
        public void onClientDisconnected() {
            LightChatPatch.LOGGER.info("[LCP.NoneBot] NoneBot client disconnected");
        }
    }
}
