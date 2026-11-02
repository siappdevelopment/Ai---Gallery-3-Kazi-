package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.database.MediaTypes
import com.photovideo.gallerykz.memories.helper.EditRenderer
import com.photovideo.gallerykz.memories.helper.MediaDetailsReader
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.model.Adjustment
import com.photovideo.gallerykz.memories.model.EditState
import com.photovideo.gallerykz.memories.model.EditTool
import com.photovideo.gallerykz.memories.model.Stroke
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.io.IOException
import java.util.concurrent.Executors

/** What happened to a Save. */
sealed class EditResult {
    object Saved : EditResult()
    class Failed(@StringRes val message: Int) : EditResult()
}

/**
 * The photo editor: the photo to edit (a preview copy of it), the current [EditState] with its undo and redo history, the
 * selected tool and adjustment, and Save, which writes the edited picture as a new photo and leaves the original untouched.
 * A slider is changed "live" (no history entry per step) and committed when the finger lifts.
 */
class EditMediaViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    val uid: Long = state.get<Long>(ARG_UID) ?: 0L

    @Volatile
    private var media: MediaEntity? = null

    private val _source = MutableLiveData<Bitmap?>()
    /** The photo at preview size; null until it is read (see [loadFailed]). */
    val source: LiveData<Bitmap?> = _source

    private val _loadFailed = MutableLiveData(false)
    val loadFailed: LiveData<Boolean> = _loadFailed

    private val _edit = MutableLiveData(EditState())
    val edit: LiveData<EditState> = _edit

    private val _canUndo = MutableLiveData(false)
    val canUndo: LiveData<Boolean> = _canUndo

    private val _canRedo = MutableLiveData(false)
    val canRedo: LiveData<Boolean> = _canRedo

    private val _tool = MutableLiveData(EditTool.entries.getOrNull(state.get<Int>(KEY_TOOL) ?: 0) ?: EditTool.ADJUST)
    val tool: LiveData<EditTool> = _tool

    private val _adjustment = MutableLiveData(Adjustment.entries.getOrNull(state.get<Int>(KEY_ADJUSTMENT) ?: 0) ?: Adjustment.BRIGHTNESS)
    val adjustment: LiveData<Adjustment> = _adjustment

    private val _saving = MutableLiveData(false)
    val saving: LiveData<Boolean> = _saving

    private val _result = MutableLiveData<Event<EditResult>>()
    val result: LiveData<Event<EditResult>> = _result

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    /** The state before a live change began; it becomes one history entry when the change is committed. */
    private var liveBase: EditState? = null

    private val current: EditState get() = _edit.value ?: EditState()

    init {
        worker.execute {
            val item = repository.find(uid)?.takeIf { it.mediaType == MediaTypes.IMAGE }
            media = item
            val bitmap = item?.let { EditRenderer.decode(getApplication<Application>().contentResolver, MediaUris.ofUid(uid), PREVIEW_SIDE) }
            if (bitmap == null) _loadFailed.postValue(true) else _source.postValue(bitmap)
        }
    }

    fun selectTool(tool: EditTool) {
        commitLive()
        state[KEY_TOOL] = tool.ordinal
        _tool.value = tool
    }

    fun selectAdjustment(adjustment: Adjustment) {
        state[KEY_ADJUSTMENT] = adjustment.ordinal
        _adjustment.value = adjustment
    }

    /** A change that is one history entry (a button, a swatch, a finished stroke). */
    fun update(change: (EditState) -> EditState) {
        commitLive()
        val before = current
        val after = change(before)
        if (after == before) return
        undoStack.addLast(before)
        redoStack.clear()
        _edit.value = after
        publishHistory()
    }

    /** A change that is still going on (a slider, typing): shown at once, one history entry when [commitLive] is called. */
    fun updateLive(change: (EditState) -> EditState) {
        if (liveBase == null) liveBase = current
        _edit.value = change(current)
    }

    fun commitLive() {
        val base = liveBase ?: return
        liveBase = null
        if (base == current) return
        undoStack.addLast(base)
        redoStack.clear()
        publishHistory()
    }

    fun addStroke(stroke: Stroke) = update { it.copy(strokes = it.strokes + stroke) }

    fun undo() {
        commitLive()
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _edit.value = previous
        publishHistory()
    }

    fun redo() {
        commitLive()
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _edit.value = next
        publishHistory()
    }

    /** Back to the untouched photo (still undoable). */
    fun reset() = update { EditState(color = it.color, brushSize = it.brushSize) }

    val hasChanges: Boolean get() = current.isEdited

    private fun publishHistory() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** Saves the edited picture as a new photo ("Pictures/Edited", Android 10+); never twice at once. */
    fun save() {
        commitLive()
        if (_saving.value == true) return
        val item = media ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _result.value = Event(EditResult.Failed(R.string.viewer_unsupported))
            return
        }
        _saving.value = true
        val edit = current
        worker.execute {
            val outcome = try {
                write(item, edit)
            } catch (e: IOException) {
                EditResult.Failed(R.string.edit_save_failed)
            } catch (e: SecurityException) {
                EditResult.Failed(R.string.edit_save_failed)
            }
            if (outcome is EditResult.Saved) repository.sync(true) { }
            _saving.postValue(false)
            _result.postValue(Event(outcome))
        }
    }

    private fun write(item: MediaEntity, edit: EditState): EditResult {
        val app = getApplication<Application>()
        val bitmap = EditRenderer.render(app, MediaUris.ofUid(uid), edit, SAVE_SIDE) ?: return EditResult.Failed(R.string.edit_save_failed)
        val resolver = app.contentResolver
        val base = MediaDetailsReader.fileName(app, item)?.let { if (it.contains('.')) it.substringBeforeLast('.') else it }?.takeIf { it.isNotBlank() } ?: "IMG"
        val values = ContentValues().apply {
            // "_edited" in the name is also how Recently edited recognises edits made here.
            put(MediaStore.MediaColumns.DISPLAY_NAME, base + "_edited.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Edited")
            put(MediaStore.MediaColumns.DATE_TAKEN, item.dateMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return EditResult.Failed(R.string.edit_save_failed)
        return try {
            val stream = resolver.openOutputStream(target) ?: throw IOException("no output")
            stream.use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw IOException("compress") }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            bitmap.recycle()
            EditResult.Saved
        } catch (e: IOException) {
            // No broken half-written file is left behind.
            resolver.delete(target, null, null)
            EditResult.Failed(R.string.edit_save_failed)
        }
    }

    companion object {
        const val ARG_UID = "uid"
        private const val KEY_TOOL = "tool"
        private const val KEY_ADJUSTMENT = "adjustment"
        private const val PREVIEW_SIDE = 1280
        private const val SAVE_SIDE = 2400
        private const val JPEG_QUALITY = 92
        private val worker = Executors.newSingleThreadExecutor()
    }
}
