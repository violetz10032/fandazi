package com.fandazi.app;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 桌面图标切换：只修改 MainActivityA / MainActivityB 两个 activity-alias 的启用状态。
 *
 * 顺序上先启用目标 alias 并校验，成功后才禁用旧 alias；任一步抛错都会把 A 重新启用、
 * 把 B 禁用，确保桌面始终至少有一个可启动入口。
 */
@CapacitorPlugin(name = "IconAlias")
public class IconAliasPlugin extends Plugin {

    private static final String ALIAS_A = ".MainActivityA";
    private static final String ALIAS_B = ".MainActivityB";

    private ComponentName alias(String suffix) {
        return new ComponentName(getContext().getPackageName(), getContext().getPackageName() + suffix);
    }

    private void setState(ComponentName cn, int state) {
        getContext().getPackageManager().setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
    }

    private boolean isEnabled(ComponentName cn) {
        return getContext().getPackageManager().getComponentEnabledSetting(cn)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void forceA() {
        try { setState(alias(ALIAS_A), PackageManager.COMPONENT_ENABLED_STATE_ENABLED); } catch (Exception ignored) {}
        try { setState(alias(ALIAS_B), PackageManager.COMPONENT_ENABLED_STATE_DISABLED); } catch (Exception ignored) {}
    }

    @PluginMethod
    public void getLauncherIcon(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("style", isEnabled(alias(ALIAS_B)) ? "b" : "a");
        call.resolve(ret);
    }

    @PluginMethod
    public void setLauncherIcon(PluginCall call) {
        String style = call.getString("style", "a");
        boolean toB = "b".equals(style);
        ComponentName target = alias(toB ? ALIAS_B : ALIAS_A);
        ComponentName other = alias(toB ? ALIAS_A : ALIAS_B);
        PackageManager pm = getContext().getPackageManager();
        try {
            // 1) 先启用目标入口；失败时旧入口仍是启用的，桌面不会没有图标
            setState(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            if (!isEnabled(target)) throw new IllegalStateException("target alias not enabled");
            // 2) 校验通过后再禁用旧入口
            setState(other, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            JSObject ret = new JSObject();
            ret.put("style", toB ? "b" : "a");
            call.resolve(ret);
        } catch (Exception e) {
            forceA();
            call.reject("切换桌面图标失败，已回滚到 A（定位碗）", e);
        }
    }
}
