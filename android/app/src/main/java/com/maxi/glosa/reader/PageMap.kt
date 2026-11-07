package com.maxi.glosa.reader

/**
 * Pairs places in an EPUB that Glosa converted from a PDF with the pages of that PDF, through the
 * EPUB's page list: one anchor where each page begins, plus a repeated one atop a document that
 * opens mid-page (see [com.maxi.glosa.convert.Sectioner]).
 */
class PageMap private constructor(private val anchors: List<Anchor>) {
    /** [resource] is a document's href; [fragment] the id of the page's anchor in it. */
    data class Anchor(val resource: String, val fragment: String, val page: Int) {
        val isContinuation: Boolean get() = fragment.endsWith(CONTINUATION)
    }

    /** Where a page begins: the anchor, and roughly how far through its document. */
    data class Place(val resource: String, val fragment: String, val progression: Double)

    /** The page shown at [progression] through [resource], taking a document's pages to be of even length. */
    fun pageAt(resource: String, progression: Double): Int? {
        val pages = anchors.filter { it.resource == resource }
        if (pages.isEmpty()) return null
        val index = (progression * pages.size).toInt().coerceIn(0, pages.lastIndex)
        return pages[index].page
    }

    /** Where [page] begins, or the closest page before it with text of its own, like a blank page's neighbor. */
    fun placeOf(page: Int): Place? {
        val anchor = anchors.firstOrNull { it.page == page && !it.isContinuation }
            ?: anchors.lastOrNull { it.page < page && !it.isContinuation }
            ?: return null
        val inResource = anchors.filter { it.resource == anchor.resource }
        return Place(anchor.resource, anchor.fragment, inResource.indexOf(anchor).toDouble() / inResource.size)
    }

    companion object {
        private const val CONTINUATION = "c"

        /** [links] are the page list's hrefs, fragment included, with their labels: the PDF's page numbers. */
        fun of(links: List<Pair<String, String>>): PageMap = PageMap(
            links.mapNotNull { (href, label) ->
                val page = label.trim().toIntOrNull() ?: return@mapNotNull null
                val fragment = href.substringAfter('#', "").ifEmpty { return@mapNotNull null }
                Anchor(href.substringBefore('#'), fragment, page)
            },
        )
    }
}
