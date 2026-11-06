package com.maxi.glosa.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Query("SELECT * FROM books WHERE title = :title AND author IS :author LIMIT 1")
    suspend fun findByTitle(title: String, author: String?): Book?

    @Query("UPDATE books SET filePath = :filePath, coverPath = :coverPath WHERE id = :id")
    suspend fun relink(id: Long, filePath: String, coverPath: String?)

    @Query("UPDATE books SET locatorJson = :locatorJson, progression = :progression, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun savePosition(id: Long, locatorJson: String, progression: Double, openedAt: Long)

    @Delete
    suspend fun delete(book: Book)
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY progression")
    fun observeForBook(bookId: Long): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun get(id: Long): Highlight?

    @Insert
    suspend fun insert(highlight: Highlight): Long

    @Update
    suspend fun update(highlight: Highlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progression")
    fun observeForBook(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Book::class, Highlight::class, Bookmark::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun highlights(): HighlightDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    }
}
