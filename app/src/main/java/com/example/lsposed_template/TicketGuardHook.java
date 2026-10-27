package com.example.lsposed_template;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class TicketGuardHook {
    static final String VERIFY_PUB_KEY_PATH = "/passport/ticket_guard/verify_pub_key/";
    private static final String NETWORK_CLASS =
            "com.bytedance.sdk.account.ticketguard.TicketGuardNetwork";
    private static final String RESULT_HEADER = "bd-ticket-guard-verify-public-key-result";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private TicketGuardHook() {
    }

    static void install(final ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> clazz = XposedHelpers.findClass(NETWORK_CLASS, classLoader);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"LIZ".equals(method.getName()) || types.length != 3
                        || types[0] != String.class || types[1] != List.class
                        || types[2] != Map.class) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String url = (String) param.args[0];
                        if (!isVerifyPubKeyUrl(url)) {
                            return;
                        }
                        try {
                            param.setResult(createLocalSuccessResponse(classLoader));
                            MainHook.log("TicketGuard blocked verify_pub_key before network url=" + url);
                        } catch (Throwable t) {
                            MainHook.log("TicketGuard failed to create local response error=" + t);
                        }
                    }
                });
                count++;
            }
            MainHook.log("TicketGuard hooked TicketGuardNetwork.LIZ count=" + count);
        } catch (Throwable t) {
            MainHook.log("TicketGuard failed to hook TicketGuardNetwork.LIZ error=" + t);
        }
    }

    static boolean isVerifyPubKeyUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            return VERIFY_PUB_KEY_PATH.equals(URI.create(url).getPath());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Object createLocalSuccessResponse(ClassLoader classLoader) throws Exception {
        Class<?> headerClass = XposedHelpers.findClass("X.1YnW", classLoader);
        Constructor<?> headerConstructor = headerClass.getDeclaredConstructor(String.class, String.class);
        Object resultHeader = headerConstructor.newInstance(RESULT_HEADER, "0");
        List<?> headers = Collections.singletonList(resultHeader);
        Class<?> responseClass = XposedHelpers.findClass("X.1Xjc", classLoader);
        Constructor<?> responseConstructor = responseClass.getDeclaredConstructor(
                int.class, String.class, List.class);
        return responseConstructor.newInstance(200, "", headers);
    }
}
