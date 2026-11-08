package ru.dimlife.finance

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.work.WorkManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.dimlife.finance.data.FinanceDatabase
import ru.dimlife.finance.data.FinanceTransaction
import ru.dimlife.finance.data.Subscription
import ru.dimlife.finance.data.CategoryBudget
import ru.dimlife.finance.data.Deposit
import ru.dimlife.finance.data.InvestmentHolding
import ru.dimlife.finance.data.CapitalTransfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray

private val Green = Color(0xFF42D982)
private val DarkBackground = Color(0xFF0B110E)
private val DarkSurface = Color(0xFF141D18)
private val Muted = Color(0xFF9BAAA0)
private val Context.financeDataStore by preferencesDataStore(name = "dimlife_finance_settings")
private val DARK_THEME_KEY = booleanPreferencesKey("dark_theme")
private val PAYMENT_REMINDERS_KEY = booleanPreferencesKey("payment_reminders")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DimLifeApp() }
    }
}

private enum class Tab(val label: String) {
    HOME("Главная"), OPERATIONS("Операции"), SUBSCRIPTIONS("Подписки"),
    CAPITAL("Капитал"), ANALYTICS("Аналитика"), SETTINGS("Настройки")
}

@Composable
private fun DimLifeApp() {
    val context = LocalContext.current
    val dao = remember { FinanceDatabase.get(context).financeDao() }
    val transactions by dao.observeTransactions().collectAsStateWithLifecycle(initialValue = emptyList())
    val subscriptions by dao.observeSubscriptions().collectAsStateWithLifecycle(initialValue = emptyList())
    val deposits by dao.observeDeposits().collectAsStateWithLifecycle(initialValue = emptyList())
    val investments by dao.observeInvestmentHoldings().collectAsStateWithLifecycle(initialValue = emptyList())
    val capitalTransfers by dao.observeCapitalTransfers().collectAsStateWithLifecycle(initialValue = emptyList())
    val monthKey = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }
    val budgets by dao.observeBudgets(monthKey).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    val darkTheme by context.financeDataStore.data.map { preferences -> preferences[DARK_THEME_KEY] ?: true }
        .collectAsStateWithLifecycle(initialValue = true)
    var showTransactionDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showDepositDialog by remember { mutableStateOf(false) }
    var showInvestmentDialog by remember { mutableStateOf(false) }
    var showCapitalTransferDialog by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    var editingSubscription by remember { mutableStateOf<Subscription?>(null) }

    val income = transactions.filter { it.type == "INCOME" }.sumOf { it.amountMinor }
    val expenses = transactions.filter { it.type == "EXPENSE" }.sumOf { it.amountMinor }
    val balance = income - expenses
    val cashBalanceAsOf = System.currentTimeMillis()
    val cashBalance = balance -
        capitalTransfers.filter { it.direction == "OUT" && it.dateMillis <= cashBalanceAsOf }.sumOf { it.amountMinor } +
        capitalTransfers.filter { it.direction == "IN" && it.dateMillis <= cashBalanceAsOf }.sumOf { it.amountMinor }

    val colors = if (darkTheme) darkColorScheme(
        primary = Green, onPrimary = Color(0xFF062010),
        background = DarkBackground, surface = DarkSurface,
        onBackground = Color.White, onSurface = Color.White,
        secondary = Color(0xFF91E8B3)
    ) else lightColorScheme(
        primary = Color(0xFF16864B), secondary = Color(0xFF287D55),
        background = Color(0xFFF4F7F4), surface = Color.White
    )

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("DimLife Finance", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("ТВОИ ФИНАНСЫ ПОД КОНТРОЛЕМ", fontSize = 9.sp, color = Muted, letterSpacing = 1.sp)
                    }
                    IconButton(onClick = { selectedTab = Tab.SETTINGS }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { tab ->
                        val icon = when (tab) {
                            Tab.HOME -> Icons.Outlined.Home
                            Tab.OPERATIONS -> Icons.Outlined.SwapHoriz
                            Tab.SUBSCRIPTIONS -> Icons.Outlined.CalendarMonth
                            Tab.CAPITAL -> Icons.Outlined.AccountBalanceWallet
                            Tab.ANALYTICS -> Icons.Outlined.PieChart
                            Tab.SETTINGS -> Icons.Outlined.Settings
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 9.sp, maxLines = 1) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (selectedTab == Tab.HOME || selectedTab == Tab.OPERATIONS) {
                    FloatingActionButton(
                        onClick = { showTransactionDialog = true },
                        containerColor = Green,
                        contentColor = Color(0xFF062010)
                    ) { Icon(Icons.Outlined.Add, contentDescription = "Добавить операцию") }
                } else if (selectedTab == Tab.SUBSCRIPTIONS) {
                    FloatingActionButton(
                        onClick = { showSubscriptionDialog = true },
                        containerColor = Green,
                        contentColor = Color(0xFF062010)
                    ) { Icon(Icons.Outlined.Add, contentDescription = "Добавить подписку") }
                } else if (selectedTab == Tab.ANALYTICS) {
                    FloatingActionButton(
                        onClick = { showBudgetDialog = true },
                        containerColor = Green,
                        contentColor = Color(0xFF062010)
                    ) { Icon(Icons.Outlined.Add, contentDescription = "Добавить бюджет") }
                }
            }
        ) { padding ->
            when (selectedTab) {
                Tab.HOME -> HomeScreen(
                    Modifier.padding(padding), cashBalance, income, expenses, transactions.take(5),
                    subscriptions.take(3),
                    onAdd = { showTransactionDialog = true },
                    onDelete = { item -> scope.launch { dao.deleteTransaction(item) } },
                    onEdit = { editingTransaction = it },
                    onEditSubscription = { editingSubscription = it }
                )
                Tab.OPERATIONS -> OperationsScreen(
                    Modifier.padding(padding), transactions,
                    onDelete = { item -> scope.launch { dao.deleteTransaction(item) } },
                    onEdit = { editingTransaction = it }
                )
                Tab.SUBSCRIPTIONS -> SubscriptionsScreen(
                    Modifier.padding(padding), subscriptions,
                    onDelete = { item -> scope.launch { dao.deleteSubscription(item) } },
                    onEdit = { editingSubscription = it }
                )
                Tab.CAPITAL -> CapitalScreen(
                    Modifier.padding(padding), cashBalance, deposits, investments, capitalTransfers, dao,
                    onAddDeposit = { showDepositDialog = true },
                    onAddInvestment = { showInvestmentDialog = true },
                    onAddTransfer = { showCapitalTransferDialog = true },
                    onDeleteTransfer = { item -> scope.launch { dao.deleteCapitalTransfer(item) } },
                    onCloseDeposit = { deposit ->
                        scope.launch {
                            val returnAmount = deposit.principalMinor + depositInterestMinor(deposit, System.currentTimeMillis())
                            dao.closeDepositToCash(deposit, CapitalTransfer(
                                direction = "IN", assetType = "Вклад", assetName = "${deposit.bank} · ${deposit.title}",
                                amountMinor = returnAmount, note = "Закрытие вклада; проценты рассчитаны ориентировочно"
                            ))
                        }
                    },
                    onSellInvestment = { holding, quantity ->
                        scope.launch {
                            val usedPrice = if (holding.currentPriceMinor > 0) holding.currentPriceMinor else holding.averageBuyPriceMinor
                            val proceeds = (quantity * usedPrice).toLong()
                            val remaining = (holding.quantity - quantity).coerceAtLeast(0.0)
                            val updated = if (remaining < 0.00000001) null else holding.copy(quantity = remaining)
                            val transfer = CapitalTransfer(
                                direction = "IN", assetType = if (holding.kind == "SHARE") "Акции" else "Фонды",
                                assetName = holding.ticker, amountMinor = proceeds,
                                note = if (holding.currentPriceMinor > 0) "Продажа по последней котировке MOEX" else "Продажа по средней цене покупки: котировки нет"
                            )
                            dao.sellInvestmentToCash(holding, updated, transfer)
                        }
                    }
                )
                Tab.ANALYTICS -> AnalyticsScreen(Modifier.padding(padding), transactions, budgets,
                    onDeleteBudget = { item -> scope.launch { dao.deleteBudget(item) } }
                )
                Tab.SETTINGS -> SettingsScreen(Modifier.padding(padding), darkTheme, dao) { enabled ->
                    scope.launch { context.financeDataStore.edit { preferences -> preferences[DARK_THEME_KEY] = enabled } }
                }
            }
        }

        if (showTransactionDialog) {
            AddTransactionDialog(
                onDismiss = { showTransactionDialog = false },
                initial = null,
                onSave = { title, amount, type, category, dateMillis ->
                    scope.launch { dao.addTransaction(FinanceTransaction(title = title, amountMinor = (amount * 100).toLong(), type = type, category = category, dateMillis = dateMillis)) }
                    showTransactionDialog = false
                }
            )
        }
        if (showSubscriptionDialog) {
            AddSubscriptionDialog(
                onDismiss = { showSubscriptionDialog = false },
                initial = null,
                onSave = { title, amount, period, nextPaymentMillis ->
                    scope.launch { dao.addSubscription(Subscription(title = title, amountMinor = (amount * 100).toLong(), period = period, nextPaymentMillis = nextPaymentMillis)) }
                    showSubscriptionDialog = false
                }
            )
        }
        if (showBudgetDialog) {
            AddBudgetDialog(onDismiss = { showBudgetDialog = false }, onSave = { category, limit ->
                scope.launch { dao.saveBudget(CategoryBudget(category = category, limitMinor = (limit * 100).toLong(), monthKey = monthKey)) }
                showBudgetDialog = false
            })
        }
        if (showDepositDialog) {
            AddDepositDialog(cashBalance = cashBalance, onDismiss = { showDepositDialog = false }, onSave = { bank, title, amount, rate, opened, maturity, capitalization, fundFromCash ->
                scope.launch {
                    val deposit = Deposit(bank = bank, title = title, principalMinor = (amount * 100).toLong(),
                        annualRateBps = (rate * 100).toInt(), openedAtMillis = opened, maturityAtMillis = maturity, capitalization = capitalization)
                    if (fundFromCash) {
                        val transfer = CapitalTransfer(direction = "OUT", assetType = "Вклад", assetName = "$bank · $title",
                            amountMinor = deposit.principalMinor, dateMillis = opened, note = "Первоначальное размещение вклада")
                        dao.addDepositWithTransfer(deposit, transfer)
                    } else dao.addDeposit(deposit)
                }
                showDepositDialog = false
            })
        }
        if (showInvestmentDialog) {
            AddInvestmentDialog(cashBalance = cashBalance, onDismiss = { showInvestmentDialog = false }, onSave = { ticker, kind, board, quantity, buyPrice, buyFromCash ->
                scope.launch {
                    val item = InvestmentHolding(ticker = ticker, title = ticker, kind = kind, quantity = quantity,
                        averageBuyPriceMinor = (buyPrice * 100).toLong(), board = board)
                    if (buyFromCash) {
                        val costMinor = (quantity * buyPrice * 100.0).toLong()
                        val transfer = CapitalTransfer(direction = "OUT", assetType = if (kind == "SHARE") "Акции" else "Фонды",
                            assetName = ticker, amountMinor = costMinor, note = "Покупка актива по средней цене")
                        dao.addInvestmentWithTransfer(item, transfer)
                    } else dao.addInvestmentHolding(item)
                    try {
                        val quote = fetchMoexQuote(ticker, board)
                        val saved = dao.getAllInvestmentHoldings().firstOrNull { it.ticker == ticker && it.priceUpdatedAtMillis == 0L }
                        if (saved != null) dao.updateInvestmentHolding(saved.copy(title = quote.title, currentPriceMinor = quote.priceMinor, priceUpdatedAtMillis = System.currentTimeMillis()))
                    } catch (_: Exception) { }
                }
                showInvestmentDialog = false
            })
        }
        if (showCapitalTransferDialog) {
            AddCapitalTransferDialog(
                cashBalance = cashBalance,
                onDismiss = { showCapitalTransferDialog = false },
                onSave = { direction, assetType, assetName, amount, note, dateMillis ->
                    scope.launch {
                        dao.addCapitalTransfer(CapitalTransfer(direction = direction, assetType = assetType,
                            assetName = assetName, amountMinor = (amount * 100).toLong(),
                            note = note, dateMillis = dateMillis))
                    }
                    showCapitalTransferDialog = false
                }
            )
        }
        editingTransaction?.let { item ->
            AddTransactionDialog(initial = item, onDismiss = { editingTransaction = null }, onSave = { title, amount, type, category, dateMillis ->
                scope.launch { dao.updateTransaction(item.copy(title = title, amountMinor = (amount * 100).toLong(), type = type, category = category, dateMillis = dateMillis)) }
                editingTransaction = null
            })
        }
        editingSubscription?.let { item ->
            AddSubscriptionDialog(initial = item, onDismiss = { editingSubscription = null }, onSave = { title, amount, period, nextPaymentMillis ->
                scope.launch { dao.updateSubscription(item.copy(title = title, amountMinor = (amount * 100).toLong(), period = period, nextPaymentMillis = nextPaymentMillis)) }
                editingSubscription = null
            })
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier, balance: Long, income: Long, expenses: Long,
    recent: List<FinanceTransaction>, subscriptions: List<Subscription>,
    onAdd: () -> Unit, onDelete: (FinanceTransaction) -> Unit,
    onEdit: (FinanceTransaction) -> Unit, onEditSubscription: (Subscription) -> Unit
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF14271C))) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("ОБЩИЙ БАЛАНС", color = Color(0xFFB5C9BA), fontSize = 11.sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(money(balance), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricPill("Доходы", income, Color(0xFF6BE59C), Modifier.weight(1f))
                        MetricPill("Расходы", expenses, Color(0xFFFF9A83), Modifier.weight(1f))
                    }
                }
            }
        }
        item { SectionHeader("Последние операции", "Все операции") }
        if (recent.isEmpty()) item { EmptyState("Пока нет операций", "Нажми +, чтобы добавить первый доход или расход.") }
        items(recent, key = { it.id }) { TransactionRow(it, onEdit, onDelete) }
        item { SectionHeader("Ближайшие подписки", "Все подписки") }
        if (subscriptions.isEmpty()) item { EmptyState("Подписок пока нет", "Добавь регулярные платежи, чтобы не забывать о них.") }
        items(subscriptions, key = { "sub-${it.id}" }) { SubscriptionRow(it, { onEditSubscription(it) }, {}) }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить операцию")
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, amount: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF20382A), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(label, color = Color(0xFFB5C9BA), fontSize = 12.sp)
        Text(money(amount), color = color, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun SectionHeader(title: String, action: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(action, fontSize = 11.sp, color = Green)
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(description, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun TransactionRow(item: FinanceTransaction, onEdit: (FinanceTransaction) -> Unit, onDelete: (FinanceTransaction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(15.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).background(if (item.type == "INCOME") Color(0xFF173A27) else Color(0xFF3A2825), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(if (item.type == "INCOME") Icons.Outlined.TrendingUp else Icons.Outlined.ShoppingBag, null, tint = if (item.type == "INCOME") Green else Color(0xFFFF9A83))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.Medium)
            Text(item.category + " · " + date(item.dateMillis), color = Muted, fontSize = 11.sp)
        }
        Text((if (item.type == "INCOME") "+" else "−") + money(item.amountMinor), color = if (item.type == "INCOME") Green else Color(0xFFFF9A83), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        IconButton(onClick = { onEdit(item) }) { Icon(Icons.Outlined.Edit, "Изменить", tint = Green) }
        IconButton(onClick = { onDelete(item) }) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = Muted) }
    }
}

@Composable
private fun OperationsScreen(modifier: Modifier, items: List<FinanceTransaction>, onDelete: (FinanceTransaction) -> Unit, onEdit: (FinanceTransaction) -> Unit) {
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("ALL") }
    val filteredItems = items.filter { item ->
        (typeFilter == "ALL" || item.type == typeFilter) &&
            (query.isBlank() || item.title.contains(query.trim(), ignoreCase = true) || item.category.contains(query.trim(), ignoreCase = true))
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Все операции", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по названию или категории") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = typeFilter == "ALL", onClick = { typeFilter = "ALL" }, label = { Text("Все") })
                FilterChip(selected = typeFilter == "INCOME", onClick = { typeFilter = "INCOME" }, label = { Text("Доходы") })
                FilterChip(selected = typeFilter == "EXPENSE", onClick = { typeFilter = "EXPENSE" }, label = { Text("Расходы") })
            }
        }
        if (filteredItems.isEmpty()) {
            item { EmptyState(if (items.isEmpty()) "История пока пуста" else "Ничего не найдено", if (items.isEmpty()) "Добавь доход или расход с помощью кнопки +." else "Измени запрос или фильтр.") }
        }
        items(filteredItems, key = { it.id }) { TransactionRow(it, onEdit, onDelete) }
    }
}

