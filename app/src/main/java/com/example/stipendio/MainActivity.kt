package com.example.stipendio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

fun currentCycle(day: Int): YearMonth {
    val t = LocalDate.now()
    val ym = YearMonth.from(t)
    return if (t.dayOfMonth >= day) ym else ym.minusMonths(1)
}

fun cycleLabel(m: YearMonth, day: Int): String {
    val f = DateTimeFormatter.ofPattern("d MMM", Locale.ITALY)
    val s = m.atDay(day)
    val e = m.plusMonths(1).atDay(day).minusDays(1)
    return "${f.format(s)} – ${f.format(e)} ${e.year}"
}

data class Item(val name: String, val amount: Double, val date: String = LocalDate.now().toString(), val category: String = "Altro")
fun cycleEnd(m: YearMonth, day: Int): LocalDate =
    m.plusMonths(1).atDay(day.coerceIn(1, 28)).minusDays(1)

fun daysRemainingInCycle(m: YearMonth, day: Int): Long {
    val today = LocalDate.now()
    val start = m.atDay(day.coerceIn(1, 28))
    val end = cycleEnd(m, day)
    return when {
        today < start -> java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        today > end -> 0
        else -> java.time.temporal.ChronoUnit.DAYS.between(today, end) + 1
    }
}

fun cycleElapsedDays(m: YearMonth, day: Int): Long {
    val today = LocalDate.now()
    val start = m.atDay(day.coerceIn(1, 28))
    val end = cycleEnd(m, day)
    return if (today <= start) 1 else java.time.temporal.ChronoUnit.DAYS.between(start, minOf(today, end)) + 1
}

data class Fixed(val id: String, val name: String, val amount: Double, val from: String, val to: String?, val category: String = "Altro") {
    fun activeIn(m: String) = from <= m && (to == null || m <= to)
}

