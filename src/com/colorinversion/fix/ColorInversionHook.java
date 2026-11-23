package com.colorinversion.fix;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 颜色反转修复：在 system_server 中兜底驱动「无障碍颜色反转」。
 *
 * 背景（Android 16/17 镜像实测反编译结论）：
 *   Settings.Secure.accessibility_display_inversion_enabled
 *     -> ColorDisplayService.onAccessibilityInversionChanged()
 *     -> DisplayTransformManager.setColorMatrix(300, MATRIX_INVERT_COLOR | null)
 *     -> DisplayTransformManager.applyColorMatrix() -> SurfaceFlinger.transact(1015)
 *
 * v1.1 实机结论（努比亚 Z60 Ultra 刷小米 14 HyperOS 4 / Android 17）：
 *   设置 key 写入正常、CDS 观察者与 DTM 调用均执行且返回成功，但 SF 侧始终收不到矩阵；
 *   用 `service call SurfaceFlinger 1015` 直接写入则立即反色。
 * 因此本模块改为：自行监听设置 + 在开关/开机/用户切换时直接向 SF 写 1015，
 * DTM 仅做状态同步（不依赖其下发结果）。
 */
public class ColorInversionHook implements IXposedHookLoadPackage {

    private static final String TAG = "[ColorInversionFix] ";

    private static final String KEY_INVERSION = "accessibility_display_inversion_enabled";
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final int LEVEL_COLOR_MATRIX_INVERT_COLOR = 300;
    private static final int SF_TRANSACTION_COLOR_MATRIX = 1015;

    private static final String CLASS_DTM = "com.android.server.display.color.DisplayTransformManager";
    private static final String CLASS_DTM_ALT = "com.android.server.wm.DisplayTransformManager";
    private static final String CLASS_CDS = "com.android.server.display.color.ColorDisplayService";
    private static final String CLASS_LOCAL_SERVICES = "com.android.server.LocalServices";

    /** 从 HyperOS 3（Android 16，NX721J）services.jar 的 ColorDisplayService.MATRIX_INVERT_COLOR 提取的兜底值。 */
    private static final float[] FALLBACK_MATRIX = new float[]{
            0.40200001f, -0.59799999f, -0.598999977f, 0f,
            -1.17400002f, -0.173999995f, -1.17499995f, 0f,
            -0.228f, -0.228f, 0.772000015f, 0f,
            1f, 1f, 1f, 1f
    };

    private static volatile boolean sInitStarted;
    private static volatile boolean sInitDone;
    private static int sInitAttempts;
    private static ClassLoader sClassLoader;
    private static Context sSystemContext;
    private static Handler sMainHandler;
    private static float[] sInversionMatrix;
    private static boolean sDtmHooked;
    private static Class<?> sDtmClass;
    private static String sDtmClassName = CLASS_DTM;
    private static volatile Object sCdsInstance;
    /** 记录我们是否已把反转矩阵写入 SF，关闭时据此决定是否需要清除。 */
    private static volatile boolean sSfMatrixWritten;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        sClassLoader = lpparam.classLoader;
        log("loaded in process=" + lpparam.processName + " firstApplication=" + lpparam.isFirstApplication);