private data class ProjectedPayment(val subscription: Subscription, val dateMillis: Long)

private fun projectPaymentsForMonth(subscriptions: List<Subscription>, monthMillis: Long): List<ProjectedPayment> {
    val monthStart = Calendar.getInstance().apply {
        timeInMillis = monthMillis
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val monthEnd = (monthStart.clone() as Calendar).apply {
        add(Calendar.MONTH, 1)
        add(Calendar.MILLISECOND, -1)
    }
    val result = mutableListOf<ProjectedPayment>()
    subscriptions.filter { it.isActive }.forEach { subscription ->
        val due = Calendar.getInstance().apply { timeInMillis = subscription.nextPaymentMillis }
        var guard = 0
        fun advance() {
            when (subscription.period) {
                "WEEKLY" -> due.add(Calendar.DAY_OF_YEAR, 7)
                "YEARLY" -> due.add(Calendar.YEAR, 1)
                else -> due.add(Calendar.MONTH, 1)
            }
        }
        while (due.timeInMillis < monthStart.timeInMillis && guard < 600) {
            advance()
            guard++
        }
        while (due.timeInMillis <= monthEnd.timeInMillis && guard < 700) {
            result += ProjectedPayment(subscription, due.timeInMillis)
            advance()
            guard++
        }
    }
    return result.sortedBy { it.dateMillis }
}

@Composable
private fun SubscriptionsScreen(modifier: Modifier, items: List<Subscription>, onDelete: (Subscription) -> Unit, onEdit: (Subscription) -> Unit) {
    var selectedDate by remember { mutableLongStateOf(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis) }
    val monthCalendar = Calendar.getInstance().apply {
        timeInMillis = selectedDate
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val monthStart = monthCalendar.timeInMillis
    val monthPayments = remember(items, monthStart) { projectPaymentsForMonth(items, monthStart) }
    val dayCount = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val offset = (monthCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val weekCount = (offset + dayCount + 6) / 7
    val selectedPayments = monthPayments.filter { sameCalendarDay(it.dateMillis, selectedDate) }
    val monthTitle = SimpleDateFormat("LLLL yyyy", Locale("ru", "RU")).format(Date(monthStart))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru", "RU")) else it.toString() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Календарь платежей", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Расписание регулярных списаний и подписок", color = Muted, fontSize = 12.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, -1) }
                            selectedDate = c.timeInMillis
                        }) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий месяц") }
                        Text(monthTitle, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, 1) }
                            selectedDate = c.timeInMillis
                        }) { Icon(Icons.Outlined.ChevronRight, "Следующий месяц") }
                    }
                    Row {
                        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { day ->
                            Text(day, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Muted, fontSize = 11.sp)
                        }
                    }
                    repeat(weekCount) { week ->
                        Row {
                            repeat(7) { column ->
                                val day = week * 7 + column - offset + 1
                                if (day in 1..dayCount) {
                                    val dateCalendar = Calendar.getInstance().apply {
                                        timeInMillis = monthStart
                                        set(Calendar.DAY_OF_MONTH, day)
                                    }
                                    val dayMillis = dateCalendar.timeInMillis
                                    val count = monthPayments.count { sameCalendarDay(it.dateMillis, dayMillis) }
                                    val selected = sameCalendarDay(dayMillis, selectedDate)
                                    Column(
                                        Modifier.weight(1f).padding(2.dp).height(44.dp)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                                else MaterialTheme.colorScheme.background,
                                                RoundedCornerShape(9.dp)
                                            )
                                            .clickable { selectedDate = dayMillis }
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(day.toString(), fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                        if (count > 0) Text("• $count", color = Green, fontSize = 9.sp, maxLines = 1)
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f).height(44.dp))
                                }
                            }
                        }
                    }
                    Text("Точки показывают дни с запланированными платежами. Нажми на дату, чтобы увидеть списания.", color = Muted, fontSize = 10.sp)
                }
            }
        }
        item {
            Text("Платежи на ${date(selectedDate)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (selectedPayments.isEmpty()) {
            item { Text("На эту дату платежей нет.", color = Muted, fontSize = 12.sp) }
        } else {
            items(selectedPayments, key = { "${it.subscription.id}-${it.dateMillis}" }) { payment ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(payment.subscription.title, fontWeight = FontWeight.SemiBold)
                        Text(when (payment.subscription.period) { "WEEKLY" -> "Еженедельный платёж"; "YEARLY" -> "Ежегодный платёж"; else -> "Ежемесячный платёж" }, color = Muted, fontSize = 10.sp)
                    }
                    Text(money(payment.subscription.amountMinor), fontWeight = FontWeight.SemiBold, color = Green)
                }
            }
        }
        item {
            Text("Все подписки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) item { EmptyState("Подписок пока нет", "Добавь сервисы и регулярные платежи — они появятся в календаре.") }
        items(items, key = { "subscription-${it.id}" }) { SubscriptionRow(it, { onEdit(it) }, { onDelete(it) }) }
        item {
            val monthly = items.sumOf { if (it.period == "YEARLY") it.amountMinor / 12 else if (it.period == "WEEKLY") it.amountMinor * 52 / 12 else it.amountMinor }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Примерная стоимость в месяц", color = Muted, fontSize = 12.sp)
                    Text(money(monthly), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Green)
                }
            }
        }
    }
}

