package com.fandazi.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileStore 的纯文件逻辑（只用 java.io，不依赖 Android / Capacitor），便于在普通 JVM 上跑故障场景。
 *
 * 安全写入流程（顺序不可调换）：
 *   1) 校验文件名 / 尺寸 / JSON 结构
 *   2) 在同一目录写唯一临时文件 &lt;name&gt;.&lt;nanotime&gt;_&lt;tid&gt;.part，并登记为「活动写入」
 *   3) flush + FileDescriptor.sync 落盘
 *   4) 读回校验：长度、SHA-256、严格 UTF-8、内容逐字符比对
 *   5) 原子替换：优先 renameTo（POSIX rename(2)，全 API 级别可用），失败再用反射调
 *      java.nio.file.Files.move(ATOMIC_MOVE)，不直接引用 nio（minSdk 22，API 26 以下没有该类）
 *   6) 任一步失败：不动正式文件，注销并清理临时文件，抛出明确错误
 *
 * 并发：同一目标文件的写入按路径串行化；不同目标文件可并行，清理时会跳过仍在写入的临时文件。
 * 故障注入：只通过构造参数 FaultHook 提供，生产路径（FileStoreCore(File)）不存在任何开关。
 */
public final class FileStoreCore {

    /** 单文件读取上限。当前 FileStore 只承载自动备份（照片只存引用），实测容量远低于此值。 */
    public static final long MAX_READ_BYTES = 32L * 1024 * 1024;
    /** 单文件写入上限。 */
    public static final long MAX_WRITE_BYTES = 32L * 1024 * 1024;
    /** 文件名长度上限。 */
    public static final int MAX_NAME = 180;
    /** 临时文件后缀。 */
    public static final String PART = ".part";
    /** 残留临时文件的保留时长（无活动登记时，超过即视为可清理）。 */
    public static final long PART_TTL_MS = 60L * 60 * 1000;

    /** 正在写入的临时文件（绝对路径 → 开始时间）。进程内共享，用于保护并发任务的临时文件。 */
    private static final ConcurrentHashMap<String, Long> ACTIVE = new ConcurrentHashMap<String, Long>();
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    /**
     * 故障注入接口：仅供测试通过构造函数传入，生产代码（插件）从不构造它。
     * 返回当前生效的注入点名称，null 表示不注入。
     */
    public interface FaultHook {
        String point();
    }

    /** 清理结果：区分「清理了多少」与「清理失败」，失败时保留可诊断信息。 */
    public static final class Cleanup {
        public final int removed;
        public final int failed;
        public final String error;
        Cleanup(int removed, int failed, String error) {
            this.removed = removed;
            this.failed = failed;
            this.error = error;
        }
        public boolean ok() { return failed == 0 && error == null; }
    }

    private final File dir;
    private final FaultHook fault;
    private volatile String lastReplaceKind = "";

    public FileStoreCore(File dir) {
        this(dir, null);
    }

    public FileStoreCore(File dir, FaultHook fault) {
        this.dir = dir;
        this.fault = fault;
    }

    public File dir() {
        return dir;
    }

    public File file(String name) {
        return new File(dir, name);
    }

    /** 最近一次成功替换所用机制（renameTo / nioAtomicMove / renameTo-fallback）。 */
    public String lastReplaceKind() {
        return lastReplaceKind;
    }

    private void faultPoint(String point) throws IOException {
        if (fault != null && point.equals(fault.point())) {
            throw new IOException("InjectedFault: " + point);
        }
    }

    private boolean faultForced(String point) {
        return fault != null && point.equals(fault.point());
    }

    /* ---------------- 名称与目录 ---------------- */

