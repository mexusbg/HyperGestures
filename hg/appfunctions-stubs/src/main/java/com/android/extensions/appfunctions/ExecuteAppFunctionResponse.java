// Compile-only stub of the com.android.extensions.appfunctions shared library (Soong
// com.android.extensions.appfunctions.stubs), written from its public API
// frameworks/base/libs/appfunctions/api/current.txt @ android-17.0.0_r1 (94b4c163). Never bundled:
// consumers depend on :appfunctions-stubs with compileOnly; the device provides the real library
// (optional uses-library, declared by the androidx.appfunctions AAR manifest).
package com.android.extensions.appfunctions;

import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;
import android.os.Bundle;

public final class ExecuteAppFunctionResponse {
    public static final String PROPERTY_RETURN_VALUE = "androidAppfunctionsReturnValue";

    public ExecuteAppFunctionResponse(@NonNull GenericDocument resultDocument) {
        throw new RuntimeException("Stub!");
    }

    public ExecuteAppFunctionResponse(
            @NonNull GenericDocument resultDocument, @NonNull Bundle extras) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public Bundle getExtras() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public GenericDocument getResultDocument() {
        throw new RuntimeException("Stub!");
    }
}
