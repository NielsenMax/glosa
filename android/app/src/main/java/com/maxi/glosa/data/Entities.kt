package com.maxi.glosa.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val filePath: String,
    val coverPath: String?,
    val locatorJson: String? = null,
    /** Fraction of the whole book read, 0.0 to 1.0. */
    val progression: Double = 0.0,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)

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
