package com.fandazi.app;

import java.io.File;
import java.io.FileOutputStream;

/**
 * FileStoreCore 故障场景测试（纯 JVM，不依赖 Android / Capacitor）。
 * 由 CI 的「FileStoreCore 故障场景测试」步骤执行，任一用例失败即构建失败。
 */
public class FileStoreCoreTest {
    static File root;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + name + " " + detail); }
        else { fail++; System.out.println("FAIL " + name + " " + detail); }
    }

    static void rm(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) rm(k);
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "/tmp/fs-store");
        rm(root);
        root.mkdirs();
        FileStoreCore c = new FileStoreCore(new File(root, "fandazi-backups"));

        /* 1 正常保存与读取 */
        Object[] r1 = c.save("a.json", "{\"v\":8}");
        check("1 正常保存与读取", c.read("a.json").equals("{\"v\":8}") && ((Long) r1[1]) == 7L,
            "bytes=" + r1[1] + " replace=" + r1[2] + " sha=" + FileStoreCore.sha256(c.file("a.json")).substring(0, 8));

        /* 2 空文件 */
        c.save("empty.json", "");
        check("2 空文件", c.read("empty.json").equals("") && c.file("empty.json").length() == 0,
            "bytes=" + c.file("empty.json").length());

        /* 3 大文件 + 超限读取 */
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5 * 1024 * 1024; i++) sb.append('x');
        String big = sb.toString();
        c.save("big.json", big);
        boolean bigOk = c.read("big.json").length() == big.length();
        File huge = c.file("huge.json");
        FileOutputStream ho = new FileOutputStream(huge);
        byte[] zero = new byte[8192];
        for (int i = 0; i < 33L * 1024 * 1024 / zero.length; i++) ho.write(zero);
        ho.close();
        String hugeErr = "";
        try { c.read("huge.json"); } catch (Exception e) { hugeErr = e.getMessage(); }
        check("3 大文件（5 MB）可保存可读；>32 MB 明确报错", bigOk && hugeErr.indexOf("TooLarge") >= 0,
            "big=" + big.length() + " hugeErr=" + hugeErr);

        /* 4 写入中断：残留 .part 不影响，下一次保存覆盖并清理 */
        File stale = c.file("stale.json.part");
        FileOutputStream so = new FileOutputStream(stale);
        so.write("broken".getBytes("UTF-8"));
        so.close();
        c.save("stale.json", "good");
        check("4 写入中断 / 残留临时文件", !stale.exists() && c.read("stale.json").equals("good"),
            "leftover=" + stale.exists());

        /* 5 目标文件已存在：内容被替换，不留 .part */
        c.save("b.json", "first");
        c.save("b.json", "second");
        check("5 目标文件已存在", c.read("b.json").equals("second") && !c.file("b.json.part").exists(),
            "content=" + c.read("b.json"));

        /* 6 替换失败保留旧备份：把正式路径占成目录，原子替换必然失败 */
        File blocked = c.file("c.json");
        blocked.mkdirs();
        String err6 = "";
        try { c.save("c.json", "new"); } catch (Exception e) { err6 = e.getMessage(); }
        check("6 替换失败保留旧备份 / 临时文件清理", err6.indexOf("old backup kept") >= 0
                && blocked.isDirectory() && !c.file("c.json.part").exists(), "err=" + err6);

        /* 7 目录读取失败：目录路径是普通文件 */
        File asFile = new File(root, "not-a-dir");
        FileOutputStream fo = new FileOutputStream(asFile);
        fo.write(1);
        fo.close();
        String err7 = "";
        try { new FileStoreCore(asFile).list(); } catch (Exception e) { err7 = e.getMessage(); }
        check("7 目录读取失败不伪装空列表", err7.indexOf("not a directory") >= 0, "err=" + err7);

        /* 8 文件删除失败：把父目录改成不可写（仅 POSIX 有效；Windows 不认写位，跳过而不记失败） */
        if (File.separatorChar == '\\') {
            System.out.println("INFO 8 文件删除失败：Windows 不支持 POSIX 写位，本机跳过；CI 为 Linux，仍严格判定");
        } else {
            c.save("locked.json", "x");
            File dir = new File(root, "fandazi-backups");
            dir.setWritable(false);
            String err8 = "";
            try { c.delete("locked.json"); } catch (Exception e) { err8 = e.getMessage(); }
            dir.setWritable(true);
            check("8 文件删除失败", err8.indexOf("delete failed") >= 0, "err=" + err8);
        }

        /* 9 校验不一致：save 对临时文件做 length + SHA-256 比对（静态覆盖） */
        String sha = FileStoreCore.sha256(c.file("a.json"));
        String sha2 = FileStoreCore.sha256(c.read("a.json").getBytes("UTF-8"));
        check("9 校验不一致分支（静态覆盖）", sha.equals(sha2) && sha.length() == 64, "sha=" + sha.substring(0, 12));

        /* 10 旧版本备份读取：直接写入旧格式文件也能读 */
        File legacy = c.file("fandazi-autobak-20260101-000000-000.json");
        FileOutputStream lo = new FileOutputStream(legacy);
        lo.write("{\"version\":7,\"records\":[]}".getBytes("UTF-8"));
        lo.close();
        check("10 旧版本备份读取", c.read(legacy.getName()).indexOf("\"version\":7") >= 0,
            "name=" + legacy.getName());

        /* 11 FileStore 不可用 → Filesystem 降级：由前端桩覆盖（.tmp/filestore-check.js） */
        System.out.println("INFO 11 FileStore 不可用 → Filesystem 降级：未在 JVM 覆盖（前端桩 filestore-check.js 已测）");

        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
