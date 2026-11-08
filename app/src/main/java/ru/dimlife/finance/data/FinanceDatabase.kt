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
import androidx.room.Transaction
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

@Entity(tableName = "deposits")
data class Deposit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    val title: String,
    val principalMinor: Long,
    val annualRateBps: Int,
    val openedAtMillis: Long = System.currentTimeMillis(),
    val maturityAtMillis: Long,
    val capitalization: Boolean = true
)

@Entity(tableName = "investment_holdings")
data class InvestmentHolding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val title: String,
    val kind: String,
    val quantity: Double,
    val averageBuyPriceMinor: Long,
    val currentPriceMinor: Long = 0,
    val currency: String = "RUB",
    val board: String = "TQBR",
    val priceUpdatedAtMillis: Long = 0
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

    @Query("SELECT * FROM transactions ORDER BY dateMillis DESC")
    suspend fun getAllTransactions(): List<FinanceTransaction>

    @Query("SELECT * FROM subscriptions ORDER BY nextPaymentMillis ASC")
    suspend fun getAllSubscriptions(): List<Subscription>

    @Query("SELECT * FROM budgets ORDER BY monthKey DESC, category ASC")
    suspend fun getAllBudgets(): List<CategoryBudget>

    @Query("SELECT * FROM deposits ORDER BY maturityAtMillis ASC")
    fun observeDeposits(): Flow<List<Deposit>>

    @Query("SELECT * FROM investment_holdings ORDER BY ticker ASC")
    fun observeInvestmentHoldings(): Flow<List<InvestmentHolding>>

    @Query("SELECT * FROM deposits ORDER BY maturityAtMillis ASC")
    suspend fun getAllDeposits(): List<Deposit>

    @Query("SELECT * FROM investment_holdings ORDER BY ticker ASC")
    suspend fun getAllInvestmentHoldings(): List<InvestmentHolding>

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("DELETE FROM subscriptions")
    suspend fun clearSubscriptions()

    @Query("DELETE FROM budgets")
    suspend fun clearBudgets()

    @Query("DELETE FROM deposits")
    suspend fun clearDeposits()

    @Query("DELETE FROM investment_holdings")
    suspend fun clearInvestmentHoldings()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(items: List<FinanceTransaction>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(items: List<Subscription>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(items: List<CategoryBudget>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeposits(items: List<Deposit>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvestmentHoldings(items: List<InvestmentHolding>)

    @Transaction
    suspend fun restoreBackup(
        transactions: List<FinanceTransaction>,
        subscriptions: List<Subscription>,
        budgets: List<CategoryBudget>,
        deposits: List<Deposit>,
        investments: List<InvestmentHolding>
    ) {
        clearTransactions()
        clearSubscriptions()
        clearBudgets()
        clearDeposits()
        clearInvestmentHoldings()
        if (transactions.isNotEmpty()) insertTransactions(transactions)
        if (subscriptions.isNotEmpty()) insertSubscriptions(subscriptions)
        if (budgets.isNotEmpty()) insertBudgets(budgets)
        if (deposits.isNotEmpty()) insertDeposits(deposits)
        if (investments.isNotEmpty()) insertInvestmentHoldings(investments)
    }

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

    @Insert
    suspend fun addDeposit(item: Deposit)

    @Delete
    suspend fun deleteDeposit(item: Deposit)

    @Insert
    suspend fun addInvestmentHolding(item: InvestmentHolding)

    @Update
    suspend fun updateInvestmentHolding(item: InvestmentHolding)

    @Delete
    suspend fun deleteInvestmentHolding(item: InvestmentHolding)

    @Query("SELECT * FROM subscriptions WHERE isActive = 1 ORDER BY nextPaymentMillis ASC")
    fun observeSubscriptions(): Flow<List<Subscription>>

    @Insert
    suspend fun addSubscription(item: Subscription)

    @Update
    suspend fun updateSubscription(item: Subscription)

    @Delete
    suspend fun deleteSubscription(item: Subscription)
}

@Database(entities = [FinanceTransaction::class, Subscription::class, CategoryBudget::class, Deposit::class, InvestmentHolding::class], version = 3, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `limitMinor` INTEGER NOT NULL, `monthKey` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_category_monthKey` ON `budgets` (`category`, `monthKey`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS deposits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bank TEXT NOT NULL, title TEXT NOT NULL, principalMinor INTEGER NOT NULL, annualRateBps INTEGER NOT NULL, openedAtMillis INTEGER NOT NULL, maturityAtMillis INTEGER NOT NULL, capitalization INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS investment_holdings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ticker TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, quantity REAL NOT NULL, averageBuyPriceMinor INTEGER NOT NULL, currentPriceMinor INTEGER NOT NULL, currency TEXT NOT NULL, board TEXT NOT NULL, priceUpdatedAtMillis INTEGER NOT NULL)")
            }
        }

        @Volatile private var INSTANCE: FinanceDatabase? = null

        fun get(context: Context): FinanceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "dimlife_finance.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
