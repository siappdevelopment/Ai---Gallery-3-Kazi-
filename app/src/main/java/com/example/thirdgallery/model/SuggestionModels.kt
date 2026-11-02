package com.example.thirdgallery.model

/** The three suggestions of the For you screen. */
enum class SuggestionKind { ALBUM, BURSTS, FAVORITES }

/** "Add these to an album": photos from one short period that are in no album yet ([fromMs]..[toMs] is that period). */
class AlbumSuggestion(
    val uids: List<Long>,
    val fromMs: Long,
    val toMs: Long,
    /** The oldest photo of the period; used to remember a dismissal. */
    val key: Long,
)

/** One burst: photos taken within moments of each other in one folder, newest first, and the shot that looks best. */
class BurstGroup(
    val uids: List<Long>,
    val bestUid: Long,
    val dateMs: Long,
)

/** "Favorite these best shots": sharp, well-lit photos that are not favorites yet. */
class FavoriteSuggestion(val uids: List<Long>)

/** Everything the suggestions are made of right now (a missing or empty part has no suggestion). */
class SuggestionSet(
    val album: AlbumSuggestion?,
    val bursts: List<BurstGroup>,
    val favorites: FavoriteSuggestion?,
)

/** One suggestion row of For you. [covers] are up to three photos shown on it, [uids] the photos it is about. */
data class SuggestionCard(
    val kind: SuggestionKind,
    val count: Int,
    val covers: List<Long>,
    val uids: List<Long>,
    val fromMs: Long,
    val toMs: Long,
)
