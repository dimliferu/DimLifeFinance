package ru.dimlife.finance

import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DimLifeApp() }
    }
}

private enum class Tab(val label: String) {
    HOME("Главная"), OPERATIONS("Операции"), SUBSCRIPTIONS("Подписки"),
    ANALYTICS("Аналитика"), SETTINGS("Настройки")
}

@Composable
private fun DimLifeApp() {
    val context = LocalContext.current
    val dao = remember { FinanceDatabase.get(context).financeDao() }
    val transactions by dao.observeTransactions().collectAsStateWithLifecycle(initialValue = emptyList())
    val subscriptions by dao.observeSubscriptions().collectAsStateWithLifecycle(initialValue = emptyList())
    val monthKey = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }
    val budgets by dao.observeBudgets(monthKey).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    val darkTheme by context.financeDataStore.data.map { preferences -> preferences[DARK_THEME_KEY] ?: true }
        .collectAsStateWithLifecycle(initialValue = true)
    var showTransactionDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    var editingSubscription by remember { mutableStateOf<Subscription?>(null) }

    val income = transactions.filter { it.type == "INCOME" }.sumOf { it.amountMinor }
    val expenses = transactions.filter { it.type == "EXPENSE" }.sumOf { it.amountMinor }
    val balance = income - expenses

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
                    Modifier.padding(padding), balance, income, expenses, transactions.take(5),
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
                Tab.ANALYTICS -> AnalyticsScreen(Modifier.padding(padding), income, expenses, transactions, budgets,
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

@Composable
private fun SubscriptionsScreen(modifier: Modifier, items: List<Subscription>, onDelete: (Subscription) -> Unit, onEdit: (Subscription) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Мои подписки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Регулярные платежи и даты списаний", color = Muted, fontSize = 12.sp)
        }
        if (items.isEmpty()) item { EmptyState("Подписок пока нет", "Добавь сервисы и регулярные платежи.") }
        items(items, key = { it.id }) { SubscriptionRow(it, { onEdit(it) }, { onDelete(it) }) }
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

@Composable
private fun AnalyticsScreen(
    modifier: Modifier,
    income: Long,
    expenses: Long,
    items: List<FinanceTransaction>,
    budgets: List<CategoryBudget>,
    onDeleteBudget: (CategoryBudget) -> Unit
) {
    val total = (income + expenses).coerceAtLeast(1)
    val monthRange = remember { currentMonthRange() }
    val spentByCategory = items.filter { it.type == "EXPENSE" && it.dateMillis in monthRange.first..monthRange.second }
        .groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Аналитика", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сводка по всем операциям", color = Muted, fontSize = 12.sp)
                Text("Доходы: ${money(income)}", color = Green, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { income.toFloat() / total }, modifier = Modifier.fillMaxWidth(), color = Green)
                Text("Расходы: ${money(expenses)}", color = Color(0xFFFF9A83), fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { expenses.toFloat() / total }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFFF9A83))
                Text("Всего операций: ${items.size}", color = Muted, fontSize = 12.sp)
            }
        }
        Text("Бюджеты на ${SimpleDateFormat("LLLL yyyy", Locale("ru", "RU")).format(Date())}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (budgets.isEmpty()) {
            EmptyState("Бюджеты не заданы", "Нажми +, чтобы установить месячный лимит для категории расходов.")
        } else {
            budgets.forEach { budget ->
                val spent = spentByCategory[budget.category] ?: 0L
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

private fun currentMonthRange(): Pair<Long, Long> {
    val start = Calendar.getInstance().apply {
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Тёмная тема", fontWeight = FontWeight.SemiBold)
                Text("Можно переключиться на светлое оформление", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = darkTheme, onCheckedChange = onThemeChange)
        }
        BackupSection(dao)
        UpdateSection()
        EmptyState("Данные хранятся на устройстве", "Финансовая информация не отправляется на сервер и не требует подключения к банку.")
        Text("DimLife Finance · версия 0.6.0", color = Muted, fontSize = 11.sp)
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
                    dao.restoreBackup(backup.first, backup.second, backup.third)
                    message = "Восстановлено: ${backup.first.size} операций, ${backup.second.size} подписок, ${backup.third.size} бюджетов. Старые данные заменены."
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
            Text("Экспортируй операции, подписки и бюджеты в JSON. Файл можно сохранить в облако или перенести на другой телефон.", color = Muted, fontSize = 12.sp)
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val transactions = dao.getAllTransactions()
                            val subscriptions = dao.getAllSubscriptions()
                            val budgets = dao.getAllBudgets()
                            pendingBackup = createFinanceBackup(transactions, subscriptions, budgets)
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
    budgets: List<CategoryBudget>
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
    return root.toString(2)
}

private fun parseFinanceBackup(jsonText: String): Triple<List<FinanceTransaction>, List<Subscription>, List<CategoryBudget>> {
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
    if (transactions.size > 100000 || subscriptions.size > 10000 || budgets.size > 10000) {
        throw IOException("В файле слишком много записей.")
    }
    if (transactions.any { it.title.isBlank() || it.amountMinor <= 0 } ||
        subscriptions.any { it.title.isBlank() || it.amountMinor <= 0 } ||
        budgets.any { it.category.isBlank() || it.limitMinor <= 0 }) {
        throw IOException("В резервной копии обнаружены некорректные записи.")
    }
    return Triple(transactions, subscriptions, budgets)
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
