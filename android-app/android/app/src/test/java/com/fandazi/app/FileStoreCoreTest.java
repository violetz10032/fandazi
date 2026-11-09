package com.fandazi.app;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FileStoreCore 故障场景测试（纯 JVM，不依赖 Android / Capacitor）。
 * 由 CI 的「FileStore 故障场景测试」步骤执行，任一用例失败即构建失败。
 *
 * 约定：依赖 POSIX 写位的用例在 Windows 上跳过并打印 INFO，不计失败（CI 为 Linux，仍严格判定）。
 */
public class FileStoreCoreTest {

    static File root;
    static int pass = 0, fail = 0, skip = 0;
    static final boolean WINDOWS = File.separatorChar == '\\';

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

    interface ThrowingRunnable { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "/tmp/fs-store");
        rm(root);
        root.mkdirs();
        FileStoreCore c = new FileStoreCore(new File(root, "fandazi-backups"));

        /* 1 正常保存与读取 */
        Object[] r1 = c.save("a.json", "{\"v\":8}");
        check("1 正常保存与读取", c.read("a.json").equals("{\"v\":8}") && ((Long) r1[1]) == 7L && "replaced".equals(r1[2]),
                "bytes=" + r1[1] + " mode=" + r1[2] + " sha=" + FileStoreCore.sha256(c.file("a.json")).substring(0, 8));

        /* 2 重复保存同名：内容变化则替换，内容相同则 unchanged 且不重写 */
        c.save("b.json", "{\"v\":1}");
        long m1 = c.file("b.json").lastModified();
        Object[] r2a = c.save("b.json", "{\"v\":2}");
        Object[] r2b = c.save("b.json", "{\"v\":2}");
        check("2 重复保存同名文件", "replaced".equals(r2a[2]) && "unchanged".equals(r2b[2]) && c.read("b.json").equals("{\"v\":2}"),
                "first=" + r2a[2] + " second=" + r2b[2] + " mtimeStable=" + (c.file("b.json").lastModified() >= m1));

        /* 3 空文件：非 JSON 允许；.json 名必须拒绝（空内容不是合法备份） */
        c.save("empty.txt", "");
        String emptyJsonErr = errOf(() -> c.save("empty.json", ""));
        check("3 空文件保存（.txt 允许 / 空 .json 拒绝）",
                c.read("empty.txt").equals("") && c.file("empty.txt").length() == 0 && emptyJsonErr.contains("BadJson"),
                "txtBytes=" + c.file("empty.txt").length() + " jsonErr=" + emptyJsonErr);

        /* 4 大文件（5 MB）可保存可读 */
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

        /* 7 写入中断：残留 .part 不影响，保存后清理过期残留 */
        File stale = c.file("stale.json.abc123.part");
        FileOutputStream so = new FileOutputStream(stale);
        so.write("half-written".getBytes("UTF-8"));
        so.close();
        stale.setLastModified(System.currentTimeMillis() - FileStoreCore.PART_TTL_MS - 5000);
        c.save("stale.json", "{\"v\":3}");
        check("7 写入中断 / 残留临时文件被清理", !stale.exists() && c.read("stale.json").equals("{\"v\":3}"),
                "leftover=" + stale.exists());

        /* 8 写入失败保留旧备份：目标路径被目录占住，原子替换必然失败 */
        File blocked = c.file("c.json");
        blocked.mkdirs();
        String err8 = errOf(() -> c.save("c.json", "{\"v\":9}"));
        check("8 替换失败保留旧备份 / 临时文件清理",
                err8.contains("ReplaceFailed") && blocked.isDirectory() && countParts(c) == 0,
                "err=" + err8 + " parts=" + countParts(c));

        /* 9 JSON 结构校验失败：不替换旧文件、不返回成功 */
        c.save("d.json", "{\"v\":1}");
        String err9a = errOf(() -> c.save("d.json", "{\"v\":2"));
        String err9b = errOf(() -> c.save("d.json", "not json at all"));
        check("9 JSON 结构坏掉时拒绝写入并保留旧文件",
                err9a.contains("BadJson") && err9b.contains("BadJson") && c.read("d.json").equals("{\"v\":1}"),
                "errA=" + err9a + " errB=" + err9b + " kept=" + c.read("d.json"));

        /* 10 读取不存在的文件 */
        String err10 = errOf(() -> c.read("nope.json"));
        check("10 读取不存在文件明确报错", err10.contains("FileNotFound"), "err=" + err10);

        /* 11 读取损坏文件：非法 UTF-8 字节 */
        File badEnc = c.file("badenc.json");
        FileOutputStream bo = new FileOutputStream(badEnc);
        bo.write(new byte[] { '{', '"', (byte) 0xC3, (byte) 0x28, '"', ':', '1', '}' });
        bo.close();
        String err11 = errOf(() -> c.read("badenc.json"));
        check("11 读取损坏文件报编码错误", err11.contains("BadEncoding"), "err=" + err11);

        /* 12 超大文件读取：> 32MB 明确拒绝 */
        File huge = c.file("huge.json");
        FileOutputStream ho = new FileOutputStream(huge);
        byte[] zero = new byte[8192];
        for (int i = 0; i < 33L * 1024 * 1024 / zero.length; i++) ho.write(zero);
        ho.close();
        String err12 = errOf(() -> c.read("huge.json"));
        check("12 超大文件读取被拒绝", err12.contains("TooLarge"), "len=" + huge.length() + " err=" + err12);
        huge.delete();

