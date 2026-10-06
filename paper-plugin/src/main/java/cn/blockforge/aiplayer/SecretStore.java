package cn.blockforge.aiplayer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecretStore {
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretStore(Path dir) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("secret.key");
            if (Files.exists(file)) key = new SecretKeySpec(Base64.getDecoder().decode(Files.readString(file).trim()), "AES");
            else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                key = generator.generateKey();
                Files.writeString(file, Base64.getEncoder().encodeToString(key.getEncoded()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化密钥存储", e);
        }
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            return "enc:" + Base64.getEncoder().encodeToString(all);
        } catch (Exception e) { throw new IllegalStateException("密钥加密失败", e); }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return "";
        if (!value.startsWith("enc:")) return value;
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(4));
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
            byte[] body = java.util.Arrays.copyOfRange(all, 12, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