        try {
            // 仅用于诊断：记录 ColorDisplayService 的 mCurrentUser。
            // 实机发现该 ROM 上存在 mCurrentUser == USER_NULL 导致反转被直接 return 的可能。
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(CLASS_CDS, sClassLoader),
                    "onStart",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sCdsInstance = param.thisObject;
                            log("ColorDisplayService.onStart mCurrentUser=" + describeCdsUser());
                        }
                    });
        } catch (Throwable t) {
            log("hook ColorDisplayService.onStart failed: " + t);
        }

        try {
            // system_server 启动流程：尽量早拿到系统 Context
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.app.ActivityThread", sClassLoader),
                    "systemMain",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            initSystemServer("ActivityThread.systemMain");
                        }
                    });
        } catch (Throwable t) {
            log("hook ActivityThread.systemMain failed: " + t);
        }

        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("com.android.server.SystemServer", sClassLoader),
                    "startOtherServices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            initSystemServer("SystemServer.startOtherServices");
                        }
                    });
        } catch (Throwable t) {
            log("hook SystemServer.startOtherServices failed: " + t);
        }
    }

    private static synchronized void initSystemServer(String source) {
        if (sInitDone || sInitStarted) {
            return;
        }
        if (sClassLoader == null) {
            return;
        }
        Context ctx = null;
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", sClassLoader),
                    "currentActivityThread");
            if (activityThread != null) {
                ctx = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
            }
        } catch (Throwable t) {
            log("getSystemContext failed: " + t);
        }
        if (ctx == null) {
            // 系统 Context 尚未就绪，主线程延后重试
            sInitAttempts++;
            if (sInitAttempts <= 10) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initSystemServer("retry#" + sInitAttempts);
                    }
                }, 1000L);
            } else {
                log("system context unavailable after retries, give up");
            }
            return;
        }

        sInitStarted = true;
        sSystemContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sMainHandler = new Handler(Looper.getMainLooper());
        log("init from " + source + ", system context ready");

        try {
            installSettingsObserver();
        } catch (Throwable t) {
            log("installSettingsObserver failed: " + t);
        }
        try {
            installBootReceiver();
        } catch (Throwable t) {
            log("installBootReceiver failed: " + t);
        }
        try {
            hookDisplayTransformManager();
        } catch (Throwable t) {
            log("hookDisplayTransformManager failed: " + t);
        }

        // 立即应用一次，并在系统服务全部就绪后再补两次
        applyState("init");
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyState("init+3s");
            }
        }, 3000L);
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyState("init+15s");
            }
        }, 15000L);

        sInitDone = true;
    }

    private static void installSettingsObserver() {
        ContentResolver resolver = sSystemContext.getContentResolver();
        ContentObserver observer = new ContentObserver(sMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                applyState("settings-observer");
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                applyState("settings-observer " + uri);
            }
        };
        resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_INVERSION), false, observer);
        log("settings observer registered: " + KEY_INVERSION);
    }

    private static void installBootReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    applyState("boot-completed");
                    sMainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyState("boot-completed+8s");
                        }
                    }, 8000L);
                } else if (ACTION_USER_SWITCHED.equals(action)) {
                    applyState("user-switched");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(ACTION_USER_SWITCHED);
        sSystemContext.registerReceiver(receiver, filter);
        log("boot/user-switch receiver registered");
    }

    private static void hookDisplayTransformManager() {
        Class<?> dtmClass = null;
        for (String name : new String[]{CLASS_DTM, CLASS_DTM_ALT}) {
            try {
                dtmClass = XposedHelpers.findClass(name, sClassLoader);
                sDtmClassName = name;
                break;
            } catch (Throwable t) {
                log("class not found: " + name);
            }
        }
        sDtmClass = dtmClass;
        if (dtmClass == null) {
            log("DisplayTransformManager class not found, will use SurfaceFlinger fallback");
        } else {
            try {
                XposedHelpers.findAndHookMethod(dtmClass, "setColorMatrix", int.class, float[].class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length < 2) {
                                    return;
                                }
                                Object levelObj = param.args[0];
                                if (!(levelObj instanceof Integer)
                                        || ((Integer) levelObj) != LEVEL_COLOR_MATRIX_INVERT_COLOR) {
                                    return;
                                }
                                boolean enabled = isInversionEnabled();
                                Object value = param.args[1];
                                if (enabled && value == null) {
                                    param.args[1] = getInversionMatrix();
                                    log("setColorMatrix(300,null) corrected -> matrix");
                                } else if (!enabled && value != null) {
                                    param.args[1] = null;
                                    log("setColorMatrix(300,matrix) corrected -> null");
                                } else {
                                    log("setColorMatrix(300) enabled=" + enabled
                                            + " len=" + (value instanceof float[] ? ((float[]) value).length : -1));
                                }
                            }
                        });
                sDtmHooked = true;
                log("hook " + sDtmClassName + ".setColorMatrix OK");
            } catch (Throwable t) {
                log("hook " + sDtmClassName + ".setColorMatrix failed: " + t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_CDS, sClassLoader,
                    "onAccessibilityInversionChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("ColorDisplayService.onAccessibilityInversionChanged called, enabled="
                                    + isInversionEnabled() + ", mCurrentUser=" + describeCdsUser());
                        }
                    });
            log("hook ColorDisplayService.onAccessibilityInversionChanged OK");
        } catch (Throwable t) {
            log("hook ColorDisplayService.onAccessibilityInversionChanged failed: " + t);
        }

        try {
            // ROM 内方法名拼写即为 isAccessiblityInversionEnabled（缺 i），仅用于诊断日志
            XposedHelpers.findAndHookMethod(CLASS_CDS, sClassLoader,
                    "isAccessiblityInversionEnabled",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("ColorDisplayService.isAccessiblityInversionEnabled() -> " + param.getResult());
                        }
                    });
        } catch (Throwable t) {
            log("hook ColorDisplayService.isAccessiblityInversionEnabled failed: " + t);
        }
    }

    /**
     * 读取当前用户的颜色反转开关并强制下发。
     */
    private static void applyState(String reason) {
        Context ctx = sSystemContext;
        if (ctx == null) {
            log("applyState(" + reason + ") skipped: no context");
            return;
        }
        try {
            int user = getCurrentUser();
            boolean enabled = readInversionSetting(user) != 0;
            float[] matrix = enabled ? getInversionMatrix() : null;

            // 1) 仅做框架状态同步：该移植 ROM 上 DTM.setColorMatrix 调用会返回成功但实际不下发，
            //    因此不再依赖它，真正的显示矩阵由下一步直接写 SurfaceFlinger。
            boolean dtmSynced = false;
            try {
                Object dtm = getDisplayTransformManager();
                if (dtm != null) {
                    XposedHelpers.callMethod(dtm, "setColorMatrix",
                            LEVEL_COLOR_MATRIX_INVERT_COLOR, matrix);
                    dtmSynced = true;
                }
            } catch (Throwable t) {
                log("DTM sync failed: " + t);
            }

            // 2) 主路径：直接向 SurfaceFlinger 写 1015（实机验证有效）。
            //    关闭时只有在我们确实写过矩阵后才清除，避免误清其他颜色变换。
            boolean sfWritten;
            if (enabled) {
                sfWritten = setSurfaceFlingerColorMatrix(matrix);
                if (sfWritten) {
                    sSfMatrixWritten = true;
                }
            } else if (sSfMatrixWritten) {
                sfWritten = setSurfaceFlingerColorMatrix(null);
                if (sfWritten) {
                    sSfMatrixWritten = false;
                }
            } else {
                sfWritten = true;
            }

            log("applyState(" + reason + ") user=" + user + " enabled=" + enabled
                    + " sfWritten=" + sfWritten + " dtmSynced=" + dtmSynced
                    + " matrixLen=" + (matrix == null ? -1 : matrix.length));
        } catch (Throwable t) {
            log("applyState(" + reason + ") failed: " + t);
        }
    }

    private static boolean isInversionEnabled() {
        return readInversionSetting(getCurrentUser()) != 0;
    }

    private static String describeCdsUser() {
        Object cds = sCdsInstance;
        if (cds == null) {
            return "unknown(no instance)";
        }
        try {
            int user = XposedHelpers.getIntField(cds, "mCurrentUser");
            return String.valueOf(user) + (user == -10000 ? " (USER_NULL!)" : "");
        } catch (Throwable t) {
            return "unknown(" + t + ")";
        }
    }

    /**
     * Settings.Secure.getIntForUser 为 @hide API，编译期不可见，运行时反射调用；
     * 反射失败时退回 getInt（system_server 通常为主用户 0）。
     */
    private static int readInversionSetting(int user) {
        Context ctx = sSystemContext;
        if (ctx == null) {
            return 0;
        }
        ContentResolver resolver = ctx.getContentResolver();
        try {
            java.lang.reflect.Method method = Settings.Secure.class.getMethod(
                    "getIntForUser", ContentResolver.class, String.class, int.class, int.class);
            Object value = method.invoke(null, resolver, KEY_INVERSION, 0, user);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable t) {
            log("getIntForUser reflection failed: " + t);
        }
        try {
            return Settings.Secure.getInt(resolver, KEY_INVERSION, 0);
        } catch (Throwable t) {
            log("read inversion setting failed: " + t);
            return 0;
        }
    }

    private static int getCurrentUser() {
        try {
            Object user = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityManager", sClassLoader),
                    "getCurrentUser");
            if (user instanceof Integer) {
                return (Integer) user;
            }
        } catch (Throwable t) {
            log("getCurrentUser failed: " + t);
        }
        return 0;
    }

    private static Object getDisplayTransformManager() {
        Class<?> serviceClass = sDtmClass;
        if (serviceClass == null) {
            for (String name : new String[]{CLASS_DTM, CLASS_DTM_ALT}) {
                try {
                    serviceClass = XposedHelpers.findClass(name, sClassLoader);
                    sDtmClassName = name;
                    sDtmClass = serviceClass;
                    break;
                } catch (Throwable ignored) {
                    // 继续尝试下一个候选类名
                }
            }
        }
        if (serviceClass == null) {
            return null;
        }
        Class<?> localServices = XposedHelpers.findClass(CLASS_LOCAL_SERVICES, sClassLoader);
        return XposedHelpers.callStaticMethod(localServices, "getService", serviceClass);
    }

    private static float[] getInversionMatrix() {
        float[] matrix = sInversionMatrix;
        if (matrix != null) {
            return matrix;
        }
        try {
            Class<?> cdsClass = XposedHelpers.findClass(CLASS_CDS, sClassLoader);
            Object value = XposedHelpers.getStaticObjectField(cdsClass, "MATRIX_INVERT_COLOR");
            if (value instanceof float[] && (((float[]) value).length == 16
                    || ((float[]) value).length == 20)) {
                matrix = ((float[]) value).clone();
                log("read MATRIX_INVERT_COLOR from framework, len=" + matrix.length);
            }
        } catch (Throwable t) {
            log("read MATRIX_INVERT_COLOR failed: " + t);
        }
        if (matrix == null) {
            matrix = FALLBACK_MATRIX.clone();
            log("use built-in fallback inversion matrix");
        }
        sInversionMatrix = matrix;
        return matrix;
    }

    /**
     * 主路径：直接向 SurfaceFlinger 写颜色矩阵（与框架 1015 事务一致）。
     * 实机（Android 17 移植包）验证：DTM.setColorMatrix 返回成功但 SF 未收到矩阵，
     * 而本方法直接 transact 能立即生效。
     * 注意：该路径不与夜间模式/灰度矩阵做合成，开启反转时会覆盖其他颜色变换。
     *
     * @return transact 是否返回 true
     */
    private static boolean setSurfaceFlingerColorMatrix(float[] matrix) {
        try {
            Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", sClassLoader);
            IBinder flinger = (IBinder) XposedHelpers.callStaticMethod(serviceManager,
                    "getService", "SurfaceFlinger");
            if (flinger == null) {
                log("SF 1015 skipped: SurfaceFlinger binder is null");
                return false;
            }
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                if (matrix != null) {
                    data.writeInt(1);
                    for (float v : matrix) {
                        data.writeFloat(v);
                    }
                } else {
                    data.writeInt(0);
                }
                boolean ok = flinger.transact(SF_TRANSACTION_COLOR_MATRIX, data, null, 0);
                log("SF 1015 " + (matrix == null ? "clear" : "set len=" + matrix.length)
                        + " transact=" + ok);
                return ok;
            } finally {
                data.recycle();
            }
        } catch (Throwable t) {
            log("SF 1015 failed: " + t);
            return false;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
