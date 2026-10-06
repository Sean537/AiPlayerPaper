package cn.blockforge.generated.paperaipaperaipaper;

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
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path keyFile;
    private final SecretKey key;

    public SecretStore(Path dir) {
        try {
            Files.createDirectories(dir);
            keyFile = dir.resolve("secret.key");
            if (Files.exists(keyFile)) {
                key = new SecretKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim()), "AES");
            } else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                key = generator.generateKey();
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 AI 密钥存储", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return "";
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return "enc:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) { throw new IllegalStateException("密钥加密失败", e); }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return "";
        if (!value.startsWith("enc:")) return value;
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(4));
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - iv.length];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