private fun sameCalendarDay(firstMillis: Long, secondMillis: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = firstMillis }
    val second = Calendar.getInstance().apply { timeInMillis = secondMillis }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun SubscriptionRow(item: Subscription, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Color(0xFF1D3326), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Autorenew, null, tint = Green)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold)
            Text("Следующий платёж: ${date(item.nextPaymentMillis)}", color = Muted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(item.amountMinor), fontWeight = FontWeight.SemiBold)
            Text(when (item.period) { "WEEKLY" -> "в неделю"; "YEARLY" -> "в год"; else -> "в месяц" }, color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Изменить", tint = Green) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = Muted) }
    }
}


private data class MoexQuote(val title: String, val priceMinor: Long)

private suspend fun fetchMoexQuote(ticker: String, board: String): MoexQuote =
    withContext(Dispatchers.IO) {
        val safeTicker = ticker.trim().uppercase(Locale.ROOT)
        if (!safeTicker.matches(Regex("[A-Z0-9._-]{1,20}"))) throw IOException("Некорректный тикер")
        val url = URL("https://iss.moex.com/iss/engines/stock/markets/shares/boards/$board/securities/$safeTicker.json?iss.meta=off")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "DimLife-Finance-Android")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("Мосбиржа ответила: ${connection.responseCode}")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val market = root.optJSONObject("marketdata") ?: throw IOException("Нет рыночных данных по тикеру")
            val columns = market.optJSONArray("columns") ?: throw IOException("Нет колонок котировки")
            val rows = market.optJSONArray("data") ?: throw IOException("Нет котировки")
            if (rows.length() == 0) throw IOException("По тикеру пока нет котировок")
            val row = rows.getJSONArray(0)
            var price = 0.0
            for (name in listOf("LAST", "LCURRENTPRICE", "MARKETPRICE", "PREVPRICE")) {
                val index = (0 until columns.length()).firstOrNull { columns.optString(it) == name } ?: continue
                val value = if (row.isNull(index)) 0.0 else row.optDouble(index, 0.0)
                if (value > 0.0) { price = value; break }
            }
            if (price <= 0.0) throw IOException("Биржа не вернула доступную цену")
            var title = safeTicker
            val securities = root.optJSONObject("securities")
            val securityColumns = securities?.optJSONArray("columns")
            val securityRows = securities?.optJSONArray("data")
            if (securityColumns != null && securityRows != null && securityRows.length() > 0) {
                val security = securityRows.getJSONArray(0)
                for (name in listOf("SHORTNAME", "SECNAME", "NAME")) {
                    val index = (0 until securityColumns.length()).firstOrNull { securityColumns.optString(it) == name } ?: continue
                    if (!security.isNull(index) && security.optString(index).isNotBlank()) { title = security.optString(index); break }
                }
            }
            MoexQuote(title, (price * 100.0).toLong())
        } finally { connection.disconnect() }
    }

private fun depositInterestMinor(deposit: Deposit, asOfMillis: Long): Long {
    val end = minOf(asOfMillis, deposit.maturityAtMillis)
    val days = ((end - deposit.openedAtMillis).coerceAtLeast(0L) / 86400000L).toInt()
    val principal = deposit.principalMinor.toDouble()
    val dailyRate = deposit.annualRateBps.toDouble() / 10000.0 / 365.0
    val total = if (deposit.capitalization) principal * Math.pow(1.0 + dailyRate, days.toDouble())
        else principal * (1.0 + dailyRate * days)
    return (total - principal).coerceAtLeast(0.0).toLong()
}

