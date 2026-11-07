package com.maxi.glosa.reader

import android.content.Context
import android.content.Intent
import com.maxi.glosa.data.BookFormat
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

object Readers {
    fun intent(context: Context, bookId: Long, format: BookFormat): Intent = when (format) {
        BookFormat.Epub -> ReaderActivity.intent(context, bookId)
        BookFormat.Pdf -> PdfReaderActivity.intent(context, bookId)
    }
}

fun Publication.pageMap(): PageMap = PageMap.of(pageList.map { it.url().toString() to it.title.orEmpty() })

/** A place in the EPUB reached through a page's anchor; the progression stands in should the anchor be gone. */
fun PageMap.Place.locator(): Locator? {
    val href = Url(resource) ?: return null
    return Locator(href, MediaType.XHTML, locations = Locator.Locations(fragments = listOf(fragment), progression = progression))
}
