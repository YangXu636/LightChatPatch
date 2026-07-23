package top.xuyangjerry.mcmod.history;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import top.xuyangjerry.mcmod.config.ChatHistoryView;

import java.nio.file.Path;

/**
 * 统一解析三种历史视图模式下的存储目录。
 *
 * 当前世界：
 *   单人 -> 世界文件夹内的 light_chat_patch/（随地图走，删地图自动清理）
 *   多人 -> .minecraft/config/light_chat_patch/worlds/mp_<IP>/
 *
 * 当前版本：
 *   单人 -> 世界路径上两级（版本文件夹）下的 light_chat_patch/
 *   多人 -> 等同于全局（服务器可能多版本连接）
 *
 * 全局：
 *   .minecraft/config/light_chat_patch/
 */
public final class HistoryPaths {
    private HistoryPaths() {
    }

    /**
     * 获取指定视图模式下的存储目录。
     * 返回 null 表示无法确定路径（如未进入世界时的"当前世界"模式）。
     */
    public static Path getStorageDir(ChatHistoryView view) {
        Minecraft mc = Minecraft.getInstance();

        switch (view) {
            case GLOBAL:
                return getGlobalDir(mc);

            case CURRENT_VERSION: {
                Path worldDir = getSingleplayerWorldDir(mc);
                if (worldDir != null) {
                    // 世界路径如 .../saves/<世界名>/，上两级到版本文件夹（或 .minecraft/）
                    Path versionDir = worldDir.getParent().getParent();
                    return versionDir.resolve("light_chat_patch");
                }
                // 多人服务器回退到全局
                return getGlobalDir(mc);
            }

            case CURRENT_WORLD: {
                Path worldDir = getSingleplayerWorldDir(mc);
                if (worldDir != null) {
                    return worldDir.resolve("light_chat_patch");
                }
                // 多人服务器
                String serverId = getMultiplayerServerId(mc);
                if (serverId != null) {
                    return getGlobalDir(mc).resolve("worlds").resolve(serverId);
                }
                return null;
            }

            default:
                return null;
        }
    }

    /**
     * 全局目录：.minecraft/config/light_chat_patch/
     */
    private static Path getGlobalDir(Minecraft mc) {
        return mc.gameDirectory.toPath()
                .resolve("config")
                .resolve("light_chat_patch");
    }

    /**
     * 获取单人世界的根目录路径（如 .../saves/<世界名>/）。
     * 通过 gameDirectory/saves/<levelName> 推断。
     * 在版本隔离下，gameDirectory 就是版本文件夹，因此路径正确。
     * 返回 null 表示非单人世界或获取失败。
     */
    private static Path getSingleplayerWorldDir(Minecraft mc) {
        if (!mc.hasSingleplayerServer()) {
            return null;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        try {
            // gameDirectory/saves/<世界名>/ 是单人世界的标准存储路径
            // 在版本隔离下，gameDirectory 为版本文件夹，saves 目录在其下
            String levelName = server.getWorldData().getLevelName();
            if (levelName == null || levelName.isEmpty()) {
                return null;
            }
            return mc.gameDirectory.toPath()
                    .resolve("saves")
                    .resolve(levelName);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 获取多人服务器的标识 ID（如 mp_<IP>）。
     * 返回 null 表示非多人或无法获取服务器信息。
     */
    private static String getMultiplayerServerId(Minecraft mc) {
        if (mc.hasSingleplayerServer()) {
            return null;
        }
        ServerData data = mc.getCurrentServer();
        if (data == null || data.ip == null || data.ip.isEmpty()) {
            return null;
        }
        return "mp_" + ChatHistoryManager.sanitize(data.ip);
    }
}
