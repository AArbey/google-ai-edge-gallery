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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.ui.graphics.vector.ImageVector

enum class FileOrganizerActionType {
  ORGANIZE_FILE,
}

data class FileOrganizerFunctionCallDetails(
  val functionName: String,
  val parameters: List<Pair<String, String>>,
  val ts: Long = System.currentTimeMillis(),
)

abstract class FileOrganizerAction(
  val type: FileOrganizerActionType,
  val icon: ImageVector,
  val functionCallDetails: FileOrganizerFunctionCallDetails,
)

data class FileOrganizationSuggestion(
  val sourceUri: String,
  val targetRelativePath: String,
  val suggestedName: String,
  val reason: String,
  val approved: Boolean = false,
)

class OrganizeFileAction(
  val sourceUri: String,
  val targetRelativePath: String,
  val suggestedName: String,
  val reason: String,
) :
  FileOrganizerAction(
    type = FileOrganizerActionType.ORGANIZE_FILE,
    icon = Icons.Outlined.DriveFileRenameOutline,
    functionCallDetails =
      FileOrganizerFunctionCallDetails(
        functionName = "organizeFile",
        parameters =
          listOf(
            "sourceUri" to sourceUri,
            "targetRelativePath" to targetRelativePath,
            "suggestedName" to suggestedName,
            "reason" to reason,
          ),
      ),
  )

data class FileTreeNode(
  val uri: String,
  val displayName: String,
  val isDirectory: Boolean,
  val children: List<FileTreeNode> = listOf(),
  val depth: Int = 0,
  val parentUri: String? = null,
)

data class FileSelectionState(
  val selectedUris: Set<String> = emptySet(),
  val expandedDirs: Set<String> = emptySet(),
)

data class FileOrganizerInput(
  val rootDisplayName: String,
  val rootUri: String,
  val prompt: String,
  val files: List<FileEntry>,
)

data class FileEntry(
  val uri: String,
  val displayName: String,
  val relativePath: String,
  val mimeType: String,
  val sizeBytes: Long,
)

data class FileOrganizerPlan(val suggestions: List<FileOrganizationSuggestion>)

data class FileOrganizerSummary(
  val totalFiles: Int,
  val approvedFiles: Int,
  val destinationRoot: String,
)

val DEFAULT_FILE_ORGANIZER_ICON: ImageVector = Icons.Outlined.FolderOpen
