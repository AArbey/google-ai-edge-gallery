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

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGFileOrganizerVM"

data class FileOrganizerUiState(
  val rootUri: Uri? = null,
  val rootDisplayName: String = "",
  val destinationUri: Uri? = null,
  val destinationDisplayName: String = "",
  val fileTree: List<FileTreeNode> = listOf(),
  val selectionState: FileSelectionState = FileSelectionState(),
  val prompt: String = "",
  val modelResponse: String = "",
  val processing: Boolean = false,
  val suggestions: List<FileOrganizationSuggestion> = listOf(),
  val lastPlanSummary: FileOrganizerSummary? = null,
  val errorMessage: String = "",
)

@HiltViewModel
class FileOrganizerViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {
  private val _uiState = MutableStateFlow(FileOrganizerUiState())
  val uiState = _uiState.asStateFlow()

  private val tools: List<FileOrganizerTools> = FileOrganizerActionStore.tools

  fun setPrompt(prompt: String) {
    _uiState.update { it.copy(prompt = prompt) }
  }

  fun setRootUri(rootUri: Uri?, displayName: String) {
    _uiState.update {
      it.copy(
        rootUri = rootUri,
        rootDisplayName = displayName,
        fileTree = listOf(),
        selectionState = FileSelectionState(),
      )
    }
  }

  fun setDestinationUri(destinationUri: Uri?, displayName: String) {
    _uiState.update { it.copy(destinationUri = destinationUri, destinationDisplayName = displayName) }
  }

  fun toggleExpanded(uri: String) {
    val current = _uiState.value.selectionState
    val expanded = current.expandedDirs.toMutableSet()
    if (expanded.contains(uri)) {
      expanded.remove(uri)
    } else {
      expanded.add(uri)
    }
    _uiState.update { it.copy(selectionState = current.copy(expandedDirs = expanded)) }
  }

  fun toggleSelected(uri: String) {
    val current = _uiState.value.selectionState
    val selected = current.selectedUris.toMutableSet()
    if (selected.contains(uri)) {
      selected.remove(uri)
    } else {
      selected.add(uri)
    }
    _uiState.update { it.copy(selectionState = current.copy(selectedUris = selected)) }
  }

  fun updateSuggestionApproval(uri: String, approved: Boolean) {
    val updated =
      _uiState.value.suggestions.map {
        if (it.sourceUri == uri) it.copy(approved = approved) else it
      }
    _uiState.update { it.copy(suggestions = updated, lastPlanSummary = updateSummary(updated)) }
  }

  fun updateSuggestionPath(uri: String, newPath: String) {
    val updated =
      _uiState.value.suggestions.map {
        if (it.sourceUri == uri) it.copy(targetRelativePath = newPath) else it
      }
    _uiState.update { it.copy(suggestions = updated, lastPlanSummary = updateSummary(updated)) }
  }

  fun updateSuggestionName(uri: String, newName: String) {
    val updated =
      _uiState.value.suggestions.map {
        if (it.sourceUri == uri) it.copy(suggestedName = newName) else it
      }
    _uiState.update { it.copy(suggestions = updated, lastPlanSummary = updateSummary(updated)) }
  }

