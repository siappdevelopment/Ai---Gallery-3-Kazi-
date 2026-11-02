package com.photovideo.gallerykz.memories.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.photovideo.gallerykz.memories.helper.GalleryLayoutPrefs
import com.photovideo.gallerykz.memories.helper.LanguagePrefs
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.helper.ThemePrefs
import com.photovideo.gallerykz.memories.repo.TrashRepository
import com.photovideo.gallerykz.memories.repo.VaultRepository
import java.util.Locale
import java.util.concurrent.Executors

/** Access to photos and videos as the system reports it. */
enum class MediaAccess { ALLOWED, LIMITED, DENIED }

/**
 * What the Settings screen shows next to its rows, as plain values (the screen formats them in the app's language):
 * every value is read from where it really lives, so nothing is stored twice.
 */
data class SettingState(
    val languageTag: String?,
    val themeMode: Int,
    val columns: Int,
    val notificationsOn: Boolean,
    val vaultSetUp: Boolean,
    /** Items in the Bin; null where the system Bin does not exist (before Android 11). */
    val binCount: Int?,
    val mediaAccess: MediaAccess,
    /** Used space of the phone's storage in bytes, null when it cannot be read. */
    val storageUsedBytes: Long?,
    val versionName: String,
)

class SettingViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableLiveData<SettingState>()
    val state: LiveData<SettingState> = _state

    private val vault = VaultRepository(application)
    private val trash = TrashRepository(application)

    /** Reads everything again (the screen comes to the front: a permission or the Bin may have changed meanwhile). */
    fun refresh() {
        worker.execute {
            _state.postValue(
                SettingState(
                    languageTag = currentLanguageTag(),
                    themeMode = effectiveTheme(),
                    columns = GalleryLayoutPrefs.getColumns(getApplication()),
                    notificationsOn = notificationsOn(),
                    vaultSetUp = vault.isSetUp,
                    binCount = binCount(),
                    mediaAccess = mediaAccess(),
                    storageUsedBytes = storageUsed(),
                    versionName = versionName(),
                ),
            )
        }
    }

    /** Light or Dark: the saved choice, or (before anything was chosen) the one the phone's own setting gives. */
    private fun effectiveTheme(): Int {
        val saved = ThemePrefs.get(getApplication())
        if (saved != ThemePrefs.SYSTEM) return saved
        val night = getApplication<Application>().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) ThemePrefs.DARK else ThemePrefs.LIGHT
    }

    /** The language of the app: the one chosen in the app, else the one saved by onboarding, else the phone's. */
    private fun currentLanguageTag(): String? {
        val chosen = AppCompatDelegate.getApplicationLocales()
        if (!chosen.isEmpty) return chosen.toLanguageTags().substringBefore(',')
        return LanguagePrefs.getSavedTag(getApplication()) ?: Locale.getDefault().toLanguageTag()
    }

    private fun notificationsOn(): Boolean {
        val app = getApplication<Application>()
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun binCount(): Int? {
        if (!trash.isSupported) return null
        return try {
            trash.load().size
        } catch (e: SecurityException) {
            null
        }
    }

    private fun mediaAccess(): MediaAccess {
        val app = getApplication<Application>()
        if (!MediaPermissionHelper.hasAccess(app)) return MediaAccess.DENIED
        if (Build.VERSION.SDK_INT >= 34) {
            val full = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            if (!full) return MediaAccess.LIMITED
        }
        return MediaAccess.ALLOWED
    }

    private fun storageUsed(): Long? = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.totalBytes - stat.availableBytes
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun versionName(): String = try {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
