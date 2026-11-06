package com.maxi.glosa.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

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

    @Query("SELECT * FROM highlights WHERE bookId = :bookId")
    suspend fun listForBook(bookId: Long): List<Highlight>

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

@Dao
interface ExplanationDao {
    @Transaction
    @Query("SELECT * FROM explanations WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeForBook(bookId: Long): Flow<List<ExplanationThread>>

    @Transaction
    @Query("SELECT * FROM explanations WHERE id = :id")
    suspend fun get(id: Long): ExplanationThread?

    @Transaction
    @Query("SELECT * FROM explanations WHERE bookId = :bookId")
    suspend fun listForBook(bookId: Long): List<ExplanationThread>

    @Insert
    suspend fun insert(explanation: Explanation): Long

    @Insert
    suspend fun insertTurn(turn: ExplanationTurn): Long

    @Query("UPDATE explanations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM explanations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Saves the explanation the first time it gets an answer; returns its id. */
    @Transaction
    suspend fun record(explanation: Explanation, turn: ExplanationTurn): Long {
        val id = if (explanation.id == 0L) insert(explanation) else explanation.id
        touch(id, turn.createdAt)
        insertTurn(turn.copy(explanationId = id))
        return id
    }
}

class SourceListConverter {
    @TypeConverter
    fun toJson(sources: List<Source>): String = JSONArray(
        sources.map { source ->
            JSONObject()
                .put("kind", source.kind.name)
                .put("title", source.title)
                .putOpt("url", source.url)
                .putOpt("locator", source.locatorJson)
        },
    ).toString()

    @TypeConverter
    fun fromJson(json: String): List<Source> {
        val array = JSONArray(json)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Source(
                kind = SourceKind.valueOf(item.getString("kind")),
                title = item.getString("title"),
                url = item.optString("url").ifEmpty { null },
                locatorJson = item.optString("locator").ifEmpty { null },
            )
        }
    }
}

@Database(
    entities = [Book::class, Highlight::class, Bookmark::class, Explanation::class, ExplanationTurn::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(SourceListConverter::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun highlights(): HighlightDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun explanations(): ExplanationDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    }
}
