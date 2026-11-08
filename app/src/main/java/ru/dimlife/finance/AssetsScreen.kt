package ru.dimlife.finance

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.dimlife.finance.data.Deposit
import ru.dimlife.finance.data.InvestmentHolding
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

private val AssetGreen = Color(0xFF42D982)
private val AssetMuted = Color(0xFF9BAAA0)
private val AssetRed = Color(0xFFFF9A83)

@Composable
internal fun AssetsScreen(
    modifier: Modifier,
    cashBalance: Long,
    deposits: List<Deposit>,
    holdings: List<InvestmentHolding>,
    onAddDeposit: () -> Unit,
    onEditDeposit: (Deposit) -> Unit,
    onDeleteDeposit: (Deposit) -> Unit,
    onAddHolding: (String) -> Unit,
    onEditHolding: (InvestmentHolding) -> Unit,
    onDeleteHolding: (InvestmentHolding) -> Unit,
    onRefreshQuotes: suspend () -> String
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var quoteMessage by remember { mutableStateOf("Котировки обновляются по запросу пользователя.") }
    val depositValue = deposits.sumOf { depositCurrentValue(it) }
    val holdingValue = holdings.sumOf { holdingCurrentValue(it) }
    val investedValue = depositValue + holdingValue

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Активы", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Текущая картина", color = AssetMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Наличные", color = AssetMuted, fontSize = 11.sp)
                        Text(formatAssetMoney(cashBalance), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Депозиты и рынок", color = AssetMuted, fontSize = 11.sp)
                        Text(formatAssetMoney(investedValue), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AssetGreen)
                    }
                }
                Text("Наличные берутся из существующего баланса операций. Вложения учитываются отдельно.",
                    color = AssetMuted, fontSize = 10.sp)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Банковские депозиты", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onAddDeposit) { Icon(Icons.Outlined.Add, null); Text("Добавить") }
        }
        if (deposits.isEmpty()) {
            AssetEmpty("Депозитов пока нет", "Добавь вклад, процентную ставку и срок — приложение рассчитает ориентировочно начисленные проценты.")
        } else {
            deposits.forEach { deposit ->
                val interest = (depositCurrentValue(deposit) - deposit.principalMinor).coerceAtLeast(0L)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(deposit.title, fontWeight = FontWeight.SemiBold)
                                Text(deposit.bank, color = AssetMuted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { onEditDeposit(deposit) }) { Icon(Icons.Outlined.Edit, "Изменить депозит", tint = AssetGreen) }
                            IconButton(onClick = { onDeleteDeposit(deposit) }) { Icon(Icons.Outlined.DeleteOutline, "Удалить депозит", tint = AssetMuted) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Ставка ${String.format(Locale("ru","RU"), "%.2f", deposit.annualRateBps / 100.0)}% годовых", fontSize = 12.sp, color = AssetMuted)
                            Text("До ${assetDate(deposit.maturityAtMillis)}", fontSize = 11.sp, color = AssetMuted)
                        }
                        Text(formatAssetMoney(depositCurrentValue(deposit)), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AssetGreen)
                        Text("Вложено ${formatAssetMoney(deposit.principalMinor)} · начислено ~${formatAssetMoney(interest)}",
                            color = AssetMuted, fontSize = 11.sp)
                        Text(if (deposit.capitalization) "С капитализацией · оценка" else "Без капитализации · оценка",
                            color = AssetMuted, fontSize = 10.sp)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Акции и фонды", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onAddHolding("STOCK") }) { Icon(Icons.Outlined.Add, null); Text("Добавить") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAddHolding("STOCK") }, modifier = Modifier.weight(1f)) { Text("Акцию") }
            OutlinedButton(onClick = { onAddHolding("FUND") }, modifier = Modifier.weight(1f)) { Text("Фонд / ETF") }
        }
        OutlinedButton(onClick = {
            scope.launch {
                refreshing = true
                quoteMessage = try { onRefreshQuotes() } catch (error: Exception) {
                    error.message ?: "Не удалось обновить котировки."
                }
                refreshing = false
            }
        }, enabled = !refreshing && holdings.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text(if (refreshing) "Обновляем котировки…" else "Обновить котировки MOEX")
        }
        Text(quoteMessage, color = AssetMuted, fontSize = 10.sp)
        if (holdings.isEmpty()) {
            AssetEmpty("Портфель пока пуст", "Добавляй инструменты по тикеру Московской биржи. Рыночная цена будет загружаться отдельно от цены покупки.")
        } else {
            holdings.forEach { holding ->
                val value = holdingCurrentValue(holding)
                val cost = (holding.quantity * holding.averageBuyPriceMinor).roundToLong()
                val profit = value - cost
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(holding.title, fontWeight = FontWeight.SemiBold)
                                Text("${holding.ticker} · ${if (holding.kind == "FUND") "Фонд / ETF" else "Акция"} · ${holding.board}",
                                    color = AssetMuted, fontSize = 10.sp)
                            }
                            IconButton(onClick = { onEditHolding(holding) }) { Icon(Icons.Outlined.Edit, "Изменить", tint = AssetGreen) }
                            IconButton(onClick = { onDeleteHolding(holding) }) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = AssetMuted) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Количество: ${String.format(Locale.US, "%.4f", holding.quantity)}", fontSize = 11.sp, color = AssetMuted)
                            Text("Покупка: ${formatAssetMoney(holding.averageBuyPriceMinor)}", fontSize = 11.sp, color = AssetMuted)
                        }
                        Text(formatAssetMoney(value), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Результат: ${if (profit >= 0) "+" else "−"}${formatAssetMoney(kotlin.math.abs(profit))}",
                                color = if (profit >= 0) AssetGreen else AssetRed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("Цена: ${formatAssetMoney(holding.currentPriceMinor.takeIf { it > 0 } ?: holding.averageBuyPriceMinor)}",
                                fontSize = 11.sp, color = AssetMuted)
                        }
                        Text(if (holding.priceUpdatedAtMillis > 0) "Котировка: ${assetDateTime(holding.priceUpdatedAtMillis)}"
                            else "Котировка ещё не загружалась", color = AssetMuted, fontSize = 10.sp)
                    }
                }
            }
        }
        Text("Котировки MOEX предоставляются с задержкой (обычно около 15 минут). Это информационные данные, не заявка на покупку или продажу.",
            color = AssetMuted, fontSize = 10.sp)
        Text("Расчёт депозита предварительный: фактические проценты определяются договором банка, датами начисления, налогами и условиями досрочного закрытия.",
            color = AssetMuted, fontSize = 10.sp)
    }
}