        /* 13 超大写入：> 32MB 拒绝，不落盘 */
        StringBuilder over = new StringBuilder((int) (FileStoreCore.MAX_WRITE_BYTES + 1024));
        over.append("{\"d\":\"");
        while (over.length() < FileStoreCore.MAX_WRITE_BYTES + 512) over.append('y');
        over.append("\"}");
        String err13 = errOf(() -> c.save("over.json", over.toString()));
        check("13 超大写入被拒绝", err13.contains("TooLarge") && !c.file("over.json").exists(), "err=" + err13);

        /* 14 空目录 / 目录不存在 / 目录不可列 */
        FileStoreCore missing = new FileStoreCore(new File(root, "not-created"));
        FileStoreCore empty = new FileStoreCore(new File(root, "empty-dir"));
        empty.ensureDir();
        int nMissing = missing.list().length, nEmpty = empty.list().length;
        File asFile = new File(root, "not-a-dir");
        FileOutputStream fo = new FileOutputStream(asFile);
        fo.write(1);
        fo.close();
        String err14 = errOf(() -> new FileStoreCore(asFile).list());
        check("14 目录不存在/空目录返回空列表，非目录报错",
                nMissing == 0 && nEmpty == 0 && err14.contains("NotADirectory"),
                "missing=" + nMissing + " empty=" + nEmpty + " err=" + err14);

        /* 15 listFiles() 返回 null：目录不可读时报错，不伪装空列表 */
        if (WINDOWS) {
            info("15 listFiles() 返回 null", "Windows 不支持 POSIX 写位，无法稳定构造不可读目录；CI 为 Linux，仍严格判定");
        } else {
            FileStoreCore noread = new FileStoreCore(new File(root, "noread"));
            noread.ensureDir();
            noread.dir().setReadable(false);
            String err15 = errOf(() -> noread.list());
            noread.dir().setReadable(true);
            check("15 listFiles() 返回 null 时报错", err15.contains("ListFailed") || err15.contains("NotADirectory"),
                    "err=" + err15);
        }

        /* 16 临时文件不得出现在 list() 里 */
        File p16 = c.file("tmp16.json.zzz.part");
        FileOutputStream po = new FileOutputStream(p16);
        po.write("x".getBytes("UTF-8"));
        po.close();
        String[] names = namesOf(c.list());
        int leaked = 0;
        for (String n : names) if (n.endsWith(".part")) leaked++;
        p16.delete();
        check("16 list() 不返回临时文件", leaked == 0 && contains(names, "a.json"), "names=" + names.length + " leaked=" + leaked);

        /* 17 删除：不存在 → deleted:false 且不报错；成功 → true */
        boolean delMissing = c.delete("ghost.json");
        boolean delOk = c.delete("empty.txt");
        check("17 删除不存在与删除成功可区分", delMissing == false && delOk == true && !c.file("empty.txt").exists(),
                "missing=" + delMissing + " deleted=" + delOk);

        /* 18 删除失败：只读目录 */
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

        /* 19 删除非法名（含目录）不得执行 */
        String err19 = errOf(() -> c.delete("../a.json"));
        check("19 删除非法名被拒绝", err19.contains("IllegalArgument"), "err=" + err19);

        /* 20 并发保存同一文件：结果必须是某一次完整内容，且不留临时文件 */
        final FileStoreCore cc = c;
        final int threads = 8;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger okCount = new AtomicInteger();
        final List<String> payloads = new ArrayList<String>();
        for (int i = 0; i < threads; i++) {
            final String payload = "{\"n\":" + i + ",\"pad\":\"" + repeat("p", 4096) + "\"}";
            payloads.add(payload);
        }
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
                "ok=" + okCount.get() + "/" + threads + " intact=" + payloads.contains(finalText) + " parts=" + countParts(c));

        /* 21 旧版本备份（schemaVersion 7）可读 */
        File legacy = c.file("fandazi-autobak-20260101-000000-000.json");
        FileOutputStream lo = new FileOutputStream(legacy);
        lo.write("{\"schemaVersion\":7,\"records\":[]}".getBytes("UTF-8"));
        lo.close();
        check("21 旧版本备份可读", c.read(legacy.getName()).indexOf("\"schemaVersion\":7") >= 0, legacy.getName());

        /* 22 中文与 emoji 内容往返一致（UTF-8 全字节域） */
        String uni = "{\"note\":\"吃饭🍚·测试\",\"emoji\":\"👨‍🍳\"}";
        c.save("uni.json", uni);
        check("22 中文与 emoji 往返一致", c.read("uni.json").equals(uni), "len=" + uni.length());

        System.out.println("TOTAL pass=" + pass + " fail=" + fail + " skip=" + skip);
        if (fail > 0) System.exit(1);
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

    static int countParts(FileStoreCore c) {
        File[] all = c.dir().listFiles();
        if (all == null) return -1;
        int n = 0;
        for (int i = 0; i < all.length; i++) if (all[i] != null && all[i].isFile() && FileStoreCore.isPart(all[i].getName())) n++;
        return n;
    }
}