    /** 合法文件名：非空、不超长、无路径分隔符、不可穿越、不以点开头、无控制字符与保留字符。 */
    public static boolean validName(String name) {
        if (name == null) return false;
        int len = name.length();
        if (len == 0 || len > MAX_NAME) return false;
        if (name.charAt(0) == '.') return false;
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20 || c == 0x7f) return false;
        }
        if (name.contains("..")) return false;
        return true;
    }

    /** 目录不存在则创建；存在但不是目录则失败。 */
    public void ensureDir() throws IOException {
        if (dir.isDirectory()) return;
        if (dir.exists()) throw new IOException("NotADirectory: " + dir.getAbsolutePath());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("FailedToCreateDir: " + dir.getAbsolutePath());
        }
    }

    /** 目录不存在 / 空目录 → 空数组；listFiles() 返回 null → 抛错；临时文件不返回。 */
    public File[] list() throws IOException {
        if (!dir.exists()) return new File[0];
        if (!dir.isDirectory()) throw new IOException("NotADirectory: " + dir.getAbsolutePath());
        File[] all = dir.listFiles();
        if (all == null) throw new IOException("ListFailed: listFiles returned null, " + dir.getAbsolutePath());
        List<File> out = new ArrayList<File>(all.length);
        for (int i = 0; i < all.length; i++) {
            File f = all[i];
            if (f != null && f.isFile() && !isPart(f.getName())) out.add(f);
        }
        return out.toArray(new File[0]);
    }

    public static boolean isPart(String name) {
        return name != null && name.endsWith(PART);
    }

    /* ---------------- 读 ---------------- */

    public String read(String name) throws IOException {
        if (!validName(name)) throw new IOException("IllegalArgument: invalid name");
        File f = file(name);
        if (!f.exists()) throw new IOException("FileNotFound: " + name);
        if (!f.isFile()) throw new IOException("NotAFile: " + name);
        if (f.length() > MAX_READ_BYTES) {
            throw new IOException("TooLarge: " + name + " " + f.length() + " > " + MAX_READ_BYTES);
        }
        return decodeUtf8(readAll(f, name), name);
    }

    /** 严格 UTF-8 解码：非法字节序列直接报错，不静默替换成 U+FFFD。 */
    static String decodeUtf8(byte[] bytes, String label) throws IOException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("BadEncoding: " + label + " is not valid UTF-8");
        }
    }

    /**
     * 读取全部字节。除了读取前的长度检查，还在读取过程中累计校验：
     * 文件在读取期间变大也会被拦住，不会无限分配内存。
     */
    private byte[] readAll(File f, String name) throws IOException {
        long declared = f.length();
        if (declared > MAX_READ_BYTES) {
            throw new IOException("TooLarge: " + name + " " + declared + " > " + MAX_READ_BYTES);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.min(declared + 16, 1 << 20));
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] tmp = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(tmp)) > 0) {
                total += n;
                if (total > MAX_READ_BYTES) {
                    throw new IOException("TooLarge: " + name + " read over " + MAX_READ_BYTES);
                }
                buf.write(tmp, 0, n);
            }
        } finally {
            closeQuietly(in);
        }
        return buf.toByteArray();
    }

    /* ---------------- 写 ---------------- */

    /**
     * 安全保存。返回 {File 正式文件, Long 字节数, String 模式}，
     * 模式为 "unchanged"（内容一致，未改动）/ "replaced"（已替换）。
     */
    public Object[] save(String name, String data) throws IOException {
        if (!validName(name)) throw new IOException("IllegalArgument: invalid name");
        if (data == null) data = "";
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            throw new IOException("TooLarge: " + bytes.length + " > " + MAX_WRITE_BYTES);
        }
        if (name.toLowerCase().endsWith(".json")) checkJsonShape(data, name);

        ensureDir();
        File dst = file(name);
        synchronized (lockFor(dst)) {
            return writeLocked(dst, name, bytes, data);
        }
    }

    private Object[] writeLocked(File dst, String name, byte[] bytes, String data) throws IOException {
        File tmp = new File(dir, name + "." + Long.toHexString(System.nanoTime())
                + "_" + Integer.toHexString((int) (Thread.currentThread().getId() & 0xffff)) + PART);
        boolean moved = false;
        ACTIVE.put(tmp.getAbsolutePath(), Long.valueOf(System.currentTimeMillis()));
        try {
            writeAndSync(tmp, bytes, name);
            faultPoint("after-write");

            /* 等长篡改：长度校验会通过，只有 SHA-256 能发现（用于验证哈希分支真的被执行） */
            if (faultForced("corrupt-same-length")) corruptSameLength(tmp);

            long written = tmp.length();
            if (written != bytes.length) {
                throw new IOException("VerifyFailed: length " + written + " != " + bytes.length + " for " + name);
            }
            String tmpSha = sha256(tmp);
            String wantSha = sha256(bytes);
            if (!tmpSha.equals(wantSha)) {
                throw new IOException("VerifyFailed: sha256 mismatch for " + name);
            }
            String back = decodeUtf8(readAll(tmp, name), name);
            if (!back.equals(data)) {
                throw new IOException("VerifyFailed: content mismatch after write for " + name);
            }

            if (dst.isFile() && dst.length() == written && sha256(dst).equals(tmpSha)) {
                return new Object[] { dst, Long.valueOf(written), "unchanged" };
            }
            faultPoint("before-replace");
            if (!replace(tmp, dst)) {
                throw new IOException("ReplaceFailed: old backup kept, " + name);
            }
            moved = true;
            return new Object[] { dst, Long.valueOf(written), "replaced" };
        } finally {
            ACTIVE.remove(tmp.getAbsolutePath());
            if (!moved) deleteQuietly(tmp);
            cleanupStaleParts(dst);
        }
    }

    /** 保持长度不变，改写尾部一个字节：只会被 SHA-256 / 内容比对发现。 */
    private static void corruptSameLength(File tmp) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(tmp, "rw");
        try {
            long len = raf.length();
            if (len <= 0) throw new IOException("CorruptInject: empty temp file");
            raf.seek(len - 1);
            int b = raf.read();
            raf.seek(len - 1);
            raf.write(b == 'x' ? 'y' : 'x');
        } finally {
            try { raf.close(); } catch (Throwable ignored) { }
        }
    }

    private void writeAndSync(File tmp, byte[] bytes, String name) throws IOException {
        FileOutputStream out = new FileOutputStream(tmp, false);
        try {
            out.write(bytes);
            out.flush();
            faultPoint("sync-fail");
            try {
                out.getFD().sync();
            } catch (IOException e) {
                throw new IOException("SyncFailed: " + name + " " + e.getMessage());
            }
        } finally {
            closeQuietly(out);
        }
    }

    /* ---------------- 原子替换 ---------------- */

    /**
     * 原子替换：先 renameTo（POSIX rename(2)，同目录覆盖目标，全 API 级别可用）；
     * 失败时用反射调 java.nio.file.Files.move(ATOMIC_MOVE)，避免在 API 26 以下触发类加载失败。
     * 任何一条路都不先删除旧文件：失败即保留旧备份。
     */
    private boolean replace(File tmp, File dst) {
        lastReplaceKind = "";
        if (!faultForced("force-nio")) {
            try {
                if (tmp.renameTo(dst)) {
                    lastReplaceKind = "renameTo";
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (nioAtomicMove(tmp, dst)) {
            lastReplaceKind = "nioAtomicMove";
            return true;
        }
        if (!dst.exists()) {
            try {
                if (tmp.renameTo(dst)) {
                    lastReplaceKind = "renameTo-fallback";
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean nioAtomicMove(File tmp, File dst) {
        try {
            Class<?> filesCls = Class.forName("java.nio.file.Files");
            Class<?> pathCls = Class.forName("java.nio.file.Path");
            Class<?> copyOptCls = Class.forName("java.nio.file.CopyOption");
            Class<?> stdOptCls = Class.forName("java.nio.file.StandardCopyOption");
            Object atomic = null;
            Object replaceOpt = null;
            Object[] consts = stdOptCls.getEnumConstants();
            if (consts == null) return false;
            for (int i = 0; i < consts.length; i++) {
                String n = String.valueOf(((Enum<?>) consts[i]).name());
                if ("ATOMIC_MOVE".equals(n)) atomic = consts[i];
                if ("REPLACE_EXISTING".equals(n)) replaceOpt = consts[i];
            }
            if (atomic == null || replaceOpt == null) return false;
            Object opts = java.lang.reflect.Array.newInstance(copyOptCls, 2);
            java.lang.reflect.Array.set(opts, 0, atomic);
            java.lang.reflect.Array.set(opts, 1, replaceOpt);
            java.lang.reflect.Method toPath = File.class.getMethod("toPath");
            Object src = toPath.invoke(tmp);
            Object two = toPath.invoke(dst);
            java.lang.reflect.Method move = filesCls.getMethod("move", pathCls, pathCls,
                    java.lang.reflect.Array.newInstance(copyOptCls, 0).getClass());
            move.invoke(null, src, two, opts);
            return !tmp.exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 清理与删除 ---------------- */

    /**
     * 清理本目录内的过期临时文件。
     * 保护规则：正在写入（已登记 ACTIVE）的临时文件一律跳过，不看年龄；当前目标文件也跳过。
     * 异常不吞成 0：失败计数与错误信息通过返回值带出，便于诊断。
     */
    public Cleanup cleanupStaleParts(File keepDst) {
        int removed = 0, failed = 0;
        String error = null;
        File[] all;
        try {
            all = dir.listFiles();
        } catch (Throwable e) {
            return new Cleanup(0, 0, "ListFailed: " + e.getClass().getSimpleName());
        }
        if (all == null) {
            return new Cleanup(0, 0, dir.exists() ? "ListFailed: listFiles returned null" : null);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length; i++) {
            File f = all[i];
            if (f == null || !f.isFile() || !isPart(f.getName())) continue;
            if (keepDst != null && f.equals(keepDst)) continue;
            if (ACTIVE.containsKey(f.getAbsolutePath())) continue;   /* 活动写入：不按年龄清理 */
            long age;
            try {
                age = now - f.lastModified();
            } catch (Throwable e) {
                failed++;
                if (error == null) error = "StatFailed: " + f.getName();
                continue;
            }
            if (age < PART_TTL_MS) continue;
            try {
                if (f.delete()) {
                    removed++;
                } else {
                    failed++;
                    if (error == null) error = "DeleteFailed: " + f.getName();
                }
            } catch (Throwable e) {
                failed++;
                if (error == null) error = "DeleteFailed: " + f.getName() + " " + e.getClass().getSimpleName();
            }
        }
        return new Cleanup(removed, failed, error);
    }

    /** 当前登记中的活动写入数量（诊断/测试用）。 */
    static int activeWriteCount() {
        return ACTIVE.size();
    }

    /** 删除：不存在返回 false；存在但删不掉抛错；不允许目录与非法名。 */
    public boolean delete(String name) throws IOException {
        if (!validName(name)) throw new IOException("IllegalArgument: invalid name");
        File f = file(name);
        if (!f.exists()) return false;
        if (!f.isFile()) throw new IOException("NotAFile: " + name);
        if (!f.delete()) throw new IOException("DeleteFailed: " + name);
        return true;
    }

    /* ---------------- 校验工具 ---------------- */

    /** JSON 结构粗检（不替代完整解析）：首尾必须是对象括号，引号成对，无非转义控制字符。 */
    static void checkJsonShape(String text, String name) throws IOException {
        int n = text.length();
        int i = 0;
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= n || text.charAt(i) != '{') throw new IOException("BadJson: " + name + " not a JSON object");
        int j = n - 1;
        while (j >= 0 && Character.isWhitespace(text.charAt(j))) j--;
        if (j <= i || text.charAt(j) != '}') throw new IOException("BadJson: " + name + " not closed");
        boolean inStr = false;
        boolean esc = false;
        for (int k = i; k <= j; k++) {
            char c = text.charAt(k);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') { inStr = false; continue; }
                if (c < 0x20) throw new IOException("BadJson: " + name + " raw control char");
            } else {
                if (c == '"') inStr = true;
            }
        }
        if (inStr || esc) throw new IOException("BadJson: " + name + " unterminated string");
    }

    public static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(bytes));
        } catch (Exception e) {
            throw new IOException("HashUnavailable: " + e.getMessage());
        }
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("HashUnavailable: " + e.getMessage());
        }
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            closeQuietly(in);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xff;
            sb.append(Character.forDigit(v >>> 4, 16)).append(Character.forDigit(v & 0xf, 16));
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static Object lockFor(File dst) {
        String key = dst.getAbsolutePath();
        Object lock = LOCKS.get(key);
        if (lock != null) return lock;
        Object created = new Object();
        Object prev = LOCKS.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }
}
