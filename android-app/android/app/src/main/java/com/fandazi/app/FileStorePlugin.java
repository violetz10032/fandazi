package com.fandazi.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.IOException;

/**
 * 自建最小文件插件：绕开 @capacitor/filesystem 在部分机型上的原生异常。
 * 只写应用私有目录 getFilesDir()/fandazi-backups/，不申请任何权限、不写外部存储。
 *
 * 本类只做参数与 JSON 转换：落盘、校验、原子替换、并发保护全部在 FileStoreCore 里，
 * 后者不依赖 Android / Capacitor，可在普通 JVM 上跑故障场景测试。
 *
 * 所有方法自身 try/catch，异常只作为 {ok:false, error} 返回，绝不抛到 Capacitor 线程。
 */
@CapacitorPlugin(name = "FileStore")
public class FileStorePlugin extends Plugin {

    private static final String DIR = "fandazi-backups";

    private static String errText(Throwable e) {
        if (e == null) return "UnknownError";
        String msg = e.getMessage();
        if (msg == null) msg = "";
        String cls = e.getClass().getSimpleName();
        if (msg.length() == 0) return cls;
        return msg.indexOf(':') >= 0 ? msg : cls + ": " + msg;
    }

    private FileStoreCore core() {
        return new FileStoreCore(new File(getContext().getFilesDir(), DIR));
    }

    @PluginMethod
    public void save(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            String name = call.getString("name");
            String data = call.getString("data");
            if (data == null) data = "";
            if (!FileStoreCore.validName(name)) {
                ret.put("ok", false);
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            FileStoreCore c = core();
            Object[] r = c.save(name, data);
            File f = (File) r[0];
            ret.put("ok", true);
            ret.put("path", f.getAbsolutePath());
            ret.put("bytes", r[1]);
            ret.put("verified", true);
            ret.put("mode", r[2]);
            ret.put("replaceKind", c.lastReplaceKind());   /* 现场诊断：renameTo / nioAtomicMove */
        } catch (Throwable e) {
            ret.put("ok", false);
            ret.put("error", errText(e));
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void list(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            FileStoreCore c = core();
            File[] all = c.list();
            JSArray files = new JSArray();
            for (int i = 0; i < all.length; i++) {
                File f = all[i];
                if (f == null || !f.isFile()) continue;
                JSObject item = new JSObject();
                item.put("name", f.getName());
                item.put("bytes", f.length());
                item.put("mtime", f.lastModified());
                files.put(item);
            }
            ret.put("ok", true);
            ret.put("files", files);
            ret.put("dirExists", c.dir().exists());
        } catch (Throwable e) {
            /* 目录存在但读不出：必须报错，不能返回 ok:true + 空列表 */
            ret.put("ok", false);
            ret.put("error", errText(e));
            ret.put("files", new JSArray());
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void read(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            String name = call.getString("name");
            if (!FileStoreCore.validName(name)) {
                ret.put("ok", false);
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            ret.put("ok", true);
            ret.put("data", core().read(name));
        } catch (Throwable e) {
            ret.put("ok", false);
            ret.put("error", errText(e));
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void delete(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            String name = call.getString("name");
            if (!FileStoreCore.validName(name)) {
                ret.put("ok", false);
                ret.put("deleted", false);
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            boolean deleted = core().delete(name);
            ret.put("ok", true);
            ret.put("deleted", deleted);   /* false = 本来就不存在，与"删除失败"区分 */
        } catch (IOException e) {
            ret.put("ok", false);
            ret.put("deleted", false);
            ret.put("error", errText(e));
        } catch (Throwable e) {
            ret.put("ok", false);
            ret.put("deleted", false);
            ret.put("error", errText(e));
        }
        call.resolve(ret);
    }

    /** 诊断：返回最近一次自动清理的真实结果与容量上限。只读，不改任何数据。 */
    @PluginMethod
    public void diag(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            FileStoreCore c = core();
            FileStoreCore.CleanupDiagnostics d = c.cleanupDiagnostics();
            JSObject lc = new JSObject();
            lc.put("at", d.at());
            lc.put("removed", d.removed());
            lc.put("failed", d.failed());
            lc.put("error", d.error() == null ? "" : d.error());
            lc.put("failedName", d.failedName() == null ? "" : d.failedName());
            ret.put("ok", true);
            ret.put("lastCleanup", lc);
            ret.put("maxReadBytes", FileStoreCore.MAX_READ_BYTES);
            ret.put("maxWriteBytes", FileStoreCore.MAX_WRITE_BYTES);
        } catch (Throwable e) {
            ret.put("ok", false);
            ret.put("error", errText(e));
        }
        call.resolve(ret);
    }
}
