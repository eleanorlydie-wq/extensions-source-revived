package eu.kanade.tachiyomi.extension.all.hentaitorrentimages

import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import java.io.IOException

/**
 * The app's built-in torrent server. Older app versions lack it (or part of it), which surfaces as
 * linkage errors; those are turned into a readable message.
 */
internal object TorrentServer {

    fun info(torrentUrl: String, title: String): TorrentInfo = call {
        try {
            TorrentUtils.getTorrentInfo(torrentUrl, title)
        } catch (_: DeadTorrentException) {
            throw Exception("Dead torrent: the torrent server or its peers didn't answer")
        }
    }

    fun streamUrl(file: TorrentFile): String = call { TorrentUtils.getStreamUrl(file) }

    fun keepAlive() {
        if (!call { TorrentUtils.keepServerAlive() }) throw IOException("The torrent server didn't start")
    }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (_: LinkageError) {
        throw IOException(UPDATE_APP)
    }

    private const val UPDATE_APP = "Reading torrents needs the app's torrent server: update the app to v0.18.1.36 or newer"
}
