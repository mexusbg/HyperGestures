// Compile-only stub of the com.android.extensions.appfunctions shared library (Soong
// com.android.extensions.appfunctions.stubs), written from its public API
// frameworks/base/libs/appfunctions/api/current.txt @ android-17.0.0_r1 (94b4c163). Never bundled:
// consumers depend on :appfunctions-stubs with compileOnly; the device provides the real library
// (optional uses-library, declared by the androidx.appfunctions AAR manifest).
package com.android.extensions.appfunctions;

import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;
import android.os.Bundle;

public final class ExecuteAppFunctionRequest {
    ExecuteAppFunctionRequest() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public Bundle getExtras() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public String getFunctionIdentifier() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public GenericDocument getParameters() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public String getTargetPackageName() {
        throw new RuntimeException("Stub!");
    }

    public static final class Builder {
        public Builder(@NonNull String targetPackageName, @NonNull String functionIdentifier) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public ExecuteAppFunctionRequest build() {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public Builder setParameters(@NonNull GenericDocument parameters) {
            throw new RuntimeException("Stub!");
        }
    }
}
