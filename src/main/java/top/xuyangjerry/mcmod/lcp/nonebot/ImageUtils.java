package top.xuyangjerry.mcmod.lcp.nonebot;

import top.xuyangjerry.mcmod.lcp.LightChatPatch;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * 图片处理工具：Base64 解码、按文件大小缩放/压缩、再编码。
 * 对齐 MC 内 LcpConfig.maxImageSize 的策略：以"文件字节数"为准，超过则迭代等比缩小并重新编码。
 */
public final class ImageUtils {

    /** base64 输入最大长度：16MB 原始 → ~21MB base64 字符串 */
    private static final int MAX_BASE64_INPUT_LENGTH = 22 * 1024 * 1024;

    /** 迭代缩放时的最小分辨率，防止无限缩小（低于 16x16 直接放弃） */
    private static final int MIN_DIMENSION = 16;

    private ImageUtils() {
    }

    /**
     * 将 base64 字符串解码为 BufferedImage。
     * 对输入长度做上限检查，防止恶意超大 base64 导致 OOM。
     */
    public static BufferedImage decode(String base64) throws IOException {
        if (base64 == null || base64.length() > MAX_BASE64_INPUT_LENGTH) {
            throw new IOException("Image base64 too large or null: " + (base64 == null ? "null" : base64.length() + " chars"));
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * 将 BufferedImage 编码为 PNG base64 字符串。
     */
    public static String encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * 将 BufferedImage 按指定 PNG 质量（0.0~1.0，1.0=无损）编码为字节数组。
     */
    private static byte[] encodePngBytes(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("PNG");
        if (!writers.hasNext()) {
            // fallback to default
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        // PNG 压缩级别：Java ImageIO 实际没有 quality 的标准模式，这里兼容设置；
        // 通常 PNG 仅用 "压缩等级"，但对体积影响较小，主要靠分辨率压缩
        try {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        } catch (UnsupportedOperationException ignored) {
            // 某些 ImageIO 提供程序不支持 PNG 显式压缩质量，跳过即可
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /**
     * 等比缩放到指定目标分辨率（按最大边比例）。
     */
    public static BufferedImage resize(BufferedImage image, int newWidth, int newHeight) {
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return resized;
    }

    /**
     * 根据文件大小（字节）限制，反复缩小/重编码图片，直到字节数 ≤ maxBytes。
     * 仅在原图超限时才会修改；不超限时直接返回原图。
     *
     * @param image    原图
     * @param maxBytes 上限（字节）
     * @return 处理后的图片（可能是原图本身）
     */
    public static BufferedImage compressToFit(BufferedImage image, long maxBytes) {
        if (image == null) return null;
        if (maxBytes <= 0) return image;

        try {
            byte[] bytes = encodePngBytes(image, 1.0f);
            if (bytes.length <= maxBytes) {
                return image;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            BufferedImage current = image;
            int attempts = 0;
            final int maxAttempts = 12; // 0.8^12 ≈ 0.068，最大缩放到 6.8%
            while (bytes.length > maxBytes && attempts < maxAttempts) {
                if (w <= MIN_DIMENSION || h <= MIN_DIMENSION) break;
                // 每次 0.8 倍，兼顾速度和质量
                int newW = Math.max(MIN_DIMENSION, (int) (w * 0.8));
                int newH = Math.max(MIN_DIMENSION, (int) (h * 0.8));
                current = resize(current, newW, newH);
                bytes = encodePngBytes(current, 1.0f);
                w = newW;
                h = newH;
                attempts++;
            }
            return current;
        } catch (IOException e) {
            LightChatPatch.LOGGER.error("[LCP.NoneBot] Failed to compress image to fit {} bytes", maxBytes, e);
            return image;
        }
    }

    /**
     * 完整流程：base64 -> 解码 -> 按文件大小限制压缩 -> 重新编码为 base64。
     * 返回 [base64字符串, 宽度, 高度]，失败返回 null。
     */
    public static String[] processImage(String base64, long maxBytes) {
        try {
            BufferedImage image = decode(base64);
            BufferedImage resized = compressToFit(image, maxBytes);
            String encoded = encode(resized);
            return new String[]{encoded, String.valueOf(resized.getWidth()), String.valueOf(resized.getHeight())};
        } catch (Exception e) {
            LightChatPatch.LOGGER.error("[LCP.NoneBot] Failed to process image", e);
            return null;
        }
    }
}
