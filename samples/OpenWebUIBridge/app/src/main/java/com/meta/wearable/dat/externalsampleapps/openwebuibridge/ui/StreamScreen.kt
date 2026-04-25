/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.openwebuibridge.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.R
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream.StreamUiState
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.wearables.WearablesViewModel
@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val isCameraStreaming = streamUiState.streamSessionState == StreamSessionState.STREAMING
  val isCameraStarting = streamUiState.streamSessionState == StreamSessionState.STARTING
  val isCameraEnabled = streamUiState.streamSessionState != StreamSessionState.STOPPED
  var modelMenuExpanded by remember { mutableStateOf(false) }
  var isSettingsExpanded by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
          modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        StreamStatusHeader(
            isBridgeRunning = streamUiState.isBridgeRunning,
            isCameraStreaming = isCameraStreaming,
            isCameraStarting = isCameraStarting,
        )

        streamUiState.videoFrame?.let { videoFrame ->
          key(streamUiState.videoFrameCount) {
            Image(
                bitmap = videoFrame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxWidth().height(88.dp),
                contentScale = ContentScale.Crop,
            )
          }
        }

        ResponsePanel(
            response = streamUiState.openWebUiResponse,
            error = streamUiState.openWebUiError,
            voiceTranscript = streamUiState.voiceTranscript,
            modifier = Modifier.weight(1f),
        )

        streamUiState.openWebUiResponse?.let { response ->
          ResponseActions(
              isSpeakingResponse = streamUiState.isSpeakingResponse,
              onCopy = { clipboardManager.setText(AnnotatedString(response)) },
              onShare = { shareText(context, response) },
              onSpeak = { streamViewModel.speakResponse() },
              onStopSpeaking = { streamViewModel.stopSpeakingResponse() },
          )
        }

        TextButton(
            onClick = { isSettingsExpanded = !isSettingsExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
              if (isSettingsExpanded) {
                stringResource(R.string.hide_settings)
              } else {
                stringResource(R.string.show_settings)
              }
          )
        }

        if (isSettingsExpanded) {
          SettingsPanel(
              modelMenuExpanded = modelMenuExpanded,
              onModelMenuExpandedChange = { modelMenuExpanded = it },
              streamViewModel = streamViewModel,
              streamUiState = streamUiState,
          )
        }

        PrimaryControls(
            isCameraEnabled = isCameraEnabled,
            isCameraStreaming = isCameraStreaming,
            isBusy = streamUiState.isAskingOpenWebUi,
            isBridgeRunning = streamUiState.isBridgeRunning,
            isListeningForVoice = streamUiState.isListeningForVoice,
            onStopBridge = {
              streamViewModel.stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            },
            onToggleCamera = { streamViewModel.toggleCameraStream() },
            onVoiceAsk = { streamViewModel.startVoiceAsk() },
            onSnapshotAsk = { streamViewModel.askOpenWebUiAboutSnapshot() },
            onCapture = { streamViewModel.capturePhoto() },
        )
      }

      if (isCameraStarting) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      }
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

@Composable
private fun StreamStatusHeader(
    isBridgeRunning: Boolean,
    isCameraStreaming: Boolean,
    isCameraStarting: Boolean,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text =
            if (isBridgeRunning) {
              stringResource(R.string.bridge_running)
            } else {
              stringResource(R.string.bridge_starting)
            },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text =
            when {
              isCameraStarting -> stringResource(R.string.bridge_camera_starting)
              isCameraStreaming -> stringResource(R.string.camera_stream_running)
              else -> stringResource(R.string.camera_stream_off)
            },
        style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
private fun ResponsePanel(
    response: String?,
    error: String?,
    voiceTranscript: String?,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when {
        response != null ->
            SelectionContainer {
              Text(text = response, style = MaterialTheme.typography.bodyLarge)
            }
        error != null ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        else ->
            Text(
                text = stringResource(R.string.openwebui_response_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
      }
      voiceTranscript?.let { transcript ->
        Text(text = transcript, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun ResponseActions(
    isSpeakingResponse: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.copy_response))
      }
      TextButton(onClick = onShare, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.share_response))
      }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onSpeak, enabled = !isSpeakingResponse, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.speak_response))
      }
      TextButton(
          onClick = onStopSpeaking,
          enabled = isSpeakingResponse,
          modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(R.string.stop_speaking_response))
      }
    }
  }
}

