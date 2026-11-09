package com.fandazi.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * FileStore 的纯文件逻辑（只用 java.io / java.nio，不依赖 Android 或 Capacitor），
 * 便于在普通 JVM 上做故障场景测试；插件层只负责参数与 JSON。
 *
 * save 顺序：写 <name>.part → flush + fsync → 读回比对字节数与 SHA-256 → 原子替换正式文件。
 * 任何一步失败都不动正式文件；替换失败时旧备份保持原样。
 */
public final class FileStoreCore {

    public static final long MAX_READ_BYTES = 32L * 1024 * 1024;
    public static final String TMP_SUFFIX = ".part";

    private final File dir;

    public FileStoreCore(File dir) {
        this.dir = dir;
    }

    public static boolean validName(String name) {
        if (name == null || name.length() == 0 || name.length() > 200) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        return true;
    }

    public File dir() {
        return dir;
    }

    public File file(String name) {
        return new File(dir, name);
    }

    /** 目录不存在时创建；存在但不是目录时直接失败。 */
    public void ensureDir() throws IOException {
        if (dir.isDirectory()) return;
        if (dir.exists()) throw new IOException("not a directory: " + dir.getAbsolutePath());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("unable to create " + dir.getAbsolutePath());
        }
    }

    /** 目录不存在 → 空列表；目录存在但 listFiles 返回 null → 报读取失败，不伪装成空列表。 */
    public File[] list() throws IOException {
        if (!dir.exists()) return new File[0];
        if (!dir.isDirectory()) throw new IOException("not a directory: " + dir.getAbsolutePath());
        File[] all = dir.listFiles();
        if (all == null) throw new IOException("listFiles returned null: " + dir.getAbsolutePath());
        return all;
    }

    public String read(String name) throws IOException {
        File f = file(name);
        if (!f.exists()) throw new IOException("FileNotFound: " + name);
        if (!f.isFile()) throw new IOException("NotAFile: " + name);
        long len = f.length();
        if (len > MAX_READ_BYTES) {
            throw new IOException("TooLarge: " + name + " " + len + " > " + MAX_READ_BYTES);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.min(len, 1 << 20));
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    public Object[] save(String name, String data) throws IOException {
        ensureDir();
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        File tmp = new File(dir, name + TMP_SUFFIX);
        File dst = file(name);
        boolean moved = false;
        try {
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
            long written = tmp.length();
            if (written != bytes.length) {
                throw new IOException("verify failed: length " + written + " != " + bytes.length);
            }
            String tmpSha = sha256(tmp);
            if (!tmpSha.equals(sha256(bytes))) {
                throw new IOException("verify failed: sha256 mismatch for " + name);
            }
            if (dst.exists() && dst.isFile() && dst.length() == written && sha256(dst).equals(tmpSha)) {
                return new Object[] { dst, written, "unchanged" };
            }
            moved = atomicReplace(tmp, dst);
            if (!moved) throw new IOException("replace failed, old backup kept: " + name);
            return new Object[] { dst, written, "replaced" };
        } finally {
            if (!moved) {
                try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) { }
            }
        }
    }

    public boolean delete(String name) throws IOException {
        File f = file(name);
        if (!f.exists()) return false;
        if (!f.isFile()) throw new IOException("NotAFile: " + name);
        boolean ok = f.delete();
        if (!ok) throw new IOException("delete failed: " + name);
        return true;
    }

    /** SHA-256 十六进制小写。 */
    public static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256 unavailable: " + e.getMessage());
        }
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("sha256 unavailable: " + e.getMessage());
        }
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * 同目录原子替换：API 26+ 走 Files.move(ATOMIC_MOVE)；老系统 / 不支持时退回 renameTo（Android 为 POSIX rename）。
     * 两种情况都不先删除旧文件，失败即保留旧备份。
     */
    private static boolean atomicReplace(File tmp, File dst) {
        try {
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return !tmp.exists();
        } catch (Throwable ignored) {
        }
        try {
            return tmp.renameTo(dst);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
