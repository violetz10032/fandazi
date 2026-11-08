package com.fandazi.app;

import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 原生未捕获异常留痕：只写应用私有目录（getFilesDir()），不申请任何权限、不改数据结构。
 * 下次启动时把最近一条崩溃的第一行以 Toast 提示；全文留在 diag-crash.txt 里备查。
 */
public class MainActivity extends BridgeActivity {

    public static final String DIAG_BOOT = "diag-boot.txt";
    public static final String DIAG_CRASH = "diag-crash.txt";
    public static final String DIAG_CRASH_LOG = "diag-crash-log.txt";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        installCrashHandler();
        writeText(DIAG_BOOT, bootLine());
        String lastCrash = readText(DIAG_CRASH);
        if (lastCrash.length() > 0) {
            try {
                Toast.makeText(getApplicationContext(), "上次崩溃：\n" + firstLine(lastCrash), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
        registerPlugin(IconAliasPlugin.class);
        registerPlugin(GeoPermissionPlugin.class);
        super.onCreate(savedInstanceState);
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    String text = stamp() + " thread=" + thread.getName() + "\n" + stack(ex);
                    writeText(DIAG_CRASH, text);
                    appendText(DIAG_CRASH_LOG, text + "\n---\n");
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(thread, ex);
            }
        });
    }

    private String bootLine() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return stamp() + " boot version=" + ver + " sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE + " model=" + Build.MODEL
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String stack(Throwable ex) {
        if (ex == null) return "(null)";
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String firstLine(String text) {
        int i = text.indexOf('\n');
        return i < 0 ? text : text.substring(0, i);
    }

    private File file(String name) {
        return new File(getFilesDir(), name);
    }

    private String readText(String name) {
        try {
            File f = file(name);
            if (!f.exists()) return "";
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    private void writeText(String name, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(name), false);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void appendText(String name, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(name), true);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
