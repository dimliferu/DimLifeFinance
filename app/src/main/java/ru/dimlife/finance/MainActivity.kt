package ru.dimlife.finance

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import kotlinx.coroutines.withContext
import ru.dimlife.finance.data.FinanceDatabase
import ru.dimlife.finance.data.FinanceTransaction
import ru.dimlife.finance.data.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import org.json.JSONObject

private val Green = Color(0xFF42D982)
private val DarkBackground = Color(0xFF0B110E)
private val DarkSurface = Color(0xFF141D18)
private val Muted = Color(0xFF9BAAA0)

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
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var darkTheme by remember { mutableStateOf(true) }
    var showTransactionDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }

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
                        Text("DimLife", fontSize = 25.sp, fontWeight = FontWeight.Bold)
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
                }
            }
        ) { padding ->
            when (selectedTab) {
                Tab.HOME -> HomeScreen(
                    Modifier.padding(padding), balance, income, expenses, transactions.take(5),
                    subscriptions.take(3),
                    onAdd = { showTransactionDialog = true },
                    onDelete = { item -> scope.launch { dao.deleteTransaction(item) } }
                )
                Tab.OPERATIONS -> OperationsScreen(
                    Modifier.padding(padding), transactions,
                    onDelete = { item -> scope.launch { dao.deleteTransaction(item) } }
                )
                Tab.SUBSCRIPTIONS -> SubscriptionsScreen(
                    Modifier.padding(padding), subscriptions,
                    onDelete = { item -> scope.launch { dao.deleteSubscription(item) } }
                )
                Tab.ANALYTICS -> AnalyticsScreen(Modifier.padding(padding), income, expenses, transactions)
                Tab.SETTINGS -> SettingsScreen(Modifier.padding(padding), darkTheme) { darkTheme = it }
            }
        }

        if (showTransactionDialog) {
            AddTransactionDialog(
                onDismiss = { showTransactionDialog = false },
                onSave = { title, amount, type, category ->
                    scope.launch {
                        dao.addTransaction(
                            FinanceTransaction(
                                title = title,
                                amountMinor = (amount * 100).toLong(),
                                type = type,
                                category = category
                            )
                        )
                    }
                    showTransactionDialog = false
                }
            )
        }
        if (showSubscriptionDialog) {
            AddSubscriptionDialog(
                onDismiss = { showSubscriptionDialog = false },
                onSave = { title, amount, period ->
                    scope.launch {
                        dao.addSubscription(
                            Subscription(
                                title = title,
                                amountMinor = (amount * 100).toLong(),
                                period = period,
                                nextPaymentMillis = System.currentTimeMillis() + if (period == "WEEKLY") 7L * 86400000 else if (period == "YEARLY") 365L * 86400000 else 30L * 86400000
                            )
                        )
                    }
                    showSubscriptionDialog = false
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier, balance: Long, income: Long, expenses: Long,
    recent: List<FinanceTransaction>, subscriptions: List<Subscription>,
    onAdd: () -> Unit, onDelete: (FinanceTransaction) -> Unit
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
        items(recent, key = { it.id }) { TransactionRow(it, onDelete) }
        item { SectionHeader("Ближайшие подписки", "Все подписки") }
        if (subscriptions.isEmpty()) item { EmptyState("Подписок пока нет", "Добавь регулярные платежи, чтобы не забывать о них.") }
        items(subscriptions, key = { "sub-${it.id}" }) { SubscriptionRow(it, {}) }
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
private fun TransactionRow(item: FinanceTransaction, onDelete: (FinanceTransaction) -> Unit) {
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
        IconButton(onClick = { onDelete(item) }) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = Muted) }
    }
}

@Composable
private fun OperationsScreen(modifier: Modifier, items: List<FinanceTransaction>, onDelete: (FinanceTransaction) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Все операции", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        if (items.isEmpty()) item { EmptyState("История пока пуста", "Добавь доход или расход с помощью кнопки +.") }
        items(items, key = { it.id }) { TransactionRow(it, onDelete) }
    }
}

@Composable
private fun SubscriptionsScreen(modifier: Modifier, items: List<Subscription>, onDelete: (Subscription) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Мои подписки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Регулярные платежи и даты списаний", color = Muted, fontSize = 12.sp)
        }
        if (items.isEmpty()) item { EmptyState("Подписок пока нет", "Добавь сервисы и регулярные платежи.") }
        items(items, key = { it.id }) { SubscriptionRow(it) { onDelete(it) } }
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
private fun SubscriptionRow(item: Subscription, onDelete: () -> Unit) {
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
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = Muted) }
    }
}

@Composable
private fun AnalyticsScreen(modifier: Modifier, income: Long, expenses: Long, items: List<FinanceTransaction>) {
    val total = (income + expenses).coerceAtLeast(1)
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
        EmptyState("Следующий шаг — подробная аналитика", "Добавим диаграммы по категориям и выбор периода в следующем этапе.")
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Тёмная тема", fontWeight = FontWeight.SemiBold)
                Text("Можно переключиться на светлое оформление", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = darkTheme, onCheckedChange = onThemeChange)
        }
        UpdateSection()
        EmptyState("Данные хранятся на устройстве", "Регистрация и подключение к банку не требуются. Экспорт и резервное копирование добавим следующим этапом.")
        Text("DimLife Finance · версия 0.2.0", color = Muted, fontSize = 11.sp)
    }
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
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.2.0"
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
                if (asset.optString("name") == "DimLife.apk") {
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
private fun AddTransactionDialog(onDismiss: () -> Unit, onSave: (String, Double, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("EXPENSE") }
    var category by remember { mutableStateOf("Прочее") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая операция") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
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
                if (title.isNotBlank() && value != null && value > 0) onSave(title.trim(), value, type, category)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddSubscriptionDialog(onDismiss: () -> Unit, onSave: (String, Double, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("MONTHLY") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая подписка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название сервиса") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Стоимость, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
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
                if (title.isNotBlank() && value != null && value > 0) onSave(title.trim(), value, period)
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
