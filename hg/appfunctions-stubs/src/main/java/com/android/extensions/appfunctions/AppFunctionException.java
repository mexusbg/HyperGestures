// Compile-only stub of the com.android.extensions.appfunctions shared library (Soong
// com.android.extensions.appfunctions.stubs), written from its public API
// frameworks/base/libs/appfunctions/api/current.txt @ android-17.0.0_r1 (94b4c163). Never bundled:
// consumers depend on :appfunctions-stubs with compileOnly; the device provides the real library
// (optional uses-library, declared by the androidx.appfunctions AAR manifest).
package com.android.extensions.appfunctions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;

public final class AppFunctionException extends Exception {
    public static final int ERROR_APP_UNKNOWN_ERROR = 3000;
    public static final int ERROR_CANCELLED = 2001;
    public static final int ERROR_CATEGORY_APP = 3;
    public static final int ERROR_CATEGORY_REQUEST_ERROR = 1;
    public static final int ERROR_CATEGORY_SYSTEM = 2;
    public static final int ERROR_CATEGORY_UNKNOWN = 0;
    public static final int ERROR_DENIED = 1000;
    public static final int ERROR_DISABLED = 1002;
    public static final int ERROR_ENTERPRISE_POLICY_DISALLOWED = 2002;
    public static final int ERROR_FUNCTION_NOT_FOUND = 1003;
    public static final int ERROR_INVALID_ARGUMENT = 1001;
    public static final int ERROR_SYSTEM_ERROR = 2000;

    public AppFunctionException(int errorCode, @Nullable String errorMessage) {
        throw new RuntimeException("Stub!");
    }

    public AppFunctionException(
            int errorCode, @Nullable String errorMessage, @NonNull Bundle extras) {
        throw new RuntimeException("Stub!");
    }

    public int getErrorCategory() {
        throw new RuntimeException("Stub!");
    }

    public int getErrorCode() {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    public String getErrorMessage() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public Bundle getExtras() {
        throw new RuntimeException("Stub!");
    }
}
