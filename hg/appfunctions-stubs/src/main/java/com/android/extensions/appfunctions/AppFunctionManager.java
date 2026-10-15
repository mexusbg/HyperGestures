// Compile-only stub of the com.android.extensions.appfunctions shared library (Soong
// com.android.extensions.appfunctions.stubs), written from its public API
// frameworks/base/libs/appfunctions/api/current.txt @ android-17.0.0_r1 (94b4c163). Never bundled:
// consumers depend on :appfunctions-stubs with compileOnly; the device provides the real library
// (optional uses-library, declared by the androidx.appfunctions AAR manifest).
package com.android.extensions.appfunctions;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import java.util.concurrent.Executor;

public final class AppFunctionManager {
    public static final int APP_FUNCTION_STATE_DEFAULT = 0;
    public static final int APP_FUNCTION_STATE_DISABLED = 2;
    public static final int APP_FUNCTION_STATE_ENABLED = 1;

    public AppFunctionManager(Context context) {
        throw new RuntimeException("Stub!");
    }

    @RequiresPermission(Manifest.permission.EXECUTE_APP_FUNCTIONS)
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull Executor executor,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback) {
        throw new RuntimeException("Stub!");
    }

    @RequiresPermission(Manifest.permission.EXECUTE_APP_FUNCTIONS)
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        throw new RuntimeException("Stub!");
    }

    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        throw new RuntimeException("Stub!");
    }

    public void setAppFunctionEnabled(
            @NonNull String functionIdentifier,
            int newEnabledState,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        throw new RuntimeException("Stub!");
    }
}