fun effectiveSalary(hist: Map<String, Double>, m: String): Double =
    hist.filterKeys { it <= m }.maxByOrNull { it.key }?.value ?: 0.0

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("budget", Context.MODE_PRIVATE)

    fun expenses(m: String): List<Item> {
        val a = JSONArray(p.getString("exp_$m", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("n"), o.getDouble("a"), o.optString("d", LocalDate.now().toString()), o.optString("c", "Altro")) }
    }
    fun saveExpenses(m: String, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("a", it.amount).put("d", it.date).put("c", it.category)) }
        p.edit().putString("exp_$m", a.toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun incomes(m: String): List<Item> {
        val a = JSONArray(p.getString("inc_$m", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("n"), o.getDouble("a"), o.optString("d", LocalDate.now().toString()), o.optString("c", "Altro")) }
    }
    fun saveIncomes(m: String, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("a", it.amount).put("d", it.date).put("c", it.category)) }
        p.edit().putString("inc_$m", a.toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun fixed(): List<Fixed> {
        val a = JSONArray(p.getString("fixed2", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Fixed(o.getString("id"), o.getString("n"), o.getDouble("a"), o.getString("f"), o.optString("t", "").ifEmpty { null }, o.optString("c", "Altro"))
        }
    }
    fun saveFixed(l: List<Fixed>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("a", it.amount).put("f", it.from).put("t", it.to ?: "").put("c", it.category)) }
        p.edit().putString("fixed2", a.toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun salaryHist(): Map<String, Double> {
        val o = JSONObject(p.getString("salary2", "{}"))
        return o.keys().asSequence().associateWith { o.getDouble(it) }
    }
    fun saveSalaryHist(h: Map<String, Double>) {
        p.edit().putString("salary2", JSONObject(h).toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun context(): Context = ctx
    fun startDay(): Int = p.getInt("start_day", 22)
    fun saveStartDay(d: Int) {
        p.edit().putInt("start_day", d).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun actual(): Map<String, Double> {
        val o = JSONObject(p.getString("actual", "{}"))
        return o.keys().asSequence().associateWith { o.getDouble(it) }
    }
    fun saveActual(h: Map<String, Double>) {
        p.edit().putString("actual", JSONObject(h).toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun remaining(m: String) = (actual()[m] ?: effectiveSalary(salaryHist(), m)) +
        incomes(m).sumOf { it.amount } -
        fixed().filter { it.activeIn(m) }.sumOf { it.amount } - expenses(m).sumOf { it.amount }

    fun exportBackup(): String {
        val root = JSONObject()
        root.put("app", "MyBudget+")
        root.put("formatVersion", 1)
        root.put("createdAt", System.currentTimeMillis())
        val data = JSONObject()
        for ((key, value) in p.all) {
            when (value) {
                is String -> data.put(key, value)
                is Int -> data.put(key, value)
                is Long -> data.put(key, value)
                is Boolean -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                is Double -> data.put(key, value)
                is Set<*> -> data.put(key, JSONArray(value.toList()))
            }
        }
        root.put("preferences", data)
        return root.toString(2)
    }

    fun importBackup(json: String) {
        val root = JSONObject(json)
        require(root.optString("app") == "Stipendio" || root.optString("app") == "MyBudget+") { "File non riconosciuto" }
        require(root.optInt("formatVersion", 0) == 1) { "Versione backup non supportata" }
        val data = root.getJSONObject("preferences")
        val editor = p.edit().clear()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.get(key)
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until value.length()) set.add(value.getString(i))
                    editor.putStringSet(key, set)
                }
                else -> editor.putString(key, value.toString())
            }
        }
        check(editor.commit()) { "Impossibile salvare il backup" }
        QuickAddWidget.refresh(ctx)
    }
}

private const val BUDGET_CHANNEL = "budget_alerts"
private const val ALERT_PREFS = "budget"
private val ALERT_THRESHOLDS = listOf(80, 90, 95)

fun budgetAlertThresholds(ctx: Context): Set<Int> =
    ctx.getSharedPreferences(ALERT_PREFS, Context.MODE_PRIVATE)
        .getStringSet("alert_thresholds", setOf("80", "90", "95"))
        ?.mapNotNull { it.toIntOrNull() }
        ?.toSet() ?: setOf(80, 90, 95)

fun setBudgetAlertThreshold(ctx: Context, threshold: Int, enabled: Boolean) {
    val p = ctx.getSharedPreferences(ALERT_PREFS, Context.MODE_PRIVATE)
    val current = budgetAlertThresholds(ctx).toMutableSet()
    if (enabled) current.add(threshold) else current.remove(threshold)
    p.edit().putStringSet("alert_thresholds", current.map { it.toString() }.toSet()).apply()
}

fun notifyBudgetAlert(ctx: Context, usedPercent: Int, remaining: Double, threshold: Int = usedPercent) {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        nm.createNotificationChannel(NotificationChannel(BUDGET_CHANNEL, "Avvisi MyBudget+", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val text = "Hai utilizzato circa $usedPercent% del budget. Disponibilità: " + eur.format(remaining)
    val n = NotificationCompat.Builder(ctx, BUDGET_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("MyBudget+ • Attenzione")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()
    nm.notify((threshold * 100000 + (System.currentTimeMillis() and 0x7fff)).toInt(), n)
}

fun checkBudgetAlerts(ctx: Context, cycle: String, previousUsedPercent: Int, currentUsedPercent: Int, remaining: Double) {
    if (currentUsedPercent <= previousUsedPercent) return
    val enabled = budgetAlertThresholds(ctx)
    val p = ctx.getSharedPreferences(ALERT_PREFS, Context.MODE_PRIVATE)
    ALERT_THRESHOLDS.filter { it in enabled && previousUsedPercent < it && currentUsedPercent >= it }
        .forEach { threshold ->
            val key = "alert_sent_" + cycle + "_" + threshold
            if (!p.getBoolean(key, false)) {
                notifyBudgetAlert(ctx, threshold, remaining, threshold)
                p.edit().putBoolean(key, true).apply()
            }
        }
}

class MainActivity : ComponentActivity() {
    private var quick by mutableStateOf(false)
    private lateinit var store: Store
    private var backupText by mutableStateOf<String?>(null)

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(store.exportBackup().toByteArray(Charsets.UTF_8)) }
                ?: error("Impossibile creare il file")
            backupText = "Backup esportato correttamente."
        }.onFailure { backupText = "Errore: ${it.message}" }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Impossibile leggere il file")
            store.importBackup(json)
            backupText = "Backup importato. I dati precedenti sono stati sostituiti con quelli del backup."
        }.onFailure { backupText = "Importazione non riuscita: ${it.message}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        quick = intent.getBooleanExtra("quick_add", false)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF2563EB),
                    secondary = Color(0xFF60A5FA),
                    background = Color(0xFFEAF2FF),
                    surface = Color.White
                )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFEAF2FF)) {
                    App(
                        store, quick,
                        onQuickConsumed = { quick = false; intent.removeExtra("quick_add") },
                        onExportBackup = { createBackup.launch("Stipendio_backup_${java.time.LocalDate.now()}.json") },
                        onImportBackup = { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        backupText = backupText,
                        onDismissBackupMessage = { backupText = null }
                    )
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        QuickAddWidget.refresh(applicationContext)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        quick = intent.getBooleanExtra("quick_add", false)
    }
}

private val eur: NumberFormat = NumberFormat.getCurrencyInstance(Locale.ITALY)

@Composable
fun App(
    store: Store,
    quickAdd: Boolean,
    onQuickConsumed: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    backupText: String?,
    onDismissBackupMessage: () -> Unit
) {
    val salaryHist = remember { mutableStateMapOf<String, Double>().apply { putAll(store.salaryHist()) } }
    val actualHist = remember { mutableStateMapOf<String, Double>().apply { putAll(store.actual()) } }
    val fixedAll = remember { mutableStateListOf<Fixed>().apply { addAll(store.fixed()) } }
    var startDay by remember { mutableStateOf(store.startDay()) }
    var month by remember { mutableStateOf(currentCycle(startDay)) }
    val ms = month.toString()
    val prev = month.minusMonths(1).toString()
    val expenses = remember(month) { mutableStateListOf<Item>().apply { addAll(store.expenses(ms)) } }
    val incomes = remember(month) { mutableStateListOf<Item>().apply { addAll(store.incomes(ms)) } }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Fixed?>(null) }
    var editingExpense by remember { mutableStateOf<Int?>(null) }
    var editingIncome by remember { mutableStateOf<Int?>(null) }
    var selectedNav by remember { mutableIntStateOf(0) }
    val appContext = store.context()

    LaunchedEffect(quickAdd) {
        if (quickAdd) { month = currentCycle(startDay); dialog = "exp"; onQuickConsumed() }
    }

    val planned = effectiveSalary(salaryHist, ms)
    val actual = actualHist[ms]
    val salary = actual ?: planned
    val activeFixed = fixedAll.filter { it.activeIn(ms) }
    val totalFixed = activeFixed.sumOf { it.amount }
    val totalVar = expenses.sumOf { it.amount }
    val totalIncome = incomes.sumOf { it.amount }
    val left = salary + totalIncome - totalFixed - totalVar
    val daysLeft = daysRemainingInCycle(month, startDay)
    val elapsedDays = cycleElapsedDays(month, startDay)
    val dailyBudget = if (daysLeft > 0) (left / daysLeft).coerceAtLeast(0.0) else 0.0
    val avgDailyVariable = if (elapsedDays > 0) totalVar / elapsedDays else 0.0
    val forecast = left - avgDailyVariable * daysLeft

    fun addFixed(n: String, a: Double, category: String) {
        val budgetBaseNow = salary + totalIncome
        val beforeUsedPct = if (budgetBaseNow > 0) (((totalFixed + totalVar) / budgetBaseNow) * 100).toInt() else 0
        fixedAll.add(Fixed(UUID.randomUUID().toString(), n, a, ms, null, category)); store.saveFixed(fixedAll)
        val afterUsedPct = if (budgetBaseNow > 0) ((((totalFixed + a) + totalVar) / budgetBaseNow) * 100).toInt() else 0
        checkBudgetAlerts(store.context(), ms, beforeUsedPct, afterUsedPct, budgetBaseNow - totalFixed - a - totalVar)
    }
    fun deleteFixed(f: Fixed) {
        val i = fixedAll.indexOf(f)
        if (f.from >= ms) fixedAll.removeAt(i) else fixedAll[i] = f.copy(to = prev)
        store.saveFixed(fixedAll)
    }
    fun editFixed(f: Fixed, a: Double) {
        val i = fixedAll.indexOf(f)
        if (f.from == ms) fixedAll[i] = f.copy(amount = a)
        else {
            fixedAll[i] = f.copy(to = prev)
            fixedAll.add(Fixed(UUID.randomUUID().toString(), f.name, a, ms, f.to))
        }
        store.saveFixed(fixedAll)
    }

    Scaffold(
        containerColor = Color(0xFFEAF2FF),
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = selectedNav == 0, onClick = { selectedNav = 0 }, icon = { Text("⌂", fontSize = 20.sp) }, label = { Text("Home") })
                NavigationBarItem(selected = selectedNav == 1, onClick = { selectedNav = 1; dialog = "transactions" }, icon = { Text("≡", fontSize = 20.sp) }, label = { Text("Transazioni") })
                NavigationBarItem(selected = selectedNav == 2, onClick = { selectedNav = 2; dialog = "analysis" }, icon = { Text("▥", fontSize = 20.sp) }, label = { Text("Analisi") })
                NavigationBarItem(selected = selectedNav == 3, onClick = { selectedNav = 3; dialog = "backup" }, icon = { Text("⚙", fontSize = 19.sp) }, label = { Text("Altro") })
            }
        }
    ) { contentPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(contentPadding).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 3.dp, border = BorderStroke(1.dp, Color(0xFFD7E3F2))) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painter = painterResource(R.drawable.ic_mybudget_wallet), contentDescription = "Logo MyBudget+", modifier = Modifier.size(44.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("MyBudget+", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0D4FA8), maxLines = 1)
                            Text("Il tuo budget, sempre sotto controllo", fontSize = 10.sp, color = Color(0xFF64748B), maxLines = 1)
                            Text("${month.plusMonths(1).month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALY).replaceFirstChar { it.uppercase() }} ${month.plusMonths(1).year}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E5FBF))
                            Text(cycleLabel(month, startDay), fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF4F7FC), border = BorderStroke(1.dp, Color(0xFFE0E8F3))) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { month = month.minusMonths(1) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) { Text("‹", fontSize = 21.sp) }
                                TextButton(onClick = { month = month.plusMonths(1) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) { Text("›", fontSize = 21.sp) }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF4F7FC), border = BorderStroke(1.dp, Color(0xFFE0E8F3))) {
                            TextButton(onClick = { dialog = "settings" }, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)) { Text("⚙", fontSize = 17.sp) }
                        }
                    }
                }
            }
        }
        item {
            val budgetBase = salary + totalIncome
            val remainingRatio = if (budgetBase > 0) (left / budgetBase).coerceIn(0.0, 1.0) else 0.0
            val remainingPct = (remainingRatio * 100).toInt()
            val usedRatio = 1.0 - remainingRatio
            val usedPct = (usedRatio * 100).toInt()
            val daysInCycle = java.time.temporal.ChronoUnit.DAYS.between(
                month.atDay(startDay.coerceIn(1, 28)),
                cycleEnd(month, startDay)
            ) + 1
            val daysColor = remainingBudgetColor(
                ((daysLeft.toDouble() / daysInCycle.coerceAtLeast(1).toDouble()) * 100).toInt()
            )
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0B5FC7),
                shadowElevation = 7.dp,
                border = BorderStroke(1.dp, Color(0xFF3D8BE8))
            ) {
                Column(Modifier.padding(17.dp)) {
                    Text("IL TUO BUDGET", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.82f))
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Disponibilità", fontSize = 12.sp, color = Color.White.copy(alpha = 0.82f))
                            Text(
                                eur.format(left),
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = remainingBudgetColor(remainingPct)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "$usedPct% del budget utilizzato",
                                fontSize = 10.sp,
                                fontWeight = if (usedPct >= 80) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (usedPct >= 80) Color(0xFFFFE08A) else Color.White.copy(alpha = 0.76f)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        BudgetRemainingRing(remainingPct = remainingPct, modifier = Modifier.size(92.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.20f))
                    Spacer(Modifier.height(12.dp))
                    Text("IL TUO RITMO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.82f))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1.25f)) {
                            Text("Puoi spendere al giorno", fontSize = 11.sp, color = Color.White.copy(alpha = 0.78f))
                            Text(eur.format(dailyBudget), fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                        Column(Modifier.weight(0.8f)) {
                            Text("Giorni", fontSize = 10.sp, color = Color.White.copy(alpha = 0.78f))
                            Text("$daysLeft", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = daysColor)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Previsione", fontSize = 10.sp, color = Color.White.copy(alpha = 0.78f))
                            Text(eur.format(forecast), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (forecast >= 0) Color(0xFF4ADE80) else Color(0xFFFF6B6B), maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (forecast >= 0) "Se mantieni questo ritmo, arriverai a fine periodo con " + eur.format(forecast) + "."
                        else "Attenzione: con il ritmo attuale rischi di esaurire il budget.",
                        fontSize = 10.sp,
                        color = if (forecast >= 0) Color.White.copy(alpha = 0.82f) else Color(0xFFFFD7D7)
                    )
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 2.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
                    Text("RIEPILOGO DEL MESE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Spacer(Modifier.height(5.dp))
                    SummaryLine("Stipendio effettivo", eur.format(salary), Color(0xFF16A34A))
                    if (actual != null) {
                        val d = actual - planned
                        SummaryLine("Differenza dal previsto", (if (d >= 0) "+" else "−") + eur.format(kotlin.math.abs(d)), if (d >= 0) Color(0xFF16A34A) else Color(0xFFDC2626))
                    }
                    SummaryLine("Introiti extra", "+" + eur.format(totalIncome), Color(0xFF16A34A))
                    SummaryLine("Spese fisse", "−" + eur.format(totalFixed), Color(0xFFEF476F))
                    SummaryLine("Spese variabili", "−" + eur.format(totalVar), Color(0xFFEF476F))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { dialog = "salary" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0D5FC7)),
                    border = BorderStroke(1.dp, Color(0xFFD0DDF0)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) { Text("Stipendio previsto", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { dialog = "actual" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0D5FC7)),
                    border = BorderStroke(1.dp, Color(0xFFD0DDF0)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) { Text("Stipendio arrivato", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        item {
            Button(
                onClick = { dialog = "backup" },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0D5FC7)),
                border = BorderStroke(1.dp, Color(0xFFD0DDF0)),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Text("Backup e ripristino", fontWeight = FontWeight.SemiBold)
            }
        }
        item { Header("Introiti extra") { dialog = "income" } }
        itemsIndexed(incomes) { i, it ->
            Row2(it, Color(0xFF4CAF50)) { editingIncome = i }
        }
        item { Header("Spese del mese") { dialog = "exp" } }
        itemsIndexed(expenses) { i, it ->
            Row2(it) { editingExpense = i }
        }
        item { Header("Spese fisse") { dialog = "fixed" } }
        items(activeFixed) { f -> Row2(Item(f.name, f.amount, category = f.category)) { editing = f } }
        item { Text("Tocca una voce per modificarne nome/importo. Le spese fisse mantengono lo storico.", fontSize = 12.sp, color = Color(0xFF64748B)) }
        }
    }

    when (dialog) {
        "settings" -> BudgetSettingsDialog(
            startDay = startDay,
            onStartDayChange = { d ->
                startDay = d.coerceIn(1, 28)
                store.saveStartDay(startDay)
                month = currentCycle(startDay)
            },
            context = appContext,
            onDismiss = { dialog = null }
        )
        "actual" -> InputDialog("Stipendio effettivo di questo mese", false, { dialog = null }) { _, a, _, _ ->
            actualHist[ms] = a; store.saveActual(actualHist); dialog = null
        }
        "salary" -> InputDialog("Stipendio previsto (minimo) da questo mese", false, { dialog = null }) { _, a, _, _ ->
            salaryHist[ms] = a; store.saveSalaryHist(salaryHist); dialog = null
        }
        "fixed" -> InputDialog("Nuova spesa fissa da questo mese", true, { dialog = null }) { n, a, _, category ->
            addFixed(n, a, category); dialog = null
        }
        "exp" -> InputDialog("Nuova spesa", true, { dialog = null }) { n, a, date, category ->
            val budgetBaseNow = salary + totalIncome
            val beforeUsedPct = if (budgetBaseNow > 0) (((totalFixed + expenses.sumOf { it.amount }) / budgetBaseNow) * 100).toInt() else 0
            expenses.add(0, Item(n, a, date, category)); store.saveExpenses(ms, expenses)
            val usedPctNow = if (budgetBaseNow > 0) (((totalFixed + expenses.sumOf { it.amount }) / budgetBaseNow) * 100).toInt() else 0
            checkBudgetAlerts(store.context(), ms, beforeUsedPct, usedPctNow, budgetBaseNow - totalFixed - expenses.sumOf { it.amount })
            dialog = null
        }
        "income" -> InputDialog("Nuovo introito", true, { dialog = null }, nameLabel = "Descrizione") { n, a, date, category ->
            incomes.add(0, Item(n, a, date, category)); store.saveIncomes(ms, incomes); dialog = null
        }
        "backup" -> BackupDialog(
            onExport = { onExportBackup(); dialog = null },
            onImport = { onImportBackup(); dialog = null },
            onDismiss = { dialog = null }
        )
        "transactions" -> TransactionsDialog(
            incomes = incomes,
            expenses = expenses,
            onAddExpense = { dialog = "exp" },
            onAddIncome = { dialog = "income" },
            onDismiss = { dialog = null }
        )
        "analysis" -> AnalysisDialog(
            salary = salary,
            income = totalIncome,
            fixed = totalFixed,
            variable = totalVar,
            remaining = left,
            expenses = expenses,
            onDismiss = { dialog = null }
        )
    }

    editing?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        var amount by remember(f) { mutableStateOf(f.amount.toString()) }
        var category by remember(f) { mutableStateOf(f.category) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifica spesa fissa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Il nome e l'importo modificati valgono da questo mese in poi; lo storico dei mesi passati resta invariato.", fontSize = 12.sp)
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it }, label = { Text("Importo €") }, singleLine = true)
                    Text("Categoria", fontSize = 12.sp, color = Color(0xFF64748B))
                    CategoryGrid(category) { category = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = amount.replace(',', '.').toDoubleOrNull()
                    if (a != null && name.isNotBlank()) {
                        val i = fixedAll.indexOf(f)
                        if (f.from == ms) fixedAll[i] = f.copy(name = name.trim(), amount = a, category = category)
                        else {
                            fixedAll[i] = f.copy(to = prev)
                            fixedAll.add(Fixed(UUID.randomUUID().toString(), name.trim(), a, ms, f.to, category))
                        }
                        store.saveFixed(fixedAll)
                        editing = null
                    }
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFixed(f); editing = null }) { Text("Elimina da qui") }
            }
        )
    }

    editingExpense?.let { index ->
        if (index in expenses.indices) {
            ItemEditDialog(
                title = "Modifica spesa",
                item = expenses[index],
                deleteText = "Elimina",
                onDismiss = { editingExpense = null },
                onSave = { item ->
                    val budgetBaseNow = salary + totalIncome
                    val beforeUsedPct = if (budgetBaseNow > 0) (((totalFixed + expenses.sumOf { it.amount }) / budgetBaseNow) * 100).toInt() else 0
                    expenses[index] = item
                    store.saveExpenses(ms, expenses)
                    val afterUsedPct = if (budgetBaseNow > 0) (((totalFixed + expenses.sumOf { it.amount }) / budgetBaseNow) * 100).toInt() else 0
                    checkBudgetAlerts(store.context(), ms, beforeUsedPct, afterUsedPct, budgetBaseNow - totalFixed - expenses.sumOf { it.amount })
                    editingExpense = null
                },
                onDelete = {
                    expenses.removeAt(index)
                    store.saveExpenses(ms, expenses)
                    editingExpense = null
                }
            )
        }
    }

    editingIncome?.let { index ->
        if (index in incomes.indices) {
            ItemEditDialog(
                title = "Modifica introito",
                item = incomes[index],
                deleteText = "Elimina",
                onDismiss = { editingIncome = null },
                onSave = { item ->
                    incomes[index] = item
                    store.saveIncomes(ms, incomes)
                    editingIncome = null
                },
                onDelete = {
                    incomes.removeAt(index)
                    store.saveIncomes(ms, incomes)
                    editingIncome = null
                }
            )
        }
    }

    if (backupText != null) {
        AlertDialog(
            onDismissRequest = onDismissBackupMessage,
            title = { Text("Backup") },
            text = { Text(backupText) },
            confirmButton = { TextButton(onClick = onDismissBackupMessage) { Text("OK") } }
        )
    }
}

