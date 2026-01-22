/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.fileorganizer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileOrganizerScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: FileOrganizerViewModel = hiltViewModel(),
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerState.selectedModel
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var showApplyDialog by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }

  val rootPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
      uri?.let {
        context.contentResolver.takePersistableUriPermission(
          it,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.setRootUri(
          it,
          it.lastPathSegment ?: stringResource(R.string.file_organizer_source_title),
        )
        viewModel.loadFileTree()
      }
    }
  val destinationPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
      uri?.let {
        context.contentResolver.takePersistableUriPermission(
          it,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.setDestinationUri(
          it,
          it.lastPathSegment ?: stringResource(R.string.file_organizer_destination_title),
        )
      }
    }

  LaunchedEffect(uiState.errorMessage) {
    if (uiState.errorMessage.isNotEmpty()) {
      showErrorDialog = true
    }
  }

  val modelStatus = modelManagerState.modelInitializationStatus[model.name]
  val modelInitialized = modelStatus?.status == ModelInitializationStatusType.INITIALIZED
  setAppBarControlsDisabled(uiState.processing || !modelInitialized)

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .padding(bottom = bottomPadding)
  ) {
    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.file_organizer_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = stringResource(R.string.file_organizer_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      FolderPickerCard(
        title = stringResource(R.string.file_organizer_source_title),
        subtitle =
          uiState.rootDisplayName.ifEmpty {
            stringResource(R.string.file_organizer_source_placeholder)
          },
        icon = Icons.Outlined.Folder,
        onClick = { rootPicker.launch(null) },
      )

      FolderPickerCard(
        title = stringResource(R.string.file_organizer_destination_title),
        subtitle =
          uiState.destinationDisplayName.ifEmpty {
            stringResource(R.string.file_organizer_destination_placeholder)
          },
        icon = Icons.Outlined.CreateNewFolder,
        onClick = { destinationPicker.launch(null) },
      )

      OutlinedTextField(
        value = uiState.prompt,
        onValueChange = viewModel::setPrompt,
        label = { Text(stringResource(R.string.file_organizer_prompt_label)) },
        placeholder = { Text(stringResource(R.string.file_organizer_prompt_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
      )

      SectionTitle(title = stringResource(R.string.file_organizer_tree_title))
      if (uiState.fileTree.isEmpty()) {
        Text(
          text = stringResource(R.string.file_organizer_tree_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Column {
          uiState.fileTree.forEach { node ->
            FileTreeNodeRow(
              node = node,
              selectionState = uiState.selectionState,
              onToggleExpanded = viewModel::toggleExpanded,
              onToggleSelected = viewModel::toggleSelected,
            )
          }
        }
      }

      Button(
        onClick = { viewModel.generatePlan(model) },
        enabled =
          !uiState.processing &&
            modelInitialized &&
            uiState.rootUri != null &&
            uiState.destinationUri != null,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.PlayCircle, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.file_organizer_generate_plan))
      }

      if (uiState.processing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            stringResource(R.string.file_organizer_generating),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      if (uiState.suggestions.isNotEmpty()) {
        SectionTitle(title = stringResource(R.string.file_organizer_review_suggestions))
        uiState.suggestions.forEach { suggestion ->
          SuggestionCard(
            suggestion = suggestion,
            onToggleApproved = { approved ->
              viewModel.updateSuggestionApproval(suggestion.sourceUri, approved)
            },
            onRenameChanged = { viewModel.updateSuggestionName(suggestion.sourceUri, it) },
            onPathChanged = { viewModel.updateSuggestionPath(suggestion.sourceUri, it) },
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = { showApplyDialog = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Outlined.TaskAlt, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.file_organizer_apply_changes))
        }
      }

      uiState.lastPlanSummary?.let { summary ->
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
          text =
            stringResource(
              R.string.file_organizer_plan_summary,
              summary.approvedFiles,
              summary.totalFiles,
            ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(16.dp))
  }

  if (showApplyDialog) {
    AlertDialog(
      onDismissRequest = { showApplyDialog = false },
      title = { Text(stringResource(R.string.file_organizer_apply_title)) },
      text = { Text(stringResource(R.string.file_organizer_apply_warning)) },
      confirmButton = {
        Button(
          onClick = {
            showApplyDialog = false
            viewModel.applyPlan { message ->
              scope.launch(Dispatchers.Main) {
                snackbarHostState.showSnackbar(message)
              }
            }
          }
        ) {
          Text(stringResource(R.string.file_organizer_apply_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { showApplyDialog = false }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }

  if (showErrorDialog) {
    AlertDialog(
      onDismissRequest = {
        showErrorDialog = false
        viewModel.clearError()
      },
      title = { Text(stringResource(R.string.file_organizer_issue_title)) },
      text = { Text(uiState.errorMessage) },
      confirmButton = {
        TextButton(
          onClick = {
            showErrorDialog = false
            viewModel.clearError()
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

@Composable
private fun FolderPickerCard(
  title: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  onClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text(title, fontWeight = FontWeight.SemiBold)
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun SectionTitle(title: String) {
  Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FileTreeNodeRow(
  node: FileTreeNode,
  selectionState: FileSelectionState,
  onToggleExpanded: (String) -> Unit,
  onToggleSelected: (String) -> Unit,
) {
  val isExpanded = selectionState.expandedDirs.contains(node.uri)
  val isSelected = selectionState.selectedUris.contains(node.uri)
  val indent = node.depth * 16
  Column(modifier = Modifier.padding(start = indent.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
      if (node.isDirectory) {
        IconButton(onClick = { onToggleExpanded(node.uri) }) {
          Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.alpha(if (isExpanded) 0.3f else 1f),
          )
        }
        Icon(Icons.Outlined.Folder, contentDescription = null)
      } else {
        Spacer(modifier = Modifier.width(40.dp))
        Icon(Icons.Outlined.Description, contentDescription = null)
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = node.displayName,
        modifier = Modifier.weight(1f),
        fontSize = 14.sp,
      )
      IconButton(onClick = { onToggleSelected(node.uri) }) {
        Icon(
          Icons.Outlined.CheckCircle,
          contentDescription = null,
          tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
        )
      }
    }
    if (node.isDirectory && isExpanded) {
      node.children.forEach { child ->
        FileTreeNodeRow(child, selectionState, onToggleExpanded, onToggleSelected)
      }
    }
  }
}

@Composable
private fun SuggestionCard(
  suggestion: FileOrganizationSuggestion,
  onToggleApproved: (Boolean) -> Unit,
  onRenameChanged: (String) -> Unit,
  onPathChanged: (String) -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
        .padding(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.CheckCircle, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = Uri.parse(suggestion.sourceUri).lastPathSegment ?: suggestion.sourceUri,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = { onToggleApproved(!suggestion.approved) }) {
        Icon(
          Icons.Outlined.TaskAlt,
          contentDescription = null,
          tint = if (suggestion.approved) MaterialTheme.colorScheme.primary else Color.Gray,
        )
      }
    }
    Text(
      text = suggestion.reason,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
      value = suggestion.targetRelativePath,
      onValueChange = onPathChanged,
      label = { Text(stringResource(R.string.file_organizer_folder_path_label)) },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
      value = suggestion.suggestedName,
      onValueChange = onRenameChanged,
      label = { Text(stringResource(R.string.file_organizer_file_name_label)) },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
  }
}
