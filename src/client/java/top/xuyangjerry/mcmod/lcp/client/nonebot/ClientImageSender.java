package top.xuyangjerry.mcmod.lcp.client.nonebot;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.network.ImageUploadPayload;
import top.xuyangjerry.mcmod.lcp.nonebot.ImageUtils;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 客户端图片发送工具：支持从剪贴板（Ctrl+V）和文件选择按钮发送图片。
 * 发送前会按配置的最大尺寸缩放图片，并检查编码后的大小是否超限。
 */
public final class ClientImageSender {

    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp"
    ));

    private static int awtCheckState = 0;
    private static boolean awtAvailable = false;

    private ClientImageSender() {
    }

    private static synchronized boolean isAwtAvailable() {
        if (awtCheckState == 0) {
            try {
                System.setProperty("java.awt.headless", "false");
                Toolkit.getDefaultToolkit();
                awtAvailable = !GraphicsEnvironment.isHeadless();
            } catch (Throwable t) {
                awtAvailable = false;
                LightChatPatch.LOGGER.warn("[LCP] AWT not available: {}", t.getMessage());
            }
            awtCheckState = 1;
        }
        return awtAvailable;
    }

    /**
     * 仅检查剪贴板中是否包含图片（不发送，用于服务端未装 mod 时提示用户）。
     */
    public static boolean isClipboardHasImage() {
        if (!isAwtAvailable()) return false;
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            return contents != null && contents.isDataFlavorSupported(DataFlavor.imageFlavor);
        } catch (Throwable t) {
            LightChatPatch.LOGGER.warn("[LCP] Failed to check clipboard image: {}", t.getMessage());
            return false;
        }
    }

    /**
     * 尝试从系统剪贴板获取图片并发送。返回 true 表示剪贴板中有图片且已触发发送流程。
     */
    public static boolean trySendClipboardImage(ChatScreen screen) {
        if (!isAwtAvailable()) {
            LightChatPatch.LOGGER.warn("[LCP] AWT not available, cannot paste image from clipboard");
            return false;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            if (contents == null) {
                LightChatPatch.LOGGER.debug("[LCP] Clipboard contents is null");
                return false;
            }
            if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                LightChatPatch.LOGGER.debug("[LCP] Clipboard does not contain image");
                return false;
            }

            Object data = contents.getTransferData(DataFlavor.imageFlavor);
            if (!(data instanceof BufferedImage image)) {
                LightChatPatch.LOGGER.warn("[LCP] Clipboard data is not BufferedImage: {}", data.getClass().getName());
                return false;
            }

            LightChatPatch.LOGGER.info("[LCP] Found image in clipboard, size: {}x{}", image.getWidth(), image.getHeight());
            processAndSendAsync(image, screen);
            return true;
        } catch (HeadlessException e) {
            LightChatPatch.LOGGER.warn("[LCP] Headless mode, cannot access clipboard image");
            return false;
        } catch (Exception e) {
            LightChatPatch.LOGGER.warn("[LCP] Failed to read image from clipboard", e);
            return false;
        }
    }

    /**
     * 打开文件选择对话框，选择图片后发送。在独立线程中执行以避免阻塞 Minecraft 主线程。
     */
    public static void openImageFileChooser(ChatScreen screen) {
        if (!isAwtAvailable()) {
            notifyUser(screen, Component.literal("[LCP] 当前环境不支持文件选择器")
                    .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("选择要发送的图片");
                chooser.setFileFilter(new FileNameExtensionFilter(
                        "图片文件 (PNG, JPG, GIF, BMP, WEBP)", "png", "jpg", "jpeg", "gif", "bmp", "webp"));
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    File file = chooser.getSelectedFile();
                    sendImageFile(file, screen);
                }
            } catch (Exception e) {
                LightChatPatch.LOGGER.error("[LCP] Failed to open file chooser", e);
                notifyUser(screen, Component.literal("[LCP] 打开文件选择器失败")
                        .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
            }
        }, "LCP-FileChooser");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 从文件读取图片并发送。
     */
    public static void sendImageFile(File file, ChatScreen screen) {
        if (file == null || !file.exists()) return;

        String name = file.getName().toLowerCase();
        String ext = "";
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = name.substring(dotIdx + 1);
        }
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            notifyUser(screen, Component.literal("[LCP] 不支持的图片格式")
                    .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null) {
                    notifyUser(screen, Component.literal("[LCP] 无法读取图片文件")
                            .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
                    return;
                }
                processAndSendAsync(image, screen);
            } catch (IOException e) {
                LightChatPatch.LOGGER.warn("[LCP] Failed to read image file: {}", file.getName(), e);
                notifyUser(screen, Component.literal("[LCP] 读取图片失败: " + e.getMessage())
                        .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
            }
        }, "LCP-ImageFileLoader");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 在独立线程中处理图片：编码为 PNG → 若超过配置大小则等比例缩小（保持宽高比）→ 发送数据包。
     * 缩放策略：优先保持原分辨率，仅当 PNG 字节数超过 maxImageSize 配置时才缩小。
     */
    private static void processAndSendAsync(BufferedImage image, ChatScreen screen) {
        Thread worker = new Thread(() -> {
            try {
                long maxBytes = getMaxImageSizeBytes();
                BufferedImage current = image;
                byte[] pngBytes = encodeToPng(current);

                if (pngBytes == null || pngBytes.length == 0) {
                    notifyUser(screen, Component.literal("[LCP] 图片编码失败")
                            .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
                    return;
                }

                // 若超过配置大小，等比例缩小（保持宽高比），最多重试 8 次
                int attempts = 0;
                while (pngBytes.length > maxBytes && attempts < 8 && current.getWidth() > 16 && current.getHeight() > 16) {
                    attempts++;
                    // 每次缩小到 80%，保持宽高比
                    int newW = (int) (current.getWidth() * 0.8);
                    int newH = (int) (current.getHeight() * 0.8);
                    current = ImageUtils.resize(current, newW, newH);
                    pngBytes = encodeToPng(current);
                    if (pngBytes == null) break;
                    LightChatPatch.LOGGER.info("[LCP] Image too large ({}), resized to {}x{} (attempt {})",
                            formatSize(pngBytes.length), newW, newH, attempts);
                }

                if (pngBytes == null || pngBytes.length == 0) {
                    notifyUser(screen, Component.literal("[LCP] 图片编码失败")
                            .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
                    return;
                }

                if (pngBytes.length > maxBytes) {
                    // 缩小 8 次后仍超限（极端情况）
                    String sizeStr = formatSize(pngBytes.length);
                    String limitStr = formatSize(maxBytes);
                    notifyUser(screen, Component.literal("[LCP] 图片过大 (" + sizeStr + ")，超过限制 (" + limitStr + ")")
                            .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
                    return;
                }

                int finalWidth = current.getWidth();
                int finalHeight = current.getHeight();
                int sendWidth = finalWidth;
                int sendHeight = finalHeight;
                byte[] sendBytes = pngBytes;
                LightChatPatch.LOGGER.info("[LCP] Sending image: {}x{}, {} bytes",
                        finalWidth, finalHeight, pngBytes.length);

                Minecraft.getInstance().execute(() -> {
                    try {
                        if (Minecraft.getInstance().player == null
                                || Minecraft.getInstance().player.connection == null) {
                            return;
                        }
                        ClientPlayNetworking.send(new ImageUploadPayload(sendBytes, sendWidth, sendHeight));
                    } catch (Exception e) {
                        LightChatPatch.LOGGER.error("[LCP] Failed to send image upload packet", e);
                    }
                });
            } catch (Exception e) {
                LightChatPatch.LOGGER.error("[LCP] Failed to process image", e);
                notifyUser(screen, Component.literal("[LCP] 图片处理失败")
                        .withStyle(Style.EMPTY.withColor(0xFFFF6666)));
            }
        }, "LCP-ImageProcessor");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 根据配置计算最大图片字节数。
     */
    private static long getMaxImageSizeBytes() {
        int size = LcpConfig.getInstance().getMaxImageSize();
        String unit = LcpConfig.getInstance().getMaxImageSizeUnit();
        if (unit == null) unit = "MB";
        long multiplier = switch (unit.toUpperCase()) {
            case "KB" -> 1024L;
            case "GB" -> 1024L * 1024 * 1024;
            default -> 1024L * 1024; // MB
        };
        return size * multiplier;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static byte[] encodeToPng(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            LightChatPatch.LOGGER.error("[LCP] Failed to encode image to PNG", e);
            return null;
        }
    }

    private static void notifyUser(ChatScreen screen, Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null && mc.gui.hud != null && mc.gui.hud.getChat() != null) {
                mc.gui.hud.getChat().addClientSystemMessage(message);
            }
        });
    }
}