@Composable
fun BudgetSettingsDialog(
    startDay: Int,
    onStartDayChange: (Int) -> Unit,
    context: Context,
    onDismiss: () -> Unit
) {
    var day by remember(startDay) { mutableStateOf(startDay.toString()) }
    var enabled by remember { mutableStateOf(budgetAlertThresholds(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impostazioni budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ciclo stipendio", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Giorno di inizio ciclo (1-28)") }, singleLine = true)
                HorizontalDivider()
                Text("Notifiche consumo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                Text("Ricevi un avviso quando raggiungi la percentuale scelta del budget.", fontSize = 11.sp, color = Color(0xFF64748B))
                ALERT_THRESHOLDS.forEach { threshold ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val next = enabled.toMutableSet().also { if (threshold in it) it.remove(threshold) else it.add(threshold) }
                        setBudgetAlertThreshold(context, threshold, threshold in next)
                        enabled = next
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = threshold in enabled, onCheckedChange = { checked ->
                            setBudgetAlertThreshold(context, threshold, checked)
                            enabled = enabled.toMutableSet().also { if (checked) it.add(threshold) else it.remove(threshold) }
                        })
                        Text("$threshold% del budget utilizzato", fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onStartDayChange(day.toIntOrNull()?.coerceIn(1, 28) ?: startDay)
                onDismiss()
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun BackupDialog(onExport: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup e ripristino") },
        text = {
            Text("Il backup contiene tutti i dati dell'app. Esporta periodicamente il file sul telefono o in un cloud. L'importazione sostituisce i dati attuali con quelli presenti nel backup.")
        },
        confirmButton = {
            TextButton(onClick = onExport) { Text("Esporta backup") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onImport) { Text("Importa backup") }
                TextButton(onClick = onDismiss) { Text("Chiudi") }
            }
        }
    )
}

@Composable
fun Header(title: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
        Button(onClick = onAdd, shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("+ Aggiungi", fontWeight = FontWeight.SemiBold) }
    }
}

