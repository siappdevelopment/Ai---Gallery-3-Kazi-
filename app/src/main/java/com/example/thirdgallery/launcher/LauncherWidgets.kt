package com.example.thirdgallery.launcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.example.thirdgallery.R
import com.example.thirdgallery.activity.SearchActivity
import com.example.thirdgallery.helper.OnboardingPrefs

/** The home-screen widgets a user can pin from the widget picker. */
enum class LauncherWidgetType(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val preview: Int,
    val provider: Class<out AppWidgetProvider>,
) {
    GALLERY(R.string.launcher_widget_gallery_title, R.string.launcher_widget_gallery_desc, R.drawable.widget_preview_gallery, GalleryWidgetProvider::class.java),
    SEARCH(R.string.launcher_widget_search_title, R.string.launcher_widget_search_desc, R.drawable.widget_preview_search, SearchWidgetProvider::class.java),
    CAMERA(R.string.launcher_widget_camera_title, R.string.launcher_widget_camera_desc, R.drawable.widget_preview_camera, CameraWidgetProvider::class.java),
}

/** Asks the home screen to pin a widget (Android 8 and up); the user confirms in the launcher's own dialog. */
object LauncherWidgetPinner {

    enum class Result { REQUESTED, NOT_SUPPORTED, FAILED }

    fun request(context: Context, type: LauncherWidgetType): Result {
        val manager = context.getSystemService(AppWidgetManager::class.java) ?: return Result.NOT_SUPPORTED
        if (!manager.isRequestPinAppWidgetSupported) return Result.NOT_SUPPORTED
        return try {
            if (manager.requestPinAppWidget(ComponentName(context, type.provider), null, null)) Result.REQUESTED else Result.FAILED
        } catch (e: Exception) {
            Result.FAILED
        }
    }
}

/** One tap target: the whole widget opens its destination. */
abstract class BaseLauncherWidgetProvider : AppWidgetProvider() {

    @get:LayoutRes
    protected abstract val layoutRes: Int

    protected abstract fun target(context: Context): Intent

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, layoutRes)
        val intent = target(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            layoutRes,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pending)
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }
}

/** Opens the launcher on the gallery page. */
class GalleryWidgetProvider : BaseLauncherWidgetProvider() {
    override val layoutRes = R.layout.widget_gallery
    override fun target(context: Context): Intent = LauncherNav.galleryIntent(context)
}

class SearchWidgetProvider : BaseLauncherWidgetProvider() {
    override val layoutRes = R.layout.widget_search

    // Before the onboarding is finished the search screen has nothing to search: open the gallery flow instead.
    override fun target(context: Context): Intent =
        if (OnboardingPrefs.getStep(context) == OnboardingPrefs.STEP_DONE) SearchActivity.intent(context) else LauncherNav.galleryIntent(context)
}

class CameraWidgetProvider : BaseLauncherWidgetProvider() {
    override val layoutRes = R.layout.widget_camera
    override fun target(context: Context): Intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
}
