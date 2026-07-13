package eu.kanade.tachiyomi.extension.pt.littletyrant

import org.jsoup.nodes.Document

class Decoder {
    // The reader now embeds a `_proxyUrls` array of relative image-loader.php paths
    // instead of the old base64-encoded `var pages` array.
    fun extractPaths(document: Document, baseUrl: String): List<String> {
        val script = document.selectFirst("script:containsData(_proxyUrls)")?.data()
            ?: error("No image URLs")

        val match = PROXY_URLS_REGEX.find(script) ?: error("Unable to parse pages")

        return match.groupValues[1]
            .split(",")
            .map { it.trim().trim('"').trim('\'').replace("\\/", "/") }
            .filter { it.isNotEmpty() }
            .map { path -> if (path.startsWith("http")) path else baseUrl + path }
    }

    companion object {
        private val PROXY_URLS_REGEX = Regex(
            """var _proxyUrls\s*=\s*\[(.*?)\]""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