@Composable
private fun SettingsPanel(
    modelMenuExpanded: Boolean,
    onModelMenuExpandedChange: (Boolean) -> Unit,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
) {
  Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
        value = streamUiState.openWebUiBaseUrl,
        onValueChange = streamViewModel::updateOpenWebUiBaseUrl,
        label = { Text(stringResource(R.string.openwebui_base_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = streamUiState.openWebUiApiKey,
        onValueChange = streamViewModel::updateOpenWebUiApiKey,
        label = { Text(stringResource(R.string.openwebui_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = streamUiState.openWebUiModel,
        onValueChange = streamViewModel::updateOpenWebUiModel,
        label = { Text(stringResource(R.string.openwebui_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(
          onClick = { streamViewModel.refreshOpenWebUiModels() },
          enabled = !streamUiState.isLoadingOpenWebUiModels,
          modifier = Modifier.weight(1f),
      ) {
        Text(
            if (streamUiState.isLoadingOpenWebUiModels) {
              stringResource(R.string.openwebui_loading_models)
            } else {
              stringResource(R.string.openwebui_load_models)
            }
        )
      }
      Box(modifier = Modifier.weight(1f)) {
        TextButton(
            onClick = { onModelMenuExpandedChange(true) },
            enabled = streamUiState.openWebUiModels.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.openwebui_select_model))
        }
        DropdownMenu(
            expanded = modelMenuExpanded,
            onDismissRequest = { onModelMenuExpandedChange(false) },
        ) {
          streamUiState.openWebUiModels.forEach { model ->
            DropdownMenuItem(
                text = { Text(model) },
                onClick = {
                  streamViewModel.updateOpenWebUiModel(model)
                  onModelMenuExpandedChange(false)
                },
            )
          }
        }
      }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = stringResource(R.string.auto_speak_responses),
          style = MaterialTheme.typography.bodyLarge,
      )
      Switch(
          checked = streamUiState.isAutoSpeakResponseEnabled,
          onCheckedChange = streamViewModel::updateAutoSpeakResponseEnabled,
      )
    }
    OutlinedTextField(
        value = streamUiState.openWebUiSystemPrompt,
        onValueChange = streamViewModel::updateOpenWebUiSystemPrompt,
        label = { Text(stringResource(R.string.openwebui_system_prompt)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = streamUiState.openWebUiPrompt,
        onValueChange = streamViewModel::updateOpenWebUiPrompt,
        label = { Text(stringResource(R.string.openwebui_prompt)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun PrimaryControls(
    isCameraEnabled: Boolean,
    isCameraStreaming: Boolean,
    isBusy: Boolean,
    isBridgeRunning: Boolean,
    isListeningForVoice: Boolean,
    onStopBridge: () -> Unit,
    onToggleCamera: () -> Unit,
    onVoiceAsk: () -> Unit,
    onSnapshotAsk: () -> Unit,
    onCapture: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      SwitchButton(
          label =
              if (isListeningForVoice) {
                stringResource(R.string.openwebui_listening)
              } else {
                stringResource(R.string.openwebui_voice_ask)
              },
          onClick = onVoiceAsk,
            enabled = isBridgeRunning && !isListeningForVoice && !isBusy,
          modifier = Modifier.weight(1f),
      )
      SwitchButton(
          label =
              if (isBusy) {
                stringResource(R.string.openwebui_asking)
              } else {
                stringResource(R.string.openwebui_snapshot_ask)
              },
          onClick = onSnapshotAsk,
          enabled = isCameraStreaming && !isBusy,
          modifier = Modifier.weight(1f),
      )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      SwitchButton(
          label =
              if (isCameraEnabled) {
                stringResource(R.string.stop_camera_stream_button_title)
              } else {
                stringResource(R.string.start_camera_stream_button_title)
              },
          onClick = onToggleCamera,
          enabled = isBridgeRunning && !isBusy,
          modifier = Modifier.weight(1f),
      )
      SwitchButton(
          label = stringResource(R.string.stop_bridge_button_title),
          onClick = onStopBridge,
          isDestructive = true,
          modifier = Modifier.weight(1f),
      )
      CaptureButton(onClick = onCapture)
    }
  }
}

private fun shareText(context: Context, text: String) {
  val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
      }
  context.startActivity(Intent.createChooser(intent, null))
}