fun categoryIcon(category: String): String = when (category) {
    "Alimentari" -> "🛒"
    "Casa" -> "🏠"
    "Auto" -> "🚗"
    "Bollette" -> "💡"
    "Abbonamenti" -> "🔄"
    "Svago" -> "🎮"
    "Salute" -> "❤️"
    "Shopping" -> "🛍️"
    "Banca" -> "🏦"
    else -> "•••"
}

@Composable
fun Row2(item: Item, amountColor: Color = Color.Unspecified, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (amountColor == Color.Unspecified) Color(0xFFF1F5F9) else Color(0xFFEAFBF2)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(categoryIcon(item.category), fontSize = 19.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDisplayDate(item.date), fontSize = 10.sp, color = Color(0xFF64748B))
                    Text("  •  " + item.category.ifBlank { "Altro" }, fontSize = 10.sp, color = Color(0xFF94A3B8), maxLines = 1)
                }
            }
            Text((if (amountColor == Color.Unspecified) "−" else "+") + eur.format(item.amount), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (amountColor == Color.Unspecified) Color(0xFF111827) else amountColor)
        }
    }
}
fun remainingBudgetColor(pct: Int): Color {
    val p = pct.coerceIn(0, 100)
    return if (p <= 50) {
        val t = p / 50f
        Color(
            red = (0x16 + (0x22 - 0x16) * t).toInt(),
            green = (0x83 + (0xC5 - 0x83) * t).toInt(),
            blue = (0xE8 + (0x5E - 0xE8) * t).toInt()
        )
    } else {
        val t = (p - 50) / 50f
        Color(
            red = (0x22 + (0xEF - 0x22) * t).toInt(),
            green = (0xC5 + (0x44 - 0xC5) * t).toInt(),
            blue = (0x5E + (0x44 - 0x5E) * t).toInt()
        )
    }
}