@Composable
private fun CapitalScreen(
    modifier: Modifier, cashBalance: Long, deposits: List<Deposit>,
    investments: List<InvestmentHolding>, transfers: List<CapitalTransfer>,
    dao: ru.dimlife.finance.data.FinanceDao,
    onAddDeposit: () -> Unit, onAddInvestment: () -> Unit, onAddTransfer: () -> Unit,
    onDeleteTransfer: (CapitalTransfer) -> Unit,
    onCloseDeposit: (Deposit) -> Unit,
    onSellInvestment: (InvestmentHolding, Double) -> Unit
) {
    val scope = rememberCoroutineScope()
    var closingDeposit by remember { mutableStateOf<Deposit?>(null) }
    var sellingHolding by remember { mutableStateOf<InvestmentHolding?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var marketMessage by remember { mutableStateOf("Котировки MOEX с задержкой примерно 15 минут.") }
    val now = System.currentTimeMillis()
    val depositValue = deposits.sumOf { it.principalMinor + depositInterestMinor(it, now) }
    val securitiesValue = investments.sumOf { ((if (it.currentPriceMinor > 0) it.currentPriceMinor else it.averageBuyPriceMinor) * it.quantity).toLong() }
    val totalCapital = cashBalance + depositValue + securitiesValue
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Мой капитал", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14271C)), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ВСЕ АКТИВЫ", color = Color(0xFFB5C9BA), fontSize = 11.sp, letterSpacing = 1.sp)
                Text(money(totalCapital), color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("Наличные", color = Color(0xFFB5C9BA), fontSize = 10.sp); Text(money(cashBalance), color = Color.White, fontSize = 12.sp) }
                    Column(Modifier.weight(1f)) { Text("Вклады", color = Color(0xFFB5C9BA), fontSize = 10.sp); Text(money(depositValue), color = Color.White, fontSize = 12.sp) }
                    Column(Modifier.weight(1f)) { Text("Биржа", color = Color(0xFFB5C9BA), fontSize = 10.sp); Text(money(securitiesValue), color = Color.White, fontSize = 12.sp) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddDeposit, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Savings, null); Spacer(Modifier.width(5.dp)); Text("Вклад") }
            OutlinedButton(onClick = onAddInvestment, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ShowChart, null); Spacer(Modifier.width(5.dp)); Text("Акция / фонд") }
        }
        OutlinedButton(onClick = onAddTransfer, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.SwapHoriz, null); Spacer(Modifier.width(7.dp)); Text("Перемещение денег")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Инвестиционный портфель", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(enabled = investments.isNotEmpty() && !refreshing, onClick = {
                scope.launch {
                    refreshing = true
                    var updated = 0
                    val errors = mutableListOf<String>()
                    investments.forEach { item ->
                        try {
                            val quote = fetchMoexQuote(item.ticker, item.board)
                            dao.updateInvestmentHolding(item.copy(title = quote.title, currentPriceMinor = quote.priceMinor, priceUpdatedAtMillis = System.currentTimeMillis()))
                            updated++
                        } catch (error: Exception) { errors.add("${item.ticker}: ${error.message}") }
                    }
                    marketMessage = if (errors.isEmpty()) "Обновлено котировок: $updated" else "Обновлено: $updated. Ошибки: ${errors.take(2).joinToString("; ")}"
                    refreshing = false
                }
            }) { Text(if (refreshing) "Обновление…" else "Обновить цены") }
        }
        Text(marketMessage, color = Muted, fontSize = 11.sp)
        if (investments.isEmpty()) EmptyState("Портфель пока пуст", "Добавь тикер акции или фонда и укажи количество и цену покупки.")
        investments.forEach { item ->
            val price = if (item.currentPriceMinor > 0) item.currentPriceMinor else item.averageBuyPriceMinor
            val value = (price * item.quantity).toLong()
            val cost = (item.averageBuyPriceMinor * item.quantity).toLong()
            val profit = value - cost
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            Text("${item.ticker} · ${if (item.kind == "SHARE") "Акция" else "Фонд / ETF"}", color = Muted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { scope.launch { dao.deleteInvestmentHolding(item) } }) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = Muted) }
                    }
                    Text("Стоимость: ${money(value)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Кол-во: ${String.format(Locale("ru", "RU"), "%.4f", item.quantity)}", color = Muted, fontSize = 11.sp)
                        Text("Цена: ${money(price)}", color = Muted, fontSize = 11.sp)
                    }
                    Text("Результат: ${if (profit >= 0) "+" else ""}${money(profit)}", color = if (profit >= 0) Green else Color(0xFFFF9A83), fontSize = 12.sp)
                    Text(if (item.priceUpdatedAtMillis > 0) "Котировка: ${date(item.priceUpdatedAtMillis)}" else "Нет котировки — показана цена покупки", color = Muted, fontSize = 10.sp)
                    OutlinedButton(onClick = { sellingHolding = item }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.TrendingDown, null); Spacer(Modifier.width(6.dp)); Text("Продать часть или всю позицию")
                    }
                }
            }
        }
        Text("Банковские вклады", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (deposits.isEmpty()) EmptyState("Вкладов пока нет", "Добавь сумму, ставку и дату окончания вклада.")
        deposits.forEach { deposit ->
            val accrued = depositInterestMinor(deposit, now)
            val expected = depositInterestMinor(deposit, deposit.maturityAtMillis)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(deposit.title, fontWeight = FontWeight.SemiBold); Text(deposit.bank, color = Muted, fontSize = 11.sp) }
                        IconButton(onClick = { scope.launch { dao.deleteDeposit(deposit) } }) { Icon(Icons.Outlined.DeleteOutline, "Удалить вклад", tint = Muted) }
                    }
                    Text(money(deposit.principalMinor), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Ставка: ${String.format(Locale("ru", "RU"), "%.2f", deposit.annualRateBps / 100.0)}% · ${if (deposit.capitalization) "с капитализацией" else "без капитализации"}", color = Muted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Начислено", color = Muted, fontSize = 10.sp); Text(money(accrued), color = Green, fontWeight = FontWeight.SemiBold) }
                        Column(horizontalAlignment = Alignment.End) { Text("К концу срока", color = Muted, fontSize = 10.sp); Text(money(expected), color = Green, fontWeight = FontWeight.SemiBold) }
                    }
                    Text("Окончание: ${date(deposit.maturityAtMillis)}", color = Muted, fontSize = 10.sp)
                    OutlinedButton(onClick = { closingDeposit = deposit }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AccountBalanceWallet, null); Spacer(Modifier.width(6.dp)); Text("Закрыть вклад и вернуть деньги")
                    }
                }
            }
        }
        Text("Перемещения между наличными и активами", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (transfers.isEmpty()) EmptyState("Перемещений пока нет", "Фиксируй пополнение вкладов и покупку активов из наличных, а также возврат денег.")
        transfers.take(30).forEach { transfer ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (transfer.direction == "OUT") "Из наличных →" else "В наличные ←"} ${transfer.assetName}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text("${transfer.assetType} · ${date(transfer.dateMillis)}${if (transfer.note.isNotBlank()) " · ${transfer.note}" else ""}", color = Muted, fontSize = 10.sp)
                }
                Text((if (transfer.direction == "OUT") "−" else "+") + money(transfer.amountMinor), color = if (transfer.direction == "OUT") Color(0xFFFF9A83) else Green, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                IconButton(onClick = { onDeleteTransfer(transfer) }) { Icon(Icons.Outlined.DeleteOutline, "Удалить перемещение", tint = Muted) }
            }
        }
        Text("Перемещение меняет остаток наличных, но не является доходом или расходом.", color = Muted, fontSize = 10.sp)
        Text("Проценты по вкладам и стоимость продажи без свежей котировки рассчитываются ориентировочно.", color = Muted, fontSize = 10.sp)
        closingDeposit?.let { deposit ->
            val accrued = depositInterestMinor(deposit, System.currentTimeMillis())
            val returned = deposit.principalMinor + accrued
            AlertDialog(
                onDismissRequest = { closingDeposit = null },
                title = { Text("Закрыть вклад?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${deposit.bank} · ${deposit.title}")
                        Text("Основная сумма: ${money(deposit.principalMinor)}", color = Muted, fontSize = 12.sp)
                        Text("Ориентировочные проценты: ${money(accrued)}", color = Muted, fontSize = 12.sp)
                        Text("Будет возвращено в наличные: ${money(returned)}", fontWeight = FontWeight.Bold)
                        Text("Фактическая сумма при досрочном закрытии может отличаться от расчёта приложения.", color = Muted, fontSize = 10.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { onCloseDeposit(deposit); closingDeposit = null }) { Text("Закрыть и вернуть") } },
                dismissButton = { TextButton(onClick = { closingDeposit = null }) { Text("Отмена") } }
            )
        }
        sellingHolding?.let { holding ->
            val priceMinor = if (holding.currentPriceMinor > 0) holding.currentPriceMinor else holding.averageBuyPriceMinor
            SellInvestmentDialog(
                holding = holding,
                priceMinor = priceMinor,
                hasMarketQuote = holding.currentPriceMinor > 0,
                onDismiss = { sellingHolding = null },
                onSave = { quantity -> onSellInvestment(holding, quantity); sellingHolding = null }
            )
        }
    }
}

