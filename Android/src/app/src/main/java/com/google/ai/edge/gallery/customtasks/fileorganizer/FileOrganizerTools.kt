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

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

private const val TAG = "AGFileOrganizerTools"

class FileOrganizerTools(val onFunctionCalled: (FileOrganizerAction) -> Unit) {
  @Tool(
    description =
      "Suggests a new folder path and clean name for a file so the user can approve organization."
  )
  fun organizeFile(
    @ToolParam(description = "The URI of the file to organize.") sourceUri: String,
    @ToolParam(
      description =
        "The relative folder path from the chosen destination root, such as '2024/Receipts'."
    )
    targetRelativePath: String,
    @ToolParam(description = "The clean file name, including extension.") suggestedName: String,
    @ToolParam(description = "A short reason for the organization suggestion.") reason: String,
  ): Map<String, String> {
    Log.d(TAG, "Organize file: $sourceUri -> $targetRelativePath/$suggestedName")
    onFunctionCalled(
      OrganizeFileAction(
        sourceUri = sourceUri,
        targetRelativePath = targetRelativePath,
        suggestedName = suggestedName,
        reason = reason,
      )
    )
    return mapOf(
      "result" to "success",
      "sourceUri" to sourceUri,
      "targetRelativePath" to targetRelativePath,
      "suggestedName" to suggestedName,
      "reason" to reason,
    )
  }
}
