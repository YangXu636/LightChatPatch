package top.xuyangjerry.mcmod.lcp.history;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import top.xuyangjerry.mcmod.lcp.config.ChatHistoryView;

import java.nio.file.Path;

public final class HistoryPaths {
    private HistoryPaths() {
    }

    public static Path getStorageDir(ChatHistoryView view) {
        Minecraft mc = Minecraft.getInstance();

        switch (view) {
            case GLOBAL:
                return getGlobalDir(mc);

            case CURRENT_VERSION: {
                Path worldDir = getSingleplayerWorldDir(mc);
                if (worldDir != null) {
                    Path versionDir = worldDir.getParent().getParent();
                    return versionDir.resolve("light_chat_patch");
                }
                return getGlobalDir(mc);
            }

            case CURRENT_WORLD: {
                Path worldDir = getSingleplayerWorldDir(mc);
                if (worldDir != null) {
                    return worldDir.resolve("light_chat_patch");
                }
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

    private static Path getGlobalDir(Minecraft mc) {
        return mc.gameDirectory.toPath()
                .resolve("config")
                .resolve("light_chat_patch");
    }

    private static Path getSingleplayerWorldDir(Minecraft mc) {
        if (!mc.hasSingleplayerServer()) {
            return null;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        try {
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