@Composable
private fun SellInvestmentDialog(
    holding: InvestmentHolding,
    priceMinor: Long,
    hasMarketQuote: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var quantityText by remember { mutableStateOf(holding.quantity.toString()) }
    val quantity = quantityText.replace(',', '.').toDoubleOrNull()
    val proceeds = quantity?.takeIf { it > 0 }?.let { (it * priceMinor).toLong() } ?: 0L
    val valid = quantity != null && quantity > 0 && quantity <= holding.quantity && proceeds > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Продажа ${holding.ticker}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Доступно бумаг / паёв: ${String.format(Locale("ru", "RU"), "%.4f", holding.quantity)}", color = Muted, fontSize = 11.sp)
                Text("Цена за единицу: ${money(priceMinor)}", fontSize = 12.sp)
                Text(if (hasMarketQuote) "Используется последняя сохранённая котировка MOEX." else "Котировки нет: используется средняя цена покупки.", color = Muted, fontSize = 10.sp)
                OutlinedTextField(quantityText, { quantityText = it }, label = { Text("Количество для продажи") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Text("Ориентировочно поступит: ${money(proceeds)}", fontWeight = FontWeight.Bold)
                if (quantity != null && quantity > holding.quantity)
                    Text("Нельзя продать больше имеющегося количества.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(quantity!!) }) { Text("Продать и вернуть деньги") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddCapitalTransferDialog(cashBalance: Long, onDismiss: () -> Unit, onSave: (String, String, String, Double, String, Long) -> Unit) {
    var direction by remember { mutableStateOf("OUT") }
    var assetType by remember { mutableStateOf("Вклад") }
    var assetName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Перемещение денег") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = direction == "OUT", onClick = { direction = "OUT" }, label = { Text("Из наличных") })
                    FilterChip(selected = direction == "IN", onClick = { direction = "IN" }, label = { Text("В наличные") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Вклад", "Акции", "Фонды", "Другое").forEach { type ->
                        FilterChip(selected = assetType == type, onClick = { assetType = type }, label = { Text(type, fontSize = 10.sp) })
                    }
                }
                OutlinedTextField(assetName, { assetName = it }, label = { Text("Название актива / банка") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Комментарий (необязательно)") }, singleLine = true)
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
                    DatePickerDialog(context, { _, y, m, d ->
                        dateMillis = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Дата: ${date(dateMillis)}") }
                Text("Доступно наличными: ${money(cashBalance)}", color = Muted, fontSize = 10.sp)
                val parsed = amount.replace(',', '.').toDoubleOrNull()
                if (direction == "OUT" && parsed != null && parsed * 100.0 > cashBalance)
                    Text("Недостаточно наличных для такого перевода.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                Text(if (direction == "OUT") "Сумма будет вычтена из наличных." else "Сумма будет добавлена к наличным.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            val value = amount.replace(',', '.').toDoubleOrNull()
            if (assetName.isNotBlank() && value != null && value > 0 && value <= 100000000000.0 &&
                (direction != "OUT" || value * 100.0 <= cashBalance))
                onSave(direction, assetType, assetName.trim(), value, note.trim(), dateMillis)
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddDepositDialog(cashBalance: Long, onDismiss: () -> Unit, onSave: (String, String, Double, Double, Long, Long, Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    var bank by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Накопительный вклад") }
    var amount by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var capitalization by remember { mutableStateOf(true) }
    var fundFromCash by remember { mutableStateOf(false) }
    var opened by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var maturity by remember { mutableLongStateOf(Calendar.getInstance().apply { add(Calendar.YEAR, 1) }.timeInMillis) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить вклад") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(bank, { bank = it }, label = { Text("Банк") }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text("Название вклада") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(rate, { rate = it }, label = { Text("Ставка, % годовых") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = opened }
                    DatePickerDialog(context, { _, y, m, d -> opened = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis },
                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Дата открытия: ${date(opened)}") }
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = maturity }
                    DatePickerDialog(context, { _, y, m, d -> maturity = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis },
                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Дата окончания: ${date(maturity)}") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Капитализация процентов", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Switch(checked = capitalization, onCheckedChange = { capitalization = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fundFromCash, onCheckedChange = { fundFromCash = it })
                    Column(Modifier.weight(1f)) {
                        Text("Списать сумму из наличных", fontSize = 12.sp)
                        Text("Отметь, если вклад открывается сейчас на эти деньги.", color = Muted, fontSize = 10.sp)
                    }
                }
                val amountMinor = amount.replace(',', '.').toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
                Text("Доступно наличными: ${money(cashBalance)}", color = Muted, fontSize = 10.sp)
                if (fundFromCash && amountMinor > cashBalance)
                    Text("Недостаточно наличных для этого вклада.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            val sum = amount.replace(',', '.').toDoubleOrNull()
            val percent = rate.replace(',', '.').toDoubleOrNull()
            if (bank.isNotBlank() && title.isNotBlank() && sum != null && sum > 0 && percent != null && percent in 0.0..100.0 && maturity > opened && opened <= System.currentTimeMillis() &&
                (!fundFromCash || sum * 100.0 <= cashBalance))
                onSave(bank.trim(), title.trim(), sum, percent, opened, maturity, capitalization, fundFromCash)
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddInvestmentDialog(cashBalance: Long, onDismiss: () -> Unit, onSave: (String, String, String, Double, Double, Boolean) -> Unit) {
    var kind by remember { mutableStateOf("SHARE") }
    var board by remember { mutableStateOf("TQBR") }
    var ticker by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var buyPrice by remember { mutableStateOf("") }
    var buyFromCash by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить в портфель") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = board == "TQBR", onClick = { kind = "SHARE"; board = "TQBR" }, label = { Text("Акция") })
                    FilterChip(selected = board == "TQIF", onClick = { kind = "FUND"; board = "TQIF" }, label = { Text("Фонд / ПИФ") })
                    FilterChip(selected = board == "TQTF", onClick = { kind = "FUND"; board = "TQTF" }, label = { Text("ETF") })
                }
                OutlinedTextField(ticker, { ticker = it.uppercase(Locale.ROOT) }, label = { Text("Тикер MOEX") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Количество бумаг / паёв") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(buyPrice, { buyPrice = it }, label = { Text("Средняя цена покупки, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Text("Рыночная цена берётся с Московской биржи с задержкой. Акции: TQBR, ПИФ: TQIF, ETF: TQTF.", color = Muted, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = buyFromCash, onCheckedChange = { buyFromCash = it })
                    Column(Modifier.weight(1f)) {
                        Text("Списать стоимость покупки из наличных", fontSize = 12.sp)
                        Text("Не отмечай, если добавляешь уже имеющиеся бумаги.", color = Muted, fontSize = 10.sp)
                    }
                }
                val purchaseMinor = ((quantity.replace(',', '.').toDoubleOrNull() ?: 0.0) *
                    (buyPrice.replace(',', '.').toDoubleOrNull() ?: 0.0) * 100.0).toLong()
                Text("Доступно наличными: ${money(cashBalance)}", color = Muted, fontSize = 10.sp)
                if (buyFromCash && purchaseMinor > cashBalance)
                    Text("Недостаточно наличных для покупки.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            val count = quantity.replace(',', '.').toDoubleOrNull()
            val price = buyPrice.replace(',', '.').toDoubleOrNull()
            val totalMinor = if (count != null && price != null) (count * price * 100.0).toLong() else Long.MAX_VALUE
            if (ticker.matches(Regex("[A-Z0-9._-]{1,20}")) && count != null && count > 0 && price != null && price > 0 &&
                (!buyFromCash || totalMinor <= cashBalance))
                onSave(ticker, kind, board, count, price, buyFromCash)
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AnalyticsScreen(
    modifier: Modifier,
    items: List<FinanceTransaction>,
    budgets: List<CategoryBudget>,
    onDeleteBudget: (CategoryBudget) -> Unit
) {
    var selectedMonth by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        })
    }
    val selectedRange = remember(selectedMonth.timeInMillis) { monthRangeFor(selectedMonth.timeInMillis) }
    val monthItems = items.filter { it.dateMillis in selectedRange.first..selectedRange.second }
    val monthIncome = monthItems.filter { it.type == "INCOME" }.sumOf { it.amountMinor }
    val monthExpenses = monthItems.filter { it.type == "EXPENSE" }.sumOf { it.amountMinor }
    val monthBalance = monthIncome - monthExpenses
    val spentByCategory = monthItems.filter { it.type == "EXPENSE" }
        .groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
        .toList().sortedByDescending { it.second }.take(6)
    val monthTitle = SimpleDateFormat("LLLL yyyy", Locale("ru", "RU"))
        .format(Date(selectedMonth.timeInMillis)).replaceFirstChar { it.titlecase(Locale("ru", "RU")) }

    val trend = (-5..0).map { offset ->
        val month = (selectedMonth.clone() as Calendar).apply { add(Calendar.MONTH, offset) }
        val range = monthRangeFor(month.timeInMillis)
        val rows = items.filter { it.dateMillis in range.first..range.second }
        val label = SimpleDateFormat("LLL", Locale("ru", "RU")).format(Date(month.timeInMillis))
        Triple(label, rows.filter { it.type == "INCOME" }.sumOf { it.amountMinor },
            rows.filter { it.type == "EXPENSE" }.sumOf { it.amountMinor })
    }
    val trendMax = trend.maxOfOrNull { maxOf(it.second, it.third) }?.coerceAtLeast(1L) ?: 1L
    val currentRange = remember { currentMonthRange() }
    val currentSpentByCategory = items.filter {
        it.type == "EXPENSE" && it.dateMillis in currentRange.first..currentRange.second
    }.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
    val currentMonthTitle = SimpleDateFormat("LLLL yyyy", Locale("ru", "RU"))
        .format(Date()).replaceFirstChar { it.titlecase(Locale("ru", "RU")) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Аналитика", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    selectedMonth = (selectedMonth.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                }) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий месяц") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Выбранный период", color = Muted, fontSize = 10.sp)
                    Text(monthTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = {
                    selectedMonth = (selectedMonth.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                }) { Icon(Icons.Outlined.ChevronRight, "Следующий месяц") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Итоги за месяц", color = Muted, fontSize = 12.sp)
                Text(money(monthBalance), fontSize = 27.sp, fontWeight = FontWeight.Bold,
                    color = if (monthBalance >= 0) Green else Color(0xFFFF9A83))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Доходы", color = Muted, fontSize = 11.sp)
                        Text(money(monthIncome), color = Green, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Расходы", color = Muted, fontSize = 11.sp)
                        Text(money(monthExpenses), color = Color(0xFFFF9A83), fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("Операций: ${monthItems.size}", color = Muted, fontSize = 11.sp)
                if (monthItems.isEmpty()) EmptyState("За этот месяц записей нет", "Добавленные доходы и расходы появятся здесь.")
            }
        }

        Text("Динамика за 6 месяцев", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Green, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(5.dp)); Text("Доходы", fontSize = 10.sp, color = Muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF9A83), RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(5.dp)); Text("Расходы", fontSize = 10.sp, color = Muted)
                    }
                }
                trend.forEach { (label, incomeValue, expenseValue) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label.replaceFirstChar { it.titlecase(Locale("ru", "RU")) }, fontSize = 11.sp, color = Muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Д", fontSize = 9.sp, color = Green, modifier = Modifier.width(14.dp))
                            LinearProgressIndicator(progress = { incomeValue.toFloat() / trendMax.toFloat() },
                                modifier = Modifier.weight(1f).height(7.dp), color = Green, trackColor = MaterialTheme.colorScheme.background)
                            Spacer(Modifier.width(8.dp))
                            Text(money(incomeValue), fontSize = 10.sp, modifier = Modifier.width(90.dp), maxLines = 1)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Р", fontSize = 9.sp, color = Color(0xFFFF9A83), modifier = Modifier.width(14.dp))
                            LinearProgressIndicator(progress = { expenseValue.toFloat() / trendMax.toFloat() },
                                modifier = Modifier.weight(1f).height(7.dp), color = Color(0xFFFF9A83), trackColor = MaterialTheme.colorScheme.background)
                            Spacer(Modifier.width(8.dp))
                            Text(money(expenseValue), fontSize = 10.sp, modifier = Modifier.width(90.dp), maxLines = 1)
                        }
                    }
                }
            }
        }

        Text("Расходы по категориям", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (spentByCategory.isEmpty()) {
            EmptyState("Расходов пока нет", "Добавь расход за выбранный месяц — здесь появится разбивка.")
        } else {
            val categoryMax = spentByCategory.maxOf { it.second }.coerceAtLeast(1L)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    spentByCategory.forEach { (category, amount) ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(category, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(money(amount), fontSize = 12.sp, color = Color(0xFFFF9A83))
                            }
                            LinearProgressIndicator(progress = { amount.toFloat() / categoryMax.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(7.dp), color = Color(0xFFFF9A83))
                        }
                    }
                }
            }
        }

        Text("Бюджеты на текущий месяц · $currentMonthTitle", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (budgets.isEmpty()) {
            EmptyState("Бюджеты не заданы", "Нажми +, чтобы установить месячный лимит для категории расходов.")
        } else {
            budgets.forEach { budget ->
                val spent = currentSpentByCategory[budget.category] ?: 0L
                val progress = (spent.toFloat() / budget.limitMinor.coerceAtLeast(1L)).coerceIn(0f, 1f)
                val exceeded = spent > budget.limitMinor
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(budget.category, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onDeleteBudget(budget) }) { Icon(Icons.Outlined.DeleteOutline, "Удалить бюджет", tint = Muted) }
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Потрачено: ${money(spent)}", fontSize = 12.sp, color = if (exceeded) Color(0xFFFF8A75) else Muted)
                            Text("Лимит: ${money(budget.limitMinor)}", fontSize = 12.sp)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = if (exceeded) Color(0xFFFF8A75) else Green)
                        Text(if (exceeded) "Превышение: ${money(spent - budget.limitMinor)}" else "Осталось: ${money(budget.limitMinor - spent)}",
                            fontSize = 12.sp, color = if (exceeded) Color(0xFFFF8A75) else Green)
                    }
                }
            }
        }
    }
}

private fun monthRangeFor(monthMillis: Long): Pair<Long, Long> {
    val start = Calendar.getInstance().apply {
        timeInMillis = monthMillis
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val end = (start.clone() as Calendar).apply {
        add(Calendar.MONTH, 1)
        add(Calendar.MILLISECOND, -1)
    }
    return start.timeInMillis to end.timeInMillis
}

private fun currentMonthRange(): Pair<Long, Long> = monthRangeFor(System.currentTimeMillis())

@Composable
private fun AddBudgetDialog(onDismiss: () -> Unit, onSave: (String, Double) -> Unit) {
    var category by remember { mutableStateOf("Продукты") }
    var amount by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый месячный бюджет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Категория: $category") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("Продукты", "Транспорт", "Жильё", "Здоровье", "Развлечения", "Покупки", "Прочее").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                        }
                    }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Лимит на месяц, ₽") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.replace(',', '.').toDoubleOrNull()
                if (value != null && value > 0) onSave(category, value)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    darkTheme: Boolean,
    dao: ru.dimlife.finance.data.FinanceDao,
    onThemeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remindersEnabled by context.financeDataStore.data
        .map { preferences -> preferences[PAYMENT_REMINDERS_KEY] ?: false }
        .collectAsStateWithLifecycle(initialValue = false)
    var reminderMessage by remember { mutableStateOf("Напомним о подписках за сутки до списания. Проверка выполняется ежедневно около 09:00.") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { context.financeDataStore.edit { it[PAYMENT_REMINDERS_KEY] = true } }
            scheduleSubscriptionReminders(context)
            reminderMessage = "Напоминания включены."
        } else {
            reminderMessage = "Разрешение на уведомления не выдано. Включи его в настройках Android."
        }
    }

    LaunchedEffect(remindersEnabled) {
        if (remindersEnabled) {
            if (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                scheduleSubscriptionReminders(context)
            } else {
                reminderMessage = "Разреши уведомления Android, чтобы получать напоминания."
            }
        } else {
            cancelSubscriptionReminders(context)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Тёмная тема", fontWeight = FontWeight.SemiBold)
                Text("Можно переключиться на светлое оформление", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = darkTheme, onCheckedChange = onThemeChange)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Напоминания о платежах", fontWeight = FontWeight.SemiBold)
                    Text("Уведомление за сутки до списания, проверка около 09:00", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = remindersEnabled, onCheckedChange = { enabled ->
                    if (!enabled) {
                        scope.launch { context.financeDataStore.edit { it[PAYMENT_REMINDERS_KEY] = false } }
                        cancelSubscriptionReminders(context)
                        reminderMessage = "Напоминания выключены."
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        scope.launch { context.financeDataStore.edit { it[PAYMENT_REMINDERS_KEY] = true } }
                        scheduleSubscriptionReminders(context)
                        reminderMessage = "Напоминания включены."
                    }
                })
            }
            Text(reminderMessage, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), color = Muted, fontSize = 11.sp)
        }
        BackupSection(dao)
        UpdateSection()
        EmptyState("Данные хранятся на устройстве", "Финансовая информация не отправляется на сервер и не требует подключения к банку.")
        Text("DimLife Finance · версия 0.10.0", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun BackupSection(dao: ru.dimlife.finance.data.FinanceDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Создай резервную копию перед переносом приложения на другой телефон.") }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingBackup
        if (uri != null && json != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    ?: throw IOException("Не удалось открыть файл для записи")
                message = "Резервная копия успешно сохранена."
            } catch (error: Exception) {
                message = error.message ?: "Не удалось сохранить резервную копию."
            }
        }
        pendingBackup = null
        busy = false
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val jsonText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: throw IOException("Не удалось прочитать файл")
                    }
                    val backup = withContext(Dispatchers.Default) { parseFinanceBackup(jsonText) }
                    dao.restoreBackup(backup.transactions, backup.subscriptions, backup.budgets, backup.deposits, backup.investments, backup.transfers)
                    message = "Восстановлено: ${backup.transactions.size} операций, ${backup.subscriptions.size} подписок, ${backup.budgets.size} бюджетов, ${backup.deposits.size} вкладов и ${backup.investments.size} позиций портфеля."
                } catch (error: Exception) {
                    message = error.message ?: "Не удалось восстановить резервную копию."
                } finally {
                    busy = false
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Резервное копирование", fontWeight = FontWeight.SemiBold)
            Text("Экспортируй операции, подписки, бюджеты, вклады, биржевой портфель и перемещения денег в JSON. Файл можно сохранить или перенести на другой телефон.", color = Muted, fontSize = 12.sp)
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val transactions = dao.getAllTransactions()
                            val subscriptions = dao.getAllSubscriptions()
                            val budgets = dao.getAllBudgets()
                            val deposits = dao.getAllDeposits()
                            val investments = dao.getAllInvestmentHoldings()
                            val transfers = dao.getAllCapitalTransfers()
                            pendingBackup = createFinanceBackup(transactions, subscriptions, budgets, deposits, investments, transfers)
                            exportLauncher.launch("DimLife-Finance-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json")
                        } catch (error: Exception) {
                            message = error.message ?: "Не удалось подготовить резервную копию."
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Создать резервную копию") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Восстановить из файла") }
            Text(message, color = Muted, fontSize = 11.sp)
            Text("Внимание: восстановление полностью заменяет текущие записи данными из файла.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}

private fun createFinanceBackup(
    transactions: List<FinanceTransaction>,
    subscriptions: List<Subscription>,
    budgets: List<CategoryBudget>,
    deposits: List<Deposit>,
    investments: List<InvestmentHolding>,
    transfers: List<CapitalTransfer>
): String {
    val root = JSONObject()
        .put("format", "dimlife-finance-backup")
        .put("version", 1)
        .put("createdAtMillis", System.currentTimeMillis())
    root.put("transactions", JSONArray().apply {
        transactions.forEach { item ->
            put(JSONObject().put("id", item.id).put("title", item.title)
                .put("amountMinor", item.amountMinor).put("type", item.type)
                .put("category", item.category).put("dateMillis", item.dateMillis))
        }
    })
    root.put("subscriptions", JSONArray().apply {
        subscriptions.forEach { item ->
            put(JSONObject().put("id", item.id).put("title", item.title)
                .put("amountMinor", item.amountMinor).put("currency", item.currency)
                .put("period", item.period).put("nextPaymentMillis", item.nextPaymentMillis)
                .put("isActive", item.isActive))
        }
    })
    root.put("budgets", JSONArray().apply {
        budgets.forEach { item ->
            put(JSONObject().put("id", item.id).put("category", item.category)
                .put("limitMinor", item.limitMinor).put("monthKey", item.monthKey)
                .put("createdAtMillis", item.createdAtMillis))
        }
    })
    root.put("deposits", JSONArray().apply {
        deposits.forEach { item ->
            put(JSONObject().put("id", item.id).put("bank", item.bank).put("title", item.title)
                .put("principalMinor", item.principalMinor).put("annualRateBps", item.annualRateBps)
                .put("openedAtMillis", item.openedAtMillis).put("maturityAtMillis", item.maturityAtMillis)
                .put("capitalization", item.capitalization))
        }
    })
    root.put("investments", JSONArray().apply {
        investments.forEach { item ->
            put(JSONObject().put("id", item.id).put("ticker", item.ticker).put("title", item.title)
                .put("kind", item.kind).put("quantity", item.quantity)
                .put("averageBuyPriceMinor", item.averageBuyPriceMinor).put("currentPriceMinor", item.currentPriceMinor)
                .put("currency", item.currency).put("board", item.board).put("priceUpdatedAtMillis", item.priceUpdatedAtMillis))
        }
    })
    root.put("transfers", JSONArray().apply {
        transfers.forEach { item ->
            put(JSONObject().put("id", item.id).put("direction", item.direction)
                .put("assetType", item.assetType).put("assetName", item.assetName)
                .put("amountMinor", item.amountMinor).put("dateMillis", item.dateMillis).put("note", item.note))
        }
    })
    return root.toString(2)
}

private data class FinanceBackupData(
    val transactions: List<FinanceTransaction>,
    val subscriptions: List<Subscription>,
    val budgets: List<CategoryBudget>,
    val deposits: List<Deposit>,
    val investments: List<InvestmentHolding>,
    val transfers: List<CapitalTransfer>
)

private fun parseFinanceBackup(jsonText: String): FinanceBackupData {
    val root = JSONObject(jsonText)
    if (root.optString("format") != "dimlife-finance-backup" || root.optInt("version") != 1) {
        throw IOException("Файл не является резервной копией DimLife Finance или имеет неподдерживаемую версию.")
    }
    val transactionsJson = root.optJSONArray("transactions") ?: throw IOException("В резервной копии нет списка операций.")
    val subscriptionsJson = root.optJSONArray("subscriptions") ?: throw IOException("В резервной копии нет списка подписок.")
    val budgetsJson = root.optJSONArray("budgets") ?: throw IOException("В резервной копии нет списка бюджетов.")

    val transactions = (0 until transactionsJson.length()).map { index ->
        val item = transactionsJson.getJSONObject(index)
        val type = item.getString("type")
        if (type != "INCOME" && type != "EXPENSE") throw IOException("Некорректный тип операции.")
        FinanceTransaction(
            id = item.getLong("id"), title = item.getString("title"),
            amountMinor = item.getLong("amountMinor"), type = type,
            category = item.getString("category"), dateMillis = item.getLong("dateMillis")
        )
    }
    val subscriptions = (0 until subscriptionsJson.length()).map { index ->
        val item = subscriptionsJson.getJSONObject(index)
        Subscription(
            id = item.getLong("id"), title = item.getString("title"),
            amountMinor = item.getLong("amountMinor"), currency = item.optString("currency", "RUB"),
            period = item.optString("period", "MONTHLY"),
            nextPaymentMillis = item.getLong("nextPaymentMillis"),
            isActive = item.optBoolean("isActive", true)
        )
    }
    val budgets = (0 until budgetsJson.length()).map { index ->
        val item = budgetsJson.getJSONObject(index)
        CategoryBudget(
            id = item.getLong("id"), category = item.getString("category"),
            limitMinor = item.getLong("limitMinor"), monthKey = item.getString("monthKey"),
            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis())
        )
    }
    val depositsJson = root.optJSONArray("deposits") ?: JSONArray()
    val deposits = (0 until depositsJson.length()).map { index ->
        val item = depositsJson.getJSONObject(index)
        Deposit(
            id = item.getLong("id"), bank = item.getString("bank"), title = item.getString("title"),
            principalMinor = item.getLong("principalMinor"), annualRateBps = item.getInt("annualRateBps"),
            openedAtMillis = item.getLong("openedAtMillis"), maturityAtMillis = item.getLong("maturityAtMillis"),
            capitalization = item.optBoolean("capitalization", true)
        )
    }
    val investmentsJson = root.optJSONArray("investments") ?: JSONArray()
    val investments = (0 until investmentsJson.length()).map { index ->
        val item = investmentsJson.getJSONObject(index)
        val kind = item.getString("kind")
        if (kind != "SHARE" && kind != "FUND") throw IOException("Некорректный тип биржевого актива.")
        InvestmentHolding(
            id = item.getLong("id"), ticker = item.getString("ticker"), title = item.getString("title"),
            kind = kind, quantity = item.getDouble("quantity"), averageBuyPriceMinor = item.getLong("averageBuyPriceMinor"),
            currentPriceMinor = item.optLong("currentPriceMinor", 0L), currency = item.optString("currency", "RUB"),
            board = item.optString("board", if (kind == "SHARE") "TQBR" else "TQTF"),
            priceUpdatedAtMillis = item.optLong("priceUpdatedAtMillis", 0L)
        )
    }
    val transfersJson = root.optJSONArray("transfers") ?: JSONArray()
    val transfers = (0 until transfersJson.length()).map { index ->
        val item = transfersJson.getJSONObject(index)
        val direction = item.getString("direction")
        val assetType = item.getString("assetType")
        if (direction != "OUT" && direction != "IN") throw IOException("Некорректное направление перемещения.")
        if (assetType !in listOf("Вклад", "Акции", "Фонды", "Другое")) throw IOException("Некорректный тип актива в перемещении.")
        CapitalTransfer(id = item.getLong("id"), direction = direction, assetType = assetType,
            assetName = item.getString("assetName"), amountMinor = item.getLong("amountMinor"),
            dateMillis = item.getLong("dateMillis"), note = item.optString("note", ""))
    }
    if (transactions.size > 100000 || subscriptions.size > 10000 || budgets.size > 10000 ||
        deposits.size > 10000 || investments.size > 10000 || transfers.size > 100000) {
        throw IOException("В файле слишком много записей.")
    }
    if (transactions.any { it.title.isBlank() || it.amountMinor <= 0 } ||
        subscriptions.any { it.title.isBlank() || it.amountMinor <= 0 } ||
        budgets.any { it.category.isBlank() || it.limitMinor <= 0 } ||
        deposits.any { it.bank.isBlank() || it.title.isBlank() || it.principalMinor <= 0 || it.annualRateBps < 0 } ||
        investments.any { it.ticker.isBlank() || it.title.isBlank() || it.quantity <= 0 || it.averageBuyPriceMinor <= 0 } ||
        transfers.any { it.assetName.isBlank() || it.amountMinor <= 0 }) {
        throw IOException("В резервной копии обнаружены некорректные записи.")
    }
    return FinanceBackupData(transactions, subscriptions, budgets, deposits, investments, transfers)
}

