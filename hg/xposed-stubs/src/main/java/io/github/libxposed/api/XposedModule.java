/*
 * Compile-only stub of the libxposed API (https://github.com/libxposed/api, Apache-2.0).
 * See XposedInterface.java for why this exists and why it must never be packaged.
 *
 * The real class extends XposedInterfaceWrapper, which implements every XposedInterface
 * method by delegating to the framework instance attached after construction. The stub
 * flattens that: it declares the same members directly so subclasses compile, with bodies
 * that never run.
 */
package io.github.libxposed.api;

import java.lang.reflect.Executable;

public abstract class XposedModule implements XposedInterface, XposedModuleInterface {

    public XposedModule() {
    }

    @Override
    public HookBuilder hook(Executable origin) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void log(int priority, String tag, String msg) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public String getFrameworkName() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public String getFrameworkVersion() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public long getFrameworkVersionCode() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getApiVersion() {
        throw new UnsupportedOperationException("stub");
    }
}
