package com.example.lsposed_template;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.ss.android.ugc.aweme";
    static final String TAG = "CJMITM";
    private static final String NATIVE_LIBRARY = "lsposed_template";
    private static final AtomicBoolean APPLICATION_ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean NATIVE_LIBRARY_LOADED = new AtomicBoolean(false);
    private static final Set<String> HOOKED_CLASSES =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<String>());
    private static volatile FileLogger fileLogger;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) {
            return;
        }
        hookApplicationAttachForLogger(lpparam);
        loadNativeMonitor(lpparam.processName);
        log("loaded package=" + lpparam.packageName + " process=" + lpparam.processName
                + " cl=" + lpparam.classLoader);
        hookAndroidBase();
        hookKnownClasses(lpparam.classLoader);
        hookClassLoader();
    }

    private static void hookApplicationAttachForLogger(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!APPLICATION_ATTACH_HOOKED.compareAndSet(false, true)) {
            return;
        }
        tryHook("Application.attach logger", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Application.class,
                        "attach",
                        Context.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Context context = (Context) param.args[0];
                                    FileLogger logger = FileLogger.create(context, lpparam.processName);
                                    if (logger == null) {
                                        return;
                                    }
                                    fileLogger = logger;
                                    logger.writeJava(Log.INFO,
                                            TAG,
                                            "MainHook",
                                            0,
                                            "Application.attach logger ready process="
                                                    + lpparam.processName
                                                    + " path="
                                                    + logger.getLogFile().getAbsolutePath());
                                    if (NATIVE_LIBRARY_LOADED.get()) {
                                        NativeBridge.setLogger(logger);
                                    } else {
                                        loadNativeMonitor(lpparam.processName);
                                    }
                                } catch (Throwable t) {
                                    Log.e(TAG, "failed to initialize file logger", t);
                                    XposedBridge.log("[" + TAG + "] failed to initialize file logger: " + t);
                                }
                            }
                        });
            }
        });
    }

    private static void loadNativeMonitor(String processName) {
        if (!NATIVE_LIBRARY_LOADED.compareAndSet(false, true)) {
            return;
        }
        try {
            System.loadLibrary(NATIVE_LIBRARY);
            FileLogger logger = fileLogger;
            if (logger != null) {
                NativeBridge.setLogger(logger);
            }
            log("native so load monitor loaded library=" + NATIVE_LIBRARY
                    + " process=" + processName);
        } catch (Throwable t) {
            NATIVE_LIBRARY_LOADED.set(false);
            log("failed to load native so monitor library=" + NATIVE_LIBRARY
                    + " process=" + processName + " error=" + t);
        }
    }

    private static void hookAndroidBase() {
        tryHook("Activity.onCreate", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Activity a = (Activity) param.thisObject;
                                if (isInteresting(a.getClass().getName()) || isInteresting(a.getIntent())) {
                                    log("Activity.onCreate " + a.getClass().getName()
                                            + " intent=" + clip(a.getIntent())
                                            + " extras=" + intentExtras(a.getIntent()));
                                }
                            }
                        });
            }
        });
        tryHook("Activity.onNewIntent", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Activity a = (Activity) param.thisObject;
                                if (isInteresting(a.getClass().getName()) || isInteresting(param.args[0])) {
                                    log("Activity.onNewIntent " + a.getClass().getName()
                                            + " intent=" + clip(param.args[0])
                                            + " extras=" + intentExtras((Intent) param.args[0]));
                                }
                            }
                        });
            }
        });
        tryHook("Activity.onResume", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Activity a = (Activity) param.thisObject;
                                if (isInteresting(a.getClass().getName()) || isInteresting(a.getIntent())) {
                                    dumpActivityState(a, "Activity.onResume");
                                }
                            }
                        });
            }
        });
        tryHook("Activity.setIntent", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Activity.class, "setIntent", Intent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Activity a = (Activity) param.thisObject;
                                if (isInteresting(a.getClass().getName()) || isInteresting(param.args[0])) {
                                    log("Activity.setIntent " + a.getClass().getName()
                                            + " intent=" + clip(param.args[0])
                                            + " extras=" + intentExtras((Intent) param.args[0]));
                                }
                            }
                        });
            }
        });
        tryHook("Activity.startActivity", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Activity.class, "startActivity", Intent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (isInteresting(param.args[0])) {
                                    log("Activity.startActivity from="
                                            + param.thisObject.getClass().getName()
                                            + " intent=" + clip(param.args[0])
                                            + " extras=" + intentExtras((Intent) param.args[0]));
                                }
                            }
                        });
            }
        });
        tryHook("ContextWrapper.startActivity", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(ContextWrapper.class, "startActivity", Intent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (isInteresting(param.args[0])) {
                                    log("ContextWrapper.startActivity from="
                                            + param.thisObject.getClass().getName()
                                            + " intent=" + clip(param.args[0])
                                            + " extras=" + intentExtras((Intent) param.args[0]));
                                }
                            }
                        });
            }
        });
        hookIntentClass();
        hookWebViewClass(WebView.class);
    }

    private static void hookIntentClass() {
        tryHook("Intent.getStringExtra", new HookAction() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod(Intent.class, "getStringExtra", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (isInteresting(param.args) || isInteresting(param.getResult())) {
                                    log("Intent.getStringExtra key=" + clip(param.args[0])
                                            + " result=" + clip(param.getResult())
                                            + " intent=" + clip(param.thisObject));
                                }
                            }
                        });
            }
        });
        tryHook("Bundle.getString", new HookAction() {
            @Override
            public void run() {
                Class<?> baseBundle = XposedHelpers.findClassIfExists(
                        "android.os.BaseBundle", Bundle.class.getClassLoader());
                if (baseBundle == null) {
                    log("BaseBundle class not found");
                    return;
                }
                XposedHelpers.findAndHookMethod(baseBundle, "getString", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (isInteresting(param.args) || isInteresting(param.getResult())) {
                                    log("Bundle.getString key=" + clip(param.args[0])
                                            + " result=" + clip(param.getResult()));
                                }
                            }
                        });
            }
        });
    }

    private static void hookKnownClasses(ClassLoader cl) {
        String[] names = new String[]{
                "com.android.ttcjpaysdk.ttcjpayapi.TTCJPayUtils",
                "com.android.ttcjpaysdk.ttcjpayapi.TTCJPayUtilsInner",
                "com.bytedance.caijing.sdk.biz.pay.TTCJPayUtilsCompat",
                "com.android.ttcjpaysdk.base.h5.CJPayH5Activity",
                "com.android.ttcjpaysdk.base.h5.CJPayWebView",
                "com.bytedance.caijing.sdk.infra.base.impl.jsb.h5jsb.CJJSBModule",
                "com.bytedance.caijing.sdk.infra.base.impl.jsb.JsbServiceImpl",
                "com.bytedance.caijing.sdk.infra.base.impl.jsb.xbridge.bridge.annie_cjh5.XPayRequestMethod",
                "com.bytedance.sdk.bridge.p478js.JsBridgeManager",
                "com.bytedance.sdk.bridge.auth.AbsBridgeAuthFilter",
                "com.bytedance.sdk.bridge.p478js.delegate.JsCallInterceptor",
                "p003X.C22033418hJ",
                "p003X.C22031818h3",
                "p003X.C18C3",
                "p003X.C1TSG",
                "com.bytedance.ies.abmock.SettingsManager",
                "com.bytedance.ies.abmock.ABManager",
                "com.bytedance.ies.abmock.ConfigCenter",
                "com.bytedance.webx.core.webview.WebViewContainer",
                "com.bytedance.caijing.sdk.infra.base.impl.container.annie_container.CJPayAnnieSchemaInterceptor",
                "com.bytedance.caijing.sdk.infra.base.impl.container.annie_container.CJPayAnnieWebKitDelegate"
        };
        for (String name : names) {
            hookClassByName(name, cl);
        }
    }

    private static void hookClassLoader() {
        tryHook("ClassLoader.loadClass", new HookAction() {
            @Override
            public void run() {
                XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof Class<?>)) {
                            return;
                        }
                        Class<?> clazz = (Class<?>) result;
                        if (isTargetClassName(clazz.getName())) {
                            hookClass(clazz);
                        }
                    }
                });
            }
        });
    }

    private static void hookClassByName(String name, ClassLoader cl) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(name, cl);
            if (clazz != null) {
                hookClass(clazz);
            }
        } catch (Throwable t) {
            log("hookClassByName failed " + name + " " + t);
        }
    }

    private static boolean isTargetClassName(String name) {
        if (name == null) {
            return false;
        }
        return name.equals("com.android.ttcjpaysdk.ttcjpayapi.TTCJPayUtils")
                || name.equals("com.android.ttcjpaysdk.ttcjpayapi.TTCJPayUtilsInner")
                || name.equals("com.bytedance.caijing.sdk.biz.pay.TTCJPayUtilsCompat")
                || name.equals("com.android.ttcjpaysdk.base.h5.CJPayH5Activity")
                || name.equals("com.android.ttcjpaysdk.base.h5.CJPayWebView")
                || name.startsWith("com.android.ttcjpaysdk.base.h5.CJPayH5Activity$")
                || name.equals("com.bytedance.caijing.sdk.infra.base.impl.jsb.h5jsb.CJJSBModule")
                || name.equals("com.bytedance.caijing.sdk.infra.base.impl.jsb.JsbServiceImpl")
                || name.equals("com.bytedance.caijing.sdk.infra.base.impl.jsb.xbridge.bridge.annie_cjh5.XPayRequestMethod")
                || name.equals("com.bytedance.sdk.bridge.p478js.JsBridgeManager")
                || name.equals("com.bytedance.sdk.bridge.auth.AbsBridgeAuthFilter")
                || name.equals("com.bytedance.sdk.bridge.p478js.delegate.JsCallInterceptor")
                || name.equals("p003X.C22033418hJ")
                || name.equals("p003X.C22031818h3")
                || name.equals("p003X.C18C3")
                || name.equals("p003X.C1TSG")
                || name.equals("com.bytedance.ies.abmock.SettingsManager")
                || name.equals("com.bytedance.ies.abmock.ABManager")
                || name.equals("com.bytedance.ies.abmock.ConfigCenter")
                || name.equals("com.bytedance.webx.core.webview.WebViewContainer")
                || name.equals("com.bytedance.caijing.sdk.infra.base.impl.container.annie_container.CJPayAnnieSchemaInterceptor")
                || name.equals("com.bytedance.caijing.sdk.infra.base.impl.container.annie_container.CJPayAnnieWebKitDelegate");
    }

    private static void hookClass(Class<?> clazz) {
        String name = clazz.getName();
        if (!HOOKED_CLASSES.add(name)) {
            return;
        }
        log("hookClass " + name);
        if (WebView.class.isAssignableFrom(clazz)) {
            hookWebViewClass(clazz);
        }
        if (Activity.class.isAssignableFrom(clazz) && name.contains("CJPayH5Activity")) {
            hookNamedMethods(clazz, "onCreate", "onNewIntent", "onResume", "onDestroy");
            hookAllActivityMethods(clazz);
        }
        if (name.contains("TTCJPayUtils")) {
            hookNamedMethods(clazz, "getCJPayUtils", "openH5ByScheme", "openH5",
                    "openH5ModalView", "openH5CashDesk", "pay", "routeSign",
                    "handleXBridgeMethod", "setJSBridgeAuth");
        } else if (name.contains("CJPayH5Activity$")) {
            hookNamedMethods(clazz, "shouldOverrideUrlLoading", "shouldInterceptRequest",
                    "onPageStarted", "onPageFinished", "onLoadResource",
                    "onReceivedError", "onReceivedHttpError");
        } else if (name.endsWith(".CJJSBModule")) {
            hookNamedMethods(clazz, "request", "openH5", "goH5", "pay");
        } else if (name.endsWith(".JsbServiceImpl")) {
            hookNamedMethods(clazz, "getH5JSBridgeHelper", "addBridgeAuthFilter",
                    "addHostXBridgeInterceptor", "delegateMessage");
        } else if (name.endsWith(".XPayRequestMethod")) {
            hookNamedMethods(clazz, "getName", "realHandle", "handle");
        } else if (name.endsWith(".JsBridgeManager")) {
            hookNamedMethods(clazz, "canHandleUrl", "delegateMessage",
                    "registerJsBridgeWithWebView", "delegateJavaScriptInterface");
        } else if (name.endsWith(".AbsBridgeAuthFilter")) {
            hookNamedMethods(clazz, "auth", "doAuthFilter");
        } else if (name.endsWith(".JsCallInterceptor") || name.equals("p003X.C18C3")) {
            hookNamedMethods(clazz, "LIZ", "LIZIZ");
        } else if (name.equals("p003X.C22033418hJ")) {
            hookNamedMethods(clazz, "LIZIZ", "LIZLLL", "LJII", "LIZJ");
        } else if (name.equals("p003X.C22031818h3")) {
            hookNamedMethods(clazz, "LIZIZ", "LIZJ", "m22717LJ");
        } else if (name.equals("p003X.C1TSG")) {
            hookNamedMethods(clazz, "Y40", "LIZIZ");
        } else if (name.contains("SettingsManager") || name.contains("ABManager")
                || name.contains("ConfigCenter")) {
            hookNamedMethods(clazz, "getBooleanValue", "getStringValue", "getIntValue",
                    "getValue");
        } else if (name.contains("CJPayAnnie")) {
            hookNamedMethods(clazz, "loadUrl", "postUrl", "shouldOverrideUrlLoading",
                    "shouldInterceptRequest", "open", "intercept");
        }
    }

    private static void hookWebViewClass(Class<?> clazz) {
        hookNamedMethods(clazz, "loadUrl", "postUrl", "loadDataWithBaseURL",
                "evaluateJavascript", "addJavascriptInterface", "setWebViewClient");
    }

    private static void hookNamedMethods(Class<?> clazz, String... names) {
        Set<String> wanted = new HashSet<>(Arrays.asList(names));
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method method : methods) {
            if (wanted.contains(method.getName())) {
                hookMethod(method);
            }
        }
    }

    private static void hookMethod(final Method method) {
        int mod = method.getModifiers();
        if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) {
            return;
        }
        final String key = method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.toString(method.getParameterTypes());
        if (!HOOKED_METHODS.add(key)) {
            return;
        }
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldLog(method, param.args, null)) {
                        log(">> " + shortMethod(method) + " args=" + args(param.args)
                                + " this=" + shortObj(param.thisObject));
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (param.thisObject instanceof Activity
                            && method.getDeclaringClass().getName().contains("CJPayH5Activity")
                            && ("onCreate".equals(method.getName())
                            || "onNewIntent".equals(method.getName())
                            || "onResume".equals(method.getName()))) {
                        dumpActivityState((Activity) param.thisObject,
                                shortMethod(method) + ".after");
                    }
                    if ("getCJPayUtils".equals(method.getName())
                            || "getTTCJPayUtilsCompat".equals(method.getName())) {
                        if (result != null) {
                            log("<< " + shortMethod(method) + " resultClass="
                                    + result.getClass().getName());
                            hookClass(result.getClass());
                        } else {
                            log("<< " + shortMethod(method) + " result=null");
                        }
                        return;
                    }
                    if (shouldLog(method, param.args, result)) {
                        log("<< " + shortMethod(method) + " result=" + clip(result));
                    }
                }
            });
        } catch (Throwable t) {
            HOOKED_METHODS.remove(key);
        }
    }

    private static void hookAllActivityMethods(Class<?> clazz) {
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method method : methods) {
            if (method.getName().toLowerCase(Locale.ROOT).contains("url")
                    || method.getName().toLowerCase(Locale.ROOT).contains("intent")
                    || method.getName().toLowerCase(Locale.ROOT).contains("schema")
                    || method.getName().toLowerCase(Locale.ROOT).contains("scheme")) {
                hookMethod(method);
            }
        }
    }

    private static boolean shouldLog(Method method, Object[] args, Object result) {
        String className = method.getDeclaringClass().getName();
        if ((className.contains("SettingsManager") || className.contains("ABManager")
                || className.contains("ConfigCenter")) && !isSettingsKeyArgs(args)) {
            return false;
        }
        if (isAlwaysInterestingMethod(method)) {
            return true;
        }
        return containsInteresting(args) || isInteresting(result) || isSettingsKeyArgs(args);
    }

    private static boolean isAlwaysInterestingMethod(Method method) {
        String name = method.getName();
        String className = method.getDeclaringClass().getName();
        if (name.equals("loadUrl") || name.equals("postUrl")
                || name.equals("loadDataWithBaseURL") || name.equals("addJavascriptInterface")
                || name.equals("evaluateJavascript") || name.equals("setWebViewClient")) {
            return false;
        }
        return className.contains("TTCJPayUtils")
                || className.contains("CJPayH5Activity")
                || className.endsWith(".CJJSBModule")
                || className.endsWith(".JsbServiceImpl")
                || className.endsWith(".XPayRequestMethod")
                || className.endsWith(".JsBridgeManager")
                || className.endsWith(".AbsBridgeAuthFilter")
                || className.equals("p003X.C22033418hJ")
                || className.equals("p003X.C22031818h3")
                || className.equals("p003X.C18C3")
                || className.equals("p003X.C1TSG")
                || className.contains("CJPayAnnie");
    }

    private static boolean containsInteresting(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (isInteresting(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSettingsKeyArgs(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                String s = ((String) arg).toLowerCase(Locale.ROOT);
                if (s.contains("webview_redirect_force_https")
                        || s.contains("webview_loadurl_force_https")
                        || s.contains("debug_replace_http_to_https")
                        || s.contains("hp_land_tab_url_disable_http")
                        || s.contains("cjpay_webview_plugin_config")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInteresting(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof byte[]) {
            return isInteresting(new String((byte[]) obj, StandardCharsets.UTF_8));
        }
        if (obj instanceof Intent) {
            return isInteresting(((Intent) obj).toUri(0));
        }
        if (obj instanceof Uri) {
            return isInteresting(obj.toString());
        }
        if (!(obj instanceof String)) {
            String className = obj.getClass().getName();
            if (isTargetClassName(className)) {
                return true;
            }
            return false;
        }
        String s = ((String) obj).toLowerCase(Locale.ROOT);
        return s.contains("cjpay")
                || s.contains("ttcjpay")
                || s.contains("f-cpay")
                || s.contains("doupay")
                || s.contains("qrcgtw")
                || s.contains("101.231.114.222")
                || s.contains("snssdk1128")
                || s.contains("douyin.com/redirect")
                || s.contains("nativeapp://")
                || s.contains("js2nativebridge")
                || s.contains("dispatch_message")
                || s.contains("ttcjpay.request")
                || s.contains("collector=")
                || s.contains("autotest")
                || s.contains("feoffline")
                || s.contains("webview_redirect_force_https")
                || s.contains("webview_loadurl_force_https")
                || s.contains("debug_replace_http_to_https")
                || s.contains("hp_land_tab_url_disable_http")
                || s.contains("cjpay_webview_plugin_config");
    }

    private static String args(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(clip(args[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String clip(Object obj) {
        if (obj == null) {
            return "null";
        }
        String s;
        try {
            if (obj instanceof byte[]) {
                s = new String((byte[]) obj, StandardCharsets.UTF_8);
            } else if (obj instanceof Intent) {
                s = ((Intent) obj).toUri(0);
            } else {
                s = String.valueOf(obj);
            }
        } catch (Throwable t) {
            s = obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 1800) {
            s = s.substring(0, 1800) + "...";
        }
        return s;
    }

    private static String shortMethod(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    private static String shortObj(Object obj) {
        if (obj == null) {
            return "static";
        }
        return obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
    }

    private static String intentExtras(Intent intent) {
        if (intent == null) {
            return "null";
        }
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String key : extras.keySet()) {
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable t) {
                    value = "<error " + t.getClass().getSimpleName() + ">";
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(key).append("=").append(clip(value));
            }
            sb.append("}");
            return clip(sb.toString());
        } catch (Throwable t) {
            return "<extras error " + t.getClass().getSimpleName() + ">";
        }
    }

    private static void dumpActivityState(Activity activity, String where) {
        if (activity == null) {
            return;
        }
        Intent intent = null;
        try {
            intent = activity.getIntent();
        } catch (Throwable ignored) {
        }
        log(where + " " + activity.getClass().getName()
                + " intent=" + clip(intent)
                + " extras=" + intentExtras(intent));
        String fields = interestingFields(activity);
        if (fields.length() > 0) {
            log(where + " fields=" + fields);
        }
    }

    private static String interestingFields(Object obj) {
        if (obj == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = obj.getClass();
        int count = 0;
        while (clazz != null && clazz != Activity.class && count < 24) {
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(obj);
                } catch (Throwable t) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                String name = field.getName().toLowerCase(Locale.ROOT);
                String className = value.getClass().getName();
                if (!isInteresting(value)
                        && !name.contains("url")
                        && !name.contains("schema")
                        && !name.contains("scheme")
                        && !name.contains("web")
                        && !className.contains("WebView")) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(field.getName()).append("=").append(clip(value));
                count++;
                if (count >= 24) {
                    break;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return clip(sb.toString());
    }

    private static void tryHook(String name, HookAction action) {
        try {
            action.run();
            log("hooked " + name);
        } catch (Throwable t) {
            log("hook failed " + name + " " + t);
        }
    }

    private static void log(String msg) {
        String line = "[" + TAG + "] " + msg;
        XposedBridge.log(line);
        Log.i(TAG, msg);
        FileLogger logger = fileLogger;
        if (logger != null) {
            try {
                logger.writeJava(Log.INFO, TAG, "MainHook", 0, msg);
            } catch (Throwable t) {
                Log.e(TAG, "failed to write file log", t);
            }
        }
    }

    private interface HookAction {
        void run();
    }
}