private data class LatestRelease(
    val version: String,
    val apkUrl: String,
    val releasePage: String
)

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.5.0"
    }
    var latest by remember { mutableStateOf<LatestRelease?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Проверяем обновления…") }

    fun checkUpdates() {
        scope.launch {
            checking = true
            try {
                latest = getLatestRelease(currentVersion)
                message = if (latest == null) "Установлена последняя версия" else "Доступна версия ${latest!!.version}"
            } catch (_: Exception) {
                message = "Не удалось проверить обновления. Проверь подключение к интернету."
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdates() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обновление приложения", fontWeight = FontWeight.SemiBold)
            Text("Установлена версия $currentVersion", color = Muted, fontSize = 12.sp)
            Text(message, fontSize = 13.sp)
            Button(
                onClick = {
                    val file = downloadedApk
                    if (file != null) {
                        try {
                            openApkInstaller(context, file)
                        } catch (error: Exception) {
                            message = error.message ?: "Не удалось открыть установщик Android"
                        }
                    } else {
                        val release = latest ?: return@Button
                        scope.launch {
                            downloading = true
                            try {
                                val apk = downloadApk(context, release.apkUrl)
                                downloadedApk = apk
                                message = "Файл скачан. Можно установить обновление."
                                openApkInstaller(context, apk)
                            } catch (error: Exception) {
                                message = error.message ?: "Не удалось скачать обновление"
                            } finally {
                                downloading = false
                            }
                        }
                    }
                },
                enabled = !downloading && !checking && (latest != null || downloadedApk != null),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        downloading -> "Скачивание…"
                        downloadedApk != null -> "Установить скачанное обновление"
                        else -> "Скачать и установить"
                    }
                )
            }
            TextButton(
                onClick = { checkUpdates() },
                enabled = !checking && !downloading
            ) {
                Text(if (checking) "Проверяем…" else "Проверить обновления")
            }
        }
    }
}

