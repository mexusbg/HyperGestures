// Compile-only stub of the com.android.extensions.appfunctions shared library (Soong
// com.android.extensions.appfunctions.stubs), written from its public API
// frameworks/base/libs/appfunctions/api/current.txt @ android-17.0.0_r1 (94b4c163). Never bundled:
// consumers depend on :appfunctions-stubs with compileOnly; the device provides the real library
// (optional uses-library, declared by the androidx.appfunctions AAR manifest).
package com.android.extensions.appfunctions;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OutcomeReceiver;

public abstract class AppFunctionService extends Service {
    @NonNull
    public static final String BIND_APP_FUNCTION_SERVICE =
            "android.permission.BIND_APP_FUNCTION_SERVICE";
    @NonNull
    public static final String SERVICE_INTERFACE = "android.app.appfunctions.AppFunctionService";

    public AppFunctionService() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        throw new RuntimeException("Stub!");
    }

    @MainThread
    public abstract void onExecuteFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull String callingPackage,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback);
}