@Composable
internal fun DepositEditorDialog(initial: Deposit?, onDismiss: () -> Unit, onSave: (Deposit) -> Unit) {
    val context = LocalContext.current
    var bank by remember(initial?.id) { mutableStateOf(initial?.bank ?: "") }
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var principal by remember(initial?.id) { mutableStateOf(initial?.principalMinor?.let { (it / 100.0).toString() } ?: "") }
    var rate by remember(initial?.id) { mutableStateOf(initial?.annualRateBps?.let { (it / 100.0).toString() } ?: "") }
    var openedAt by remember(initial?.id) { mutableLongStateOf(initial?.openedAtMillis ?: System.currentTimeMillis()) }
    var maturityAt by remember(initial?.id) { mutableLongStateOf(initial?.maturityAtMillis ?: (System.currentTimeMillis() + 365L * 86400000)) }
    var capitalization by remember(initial?.id) { mutableStateOf(initial?.capitalization ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый депозит" else "Изменить депозит") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(bank, { bank = it }, label = { Text("Банк") }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text("Название вклада") }, singleLine = true)
                OutlinedTextField(principal, { principal = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(rate, { rate = it }, label = { Text("Ставка годовых, %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedButton(onClick = { showAssetDatePicker(context, openedAt) { openedAt = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Дата открытия: ${assetDate(openedAt)}")
                }
                OutlinedButton(onClick = { showAssetDatePicker(context, maturityAt) { maturityAt = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Дата окончания: ${assetDate(maturityAt)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Капитализация процентов", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Ориентировочный ежедневный расчёт", color = AssetMuted, fontSize = 10.sp)
                    }
                    Switch(checked = capitalization, onCheckedChange = { capitalization = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = principal.replace(',', '.').toDoubleOrNull()
                val annual = rate.replace(',', '.').toDoubleOrNull()
                if (bank.isNotBlank() && title.isNotBlank() && amount != null && amount > 0 &&
                    annual != null && annual >= 0 && annual <= 100 && maturityAt > openedAt
                ) {
                    onSave(Deposit(
                        id = initial?.id ?: 0, bank = bank.trim(), title = title.trim(),
                        principalMinor = (amount * 100).roundToLong(),
                        annualRateBps = (annual * 100).roundToInt(),
                        openedAtMillis = openedAt, maturityAtMillis = maturityAt,
                        capitalization = capitalization
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
internal fun HoldingEditorDialog(
    initial: InvestmentHolding?, defaultKind: String, onDismiss: () -> Unit, onSave: (InvestmentHolding) -> Unit
) {
    var kind by remember(initial?.id) { mutableStateOf(initial?.kind ?: defaultKind) }
    var ticker by remember(initial?.id) { mutableStateOf(initial?.ticker ?: "") }
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var quantity by remember(initial?.id) { mutableStateOf(initial?.quantity?.toString() ?: "") }
    var averagePrice by remember(initial?.id) { mutableStateOf(initial?.averageBuyPriceMinor?.let { (it / 100.0).toString() } ?: "") }
    var board by remember(initial?.id) { mutableStateOf(initial?.board ?: if (defaultKind == "FUND") "TQTF" else "TQBR") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Добавить инструмент" else "Изменить инструмент") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == "STOCK", onClick = { kind = "STOCK"; board = "TQBR" }, label = { Text("Акция") })
                    FilterChip(selected = kind == "FUND", onClick = { kind = "FUND"; board = "TQTF" }, label = { Text("Фонд / ETF") })
                }
                OutlinedTextField(ticker, { ticker = it.uppercase(Locale.ROOT).filter { ch -> ch.isLetterOrDigit() } },
                    label = { Text("Тикер MOEX") }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text("Название инструмента") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Количество бумаг") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(averagePrice, { averagePrice = it }, label = { Text("Средняя цена покупки, ₽") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = board == "TQBR", onClick = { board = "TQBR" }, label = { Text("TQBR") })
                    FilterChip(selected = board == "TQTF", onClick = { board = "TQTF" }, label = { Text("TQTF") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val qty = quantity.replace(',', '.').toDoubleOrNull()
                val price = averagePrice.replace(',', '.').toDoubleOrNull()
                if (ticker.isNotBlank() && title.isNotBlank() && qty != null && qty > 0 && price != null && price > 0) {
                    onSave(InvestmentHolding(
                        id = initial?.id ?: 0, ticker = ticker.trim(), title = title.trim(), kind = kind,
                        quantity = qty, averageBuyPriceMinor = (price * 100).roundToLong(),
                        currentPriceMinor = initial?.currentPriceMinor ?: 0, currency = "RUB", board = board,
                        priceUpdatedAtMillis = initial?.priceUpdatedAtMillis ?: 0
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun depositCurrentValue(deposit: Deposit): Long {
    val end = minOf(System.currentTimeMillis(), deposit.maturityAtMillis)
    val days = (end - deposit.openedAtMillis).coerceAtLeast(0L) / 86400000.0
    val rate = deposit.annualRateBps / 10000.0
    val principal = deposit.principalMinor.toDouble()
    val interest = if (deposit.capitalization) principal * ((1.0 + rate / 365.0).pow(days) - 1.0)
        else principal * rate * days / 365.0
    return (principal + interest.coerceAtLeast(0.0)).roundToLong()
}

private fun holdingCurrentValue(holding: InvestmentHolding): Long {
    val price = holding.currentPriceMinor.takeIf { it > 0 } ?: holding.averageBuyPriceMinor
    return (holding.quantity * price).roundToLong()
}

private fun formatAssetMoney(minor: Long): String =
    String.format(Locale("ru", "RU"), "%,.2f ₽", minor / 100.0).replace(',', ' ').replace(".00 ₽", " ₽")

private fun assetDate(millis: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU")).format(Date(millis))
private fun assetDateTime(millis: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(millis))

private fun showAssetDatePicker(context: Context, millis: Long, onSelected: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    DatePickerDialog(context, { _, year, month, day ->
        onSelected(Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
}

private data class MoexQuote(val priceMinor: Long, val board: String)

internal suspend fun refreshMoexQuotes(
    holdings: List<InvestmentHolding>,
    update: suspend (InvestmentHolding) -> Unit
): String = withContext(Dispatchers.IO) {
    if (holdings.isEmpty()) return@withContext "В портфеле нет инструментов для обновления."
    var updated = 0
    var failed = 0
    holdings.forEach { holding ->
        try {
            val quote = fetchMoexQuote(holding.ticker, holding.board)
            update(holding.copy(currentPriceMinor = quote.priceMinor, board = quote.board,
                priceUpdatedAtMillis = System.currentTimeMillis()))
            updated++
        } catch (_: Exception) { failed++ }
    }
    "Обновлено: $updated · без котировки: $failed. Данные MOEX могут быть задержаны."
}

private fun fetchMoexQuote(ticker: String, preferredBoard: String): MoexQuote {
    val safeTicker = ticker.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    require(safeTicker.isNotBlank()) { "Не указан тикер." }
    val url = URL("https://iss.moex.com/iss/engines/stock/markets/shares/securities/$safeTicker.json?iss.only=marketdata&iss.meta=off")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 12000
        readTimeout = 12000
        setRequestProperty("User-Agent", "DimLife-Finance/0.9")
    }
    try {
        if (connection.responseCode !in 200..299) throw IllegalStateException("MOEX вернула HTTP ${connection.responseCode}")
        val json = connection.inputStream.bufferedReader().use { it.readText() }
        val marketData = JSONObject(json).optJSONObject("marketdata")
            ?: throw IllegalStateException("Биржа не вернула рыночные данные.")
        val columns = marketData.getJSONArray("columns")
        val rows = marketData.getJSONArray("data")
        fun column(name: String): Int = (0 until columns.length()).firstOrNull { columns.optString(it) == name } ?: -1
        val lastIndex = column("LAST")
        val boardIndex = column("BOARDID")
        if (lastIndex < 0 || boardIndex < 0) throw IllegalStateException("В ответе MOEX нет цены или торгового режима.")
        val candidates = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONArray(index)
            if (row.isNull(lastIndex)) null else {
                val price = row.optDouble(lastIndex, 0.0)
                val board = row.optString(boardIndex)
                if (price > 0 && board.isNotBlank()) MoexQuote((price * 100).roundToLong(), board) else null
            }
        }
        candidates.firstOrNull { it.board == preferredBoard }?.let { return it }
        return candidates.firstOrNull() ?: throw IllegalStateException("Для тикера $safeTicker сейчас нет доступной цены.")
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun AssetEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(15.dp)).padding(16.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(description, color = AssetMuted, fontSize = 11.sp)
    }
}
