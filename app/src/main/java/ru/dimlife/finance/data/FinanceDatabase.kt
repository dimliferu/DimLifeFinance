package ru.dimlife.finance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
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

@Dao
interface FinanceDao {
    @Query("SELECT * FROM transactions ORDER BY dateMillis DESC")
    fun observeTransactions(): Flow<List<FinanceTransaction>>

    @Insert
    suspend fun addTransaction(item: FinanceTransaction)

    @Delete
    suspend fun deleteTransaction(item: FinanceTransaction)

    @Query("SELECT * FROM subscriptions WHERE isActive = 1 ORDER BY nextPaymentMillis ASC")
    fun observeSubscriptions(): Flow<List<Subscription>>

    @Insert
    suspend fun addSubscription(item: Subscription)

    @Delete
    suspend fun deleteSubscription(item: Subscription)
}

@Database(entities = [FinanceTransaction::class, Subscription::class], version = 1, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile private var INSTANCE: FinanceDatabase? = null

        fun get(context: Context): FinanceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "dimlife_finance.db"
                ).build().also { INSTANCE = it }
            }
    }
}
