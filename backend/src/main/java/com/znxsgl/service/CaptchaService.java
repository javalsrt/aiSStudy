package com.znxsgl.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端图形验证码。
 *
 * 内存存储、5 分钟过期、校验后立即失效（一次性）。
 * 单实例部署足够；多实例部署时应改为 Redis 共享存储。
 */
@Service
public class CaptchaService {

    /** 去掉 0/O、1/I/L 等易混淆字符，降低识别歧义 */
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String code, long expiresAt) {
    }

    /** 生成验证码：返回 captchaId 与可直接放入 img src 的 PNG data URL */
    public Map<String, String> create() {
        cleanExpired();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String captchaId = UUID.randomUUID().toString();
        store.put(captchaId, new Entry(code.toString(), System.currentTimeMillis() + TTL_MS));
        return Map.of("captchaId", captchaId, "image", render(code.toString()));
    }

    /** 校验验证码：忽略大小写，无论对错都作废，防止同一验证码被重复尝试 */
    public boolean validate(String captchaId, String input) {
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        Entry entry = store.remove(captchaId);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return false;
        }
        return entry.code().equalsIgnoreCase(input.trim());
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    /** 用 Java2D 绘制：渐变底 + 干扰线 + 噪点 + 轻微旋转的字符 */
    private String render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(238, 242, 255), WIDTH, HEIGHT, new Color(236, 254, 255)));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(150 + random.nextInt(80), 170 + random.nextInt(70), 210 + random.nextInt(40)));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            for (int i = 0; i < 50; i++) {
                g.setColor(new Color(170 + random.nextInt(70), 170 + random.nextInt(70), 170 + random.nextInt(70)));
                g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }

            g.setFont(new Font("Arial", Font.BOLD, 26));
            for (int i = 0; i < code.length(); i++) {
                AffineTransform old = g.getTransform();
                int x = 14 + i * 26;
                int y = 29 + random.nextInt(5) - 2;
                g.rotate((random.nextDouble() - 0.5) * 0.5, x, y);
                g.setColor(new Color(30 + random.nextInt(70), 60 + random.nextInt(80), 120 + random.nextInt(80)));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(old);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("验证码生成失败", e);
        }
    }
}
