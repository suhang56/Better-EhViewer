/*
 * Copyright 2026 Better-EhViewer contributors
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.gallerylist

/** Favourite state of one gallery, always resolved by gallery id, never by title. */
enum class FavoriteState {
    /** In the local favourites database. */
    LOCAL,

    /** In the E-Hentai/ExHentai cloud favourites. */
    CLOUD,

    /** Known not to be favourited (locally nor in the cloud). */
    NOT_FAVORITED,

    /** Cloud state unknown (not signed in, never synced, sync failed, conflicting data). */
    UNKNOWN,
    ;

    val isFavorited: Boolean get() = this == LOCAL || this == CLOUD
}

/**
 * What the gallery list page itself told us about one gallery: E-Hentai draws a
 * coloured border around favourited galleries for signed-in users.
 *
 * @param slot cloud favourite slot 0..9, [SLOT_UNKNOWN_COLOR] if favourited with
 *   an unrecognised colour, or [SLOT_NONE] if no favourite marker was present.
 * @param loginProven true if the page proved the session is valid (ExHentai page,
 *   or at least one favourite marker on the page). Without that proof "no marker"
 *   could also mean "silently logged out".
 */
data class InlineObservation(val slot: Int, val loginProven: Boolean, val observedAt: Long) {
    val favorited: Boolean get() = slot != SLOT_NONE

    companion object {
        const val SLOT_NONE = -2
        const val SLOT_UNKNOWN_COLOR = -1
    }
}

/** Persisted progress of the full cloud favourites synchronisation. */
data class CloudSyncState(
    /** Time of the last *complete* sync, 0 if never. */
    val lastFullSyncAt: Long = 0,
    /** Time of the last attempt (success or not), 0 if never. */
    val lastAttemptAt: Long = 0,
    /** Error of the last attempt, null if it did not fail. */
    val lastError: String? = null,
    /** Reason code of the last attempt if it stopped early ([CloudFavoriteSyncer.Result.Partial]), else null. */
    val lastPartial: String? = null,
) {
    fun isFresh(now: Long, ttlMillis: Long = DEFAULT_TTL): Boolean = lastFullSyncAt > 0 && now - lastFullSyncAt in 0..ttlMillis

    /**
     * A failed sync is retried after [retryMillis]; a partial one (e.g. more favourites than the
     * page cap) only after [ttlMillis], so huge favourite lists are not re-crawled every half hour.
     */
    fun shouldAutoSync(now: Long, ttlMillis: Long = DEFAULT_TTL, retryMillis: Long = DEFAULT_RETRY): Boolean {
        if (isFresh(now, ttlMillis)) return false
        if (lastAttemptAt == 0L) return true
        val window = if (lastPartial != null) ttlMillis else retryMillis
        return now - lastAttemptAt !in 0..window
    }

    companion object {
        const val DEFAULT_TTL = 24L * 60 * 60 * 1000
        const val DEFAULT_RETRY = 30L * 60 * 1000
    }
}

/**
 * Resolves the favourite state of galleries from all known sources.
 *
 * Priority:
 *  1. local favourites database
 *  2. the inline marker of the list page (fresh, from the server)
 *  3. the cloud favourites cache (from favourites pages / full sync / add & remove)
 *  4. a fresh complete sync proves absence
 *  5. otherwise UNKNOWN - never silently "not favourited"
 */
class FavoriteResolver(
    private val isLocalFavorite: (Long) -> Boolean,
    private val cloudCacheEntry: (Long) -> CloudFavoriteCache.Entry?,
    private val inlineObservation: (Long) -> InlineObservation?,
    private val syncState: CloudSyncState,
    private val signedIn: Boolean,
    private val cloudCacheEmpty: Boolean,
    private val now: Long,
    /**
     * True if this session was recently proven valid (a favourites page loaded). Then a list
     * page without favourite marker is trusted even when none of its galleries is favourited.
     */
    private val sessionVerified: Boolean = false,
) {
    fun resolve(gid: Long): FavoriteState {
        if (isLocalFavorite(gid)) return FavoriteState.LOCAL
        val cached = cloudCacheEntry(gid)
        val inline = inlineObservation(gid)
        if (inline != null) {
            if (inline.favorited) return FavoriteState.CLOUD
            // "No marker" means "not favourited" only if the session is known to be valid:
            // a silently logged-out session shows no markers at all.
            val trusted = inline.loginProven || sessionVerified
            if (cached != null) {
                // The page says "not favourited" but the cache says it is: the newer one wins.
                if (inline.observedAt < cached.updatedAt) return FavoriteState.CLOUD
                return if (trusted) FavoriteState.NOT_FAVORITED else FavoriteState.UNKNOWN
            }
            if (trusted && signedIn) return FavoriteState.NOT_FAVORITED
        }
        if (cached != null) return FavoriteState.CLOUD
        if (syncState.isFresh(now)) return FavoriteState.NOT_FAVORITED
        // Never signed in, never synced, nothing cached: there are no cloud favourites to speak of.
        if (!signedIn && syncState.lastFullSyncAt == 0L && cloudCacheEmpty) return FavoriteState.NOT_FAVORITED
        return FavoriteState.UNKNOWN
    }
}