@Composable
fun BudgetRemainingRing(remainingPct: Int, modifier: Modifier = Modifier) {
    val pct = remainingPct.coerceIn(0, 100)
    val sweep = 360f * pct / 100f
    val ringColor = remainingBudgetColor(pct)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$pct%", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("rimanenza", fontSize = 8.sp, color = Color.White.copy(alpha = 0.82f))
        }
    }
}

@Composable
fun MiniKpi(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color(0xFFF6F9FD), border = BorderStroke(1.dp, Color(0xFFE1E9F4))) {
        Column(Modifier.padding(9.dp)) {
            Text(label, fontSize = 9.sp, color = Color(0xFF64748B), maxLines = 1)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1)
        }
    }
}

@Composable
fun SummaryLine(label: String, value: String, valueColor: Color) {
    val dotColor = when {
        label.contains("Stipendio") -> Color(0xFF14B8A6)
        label.contains("Introiti") -> Color(0xFF22C55E)
        label.contains("Differenza") -> Color(0xFF3B82F6)
        else -> Color(0xFFEF476F)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(28.dp), shape = RoundedCornerShape(50), color = dotColor.copy(alpha = 0.14f)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    when {
                        label.contains("Stipendio") -> "€"
                        label.contains("Introiti") -> "+"
                        label.contains("Differenza") -> "↕"
                        else -> "−"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = dotColor
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF475569))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}


