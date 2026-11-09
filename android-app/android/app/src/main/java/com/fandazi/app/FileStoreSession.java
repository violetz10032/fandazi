package com.fandazi.app;

import java.io.File;

/**
 * 每个插件实例持有一份 FileStoreCore，保证 save 产生的诊断能被后续 diag 读到。
 *
 * 设计要点：
 * - 状态挂在<b>实例</b>上，不用静态可变状态（避免跨进程/跨插件实例串味）；
 * - 懒加载 + 双重检查，volatile 保证可见性；
 * - 可注入 FaultHook（仅测试用），生产插件构造时不传。
 */
final class FileStoreSession {

    private final File dir;
    private final FileStoreCore.FaultHook fault;
    private volatile FileStoreCore core;

    FileStoreSession(File dir) {
        this(dir, null);
    }

    FileStoreSession(File dir, FileStoreCore.FaultHook fault) {
        this.dir = dir;
        this.fault = fault;
    }

    /** 线程安全的懒加载：始终返回同一个 FileStoreCore。 */
    FileStoreCore core() {
        FileStoreCore c = core;
        if (c == null) {
            synchronized (this) {
                c = core;
                if (c == null) {
                    c = new FileStoreCore(dir, fault);
                    core = c;
                }
            }
        }
        return c;
    }

    /** 是否已经初始化（测试用，判断懒加载是否真的延迟）。 */
    boolean initialized() {
        return core != null;
    }

    File dir() {
        return dir;
    }
}
