package io.odoworks.logbook.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startOdometerKm: Float,
    val endOdometerKm: Float? = null,
    val leftWithoutClassifying: Boolean = false,
    val destinationName: String? = null,
    val destinationAddress: String? = null,
    val destinationKey: String? = null,
)
enum class Category(val label: String) { BUSINESS("업무"), COMMUTE("출퇴근"), PERSONAL("개인") }
@Entity(tableName = "classifications", foreignKeys = [ForeignKey(entity = TripRow::class, parentColumns = ["id"], childColumns = ["tripId"])], indices = [Index("tripId")])
data class ClassificationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val category: Category,
    val decidedAt: Long,
    @ColumnInfo(defaultValue = "'USER'") val source: ClassificationSource = ClassificationSource.USER,
)
class CategoryConverter {
    @TypeConverter fun decode(value: String): Category = Category.valueOf(value)
    @TypeConverter fun encode(value: Category): String = value.name
    @TypeConverter fun decodeSource(value: String): ClassificationSource = ClassificationSource.valueOf(value)
    @TypeConverter fun encodeSource(value: ClassificationSource): String = value.name
}
data class TripWithCategory(
    @Embedded val trip: TripRow,
    val category: Category?,
    val source: ClassificationSource? = null,
) {
    val distanceKm: Float get() = ((trip.endOdometerKm ?: trip.startOdometerKm) - trip.startOdometerKm).coerceAtLeast(0f)
    val label: String get() = category?.let { it.label + if (source == ClassificationSource.SUGGESTED) "·추천" else "" } ?: if (trip.leftWithoutClassifying) "미분류·하차" else "미분류"
}
private const val TRIPS_WITH_CURRENT = "SELECT t.*, c.category, c.source FROM trips t LEFT JOIN classifications c ON c.id=(SELECT id FROM classifications WHERE tripId=t.id ORDER BY decidedAt DESC, id DESC LIMIT 1)"
@Dao
interface LogbookDao {
    @Insert suspend fun insertTrip(trip: TripRow): Long
    @Insert suspend fun classify(row: ClassificationRow): Long
    @Query("UPDATE trips SET endedAt=:at, endOdometerKm=:km WHERE id=:id AND endedAt IS NULL")
    suspend fun finish(id: Long, at: Long, km: Float)
    @Query("UPDATE trips SET leftWithoutClassifying=1 WHERE id=:id AND endedAt IS NULL")
    suspend fun markLeft(id: Long)
    @Query("UPDATE trips SET destinationName=:name, destinationAddress=:address, destinationKey=:key WHERE id=:id AND endedAt IS NULL")
    suspend fun setDestination(id: Long, name: String?, address: String?, key: String?)
    @Query(TRIPS_WITH_CURRENT + " ORDER BY t.startedAt DESC, t.id DESC")
    fun observeTrips(): Flow<List<TripWithCategory>>
    @Query(TRIPS_WITH_CURRENT + " WHERE t.endedAt IS NULL ORDER BY t.id DESC LIMIT 1")
    suspend fun unfinished(): TripWithCategory?
    @Query(TRIPS_WITH_CURRENT + " WHERE t.id=:id")
    suspend fun trip(id: Long): TripWithCategory?
    @Query("SELECT c.id, c.tripId, t.destinationKey, c.category, c.source, c.decidedAt FROM classifications c JOIN trips t ON t.id=c.tripId WHERE t.destinationKey=:key AND t.id!=:currentTripId")
    suspend fun choices(key: String, currentTripId: Long): List<DestinationChoice>
    @Transaction suspend fun suggestion(id: Long): Category? {
        val key = trip(id)?.trip?.destinationKey ?: return null
        return recommend(choices(key, id), key, id)
    }
    @Transaction suspend fun finishWithSuggestion(id: Long, at: Long, km: Float, decidedAt: Long) {
        val current = trip(id) ?: return
        if (current.trip.endedAt != null) return
        automaticClassification(current.category, suggestion(id))?.let {
            classify(ClassificationRow(tripId = id, category = it.category, decidedAt = decidedAt, source = it.source))
        }
        finish(id, at, km)
    }
    @Transaction suspend fun confirmSuggested(ids: List<Long>, decidedAt: Long) {
        for (id in ids.distinct()) {
            val row = trip(id) ?: continue
            if (row.source == ClassificationSource.SUGGESTED && row.category != null)
                classify(ClassificationRow(tripId = id, category = row.category, decidedAt = decidedAt))
        }
    }
}
@Database(entities = [TripRow::class, ClassificationRow::class], version = 2, exportSchema = false)
@TypeConverters(CategoryConverter::class)
abstract class LogbookDatabase : RoomDatabase() {
    abstract fun dao(): LogbookDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN destinationName TEXT")
                db.execSQL("ALTER TABLE trips ADD COLUMN destinationAddress TEXT")
                db.execSQL("ALTER TABLE trips ADD COLUMN destinationKey TEXT")
                db.execSQL("ALTER TABLE classifications ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'")
            }
        }
        @Volatile private var instance: LogbookDatabase? = null
        fun get(context: Context): LogbookDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LogbookDatabase::class.java, "logbook.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
