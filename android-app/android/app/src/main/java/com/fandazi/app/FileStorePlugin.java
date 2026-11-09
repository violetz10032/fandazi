package com.fandazi.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 自建最小文件插件：绕开 @capacitor/filesystem 在部分机型上的原生异常。
 * 只写应用私有目录 getFilesDir()/fandazi-backups/，不申请任何权限、不写外部存储。
 * 所有方法自身 try/catch，异常只作为 {ok:false, error} 返回，绝不抛到 Capacitor 线程。
 */
@CapacitorPlugin(name = "FileStore")
public class FileStorePlugin extends Plugin {

    private static final String DIR = "fandazi-backups";

    private static String errText(Throwable e) {
        if (e == null) return "UnknownError";
        String msg = e.getMessage();
        return e.getClass().getName() + (msg == null ? "" : ": " + msg);
    }

    private static boolean validName(String name) {
        if (name == null || name.length() == 0 || name.length() > 200) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        return true;
    }

    private File dir() {
        return new File(getContext().getFilesDir(), DIR);
    }

    @PluginMethod
    public void save(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            String name = call.getString("name");
            String data = call.getString("data");
            if (data == null) data = "";
            if (!validName(name)) {
                ret.put("ok", false);
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            File d = dir();
            if (!d.exists() && !d.mkdirs() && !d.exists()) {
                ret.put("ok", false);
                ret.put("error", "IOException: unable to create " + d.getAbsolutePath());
                call.resolve(ret);
                return;
            }
            File f = new File(d, name);
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            ret.put("ok", true);
            ret.put("path", f.getAbsolutePath());
            ret.put("bytes", bytes.length);
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
            File d = dir();
            File[] all = d.exists() ? d.listFiles() : null;
            if (all != null) {
                for (File f : all) {
                    if (f == null || !f.isFile()) continue;
                    JSObject item = new JSObject();
                    item.put("name", f.getName());
                    item.put("bytes", f.length());
                    item.put("mtime", f.lastModified());
                    files.put(item);
                }
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
            if (!validName(name)) {
                ret.put("ok", false);
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            File f = new File(dir(), name);
            if (!f.exists()) {
                ret.put("ok", false);
                ret.put("error", "FileNotFound: " + name);
                call.resolve(ret);
                return;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            ret.put("ok", true);
            ret.put("data", new String(buf.toByteArray(), StandardCharsets.UTF_8));
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
            if (!validName(name)) {
                ret.put("ok", false);
                ret.put("error", "IllegalArgument: invalid name");
                call.resolve(ret);
                return;
            }
            File f = new File(dir(), name);
            if (!f.exists()) {
                ret.put("ok", true);
                ret.put("deleted", false);
                call.resolve(ret);
                return;
            }
            boolean ok = f.delete();
            ret.put("ok", ok);
            ret.put("deleted", ok);
            if (!ok) ret.put("error", "IOException: delete failed " + name);
        } catch (Throwable e) {
            ret.put("ok", false);
            ret.put("error", errText(e));
        }
        call.resolve(ret);
    }
}
