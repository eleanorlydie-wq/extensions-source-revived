package eu.kanade.tachiyomi.extension.tr.shadowceviri

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga

class ShadowCeviri : ZeistManga("Shadow Çeviri", "https://shadowceviri.blogspot.com", "tr") {

    // ============================== Popular ===============================
    // Site's popular widget now renders as <div class="widget PopularPosts current" id="PopularPosts3">
    // containing <article ...><div class="item-thumbnail"><img src=...></div><h3 class="post-title"><a href=...>Title</a></h3></article>
    // Only the ".current" tab (weekly) is used to avoid duplicate entries from the monthly/yearly tabs.
    override val popularMangaSelector = "div.PopularPosts.current article"
    override val popularMangaSelectorTitle = "h3.post-title > a"
    override val popularMangaSelectorUrl = "h3.post-title > a"

    // ============================== Chapters ==============================
    // Manga pages now render <div id="clwd" class="bixbox bxcl epcheck"><script>clwd.run('Title');</script></div>
    // (the old #myUL > script mechanism is gone from the template), so let the theme's
    // default #clwd detection in getChapterFeedUrl() handle it instead of forcing the old feed.
    // The blog's actual post label for chapters is "Chapter" (confirmed via
    // /feeds/posts/default/-/Chapter/<title>?alt=json returning entries with category term "Chapter"),
    // not the Turkish "Bölüm" that was previously hardcoded.
    override val chapterCategory = "Chapter"
}
