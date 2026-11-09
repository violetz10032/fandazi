package com.fandazi.app;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FileStoreCore 故障场景测试（纯 JVM，不依赖 Android / Capacitor）。
 * 由 CI 的「FileStore 故障场景测试」步骤执行，任一用例失败即构建失败。
 *
 * 故障注入通过构造函数传入 FaultHook，按线程生效：生产插件永远不传 hook，
 * 因此不存在「运行时可被外部切换」的调试开关（见第 33 项断言）。
 *
 * 约定：依赖 POSIX 写位的用例在 Windows 上跳过并打印 INFO，不计失败（CI 为 Linux，仍严格判定）。
 */
public class FileStoreCoreTest {

    static File root;
    static FileStoreCore c;
    static int pass = 0, fail = 0, skip = 0;
    static final boolean WINDOWS = File.separatorChar == '\\';

    /** 线程级注入点：只有设置它的线程会受影响，便于并发场景。 */
    static final ThreadLocal<String> POINT = new ThreadLocal<String>();
    static final ThreadLocal<Integer> DELAY_MS = new ThreadLocal<Integer>();

    static final FileStoreCore.FaultHook HOOK = new FileStoreCore.FaultHook() {
        public String point() {
            Integer d = DELAY_MS.get();
            if (d != null && d.intValue() > 0) {
                try { Thread.sleep(d.intValue()); } catch (InterruptedException ignored) { }
            }
            return POINT.get();
        }
    };

    static void check(String name, boolean ok, Object detail) {
        if (ok) { pass++; System.out.println("PASS " + name + " " + detail); }
        else { fail++; System.out.println("FAIL " + name + " " + detail); }
    }

    static void info(String name, String why) {
        skip++;
        System.out.println("INFO " + name + " -> " + why);
    }

