/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.compose.theme

import android.content.Context
import androidx.annotation.ColorRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.android.internal.R
import com.hypergestures.res.HgSysRes

/** CompositionLocal used to pass [AndroidColorScheme] down the tree. */
val LocalAndroidColorScheme =
    staticCompositionLocalOf<AndroidColorScheme> {
        throw IllegalStateException(
            "No AndroidColorScheme configured. Make sure to use LocalAndroidColorScheme in a " +
                "Composable surrounded by a PlatformTheme {}."
        )
    }

/**
 * The Android color scheme.
 *
 * This scheme contains the Material3 colors that are not available on
 * [androidx.compose.material3.MaterialTheme]. For other colors (e.g. primary), use
 * `MaterialTheme.colorScheme` instead.
 */
@Immutable
class AndroidColorScheme(
    // fixed tokens
    val primaryFixed: Color,
    val primaryFixedDim: Color,
    val onPrimaryFixed: Color,
    val onPrimaryFixedVariant: Color,
    val secondaryFixed: Color,
    val secondaryFixedDim: Color,
    val onSecondaryFixed: Color,
    val onSecondaryFixedVariant: Color,
    val tertiaryFixed: Color,
    val tertiaryFixedDim: Color,
    val onTertiaryFixed: Color,
    val onTertiaryFixedVariant: Color,

    // custom tokens
    val brandA: Color,
    val brandB: Color,
    val brandC: Color,
    val brandD: Color,
    val clockHour: Color,
    val clockMinute: Color,
    val clockSecond: Color,
    val onShadeActive: Color,
    val onShadeActiveVariant: Color,
    val onShadeInactive: Color,
    val onShadeInactiveVariant: Color,
    val onThemeApp: Color,
    val overviewBackground: Color,
    val shadeActive: Color,
    val shadeDisabled: Color,
    val shadeInactive: Color,
    val themeApp: Color,
    val themeAppRing: Color,
    val themeNotif: Color,
    val underSurface: Color,
    val weatherTemp: Color,
    val widgetBackground: Color,
    val surfaceEffect0: Color,
    val surfaceEffect1: Color,
    val surfaceEffect2: Color,
    val surfaceEffect3: Color,
    val surfaceEffect0Fallback: Color,
) {
    companion object {
        internal fun color(context: Context, @ColorRes id: Int): Color {
            return Color(context.resources.getColor(id, context.theme))
        }

        // HyperGestures: the customColor* tokens are private framework colors, looked up by name
        // rather than inlined as this firmware's IDs (HgSysRes); missing → transparent.
        internal fun color(context: Context, name: String): Color {
            return Color(HgSysRes.getColor(context, name, 0))
        }

        operator fun invoke(context: Context): AndroidColorScheme {
            return AndroidColorScheme(
                // Fixed tokens.
                primaryFixed = color(context, R.color.system_primary_fixed),
                primaryFixedDim = color(context, R.color.system_primary_fixed_dim),
                onPrimaryFixed = color(context, R.color.system_on_primary_fixed),
                onPrimaryFixedVariant = color(context, R.color.system_on_primary_fixed_variant),
                secondaryFixed = color(context, R.color.system_secondary_fixed),
                secondaryFixedDim = color(context, R.color.system_secondary_fixed_dim),
                onSecondaryFixed = color(context, R.color.system_on_secondary_fixed),
                onSecondaryFixedVariant = color(context, R.color.system_on_secondary_fixed_variant),
                tertiaryFixed = color(context, R.color.system_tertiary_fixed),
                tertiaryFixedDim = color(context, R.color.system_tertiary_fixed_dim),
                onTertiaryFixed = color(context, R.color.system_on_tertiary_fixed),
                onTertiaryFixedVariant = color(context, R.color.system_on_tertiary_fixed_variant),

                // Custom tokens.
                brandA = color(context, "customColorBrandA"),
                brandB = color(context, "customColorBrandB"),
                brandC = color(context, "customColorBrandC"),
                brandD = color(context, "customColorBrandD"),
                clockHour = color(context, "customColorClockHour"),
                clockMinute = color(context, "customColorClockMinute"),
                clockSecond = color(context, "customColorClockSecond"),
                onShadeActive = color(context, "customColorOnShadeActive"),
                onShadeActiveVariant = color(context, "customColorOnShadeActiveVariant"),
                onShadeInactive = color(context, "customColorOnShadeInactive"),
                onShadeInactiveVariant = color(context, "customColorOnShadeInactiveVariant"),
                onThemeApp = color(context, "customColorOnThemeApp"),
                overviewBackground = color(context, "customColorOverviewBackground"),
                shadeActive = color(context, "customColorShadeActive"),
                shadeDisabled = color(context, "customColorShadeDisabled"),
                shadeInactive = color(context, "customColorShadeInactive"),
                themeApp = color(context, "customColorThemeApp"),
                themeAppRing = color(context, "customColorThemeAppRing"),
                themeNotif = color(context, "customColorThemeNotif"),
                underSurface = color(context, "customColorUnderSurface"),
                weatherTemp = color(context, "customColorWeatherTemp"),
                widgetBackground = color(context, "customColorWidgetBackground"),
                surfaceEffect0 = color(context, "customColorSurfaceEffect0"),
                surfaceEffect1 = color(context, "customColorSurfaceEffect1"),
                surfaceEffect2 = color(context, "customColorSurfaceEffect2"),
                surfaceEffect3 = color(context, "customColorSurfaceEffect3"),
                surfaceEffect0Fallback = color(context, "customColorSurfaceEffect0Fallback"),
            )
        }
    }
}
