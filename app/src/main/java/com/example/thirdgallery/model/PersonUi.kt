package com.example.thirdgallery.model

/**
 * One person (a group of faces) as the screens show them. [name] is null until the user names the group.
 * The picture is the largest face of the group: the photo ([mediaId], [mediaType]) and the face's box as 0..1 shares.
 */
data class PersonUi(
    val clusterId: Long,
    val name: String?,
    val photoCount: Int,
    val mediaId: Long,
    val mediaType: Int,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

/**
 * The people of the library: [people] are the groups that can be trusted to be one person (a face seen in at least
 * three photos, or one the user named), most photos first; [unnamed] are smaller groups (two photos) that are not
 * sure enough to be a person yet and wait for a name.
 */
data class PeopleResult(
    val people: List<PersonUi>,
    val unnamed: List<PersonUi>,
)