data class CategoryOption(val name: String, val icon: String)

@Composable
fun CategoryGrid(selected: String, onSelected: (String) -> Unit) {
    val categories = listOf(
        CategoryOption("Alimentari", "🛒"),
        CategoryOption("Casa", "🏠"),
        CategoryOption("Auto", "🚗"),
        CategoryOption("Bollette", "💡"),
        CategoryOption("Abbonamenti", "🔄"),
        CategoryOption("Svago", "🎮"),
        CategoryOption("Salute", "❤️"),
        CategoryOption("Shopping", "🛍️"),
        CategoryOption("Banca", "🏦"),
        CategoryOption("Altro", "•••")
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        categories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { c ->
                    val active = selected == c.name
                    Surface(
                        modifier = Modifier.weight(1f).height(48.dp).clickable { onSelected(c.name) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) Color(0xFFE6F0FF) else Color(0xFFF6F8FC),
                        border = BorderStroke(1.dp, if (active) Color(0xFF2563EB) else Color(0xFFE2E8F0))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(c.icon, fontSize = 16.sp, lineHeight = 16.sp)
                            Text(c.name, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) Color(0xFF1557B0) else Color(0xFF475569), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ItemEditDialog(
    title: String,
    item: Item,
    deleteText: String,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(item) { mutableStateOf(item.name) }
    var amount by remember(item) { mutableStateOf(item.amount.toString()) }
    var date by remember(item) { mutableStateOf(item.date) }
    var category by remember(item) { mutableStateOf(item.category) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Importo €") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Data") }, singleLine = true, supportingText = { Text("Predefinita a oggi, modificabile") })
                Text("Categoria", fontSize = 12.sp, color = Color(0xFF64748B))
                CategoryGrid(category) { category = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.replace(',', '.').toDoubleOrNull()
                if (a != null && name.isNotBlank()) onSave(Item(name.trim(), a, normalizeDate(date), category))
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text(deleteText) } }
    )
}