private suspend fun getLatestRelease(currentVersion: String): LatestRelease? =
    withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/repos/dimliferu/DimLifeFinance/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DimLife-Android")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("GitHub вернул ошибку: ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null
            val version = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val assetName = asset.optString("name")
                if (assetName == "DimLife-Finance-v$version.apk" || assetName == "DimLife.apk") {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank() || compareVersions(version, currentVersion) <= 0) null
            else LatestRelease(version, apkUrl, json.optString("html_url"))
        } finally {
            connection.disconnect()
        }
    }

private fun compareVersions(first: String, second: String): Int {
    val left = first.split(".").map { it.toIntOrNull() ?: 0 }
    val right = second.split(".").map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(left.size, right.size)) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private suspend fun downloadApk(context: Context, url: String): File =
    withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DimLife-Android")
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "DimLife-update.apk")
        try {
            if (connection.responseCode !in 200..299) throw IOException("Не удалось скачать APK: ${connection.responseCode}")
            connection.inputStream.use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            if (apk.length() < 100_000) throw IOException("Скачанный файл слишком маленький и не похож на APK")
            ZipFile(apk).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) throw IOException("Файл обновления повреждён")
            }
            apk
        } catch (error: Exception) {
            apk.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

private fun openApkInstaller(context: Context, apk: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
}

@Composable
private fun AddTransactionDialog(initial: FinanceTransaction?, onDismiss: () -> Unit, onSave: (String, Double, String, String, Long) -> Unit) {
    val context = LocalContext.current
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var amount by remember(initial?.id) { mutableStateOf(initial?.amountMinor?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: "EXPENSE") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "Прочее") }
    var dateMillis by remember(initial?.id) { mutableLongStateOf(initial?.dateMillis ?: System.currentTimeMillis()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая операция" else "Редактировать операцию") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = dateMillis }
                    DatePickerDialog(context, { _, year, month, day ->
                        dateMillis = Calendar.getInstance().apply { set(year, month, day, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Дата: ${date(dateMillis)}") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE" }, label = { Text("Расход") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = type == "INCOME", onClick = { type = "INCOME" }, label = { Text("Доход") })
                }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Категория: $category") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("Продукты", "Транспорт", "Жильё", "Здоровье", "Развлечения", "Зарплата", "Покупки", "Прочее").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.replace(',', '.').toDoubleOrNull()
                if (title.isNotBlank() && value != null && value > 0) onSave(title.trim(), value, type, category, dateMillis)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddSubscriptionDialog(initial: Subscription?, onDismiss: () -> Unit, onSave: (String, Double, String, Long) -> Unit) {
    val context = LocalContext.current
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var amount by remember(initial?.id) { mutableStateOf(initial?.amountMinor?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: "") }
    var period by remember(initial?.id) { mutableStateOf(initial?.period ?: "MONTHLY") }
    var nextPaymentMillis by remember(initial?.id) { mutableLongStateOf(initial?.nextPaymentMillis ?: (System.currentTimeMillis() + 30L * 86400000)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая подписка" else "Редактировать подписку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название сервиса") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Стоимость, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = nextPaymentMillis }
                    DatePickerDialog(context, { _, year, month, day ->
                        nextPaymentMillis = Calendar.getInstance().apply { set(year, month, day, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Следующий платёж: ${date(nextPaymentMillis)}") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = period == "WEEKLY", onClick = { period = "WEEKLY" }, label = { Text("Неделя") })
                    FilterChip(selected = period == "MONTHLY", onClick = { period = "MONTHLY" }, label = { Text("Месяц") })
                    FilterChip(selected = period == "YEARLY", onClick = { period = "YEARLY" }, label = { Text("Год") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.replace(',', '.').toDoubleOrNull()
                if (title.isNotBlank() && value != null && value > 0) onSave(title.trim(), value, period, nextPaymentMillis)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun money(minor: Long): String {
    val amount = minor / 100.0
    return String.format(Locale("ru", "RU"), "%,.2f ₽", amount).replace(',', ' ').replace(".00 ₽", " ₽")
}

private fun date(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale("ru", "RU")).format(Date(millis))