    static void rm(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) rm(k);
        f.delete();
    }

    static String errOf(ThrowingRunnable r) {
        try { r.run(); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    /** 在指定注入点执行一次，结束后复位。 */
    static String withPoint(String point, ThrowingRunnable r) {
        POINT.set(point);
        try { return errOf(r); }
        finally { POINT.remove(); DELAY_MS.remove(); }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "/tmp/fs-store");
        rm(root);
        root.mkdirs();
        File dataDir = new File(root, "fandazi-backups");
        c = new FileStoreCore(dataDir, HOOK);

        /* 1 正常保存与读取 */
        Object[] r1 = c.save("a.json", "{\"v\":8}");
        check("1 正常保存与读取", c.read("a.json").equals("{\"v\":8}") && ((Long) r1[1]) == 7L && "replaced".equals(r1[2]),
                "bytes=" + r1[1] + " mode=" + r1[2] + " sha=" + FileStoreCore.sha256(c.file("a.json")).substring(0, 8));

        /* 2 重复保存同名 */
        c.save("b.json", "{\"v\":1}");
        Object[] r2a = c.save("b.json", "{\"v\":2}");
        Object[] r2b = c.save("b.json", "{\"v\":2}");
        check("2 重复保存同名文件", "replaced".equals(r2a[2]) && "unchanged".equals(r2b[2]) && c.read("b.json").equals("{\"v\":2}"),
                "first=" + r2a[2] + " second=" + r2b[2]);

        /* 3 空文件 */
        c.save("empty.txt", "");
        String emptyJsonErr = errOf(() -> c.save("empty.json", ""));
        check("3 空文件保存（.txt 允许 / 空 .json 拒绝）",
                c.read("empty.txt").equals("") && c.file("empty.txt").length() == 0 && emptyJsonErr.contains("BadJson"),
                "txtBytes=" + c.file("empty.txt").length() + " jsonErr=" + emptyJsonErr);

        /* 4 大文件（5 MB） */
        StringBuilder sb = new StringBuilder(5 * 1024 * 1024 + 16);
        sb.append("{\"data\":\"");
        for (int i = 0; i < 5 * 1024 * 1024; i++) sb.append('x');
        sb.append("\"}");
        String big = sb.toString();
        c.save("big.json", big);
        check("4 大文件（5MB）保存与读取", c.read("big.json").length() == big.length(),
                "bytes=" + c.file("big.json").length());

        /* 5 非法文件名 */
        String[] bad = { "", ".hidden", "a/b.json", "a\\b.json", "a:b.json", "a*b.json", "a?b.json",
                "a\"b.json", "a<b.json", "a|b.json", "tab\tname.json", repeat("x", 200) };
        int rejected = 0;
        for (String n : bad) if (!FileStoreCore.validName(n)) rejected++;
        check("5 非法文件名全部拒绝", rejected == bad.length, "rejected=" + rejected + "/" + bad.length);

        /* 6 路径穿越 */
        String[] trav = { "../evil.json", "..", "a/../../b.json", "..\\evil.json", "/etc/passwd", "C:\\x.json" };
        int travRejected = 0;
        for (String n : trav) if (!FileStoreCore.validName(n)) travRejected++;
        String travErr = errOf(() -> c.save("../escape.json", "{\"v\":1}"));
        boolean escaped = new File(root, "escape.json").exists();
        check("6 路径穿越拒绝且不落盘", travRejected == trav.length && !escaped && travErr.contains("IllegalArgument"),
                "rejected=" + travRejected + "/" + trav.length + " escapedFile=" + escaped);

        /* 7 过期残留临时文件被清理 */
        File stale = c.file("stale.json.abc123_1.part");
        writeBytes(stale, "half-written");
        stale.setLastModified(System.currentTimeMillis() - FileStoreCore.PART_TTL_MS - 5000);
        c.save("stale.json", "{\"v\":3}");
        check("7 过期残留临时文件被清理", !stale.exists() && c.read("stale.json").equals("{\"v\":3}"),
                "leftover=" + stale.exists());

        /* 8 替换失败保留旧备份 */
        File blocked = c.file("c.json");
        blocked.mkdirs();
        String err8 = errOf(() -> c.save("c.json", "{\"v\":9}"));
        check("8 替换失败保留旧备份 / 临时文件清理",
                err8.contains("ReplaceFailed") && blocked.isDirectory() && countParts(c) == 0,
                "err=" + err8 + " parts=" + countParts(c));

        /* 9 JSON 结构非法 */
        c.save("d.json", "{\"v\":1}");
        String err9a = errOf(() -> c.save("d.json", "{\"v\":2"));
        String err9b = errOf(() -> c.save("d.json", "not json at all"));
        check("9 JSON 结构坏掉时拒绝写入并保留旧文件",
                err9a.contains("BadJson") && err9b.contains("BadJson") && c.read("d.json").equals("{\"v\":1}"),
                "errA=" + err9a + " errB=" + err9b);

        /* 10 读取不存在 */
        String err10 = errOf(() -> c.read("nope.json"));
        check("10 读取不存在文件明确报错", err10.contains("FileNotFound"), "err=" + err10);

        /* 11 读取损坏（非法 UTF-8） */
        File badEnc = c.file("badenc.json");
        writeBytes(badEnc, new byte[] { '{', '"', (byte) 0xC3, (byte) 0x28, '"', ':', '1', '}' });
        String err11 = errOf(() -> c.read("badenc.json"));
        check("11 读取损坏文件报编码错误", err11.contains("BadEncoding"), "err=" + err11);

        /* 12 超大读取 */
        File huge = c.file("huge.json");
        FileOutputStream ho = new FileOutputStream(huge);
        byte[] zero = new byte[8192];
        for (int i = 0; i < 33L * 1024 * 1024 / zero.length; i++) ho.write(zero);
        ho.close();
        String err12 = errOf(() -> c.read("huge.json"));
        check("12 超大文件读取被拒绝", err12.contains("TooLarge"), "len=" + huge.length());
        huge.delete();

        /* 13 超大写入 */
        StringBuilder over = new StringBuilder((int) (FileStoreCore.MAX_WRITE_BYTES + 1024));
        over.append("{\"d\":\"");
        while (over.length() < FileStoreCore.MAX_WRITE_BYTES + 512) over.append('y');
        over.append("\"}");
        String err13 = errOf(() -> c.save("over.json", over.toString()));
        check("13 超大写入被拒绝", err13.contains("TooLarge") && !c.file("over.json").exists(), "err=" + err13);

        /* 14 目录不存在/空目录/非目录 */
        FileStoreCore missing = new FileStoreCore(new File(root, "not-created"));
        FileStoreCore empty = new FileStoreCore(new File(root, "empty-dir"));
        empty.ensureDir();
        int nMissing = missing.list().length, nEmpty = empty.list().length;
        File asFile = new File(root, "not-a-dir");
        writeBytes(asFile, "x");
        String err14 = errOf(() -> new FileStoreCore(asFile).list());
        check("14 目录不存在/空目录返回空列表，非目录报错",
                nMissing == 0 && nEmpty == 0 && err14.contains("NotADirectory"),
                "missing=" + nMissing + " empty=" + nEmpty + " err=" + err14);

        /* 15 listFiles() 返回 null（POSIX） */
        if (WINDOWS) {
            info("15 listFiles() 返回 null", "Windows 不支持 POSIX 写位；CI 为 Linux，仍严格判定");
        } else {
            FileStoreCore noread = new FileStoreCore(new File(root, "noread"));
            noread.ensureDir();
            noread.dir().setReadable(false);
            String err15 = errOf(() -> noread.list());
            noread.dir().setReadable(true);
            check("15 listFiles() 返回 null 时报错", err15.contains("ListFailed") || err15.contains("NotADirectory"),
                    "err=" + err15);
        }

        /* 16 list() 不返回临时文件 */
        File p16 = c.file("tmp16.json.zzz_1.part");
        writeBytes(p16, "x");
        String[] names = namesOf(c.list());
        int leaked = 0;
        for (String n : names) if (n.endsWith(".part")) leaked++;
        p16.delete();
        check("16 list() 不返回临时文件", leaked == 0 && contains(names, "a.json"), "leaked=" + leaked);

        /* 17 删除语义 */
        boolean delMissing = c.delete("ghost.json");
        boolean delOk = c.delete("empty.txt");
        check("17 删除不存在与删除成功可区分", !delMissing && delOk && !c.file("empty.txt").exists(),
                "missing=" + delMissing + " deleted=" + delOk);

        /* 18 删除失败（POSIX） */
        if (WINDOWS) {
            info("18 删除失败报错", "Windows 不支持 POSIX 写位；CI 为 Linux，仍严格判定");
        } else {
            c.save("locked.json", "{\"v\":1}");
            File dir = c.dir();
            dir.setWritable(false);
            String err18 = errOf(() -> c.delete("locked.json"));
            dir.setWritable(true);
            check("18 删除失败明确报错", err18.contains("DeleteFailed"), "err=" + err18);
        }

        /* 19 删除非法名 */
        String err19 = errOf(() -> c.delete("../a.json"));
        check("19 删除非法名被拒绝", err19.contains("IllegalArgument"), "err=" + err19);

        /* 20 并发保存同一文件 */
        final FileStoreCore cc = c;
        final int threads = 8;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger okCount = new AtomicInteger();
        final List<String> payloads = new ArrayList<String>();
        for (int i = 0; i < threads; i++) payloads.add("{\"n\":" + i + ",\"pad\":\"" + repeat("p", 4096) + "\"}");
        for (int i = 0; i < threads; i++) {
            final String payload = payloads.get(i);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        go.await();
                        cc.save("race.json", payload);
                        okCount.incrementAndGet();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            });
            t.start();
        }
        go.countDown();
        done.await();
        String finalText = c.read("race.json");
        check("20 并发保存同一文件不互相破坏",
                okCount.get() == threads && payloads.contains(finalText) && countParts(c) == 0,
                "ok=" + okCount.get() + "/" + threads + " intact=" + payloads.contains(finalText));

        /* 21 旧版本备份可读 */
        File legacy = c.file("fandazi-autobak-20260101-000000-000.json");
        writeBytes(legacy, "{\"schemaVersion\":7,\"records\":[]}");
        check("21 旧版本备份可读", c.read(legacy.getName()).indexOf("\"schemaVersion\":7") >= 0, legacy.getName());

        /* 22 中文与 emoji 往返 */
        String uni = "{\"note\":\"吃饭🍚·测试\",\"emoji\":\"👨‌‍🍳\"}";
        c.save("uni.json", uni);
        check("22 中文与 emoji 往返一致", c.read("uni.json").equals(uni), "len=" + uni.length());

        /* 23 SHA-256 不一致（等长篡改）：长度校验会过，必须由哈希拦住 */
        c.save("f23.json", "{\"v\":\"old\"}");
        String old23 = c.read("f23.json");
        String err23 = withPoint("corrupt-same-length", () -> c.save("f23.json", "{\"v\":\"new\"}"));
        check("23 等长篡改触发 SHA-256 校验失败并保留旧文件",
                err23.contains("sha256 mismatch") && err23.indexOf("length ") < 0
                        && c.read("f23.json").equals(old23) && countParts(c) == 0,
                "err=" + err23 + " kept=" + c.read("f23.json"));

        /* 24 写入中断 */
        c.save("f24.json", "{\"v\":1}");
        String err24 = withPoint("after-write", () -> c.save("f24.json", "{\"v\":2}"));
        check("24 写入中断后旧文件完好且无残留临时文件",
                err24.contains("InjectedFault") && c.read("f24.json").equals("{\"v\":1}") && countParts(c) == 0,
                "err=" + err24);

        /* 25 fsync 失败 */
        c.save("f25.json", "{\"v\":1}");
        String err25 = withPoint("sync-fail", () -> c.save("f25.json", "{\"v\":2}"));
        check("25 同步落盘失败时旧文件完好",
                err25.contains("InjectedFault") && c.read("f25.json").equals("{\"v\":1}"),
                "err=" + err25);

        /* 26 替换阶段失败 */
        c.save("f26.json", "{\"v\":\"keep\"}");
        String old26 = c.read("f26.json");
        String err26 = withPoint("before-replace", () -> c.save("f26.json", "{\"v\":\"drop\"}"));
        check("26 替换失败时旧备份不被删除也不被改写",
                err26.contains("InjectedFault") && c.read("f26.json").equals(old26) && countParts(c) == 0,
                "err=" + err26 + " kept=" + c.read("f26.json"));

        /* 27 替换机制可追踪 */
        c.save("f27.json", "{\"v\":1}");
        String kind = c.lastReplaceKind();
        check("27 记录实际使用的替换机制", kind != null && kind.length() > 0, "kind=" + kind);

        /* 28 清理只针对过期临时文件 */
        File fresh = c.file("other.json.dead_1.part");
        writeBytes(fresh, "in-progress");
        c.save("f28.json", "{\"v\":1}");
        boolean freshSurvived = fresh.exists();
        fresh.setLastModified(System.currentTimeMillis() - FileStoreCore.PART_TTL_MS - 5000);
        c.save("f28.json", "{\"v\":2}");
        boolean staleRemoved = !fresh.exists();
        check("28 只清理过期临时文件，不动其它任务的新鲜临时文件",
                freshSurvived && staleRemoved, "freshSurvived=" + freshSurvived + " staleRemoved=" + staleRemoved);

        /* 29 旧命名与前端正则兼容 */
        String legacyName = "fandazi-autobak-20261109-120000-123.json";
        c.save(legacyName, "{\"schemaVersion\":8,\"records\":[]}");
        boolean listed = contains(namesOf(c.list()), legacyName);
        boolean reOk = java.util.regex.Pattern.matches(
                "^fandazi-autobak-[0-9]{8}-[0-9]{6}(?:-[0-9]{1,4})?\\.json$", legacyName);
        check("29 旧备份命名可写可列可读并匹配前端过滤正则",
                listed && reOk && c.read(legacyName).contains("\"schemaVersion\":8"),
                "listed=" + listed + " regex=" + reOk);

        /* 30 强制走 nio 降级路径 */
        c.save("f30.json", "{\"v\":1}");
        String kind30;
        boolean ok30 = false;
        String err30 = "";
        POINT.set("force-nio");
        try {
            c.save("f30.json", "{\"v\":2}");
            ok30 = true;
        } catch (Exception e) {
            err30 = String.valueOf(e.getMessage());
        } finally {
            POINT.remove();
            kind30 = c.lastReplaceKind();
        }
        check("30 renameTo 不可用时降级到 nio 原子替换",
                ok30 && c.read("f30.json").equals("{\"v\":2}") && "nioAtomicMove".equals(kind30),
                "ok=" + ok30 + " kind=" + kind30 + " err=" + err30);

        /* 31 并发清理不误删「活动写入」的临时文件（真实并发，年龄已过期） */
        final CountDownLatch slowDone = new CountDownLatch(1);
        final List<String> slowErr = new ArrayList<String>();
        Thread slow = new Thread(new Runnable() {
            public void run() {
                POINT.remove();
                DELAY_MS.set(Integer.valueOf(700));   /* 每次注入点检查停 700ms，制造长任务窗口 */
                try {
                    c.save("slow.json", "{\"v\":\"slow\",\"pad\":\"" + repeat("s", 2048) + "\"}");
                } catch (Throwable e) {
                    synchronized (slowErr) { slowErr.add(String.valueOf(e.getMessage())); }
                } finally {
                    POINT.remove();
                    DELAY_MS.remove();
                    slowDone.countDown();
                }
            }
        });
        slow.start();

        File slowPart = null;
        for (int i = 0; i < 80; i++) {
            slowPart = firstPart();
            if (slowPart != null) break;
            Thread.sleep(20);
        }
        boolean found = slowPart != null;
        if (found) slowPart.setLastModified(System.currentTimeMillis() - FileStoreCore.PART_TTL_MS - 60000);
        FileStoreCore.Cleanup clean = c.cleanupStaleParts(null);
        boolean stillThere = found && slowPart.exists();
        slow.join(10000);
        boolean slowOk = slowDone.getCount() == 0 && c.read("slow.json").indexOf("\"slow\"") > 0;
        check("31 清理不误删活动写入的临时文件（即使已过期），长任务最终成功",
                found && stillThere && clean.removed == 0 && slowOk && slowErr.isEmpty() && countParts(c) == 0,
                "found=" + found + " stillThere=" + stillThere + " removed=" + clean.removed
                        + " slowOk=" + slowOk + " err=" + slowErr);

        /* 32 清理失败必须可诊断（POSIX） */
        if (WINDOWS) {
            info("32 清理失败可诊断", "Windows 不支持 POSIX 写位；CI 为 Linux，仍严格判定");
        } else {
            File stuck = c.file("stuck.json.stuck_1.part");
            writeBytes(stuck, "x");
            stuck.setLastModified(System.currentTimeMillis() - FileStoreCore.PART_TTL_MS - 60000);
            File dir = c.dir();
            dir.setWritable(false);
            FileStoreCore.Cleanup cleanBad = c.cleanupStaleParts(null);
            dir.setWritable(true);
            boolean diag = cleanBad.failed > 0 && cleanBad.error != null && cleanBad.error.indexOf("DeleteFailed") >= 0;
            boolean notSilent = !(cleanBad.removed == 0 && cleanBad.failed == 0 && cleanBad.error == null);
            check("32 清理失败与「没有临时文件」可区分", diag && notSilent,
                    "removed=" + cleanBad.removed + " failed=" + cleanBad.failed + " error=" + cleanBad.error);
            stuck.delete();
        }

        /* 33 生产类中不得存在运行时可切换的故障注入字段 */
        boolean staticFault = false;
        Field[] fields = FileStoreCore.class.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            String n = fields[i].getName();
            if ("FAULT".equals(n) || "faultPoint".equals(n)) {
                staticFault = java.lang.reflect.Modifier.isStatic(fields[i].getModifiers());
            }
        }
        FileStoreCore prod = new FileStoreCore(new File(root, "prod-dir"));
        prod.save("p.json", "{\"v\":1}");
        check("33 故障注入只能由构造函数传入，生产实例无静态开关",
                !staticFault && prod.lastReplaceKind().length() > 0,
                "staticFaultField=" + staticFault + " prodKind=" + prod.lastReplaceKind());

        System.out.println("TOTAL pass=" + pass + " fail=" + fail + " skip=" + skip);
        if (fail > 0) System.exit(1);
    }

    static void writeBytes(File f, String s) throws Exception {
        writeBytes(f, s.getBytes("UTF-8"));
    }

    static void writeBytes(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f, false);
        try { o.write(b); o.flush(); } finally { try { o.close(); } catch (Exception ignored) { } }
    }

    static File firstPart() {
        File[] all = c.dir().listFiles();
        if (all == null) return null;
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null && all[i].isFile() && FileStoreCore.isPart(all[i].getName())) return all[i];
        }
        return null;
    }

    static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    static boolean contains(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (v.equals(arr[i])) return true;
        return false;
    }

    static String[] namesOf(File[] files) {
        String[] out = new String[files.length];
        for (int i = 0; i < files.length; i++) out[i] = files[i].getName();
        return out;
    }

    static int countParts(FileStoreCore core) {
        File[] all = core.dir().listFiles();
        if (all == null) return -1;
        int n = 0;
        for (int i = 0; i < all.length; i++) if (all[i] != null && all[i].isFile() && FileStoreCore.isPart(all[i].getName())) n++;
        return n;
    }
}