fun normalizeDate(raw: String): String {
    val s = raw.trim()
    return try {
        if (s.length == 10 && s[2] == '/' && s[5] == '/') LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toString()
        else LocalDate.parse(s).toString()
    } catch (_: Exception) { LocalDate.now().toString() }
}

fun formatDisplayDate(raw: String): String = try {
    LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
} catch (_: Exception) { raw }

@Composable
fun InputDialog(title: String, askName: Boolean, onDismiss: () -> Unit, label: String = "Importo €", nameLabel: String = "Descrizione", onOk: (String, Double, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var category by remember { mutableStateOf("Altro") }
    val categories = listOf("Alimentari", "Casa", "Auto", "Bollette", "Abbonamenti", "Svago", "Salute", "Shopping", "Banca", "Altro")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) OutlinedTextField(name, { name = it }, label = { Text(nameLabel) }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text(label) }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Data") }, singleLine = true, supportingText = { Text("Predefinita a oggi, modificabile") })
                if (askName) {
                    Text("Categoria", fontSize = 12.sp, color = Color(0xFF64748B))
                    CategoryGrid(category) { category = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.replace(',', '.').toDoubleOrNull()
                if (a != null) onOk(name.ifBlank { "Spesa" }, a, normalizeDate(date), category)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}


@Composable
fun TransactionsDialog(
    incomes: List<Item>,
    expenses: List<Item>,
    onAddExpense: () -> Unit,
    onAddIncome: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transazioni") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 360.dp)) {
                items(incomes) { item -> Row2(item, Color(0xFF16A34A)) {} }
                items(expenses) { item -> Row2(item) {} }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onAddIncome) { Text("+ Entrata") }
                TextButton(onClick = onAddExpense) { Text("+ Spesa") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

@Composable
fun AnalysisDialog(
    salary: Double,
    income: Double,
    fixed: Double,
    variable: Double,
    remaining: Double,
    expenses: List<Item>,
    onDismiss: () -> Unit
) {
    val base = salary + income
    val categoryTotals = expenses.groupBy { it.category.ifBlank { "Altro" } }
        .mapValues { (_, items) -> items.sumOf { it.amount } }
        .toList()
        .sortedByDescending { it.second }
    val used = if (base > 0) ((fixed + variable) / base).coerceIn(0.0, 1.0) else 0.0
    val pct = (used * 100).toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analisi del mese") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    CircularProgressIndicator(
                        progress = { used.toFloat() },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 14.dp,
                        color = if (pct >= 80) Color(0xFFF59E0B) else Color(0xFF1683E8),
                        trackColor = Color(0xFFE5EAF2)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$pct%", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF123B6D))
                        Text("del budget", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Spese totali: ${eur.format(fixed + variable)}", fontWeight = FontWeight.SemiBold)
                Text("Disponibilità: ${eur.format(remaining)}", color = if (remaining >= 0) Color(0xFF16A34A) else Color(0xFFDC2626))
                if (categoryTotals.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Spese variabili per categoria", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                    categoryTotals.forEach { (category, total) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(category, Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFF475569))
                            Text(eur.format(total), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
