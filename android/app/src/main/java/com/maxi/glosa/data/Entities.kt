package com.maxi.glosa.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** The two ways to read a book imported as PDF: as printed, or reflowed from the EPUB converted from it. */
enum class BookFormat { Epub, Pdf }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    /** The reflowable text; for a PDF, null until its conversion finishes. */
    val epubPath: String?,
    val pdfPath: String? = null,
    val coverPath: String?,
    val epubLocatorJson: String? = null,
    val pdfLocatorJson: String? = null,
    /** Fraction of the whole book read, 0.0 to 1.0. */
    val progression: Double = 0.0,
    /** How the book opens next: the way it was last read. */
    @ColumnInfo(defaultValue = "Epub") val format: BookFormat = BookFormat.Epub,
    /** Why the PDF could not be converted; null while it converts and once it has. */
    val conversionError: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
) {
    val isConverting: Boolean get() = pdfPath != null && epubPath == null && conversionError == null

    /** The format the reader can open now: a PDF still converting, or one that could not be, only as printed. */
    val readableFormat: BookFormat get() = if (epubPath == null) BookFormat.Pdf else if (pdfPath == null) BookFormat.Epub else format
}

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val text: String,
    val color: HighlightColor,
    val note: String? = null,
    val chapter: String?,
    val progression: Double,
    val createdAt: Long,
)

enum class HighlightColor(val argb: Long) {
    Yellow(0xFFFFD54F),
    Green(0xFF81C784),
    Blue(0xFF64B5F6),
    Pink(0xFFF06292),
}

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val chapter: String?,
    val excerpt: String?,
    val progression: Double,
    val createdAt: Long,
)

/** A passage the reader asked about, with every question and answer that followed. */
@Entity(
    tableName = "explanations",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class Explanation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val selectedText: String,
    val chapter: String?,
    val progression: Double,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "explanation_turns",
    foreignKeys = [ForeignKey(Explanation::class, ["id"], ["explanationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("explanationId")],
)
data class ExplanationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val explanationId: Long,
    val question: String,
    val answer: String,
    /** In the order the answer cites them as [1], [2]… */
    val sources: List<Source>,
    val createdAt: Long,
)

data class ExplanationThread(
    @Embedded val explanation: Explanation,
    @Relation(parentColumn = "id", entityColumn = "explanationId") val turns: List<ExplanationTurn>,
)

enum class SourceKind { Book, Wikipedia, Dictionary, Web, Notes, Calculator }

/** Where part of an answer came from: a place in the book (`locatorJson`) or a page on the web (`url`). */
data class Source(val kind: SourceKind, val title: String, val url: String? = null, val locatorJson: String? = null)
