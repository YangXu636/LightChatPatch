package top.xuyangjerry.mcmod.lcp.client.nonebot;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClientImageCache {
    private static final int MAX_CACHE_SIZE = 32;
    public static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("\\[Image\\s*#(\\d+)\\]");

    private static final LinkedHashMap<Integer, ImageEntry> CACHE = new LinkedHashMap<>();
    private static int nextId = 1;

    private ClientImageCache() {
    }

    public static synchronized int addImage(byte[] pngBytes, int width, int height) {
        int id = nextId++;
        if (registerImage(id, pngBytes, width, height)) {
            return id;
        }
        return -1;
    }

    /**
     * 用指定 id 注册一张图片纹理（用于重进世界后从历史文件恢复）。
     * 若该 id 已存在则会被覆盖。同时确保 nextId 不会与已恢复的 id 冲突。
     *
     * @param id       原始图片 id（来自历史文件）
     * @param pngBytes PNG 字节数据
     * @param width    图片宽度
     * @param height   图片高度
     * @return 注册成功返回 true，失败返回 false
     */
    public static synchronized boolean restoreImage(int id, byte[] pngBytes, int width, int height) {
        boolean ok = registerImage(id, pngBytes, width, height);
        if (ok && id >= nextId) {
            nextId = id + 1;
        }
        return ok;
    }

    private static boolean registerImage(int id, byte[] pngBytes, int width, int height) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(pngBytes));
            String texName = "lcp_image_" + id;
            DynamicTexture texture = new DynamicTexture((Supplier<String>) () -> texName, image);
            Identifier location = Identifier.fromNamespaceAndPath("light_chat_patch", texName);
            Minecraft.getInstance().getTextureManager().register(location, texture);

            ImageEntry entry = new ImageEntry(texture, location, width, height, pngBytes);
            CACHE.put(id, entry);
            evictIfNeeded();
            LightChatPatch.LOGGER.info("[LCP.NoneBot] Cached image #{} ({}x{}, {} bytes)", id, width, height, pngBytes.length);
            return true;
        } catch (Throwable e) {
            // 捕获所有异常（含 OutOfMemoryError），避免图片解码失败导致游戏崩溃
            LightChatPatch.LOGGER.error("[LCP] Failed to decode image #{} ({}x{}, {} bytes) - {}",
                    id, width, height, pngBytes.length, e.getClass().getSimpleName(), e);
            return false;
        }
    }

    public static int extractImageId(String text) {
        if (text == null || text.isEmpty()) return -1;
        Matcher m = IMAGE_TAG_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public static synchronized ImageEntry getImage(int id) {
        ImageEntry entry = CACHE.get(id);
        if (entry != null) {
            CACHE.remove(id);
            CACHE.put(id, entry);
        }
        return entry;
    }

    public static synchronized void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (ImageEntry entry : CACHE.values()) {
            mc.getTextureManager().release(entry.location());
            entry.texture().close();
        }
        CACHE.clear();
    }

    private static void evictIfNeeded() {
        while (CACHE.size() > MAX_CACHE_SIZE) {
            Map.Entry<Integer, ImageEntry> eldest = CACHE.entrySet().iterator().next();
            ImageEntry entry = eldest.getValue();
            Minecraft.getInstance().getTextureManager().release(entry.location());
            entry.texture().close();
            CACHE.remove(eldest.getKey());
        }
    }

    public static String makeTag(int id) {
        return String.format(Locale.ROOT, "[Image #%d]", id);
    }

    public record ImageEntry(DynamicTexture texture, Identifier location, int width, int height, byte[] pngBytes) {
    }
}
