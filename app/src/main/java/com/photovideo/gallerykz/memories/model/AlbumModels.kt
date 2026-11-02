package com.photovideo.gallerykz.memories.model

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map as mapPaging
import com.photovideo.gallerykz.memories.database.MediaEntity

/** What an album card or smart collection row opens. */
enum class AlbumKind(val key: String) {
    /** A folder on the device (MediaStore bucket). */
    DEVICE("device"),

    /** An album the user created in the app. */
    USER("user"),
    FAVORITES("favorites"),
    VIDEOS("videos"),
    SCREENSHOTS("screenshots"),
    RECENT("recent"),
    YEAR("year"),
    MONTH("month"),

    /** The photos of one person (a face group of People & faces); the id is the group's cluster id. */
    PERSON("person"),

    /** The photos of one place (photos with a GPS position in the same 0.1 degree square); the id is the place's cell. */
    PLACE("place"),

    /** "Made for you": the photos that are crisp, well lit and high quality (measured on the device). */
    BEST_PHOTOS("best_photos"),

    /** "Made for you": clear photos of one to three people, found with the on-device face scan. */
    BEST_PORTRAITS("best_portraits"),

    /** "Made for you": photos with a sign of an edit (made in this or another app) in the last 90 days. */
    RECENTLY_EDITED("recently_edited"),

    /** Every photo on the device (the "Photos" button of Search); videos are not part of it. */
    PHOTOS("photos"),

    /** The results of a file-name search (the query travels separately). */
    SEARCH("search");

    companion object {
        fun fromKey(key: String?): AlbumKind = entries.firstOrNull { it.key == key } ?: DEVICE
    }
}

/** One album card of the Albums tab. [covers] holds up to four item uids, newest first. */
data class AlbumCard(
    val key: String,
    val kind: AlbumKind,
    val id: Long,
    val name: String,
    val total: Int,
    val lastDate: Long,
    val covers: List<Long?>,
)

enum class AlbumSort { NEWEST, OLDEST, NAME }

/** Day headers between the items of a day (the same grid rows the Gallery tab uses). */
fun PagingData<MediaEntity>.withDayHeaders(): PagingData<MediaUiModel> =
    mapPaging<MediaEntity, MediaUiModel.Item> { MediaUiModel.Item(it) }
        .insertSeparators<MediaUiModel.Item, MediaUiModel> { before, after ->
            when {
                after == null -> null
                before == null || before.media.dayKey != after.media.dayKey -> MediaUiModel.DayHeader(after.media.dayKey)
                else -> null
            }
        }
