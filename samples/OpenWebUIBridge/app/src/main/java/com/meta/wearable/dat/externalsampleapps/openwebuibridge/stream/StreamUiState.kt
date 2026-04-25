/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State
//
// This data class manages UI state for camera streaming operations using the DAT API.

package com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamSessionState

enum class SnapshotImageQuality(
    val maxDimension: Int,
    val jpegQuality: Int,
) {
  STANDARD(maxDimension = 896, jpegQuality = 82),
  HIGH(maxDimension = 1600, jpegQuality = 92),
  ORIGINAL(maxDimension = Int.MAX_VALUE, jpegQuality = 95),
}

data class StreamUiState(
    val isBridgeRunning: Boolean = false,
    val streamSessionState: StreamSessionState = StreamSessionState.STOPPED,
    val videoFrame: Bitmap? = null,
    val videoFrameCount: Int = 0,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    val openWebUiBaseUrl: String = "",
    val openWebUiApiKey: String = "",
    val openWebUiModel: String = "",
    val openWebUiChatId: String = "",
    val openWebUiSessionId: String = "",
    val openWebUiModels: List<String> = emptyList(),
    val isLoadingOpenWebUiModels: Boolean = false,
    val isAutoSpeakResponseEnabled: Boolean = false,
    val snapshotImageQuality: SnapshotImageQuality = SnapshotImageQuality.HIGH,
    val openWebUiSystemPrompt: String =
        "You are being used hands-free through Meta smart glasses. The user may be walking, looking at the scene through the glasses camera, and listening to spoken responses. Be concise, practical, and describe visual details only when they matter.",
    val openWebUiPrompt: String = "What is visible in this glasses camera frame?",
    val openWebUiResponse: String? = null,
    val openWebUiError: String? = null,
    val isAskingOpenWebUi: Boolean = false,
    val isListeningForVoice: Boolean = false,
    val isSpeakingResponse: Boolean = false,
    val voiceTranscript: String? = null,
)
