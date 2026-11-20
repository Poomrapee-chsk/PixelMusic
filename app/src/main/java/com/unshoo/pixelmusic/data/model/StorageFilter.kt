package com.unshoo.pixelmusic.data.model

enum class StorageFilter(val value: Int) {
    ALL(0),
    OFFLINE(1),
    ONLINE(2),
    DOWNLOADED_ONLY(3),

    /**
     * Internal variant of [ALL]: everything, except streaming songs that duplicate a song
     * already in the local library. Never selected directly by the user — it is what [ALL]
     * resolves to when the "Hide cloud duplicates" preference is on.
     */
    ALL_DEDUPLICATED(4);

    companion object {
        fun resolve(filter: StorageFilter, hideLocalMedia: Boolean, hideCloudDuplicates: Boolean): StorageFilter =
            when {
                hideLocalMedia -> ONLINE
                filter == ALL && hideCloudDuplicates -> ALL_DEDUPLICATED
                else -> filter
            }
    }
}
