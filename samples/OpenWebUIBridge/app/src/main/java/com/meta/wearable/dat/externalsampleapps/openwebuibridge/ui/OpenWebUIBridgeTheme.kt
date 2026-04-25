/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.openwebuibridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.wearables.AppThemeMode

@Composable
fun OpenWebUIBridgeTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
  val useDarkTheme =
      when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
      }

  MaterialTheme(
      colorScheme =
          if (useDarkTheme) {
            darkColorScheme()
          } else {
            lightColorScheme()
          },
      content = content,
  )
}
