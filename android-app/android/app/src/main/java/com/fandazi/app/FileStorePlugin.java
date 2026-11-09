package com.fandazi.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

/**
 * 自建最小文件插件：绕开 @capacitor/filesystem 在部分机型上的原生异常。
 * 只写应用私有目录 getFilesDir()/fandazi-backups/，不申请任何权限、不写外部存储。
 * 所有方法自身 try/catch，异常只作为 {ok:false, error} 返回，绝不抛到 Capacitor 线程。
 *
 * save 由 FileStoreCore 实现：临时文件 → fsync → 读回比对字节数 / SHA-256 → 原子替换；
 * 替换失败保留旧备份；list 在目录存在但无法列出时报失败，不返回成功空列表。
 */
@CapacitorPlugin(name = "FileStore")
public class FileStorePlugin extends Plugin {

    private static final String DIR = "fandazi-backups";

    private static String errText(Throwable e) {
        if (e == null) return "UnknownError";
        String msg = e.getMessage();
        return e.getClass().getName() + (msg == null ? "" : ": " + msg);
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
            Object[] r = core().save(name, data);
            File f = (File) r[0];
            ret.put("ok", true);
            ret.put("path", f.getAbsolutePath());
            ret.put("bytes", r[1]);
            ret.put("verified", true);
            ret.put("replace", r[2]);
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
            JSArray files = new JSArray();
            File[] all = core().list();
            for (File f : all) {
                if (f == null || !f.isFile()) continue;
                if (f.getName().endsWith(FileStoreCore.TMP_SUFFIX)) continue;
                JSObject item = new JSObject();
                item.put("name", f.getName());
                item.put("bytes", f.length());
                item.put("mtime", f.lastModified());
                files.put(item);
            }
            ret.put("ok", true);
            ret.put("files", files);
        } catch (Throwable e) {
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
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            boolean deleted = core().delete(name);
            ret.put("ok", true);
            ret.put("deleted", deleted);
        } catch (Throwable e) {
            ret.put("ok", false);
            ret.put("deleted", false);
            ret.put("error", errText(e));
        }
        call.resolve(ret);
    }
}
