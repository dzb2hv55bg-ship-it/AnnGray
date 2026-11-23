package ru.momento.app.domain

/** A single MOMENTO clip. The five-second ceiling is a product rule, not a suggestion. */
data class Moment(
    val id: String,
    val ownerId: String,
    val localUri: String,
    val capturedAtEpochMillis: Long,
    val durationMillis: Long,
    val caption: String? = null,
    val uploadState: UploadState = UploadState.LOCAL_ONLY,
    val audienceSpaceIds: Set<String> = emptySet()
) {
    init {
        require(durationMillis in 1L..MAX_MOMENT_DURATION_MS) {
            "A MOMENTO clip must be between 1 ms and 5,000 ms"
        }
    }

    companion object {
        const val MAX_MOMENT_DURATION_MS = 5_000L
    }
}

enum class UploadState {
    LOCAL_ONLY,
    QUEUED,
    UPLOADING,
    UPLOADED_PRIVATE,
    PUBLISHED,
    FAILED
}

enum class SpaceKind { CIRCLE, INTEREST_ROOM }
enum class SpaceVisibility { PRIVATE, PUBLIC }
enum class JoinPolicy { INVITE_ONLY, OPEN, APPROVAL }

data class Space(
    val id: String,
    val name: String,
    val description: String,
    val kind: SpaceKind,
    val visibility: SpaceVisibility,
    val joinPolicy: JoinPolicy,
    val memberCount: Int
)
