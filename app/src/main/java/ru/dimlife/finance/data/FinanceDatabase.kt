package ru.dimlife.finance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class FinanceTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amountMinor: Long,
    val type: String,
    val category: String,
    val dateMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amountMinor: Long,
    val currency: String = "RUB",
    val period: String = "MONTHLY",
    val nextPaymentMillis: Long,
    val isActive: Boolean = true
)

@Entity(
    tableName = "budgets",
    indices = [Index(value = ["category", "monthKey"], unique = true)]
)
data class CategoryBudget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val limitMinor: Long,
    val monthKey: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM transactions ORDER BY dateMillis DESC")
    fun observeTransactions(): Flow<List<FinanceTransaction>>

    @Insert
    suspend fun addTransaction(item: FinanceTransaction)

    @Update
    suspend fun updateTransaction(item: FinanceTransaction)

    @Delete
    suspend fun deleteTransaction(item: FinanceTransaction)

    @Query("SELECT * FROM budgets WHERE monthKey = :monthKey ORDER BY category ASC")
    fun observeBudgets(monthKey: String): Flow<List<CategoryBudget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBudget(item: CategoryBudget)

    @Delete
    suspend fun deleteBudget(item: CategoryBudget)

    @Query("SELECT * FROM subscriptions WHERE isActive = 1 ORDER BY nextPaymentMillis ASC")
    fun observeSubscriptions(): Flow<List<Subscription>>

    @Insert
    suspend fun addSubscription(item: Subscription)

    @Update
    suspend fun updateSubscription(item: Subscription)

    @Delete
    suspend fun deleteSubscription(item: Subscription)
}

@Database(entities = [FinanceTransaction::class, Subscription::class, CategoryBudget::class], version = 2, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `limitMinor` INTEGER NOT NULL, `monthKey` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_category_monthKey` ON `budgets` (`category`, `monthKey`)")
            }
        }

        @Volatile private var INSTANCE: FinanceDatabase? = null

        fun get(context: Context): FinanceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "dimlife_finance.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
