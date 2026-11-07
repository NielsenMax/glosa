package com.maxi.glosa.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfToEpubTest {
    @Test
    fun `joins a paragraph across a page break and puts the hyphenated word back together`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("Dealers quote the prices at which they are willing to trade with", 100f, indent = 15f)
                        line("anyone who asks. The difference between both prices is what mar-", 112f)
                    },
                    page {
                        line("ket makers earn for offering immediacy to impatient traders.", 100f, isLast = true)
                    },
                ),
            ),
        )

        assertEquals(
            listOf(
                "<span id=\"p1\" epub:type=\"pagebreak\" role=\"doc-pagebreak\" aria-label=\"1\"></span>" +
                    "Dealers quote the prices at which they are willing to trade with anyone who asks. The difference between both prices is what " +
                    "mar<span id=\"p2\" epub:type=\"pagebreak\" role=\"doc-pagebreak\" aria-label=\"2\"></span>ket makers earn for offering immediacy to impatient traders.",
            ),
            epub.paragraphs(),
        )
    }

    @Test
    fun `keeps the hyphen of a compound broken at a line end when the book uses both halves as words`() {
        val paragraph = paragraphOf(
            "Dealers earn the spread for supplying liquidity to traders who",
            "cannot wait. Patient traders who post limit orders are liquidity-",
            "supplying traders too, and they earn the same spread.",
        )

        assertTrue(paragraph, paragraph.contains("are liquidity-supplying traders too"))
    }

    @Test
    fun `keeps the hyphen of a word the book hyphenates even where it opens a line`() {
        val paragraph = paragraphOf(
            "Brokers send orders for the stocks on the list to the floor, and the",
            "odd-numbered ones go to the dealers who trade them upstairs. Even and odd-",
            "numbered lists alternate from one trading day to the next.",
        )

        assertTrue(paragraph, paragraph.contains("Even and odd-numbered lists"))
    }

    @Test
    fun `keeps the hyphens of a longer compound broken after one of them`() {
        val paragraph = paragraphOf(
            "Few stocks list on an exchange abroad, and most corporate bonds",
            "and nearly all municipal bonds, including those of small towns, trade over-the-",
            "counter, where dealers quote them by telephone to their clients.",
        )

        assertTrue(paragraph, paragraph.contains("trade over-the-counter, where"))
    }

    @Test
    fun `keeps the hyphens of a longer compound broken before one of them`() {
        val paragraph = paragraphOf(
            "When traders buy and sell at random, transaction prices bounce",
            "between the bid and the ask that dealers quote in a steady back-",
            "and-forth that looks like volatility but is only the spread.",
        )

        assertTrue(paragraph, paragraph.contains("a steady back-and-forth that"))
    }

    @Test
    fun `joins a word broken after a prefix that is no word of its own`() {
        val paragraph = paragraphOf(
            "Dealers who cast a wide net see more orders than the others do,",
            "and those among them who study the market closely enough to fore-",
            "cast the order flow set better quotes than the rest of them.",
        )

        assertTrue(paragraph, paragraph.contains("enough to forecast the order"))
    }

    @Test
    fun `joins a word whose second half shows up only where it ends a broken word`() {
        val paragraph = paragraphOf(
            "Some exchanges permit block trades off the floor at any hour,",
            "but most of the others close the floor to them at noon and stop permit-",
            "ting them once the specialist has quoted the stock for the day.",
        )

        assertTrue(paragraph, paragraph.contains("stop permitting them"))
    }

    @Test
    fun `joins a word broken where a half too short to judge happens to be a word`() {
        val paragraph = paragraphOf(
            "The exchange is a body that lets any trader quote any stock it",
            "lists, and it charges the same fee for every seat, so any-",
            "body with a seat can post a bid or an offer on the floor.",
        )

        assertTrue(paragraph, paragraph.contains("so anybody with a seat"))
    }

    @Test
    fun `spells a V that the scan read as U the way the rest of the book spells it`() {
        val paragraph = paragraphOf(
            "The value of a quote depends on the value traders put on time, and",
            "the value of time is the value of what they forgo. Ualue traders buy",
            "what is cheap relative to its fundamental value.",
        )

        assertTrue(paragraph, paragraph.contains("forgo. Value traders buy"))
    }

    @Test
    fun `leaves a word with a U as it is when the book never spells it with a V`() {
        val paragraph = paragraphOf(
            "The value of a quote depends on the value traders put on time, and",
            "the value of time is the value of what they forgo. On the Bourse",
            "brokers quote what dealers in other markets value.",
        )

        assertTrue(paragraph, paragraph.contains("On the Bourse brokers"))
    }

    @Test
    fun `leaves the U of an abbreviation alone in a book that cites cases with v`() {
        val paragraph = paragraphOf(
            "The courts heard SEC v. Smith, SEC v. Jones, SEC v. Brown, SEC v.",
            "Green and SEC v. White before the U.S. markets changed the rules",
            "that dealers follow when they quote.",
        )

        assertTrue(paragraph, paragraph.contains("before the U.S. markets"))
    }

    @Test
    fun `leaves the end of a word broken across lines to be read with the rest of it`() {
        val paragraph = paragraphOf(
            "Spreads vary with volume, vary with volatility, vary with the hour and",
            "news. They vary with the season, and they vary least in Jan-",
            "uary, when dealers have reset their books.",
        )

        assertTrue(paragraph, paragraph.contains("least in January, when"))
    }

    @Test
    fun `opens a paragraph at every indented first line`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("The first paragraph opens the chapter and goes on for a while", 100f, indent = 15f)
                        line("until it ends on this short line.", 112f, isLast = true)
                        line("A second paragraph opens with an indent, like any other one", 124f, indent = 15f)
                        line("and it closes right here.", 136f, isLast = true)
                    },
                ),
            ),
        )

        assertEquals(
            listOf(
                "<span id=\"p1\" epub:type=\"pagebreak\" role=\"doc-pagebreak\" aria-label=\"1\"></span>" +
                    "The first paragraph opens the chapter and goes on for a while until it ends on this short line.",
                "A second paragraph opens with an indent, like any other one and it closes right here.",
            ),
            epub.paragraphs(),
        )
    }

    @Test
    fun `leaves running heads and page numbers out of the text`() {
        val pages = (1..6).map { number ->
            page {
                text("TRADING AND EXCHANGES", x = 150f, baseline = 30f, size = 8f)
                line("Page $number tells how traders arrange their trades with dealers", 100f, indent = 15f)
                line("and brokers, and what each of them earns.", 112f, isLast = true)
                text("$number", x = 195f, baseline = 570f, size = 8f)
            }
        }

        val text = convert(FakePdf(pages)).body()

        assertFalse(text.contains("TRADING AND EXCHANGES"))
        assertFalse("folios did not turn into footnotes:\n$text", text.contains("<aside"))
    }

    @Test
    fun `splits chapters at the bookmarks and lists where every page begins`() {
        val pdf = FakePdf(
            pages = listOf(
                chapterPage("Dealers"),
                bodyPage(),
                chapterPage("Brokers"),
                bodyPage(),
            ),
            outline = listOf(OutlineEntry("Dealers", 0, 0), OutlineEntry("Brokers", 2, 0)),
        )

        val epub = convert(pdf)

        assertEquals(listOf("s001.xhtml", "s002.xhtml"), epub.spine())
        assertEquals(listOf("s001.xhtml" to "Dealers", "s002.xhtml" to "Brokers"), epub.links("toc"))
        assertEquals(
            listOf("s001.xhtml#p1" to "1", "s001.xhtml#p2" to "2", "s002.xhtml#p3" to "3", "s002.xhtml#p4" to "4"),
            epub.links("page-list"),
        )
    }

    @Test
    fun `finds chapters by their headings when the PDF has no bookmarks`() {
        val pages = listOf(chapterPage("Dealers")) + List(3) { bodyPage() } + listOf(chapterPage("Brokers")) + List(3) { bodyPage() }

        val epub = convert(FakePdf(pages))

        assertEquals(listOf("s001.xhtml", "s002.xhtml"), epub.spine())
        assertEquals(listOf("s001.xhtml" to "Dealers", "s002.xhtml" to "Brokers"), epub.links("toc"))
    }

    @Test
    fun `sets a footnote apart after the paragraph it interrupts`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("Specialists keep a book of the limit orders that brokers leave", 100f, indent = 15f)
                        line("with them, and they trade for their own account when nobody else", 112f)
                        text("1 The book was a real notebook until the nineties.", x = 50f, baseline = 540f, size = 8f)
                    },
                    page {
                        line("will take the other side of an order.", 100f, isLast = true)
                    },
                ),
            ),
        )

        val body = epub.body()
        val paragraphEnd = body.indexOf("other side of an order.</p>")
        val note = body.indexOf("<aside class=\"notes\"><p>1 The book was a real notebook until the nineties.</p></aside>")
        assertTrue("the note follows the paragraph:\n$body", paragraphEnd in 0 until note)
    }

    @Test
    fun `crops a drawing between two paragraphs into a figure`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("The figure below shows the bid and the ask over a trading day", 100f, indent = 15f)
                        line("for one stock, quote by quote, as the dealers revised them all", 112f)
                        line("day long.", 124f, isLast = true)
                        drawing(Box(80f, 150f, 320f, 300f))
                        line("Notice how the spread narrows as the market opens and widens", 340f, indent = 15f)
                        line("again before the close, when dealers want to go home flat and", 352f)
                        line("quote less.", 364f, isLast = true)
                    },
                ),
            ),
        )

        val image = Regex("<img src=\"(img/[^\"]+)\"").find(epub.body())?.groupValues?.get(1)
        assertTrue("the body shows the figure:\n${epub.body()}", image != null)
        assertTrue("the EPUB carries the image", "OEBPS/$image" in epub.entries)
        assertTrue(epub.body().indexOf("day long.</p>") < epub.body().indexOf("<img") && epub.body().indexOf("<img") < epub.body().indexOf("Notice how"))
    }

    @Test
    fun `starts a page that holds only a figure at that figure`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("A paragraph that the next page interrupts with a full-page chart", 100f, indent = 15f)
                        line("and that only goes on after it, as typesetters often arrange it", 112f)
                    },
                    page { drawing(Box(60f, 60f, 340f, 540f)) },
                    page {
                        line("so that the chart sits near the text that discusses it.", 100f, isLast = true)
                        line("A new paragraph follows, long enough to fill the line from the", 112f, indent = 15f)
                        line("left edge of the column to its right edge.", 124f, isLast = true)
                    },
                ),
            ),
        )

        val body = epub.body()
        assertTrue("page 2 opens at the chart:\n$body", body.contains("aria-label=\"2\"></span>\n<div class=\"figure\">"))
    }

    @Test
    fun `keeps a box set flush left and narrower than the column as one paragraph`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("Dealers set their spreads to recover the costs of doing business", 100f, indent = 15f)
                        line("and the losses they suffer when trading with informed traders.", 112f)
                        line("The page discusses both of them in turn, one after the other.", 124f)
                        text("In practice, dealers set their bid and ask prices", x = 50f, baseline = 160f, size = 8f)
                        text("by using their experience", x = 50f, baseline = 170f, size = 8f)
                        text("to interpret what the market tells them,", x = 50f, baseline = 180f, size = 8f)
                        text("as described here.", x = 50f, baseline = 190f, size = 8f)
                    },
                ),
            ),
        )

        assertTrue(
            epub.paragraphs().contains(
                "In practice, dealers set their bid and ask prices by using their experience to interpret what the market tells them, " +
                    "as described here.",
            ),
        )
    }

    @Test
    fun `takes the labels of a chart as part of the figure and keeps its caption as text`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("Figure 14-2 shows how dealers adjust their quotes after a trade", 100f, indent = 15f)
                        line("for a dealer who has just sold to a buyer and must now decide", 112f)
                        line("where to quote next, given what that sale revealed about prices", 124f)
                        line("and about the traders on the other side.", 136f, isLast = true)
                        drawing(Box(80f, 160f, 320f, 280f))
                        text("If the next trader", x = 100f, baseline = 292f, size = 7f)
                        text("is a buyer.", x = 100f, baseline = 300f, size = 7f)
                        text("Figure 14-2. Quotation Adjustments After a Buyer Arrives", x = 50f, baseline = 330f, size = 9f)
                    },
                ),
            ),
        )

        assertFalse("labels stay in the picture:\n${epub.body()}", epub.body().contains("next trader"))
        assertTrue("a sentence about the figure is no caption", epub.paragraphs().single().contains("Figure 14-2 shows how dealers"))
        assertTrue(epub.body().contains("""<p class="caption">Figure 14-2. Quotation Adjustments After a Buyer Arrives</p>"""))
    }

    @Test
    fun `lets a stray large digit on a scan sit inside a paragraph without ending it`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("Figure 14-2 illustrates the quotation adjustments following the", 100f, indent = 15f)
                        line("arrival of a buyer. The bid and ask both rise by half of the", 112f)
                        text("4", x = 200f, baseline = 118f, size = 14f)
                        line("adverse selection spread component.", 124f, isLast = true)
                    },
                ),
            ),
        )

        assertEquals(
            listOf(
                "<span id=\"p1\" epub:type=\"pagebreak\" role=\"doc-pagebreak\" aria-label=\"1\"></span>" +
                    "Figure 14-2 illustrates the quotation adjustments following the arrival of a buyer. The bid and ask both rise by " +
                    "half of the adverse selection spread component.",
            ),
            epub.paragraphs(),
        )
    }

    @Test
    fun `refuses a scan that has no text layer`() {
        val pdf = FakePdf(List(3) { page { drawing(Box(50f, 50f, 350f, 550f)) } })

        val error = assertThrows(ConversionException::class.java) { convert(pdf) }

        assertTrue(error.message.orEmpty().contains("OCR"))
    }

    @Test
    fun `writes well-formed documents whatever characters the text holds`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("AT&T quoted <1/8> wide spreads in \u0001 those years, said the", 100f, indent = 15f)
                        line("report on telephone stocks.", 112f, isLast = true)
                    },
                ),
            ),
        )

        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        (epub.spine() + "nav.xhtml" + "content.opf").forEach { name -> parser.parse(ByteArrayInputStream(epub.entries.getValue("OEBPS/$name"))) }
        assertTrue(epub.body().contains("AT&amp;T quoted &lt;1/8&gt; wide spreads in those years"))
    }

    @Test
    fun `stores the mimetype first and uncompressed, as readers require`() {
        val epub = convert(FakePdf(listOf(bodyPage())))

        assertEquals("mimetype", epub.first.name)
        assertEquals(ZipEntry.STORED, epub.first.method)
    }

    @Test
    fun `declares the language the book is written in`() {
        val epub = convert(
            FakePdf(
                listOf(
                    page {
                        line("El diferencial es el precio que pagan los que tienen apuro por", 100f, indent = 15f)
                        line("operar, y la ganancia de los que les dan liquidez para la venta.", 112f, isLast = true)
                    },
                ),
            ),
        )

        assertTrue(String(epub.entries.getValue("OEBPS/content.opf")).contains("<dc:language>es</dc:language>"))
    }

    private fun chapterPage(title: String) = page {
        text(title, x = 50f, baseline = 80f, size = 20f, isBold = true)
        line("The chapter opens with a paragraph that sets out what it covers", 130f)
        line("and why it matters to anyone who trades.", 142f, isLast = true)
    }

    private fun bodyPage() = page {
        line("Traders who want to trade quickly pay the spread to those who are", 100f, indent = 15f)
        line("willing to wait, which is the whole business of a market maker.", 112f, isLast = true)
    }

    private class Epub(val entries: Map<String, ByteArray>, val first: ZipEntry) {
        private fun text(name: String) = String(entries.getValue("OEBPS/$name"))

        fun spine(): List<String> {
            val opf = text("content.opf")
            val hrefs = Regex("<item id=\"([^\"]+)\" href=\"([^\"]+)\"").findAll(opf).associate { it.groupValues[1] to it.groupValues[2] }
            return Regex("<itemref idref=\"([^\"]+)\"").findAll(opf).map { hrefs.getValue(it.groupValues[1]) }.toList()
        }

        fun body(): String = spine().joinToString("\n") { text(it).substringAfter("<body>").substringBefore("</body>") }

        fun paragraphs(): List<String> = Regex("<p>(.*?)</p>").findAll(body()).map { it.groupValues[1] }.toList()

        fun links(nav: String): List<Pair<String, String>> {
            val section = text("nav.xhtml").substringAfter("epub:type=\"$nav\"").substringBefore("</nav>")
            return Regex("<a href=\"([^\"]+)\">([^<]+)</a>").findAll(section).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
    }

    private fun paragraphOf(vararg lines: String): String {
        val pdf = FakePdf(
            listOf(
                page {
                    lines.forEachIndexed { i, text -> line(text, 100f + 12f * i, indent = if (i == 0) 15f else 0f, isLast = i == lines.lastIndex) }
                },
            ),
        )
        return convert(pdf).paragraphs().single()
    }

    private fun convert(pdf: FakePdf): Epub {
        val out = ByteArrayOutputStream()
        PdfToEpub(pdf).convert("urn:uuid:test", "Test", null, out) {}
        val entries = linkedMapOf<String, ByteArray>()
        var first: ZipEntry? = null
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (first == null) first = entry
                entries[entry.name] = zip.readBytes()
            }
        }
        return Epub(entries, checkNotNull(first))
    }
}
