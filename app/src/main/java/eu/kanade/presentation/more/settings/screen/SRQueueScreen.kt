package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.sr.SRCacheManager
import eu.kanade.tachiyomi.data.sr.SRQueueState
import eu.kanade.tachiyomi.data.sr.SuperResolutionSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.superresolution.ChapterMetadata
import mihon.core.superresolution.SRQueueItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SRQueueScreen : VoyagerScreen {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val navigator = cafe.adriel.voyager.navigator.LocalNavigator.currentOrThrow
        val srSync = remember { Injekt.get<SuperResolutionSync>() }
        val queueState by srSync.queueProcessor.state.collectAsState()
        val haptic = LocalHapticFeedback.current

        var completedChapters by remember { mutableStateOf<List<Pair<Long, ChapterMetadata>>>(emptyList()) }

        val selectedInProgress = remember { mutableStateListOf<Long>() }
        var isSelectingInProgress by remember { mutableStateOf(false) }

        val selectedCompleted = remember { mutableStateListOf<Long>() }
        val completedAllSelected by remember(completedChapters) {
            derivedStateOf { selectedCompleted.size == completedChapters.size && completedChapters.isNotEmpty() }
        }
        val completedAnySelected by remember(completedChapters) {
            derivedStateOf { selectedCompleted.isNotEmpty() }
        }
        val inProgressAnySelected by remember(queueState.inProgress) {
            derivedStateOf { selectedInProgress.isNotEmpty() }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                completedChapters = SRCacheManager.getDiskCache().getCompletedChapters()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (isSelectingInProgress)
                                stringResource(MR.strings.sr_queue_selected_count, selectedInProgress.size)
                            else
                                stringResource(MR.strings.pref_sr_batch_queue),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSelectingInProgress) {
                                selectedInProgress.clear()
                                isSelectingInProgress = false
                            } else {
                                navigator.pop()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        if (completedChapters.isNotEmpty()) {
                            TextButton(onClick = {
                                if (completedAllSelected) selectedCompleted.clear()
                                else {
                                    selectedCompleted.clear()
                                    selectedCompleted.addAll(completedChapters.map { it.first })
                                }
                            }) {
                                Text(
                                    if (completedAllSelected) stringResource(MR.strings.action_select_inverse)
                                    else stringResource(MR.strings.action_select_all),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (inProgressAnySelected || completedAnySelected || completedChapters.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (inProgressAnySelected) {
                            TextButton(
                                onClick = {
                                    selectedInProgress.toList().forEach { srSync.queueProcessor.cancel(it) }
                                    selectedInProgress.clear()
                                    isSelectingInProgress = false
                                },
                            ) {
                                Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(MR.strings.sr_queue_cancel_selected))
                            }
                        }
                        TextButton(
                            onClick = {
                                val diskCache = SRCacheManager.getDiskCache()
                                selectedCompleted.toList().forEach { diskCache.removeChapter(it) }
                                completedChapters = completedChapters.filter { (id, _) -> id !in selectedCompleted }
                                selectedCompleted.clear()
                            },
                            enabled = completedAnySelected,
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(MR.strings.sr_queue_delete_selected))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                val diskCache = SRCacheManager.getDiskCache()
                                completedChapters.forEach { (id, _) -> diskCache.removeChapter(id) }
                                completedChapters = emptyList()
                                selectedCompleted.clear()
                            },
                            enabled = completedChapters.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(MR.strings.sr_queue_clear_all))
                        }
                    }
                }
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                item {
                    Text(
                        text = stringResource(MR.strings.sr_queue_in_progress),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }

                if (queueState.inProgress.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.sr_queue_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    items(queueState.inProgress, key = { it.chapterId }) { item ->
                        InProgressItem(
                            item = item,
                            isSelecting = isSelectingInProgress,
                            checked = item.chapterId in selectedInProgress,
                            onLongClick = {
                                if (!isSelectingInProgress) {
                                    isSelectingInProgress = true
                                    selectedInProgress.add(item.chapterId)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onClick = {
                                if (isSelectingInProgress) {
                                    if (item.chapterId in selectedInProgress) selectedInProgress.remove(item.chapterId)
                                    else selectedInProgress.add(item.chapterId)
                                    if (selectedInProgress.isEmpty()) isSelectingInProgress = false
                                }
                            },
                            onCancel = { srSync.queueProcessor.cancel(it) },
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }

                item {
                    Text(
                        text = stringResource(MR.strings.sr_queue_completed),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    )
                }

                if (completedChapters.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.sr_queue_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    val grouped = completedChapters.groupBy { (_, meta) -> meta.mangaTitle }
                    grouped.forEach { (mangaTitle, chapters) ->
                        item {
                            Text(
                                text = mangaTitle,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(chapters, key = { it.first }) { (chapterId, meta) ->
                            CompletedItem(
                                chapterId = chapterId,
                                meta = meta,
                                checked = chapterId in selectedCompleted,
                                onToggle = { id ->
                                    if (selectedCompleted.contains(id)) selectedCompleted.remove(id)
                                    else selectedCompleted.add(id)
                                },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InProgressItem(
    item: SRQueueItem,
    isSelecting: Boolean,
    checked: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onCancel: (Long) -> Unit,
) {
    val progress = if (item.totalPages > 0) item.processedPages.toFloat() / item.totalPages else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelecting) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.chapterName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.mangaTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                if (item.totalPages > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${item.processedPages}/${item.totalPages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!isSelecting) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { onCancel(item.chapterId) }) {
                Icon(Icons.Outlined.Cancel, contentDescription = null)
            }
        }
    }
}

@Composable
private fun CompletedItem(
    chapterId: Long,
    meta: ChapterMetadata,
    checked: Boolean,
    onToggle: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(chapterId) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta.chapterName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${meta.pageCount} pages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