  fun loadFileTree() {
    val rootUri = _uiState.value.rootUri ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val rootDoc = DocumentFile.fromTreeUri(appContext, rootUri)
      if (rootDoc == null) {
        _uiState.update { it.copy(errorMessage = "Unable to access the selected folder.") }
        return@launch
      }
      val tree = buildTree(rootDoc, rootDoc, depth = 0)
      _uiState.update { it.copy(fileTree = listOf(tree)) }
    }
  }

  fun generatePlan(model: Model) {
    val rootUri = _uiState.value.rootUri ?: return
    val destinationUri = _uiState.value.destinationUri ?: return
    val prompt = _uiState.value.prompt.trim()
    if (prompt.isEmpty()) {
      _uiState.update { it.copy(errorMessage = "Please enter a prompt before generating a plan.") }
      return
    }
    viewModelScope.launch(Dispatchers.Default) {
      if (model.instance == null) {
        _uiState.update { it.copy(errorMessage = "Model is not initialized yet.") }
        return@launch
      }
      FileOrganizerActionStore.clear()
      _uiState.update {
        it.copy(processing = true, suggestions = listOf(), modelResponse = "", errorMessage = "")
      }

      val selectedEntries = buildSelectedEntries(rootUri)
      if (selectedEntries.isEmpty()) {
        _uiState.update {
          it.copy(processing = false, errorMessage = "Select at least one file to organize.")
        }
        return@launch
      }

      val input =
        FileOrganizerInput(
          rootDisplayName = _uiState.value.rootDisplayName,
          rootUri = rootUri.toString(),
          prompt = prompt,
          files = selectedEntries,
        )
      val systemMessage =
        Message.of(getSystemPrompt(destinationUri, _uiState.value.destinationDisplayName))
      resetConversation(model = model, systemMessage = systemMessage)

      val instance = model.instance as LlmModelInstance
      val contents = listOf(Content.Text(buildInstruction(input)))
      instance
        .conversation
        .sendMessageAsync(Message.of(contents))
        .catch { error ->
          Log.e(TAG, "Failed to generate suggestions", error)
          _uiState.update {
            it.copy(
              errorMessage = error.message ?: "Failed to generate suggestions.",
              processing = false,
            )
          }
        }
        .onCompletion { _uiState.update { it.copy(processing = false) } }
        .collect { message ->
          _uiState.update {
            it.copy(modelResponse = processLlmResponse(it.modelResponse + message.toString()))
          }
        }
      val suggestions = buildSuggestionsFromActions()
      if (suggestions.isEmpty()) {
        _uiState.update {
          it.copy(
            errorMessage = "No suggestions were generated. Try refining your prompt.",
            processing = false,
          )
        }
        resetConversation(model = model, systemMessage = systemMessage)
        return@launch
      }
      val summary =
        FileOrganizerSummary(
          totalFiles = suggestions.size,
          approvedFiles = suggestions.count { it.approved },
          destinationRoot = _uiState.value.destinationDisplayName,
        )
      _uiState.update { it.copy(suggestions = suggestions, lastPlanSummary = summary) }
      resetConversation(model = model, systemMessage = systemMessage)
    }
  }

  fun applyPlan(onApplied: (String) -> Unit) {
    val destinationUri = _uiState.value.destinationUri ?: return
    val approved = _uiState.value.suggestions.filter { it.approved }
    if (approved.isEmpty()) {
      onApplied("Select at least one approved file to apply.")
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val resultMessage = applyApprovedSuggestions(destinationUri, approved)
      _uiState.update {
        it.copy(
          lastPlanSummary =
            it.lastPlanSummary?.copy(approvedFiles = approved.size) ?: it.lastPlanSummary
        )
      }
      withContext(Dispatchers.Main) { onApplied(resultMessage) }
    }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = "") }
  }

  fun resetState() {
    FileOrganizerActionStore.clear()
    _uiState.update { FileOrganizerUiState() }
  }

  fun getTools(): List<FileOrganizerTools> = tools

  private fun resetConversation(model: Model, systemMessage: Message) {
    LlmChatModelHelper.resetConversation(
      model = model,
      supportImage = false,
      supportAudio = false,
      systemMessage = systemMessage,
      tools = tools,
      enableConversationConstrainedDecoding = true,
    )
  }

  private fun buildSuggestionsFromActions(): List<FileOrganizationSuggestion> {
    return FileOrganizerActionStore.snapshot().filterIsInstance<OrganizeFileAction>().map {
      FileOrganizationSuggestion(
        sourceUri = it.sourceUri,
        targetRelativePath = it.targetRelativePath,
        suggestedName = it.suggestedName,
        reason = it.reason,
        approved = true,
      )
    }
  }

  private fun updateSummary(suggestions: List<FileOrganizationSuggestion>): FileOrganizerSummary? {
    val summary = _uiState.value.lastPlanSummary ?: return null
    return summary.copy(approvedFiles = suggestions.count { it.approved })
  }

  private fun buildSelectedEntries(rootUri: Uri): List<FileEntry> {
    val selectedUris = _uiState.value.selectionState.selectedUris
    if (selectedUris.isEmpty()) {
      return emptyList()
    }
    val entries = mutableListOf<FileEntry>()
    selectedUris.forEach { uriString ->
      val uri = Uri.parse(uriString)
      val doc = loadDocumentFile(uri) ?: return@forEach
      val entry = toFileEntry(rootUri, doc)
      if (entry != null) {
        entries.add(entry)
      } else if (doc.isDirectory) {
        entries.addAll(buildEntriesFromDirectory(rootUri, doc))
      }
    }
    return entries
  }

  private fun buildTree(
    rootDoc: DocumentFile,
    current: DocumentFile,
    depth: Int,
  ): FileTreeNode {
    val children =
      if (current.isDirectory) {
        current.listFiles().map { child -> buildTree(rootDoc, child, depth + 1) }
      } else {
        listOf()
      }
    val uri = current.uri.toString()
    return FileTreeNode(
      uri = uri,
      displayName = current.name ?: current.uri.lastPathSegment.orEmpty(),
      isDirectory = current.isDirectory,
      children = children,
      depth = depth,
      parentUri = rootDoc.uri.toString(),
    )
  }

  private fun buildEntriesFromDirectory(rootUri: Uri, directory: DocumentFile): List<FileEntry> {
    val results = mutableListOf<FileEntry>()
    directory.listFiles().forEach { child ->
      val entry = toFileEntry(rootUri, child)
      if (entry != null) {
        results.add(entry)
      } else if (child.isDirectory) {
        results.addAll(buildEntriesFromDirectory(rootUri, child))
      }
    }
    return results
  }

  private fun toFileEntry(rootUri: Uri, doc: DocumentFile): FileEntry? {
    if (doc.isDirectory) {
      return null
    }
    val relativePath = getRelativePath(rootUri, doc.uri)
    return FileEntry(
      uri = doc.uri.toString(),
      displayName = doc.name ?: doc.uri.lastPathSegment.orEmpty(),
      relativePath = relativePath,
      mimeType = doc.type ?: "application/octet-stream",
      sizeBytes = doc.length(),
    )
  }

  private fun loadDocumentFile(uri: Uri): DocumentFile? {
    return DocumentFile.fromSingleUri(appContext, uri)
      ?: DocumentFile.fromTreeUri(appContext, uri)
  }

  private fun getRelativePath(rootUri: Uri, childUri: Uri): String {
    val rootId = DocumentsContract.getTreeDocumentId(rootUri)
    val childId = DocumentsContract.getDocumentId(childUri)
    return if (childId == rootId) {
      ""
    } else if (childId.startsWith(rootId)) {
      childId.removePrefix(rootId).trimStart('/')
    } else {
      childUri.lastPathSegment.orEmpty()
    }
  }

  private fun buildInstruction(input: FileOrganizerInput): String {
    val json = JSONObject()
    json.put("rootDisplayName", input.rootDisplayName)
    json.put("rootUri", input.rootUri)
    json.put("prompt", input.prompt)
    val filesArray = JSONArray()
    input.files.forEach { entry ->
      val fileJson = JSONObject()
      fileJson.put("uri", entry.uri)
      fileJson.put("displayName", entry.displayName)
      fileJson.put("relativePath", entry.relativePath)
      fileJson.put("mimeType", entry.mimeType)
      fileJson.put("sizeBytes", entry.sizeBytes)
      filesArray.put(fileJson)
    }
    json.put("files", filesArray)
    return json.toString()
  }

  private fun getSystemPrompt(destinationUri: Uri, destinationName: String): String {
    return buildString {
      append("You are organizing files for the user. ")
      append("Use tool calls only. Suggest a folder path and clean name for every file. ")
      append("Destination root: $destinationName (${destinationUri}). ")
      append(
        "Use only relative folder paths (no leading slash). Keep file extensions unchanged. " +
          "Return one organizeFile call per file and include a short reason.",
      )
    }
  }

  private fun applyApprovedSuggestions(
    destinationUri: Uri,
    approved: List<FileOrganizationSuggestion>,
  ): String {
    val resolver = appContext.contentResolver
    val destinationDoc = DocumentFile.fromTreeUri(appContext, destinationUri)
    if (destinationDoc == null) {
      return "Unable to access destination folder."
    }
    var movedCount = 0
    var skippedCount = 0
    approved.forEach { suggestion ->
      val sourceUri = Uri.parse(suggestion.sourceUri)
      val sourceDoc = DocumentFile.fromSingleUri(appContext, sourceUri)
      if (sourceDoc == null) {
        skippedCount++
        return@forEach
      }
      val sanitizedPath = sanitizeRelativePath(suggestion.targetRelativePath) ?: run {
        skippedCount++
        return@forEach
      }
      val targetDir = ensureDirectory(destinationDoc, sanitizedPath)
      if (targetDir == null) {
        skippedCount++
        return@forEach
      }
      val safeName =
        sanitizeFileName(suggestion.suggestedName, sourceDoc.name ?: suggestion.suggestedName)
          ?: run {
            skippedCount++
            return@forEach
          }
      val finalName =
        ensureUniqueName(
          targetDir = targetDir,
          desiredName = safeName,
          originalName = sourceDoc.name ?: safeName,
        )
          ?: run {
            skippedCount++
            return@forEach
          }
      val parentUri = sourceDoc.parentFile?.uri
      if (parentUri == null) {
        skippedCount++
        return@forEach
      }
      try {
        val moved =
          DocumentsContract.moveDocument(
            resolver,
            sourceDoc.uri,
            parentUri,
            targetDir.uri,
          )
        if (moved != null) {
          val movedDoc = DocumentFile.fromSingleUri(appContext, moved)
          if (movedDoc != null) {
            val renameResult = movedDoc.renameTo(finalName)
            if (renameResult) {
              movedCount++
            } else {
              skippedCount++
            }
          } else {
            skippedCount++
          }
        } else {
          skippedCount++
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to move file", e)
        skippedCount++
      }
    }
    return "Moved $movedCount file(s), skipped $skippedCount."
  }

  private fun sanitizeRelativePath(path: String): String? {
    val trimmed = path.trim().trimStart('/').trimEnd('/')
    if (trimmed.isBlank()) {
      return ""
    }
    if (trimmed.contains("..") || trimmed.contains("\\") || trimmed.contains(":")) {
      return null
    }
    return trimmed
  }

  private fun sanitizeFileName(candidate: String, originalName: String): String? {
    val trimmed = candidate.trim()
    if (trimmed.isBlank() || trimmed.contains("/") || trimmed.contains("\\")) {
      return null
    }
    val originalExtension = originalName.substringAfterLast('.', "")
    val baseName = trimmed.substringBeforeLast('.', trimmed)
    if (baseName.isBlank()) {
      return null
    }
    return if (originalExtension.isNotEmpty()) {
      "$baseName.$originalExtension"
    } else {
      baseName
    }
  }

  private fun ensureUniqueName(
    targetDir: DocumentFile,
    desiredName: String,
    originalName: String,
  ): String? {
    if (targetDir.findFile(desiredName) == null) {
      return desiredName
    }
    val baseName = desiredName.substringBeforeLast('.', desiredName)
    val extension = desiredName.substringAfterLast('.', "")
    for (index in 2..99) {
      val candidate =
        if (extension.isNotEmpty()) {
          "$baseName ($index).$extension"
        } else {
          "$baseName ($index)"
        }
      if (targetDir.findFile(candidate) == null) {
        return candidate
      }
    }
    return if (targetDir.findFile(originalName) == null) originalName else null
  }

  private fun ensureDirectory(root: DocumentFile, relativePath: String): DocumentFile? {
    if (relativePath.isBlank()) {
      return root
    }
    var current = root
    val segments = relativePath.split("/").filter { it.isNotBlank() }
    for (segment in segments) {
      val existing = current.findFile(segment)
      current =
        if (existing != null && existing.isDirectory) {
          existing
        } else {
          current.createDirectory(segment) ?: return null
        }
    }
    return current
  }
}
