/*
 * Compile-only stub of the libxposed API (https://github.com/libxposed/api, Apache-2.0).
 *
 * Only the surface this module actually calls is declared, matching the API 102 javadoc
 * signatures exactly. No constants are declared on purpose: interface constants are inlined
 * at compile time, and a wrong value would silently diverge from the real API. These classes
 * must never be packaged into the APK — LSPosed provides the real ones at runtime.
 */
package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.util.List;

public interface XposedInterface {

    HookBuilder hook(Executable origin);

    void log(int priority, String tag, String msg);

    void log(int priority, String tag, String msg, Throwable tr);

    String getFrameworkName();

    String getFrameworkVersion();

    long getFrameworkVersionCode();

    int getApiVersion();

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface Chain {
        Executable getExecutable();

        Object getThisObject();

        List<Object> getArgs();

        Object getArg(int index);

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;

        Object proceedWith(Object thisObject) throws Throwable;

        Object proceedWith(Object thisObject, Object[] args) throws Throwable;
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);

        HookBuilder setId(String id);

        HookHandle intercept(Hooker hooker);
    }

    interface HookHandle {
        void unhook();
    }
}
