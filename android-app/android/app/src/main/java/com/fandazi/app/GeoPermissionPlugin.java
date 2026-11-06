package com.fandazi.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * 定位权限的显式检查 / 申请 / 跳设置。
 *
 * Capacitor 的 BridgeWebChromeClient 在 WebView 触发定位时也会拉系统权限框，
 * 但它不检查定位服务开关，也无法区分「本次拒绝」与「永久拒绝」。这里在调用前先查一次，
 * 把三种失败拆开交给前端提示；只申请 COARSE + FINE，不申请后台定位。
 */
@CapacitorPlugin(
    name = "GeoPermission",
    permissions = {
        @Permission(
            alias = "location",
            strings = {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        )
    }
)
public class GeoPermissionPlugin extends Plugin {

    private static final String PREFS = "fandazi_geo";
    private static final String KEY_REQUESTED = "requested";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean serviceOn() {
        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /** 申请过、且系统已不再允许弹框（「不再询问」/ 多次拒绝），只能去设置里改 */
    private boolean permanentDenied() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if (!prefs().getBoolean(KEY_REQUESTED, false)) return false;
        Activity act = getActivity();
        if (act == null) return false;
        boolean fineRationale = act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarseRationale = act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION);
        return !fineRationale && !coarseRationale;
    }

    private JSObject stateObject() {
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean ok = fine || coarse;
        boolean permanent = !ok && permanentDenied();
        boolean service = serviceOn();
        JSObject ret = new JSObject();
        ret.put("state", ok ? "granted" : (permanent ? "denied_permanent" : "denied"));
        ret.put("precise", fine);
        ret.put("service", service);
        ret.put("canAsk", !ok && !permanent);
        ret.put("requested", prefs().getBoolean(KEY_REQUESTED, false));
        return ret;
    }

    @PluginMethod
    public void check(PluginCall call) {
        call.resolve(stateObject());
    }

    @PluginMethod
    public void request(PluginCall call) {
        boolean ok = granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (ok || permanentDenied()) {
            call.resolve(stateObject());
            return;
        }
        prefs().edit().putBoolean(KEY_REQUESTED, true).apply();
        requestPermissionForAlias("location", call, "onLocationPermissionResult");
    }

    @PermissionCallback
    private void onLocationPermissionResult(PluginCall call) {
        call.resolve(stateObject());
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("无法打开应用设置页", e);
        }
    }

    @PluginMethod
    public void openLocationSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("无法打开系统定位设置页", e);
        }
    }
}
