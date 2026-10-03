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
import androidx.compose.foundation.Image
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

data class Item(val name: String, val amount: Double)
data class Fixed(val id: String, val name: String, val amount: Double, val from: String, val to: String?) {
    fun activeIn(m: String) = from <= m && (to == null || m <= to)
}

fun effectiveSalary(hist: Map<String, Double>, m: String): Double =
    hist.filterKeys { it <= m }.maxByOrNull { it.key }?.value ?: 0.0

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("budget", Context.MODE_PRIVATE)

    fun expenses(m: String): List<Item> {
        val a = JSONArray(p.getString("exp_$m", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("n"), o.getDouble("a")) }
    }
    fun saveExpenses(m: String, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("a", it.amount)) }
        p.edit().putString("exp_$m", a.toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun incomes(m: String): List<Item> {
        val a = JSONArray(p.getString("inc_$m", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("n"), o.getDouble("a")) }
    }
    fun saveIncomes(m: String, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("a", it.amount)) }
        p.edit().putString("inc_$m", a.toString()).apply()
        QuickAddWidget.refresh(ctx)
    }
    fun fixed(): List<Fixed> {
        val a = JSONArray(p.getString("fixed2", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Fixed(o.getString("id"), o.getString("n"), o.getDouble("a"), o.getString("f"), o.optString("t", "").ifEmpty { null })
        }
    }
    fun saveFixed(l: List<Fixed>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("a", it.amount).put("f", it.from).put("t", it.to ?: "")) }
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

fun notifyBudgetAlert(ctx: Context, usedPercent: Int, remaining: Double) {
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
    nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), n)
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

    fun addFixed(n: String, a: Double) {
        fixedAll.add(Fixed(UUID.randomUUID().toString(), n, a, ms, null)); store.saveFixed(fixedAll)
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
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painter = painterResource(R.drawable.ic_mybudget_wallet), contentDescription = "Logo MyBudget+", modifier = Modifier.size(42.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MyBudget+", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0D4FA8), maxLines = 1, softWrap = false)
                        Text("Il tuo budget, sempre sotto controllo", fontSize = 10.sp, color = Color(0xFF64748B), maxLines = 1, softWrap = false)
                    }
                    Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF4F7FC)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { month = month.minusMonths(1) }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("‹", fontSize = 21.sp) }
                            TextButton(onClick = { month = month.plusMonths(1) }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("›", fontSize = 21.sp) }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF4F7FC)) {
                        TextButton(onClick = { dialog = "day" }, contentPadding = PaddingValues(10.dp)) { Text("⚙", fontSize = 17.sp) }
                    }
                }
            }
        }
        item {
            val budgetBase = salary + totalIncome
            val usedRatio = if (budgetBase > 0) ((totalFixed + totalVar) / budgetBase).coerceIn(0.0, 1.0) else 0.0
            val usedPct = (usedRatio * 100).toInt()
            val progressColor = if (usedPct >= 90) Color(0xFFEF4444) else if (usedPct >= 80) Color(0xFFF59E0B) else Color(0xFF1683E8)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Color(0xFF0B5FC7), shadowElevation = 5.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text("Buongiorno!", color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp)
                    Text("Il tuo budget, in sintesi", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("SALDO DISPONIBILE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                    Text(eur.format(left), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = if (left >= 0) Color(0xFF123B6D) else Color(0xFFDC2626))
                                }
                                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEAF2FF)) {
                                    Text("$usedPct%", modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = progressColor)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { usedRatio.toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp), color = progressColor, trackColor = Color(0xFFE7EDF5))
                            Spacer(Modifier.height(6.dp))
                            Text(if (usedPct >= 80) "Attenzione: hai superato l'80% del budget" else "$usedPct% dello stipendio mensile", fontSize = 11.sp, fontWeight = if (usedPct >= 80) FontWeight.SemiBold else FontWeight.Normal, color = if (usedPct >= 80) progressColor else Color(0xFF64748B))
                        }
                    }
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
                OutlinedButton(onClick = { dialog = "salary" }, Modifier.weight(1f)) { Text("Stipendio previsto", fontSize = 12.sp) }
                OutlinedButton(onClick = { dialog = "actual" }, Modifier.weight(1f)) { Text("Stipendio arrivato", fontSize = 12.sp) }
            }
        }
        item {
            OutlinedButton(onClick = { dialog = "backup" }, Modifier.fillMaxWidth()) {
                Text("Backup e ripristino")
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
        items(activeFixed) { f -> Row2(Item(f.name, f.amount)) { editing = f } }
        item { Text("Tocca una voce per modificarne nome/importo. Le spese fisse mantengono lo storico.", fontSize = 12.sp, color = Color(0xFF64748B)) }
        }
    }

    when (dialog) {
        "day" -> InputDialog("Giorno di inizio ciclo", false, { dialog = null }, label = "Giorno (1-28)") { _, a ->
            val d = a.toInt().coerceIn(1, 28)
            startDay = d; store.saveStartDay(d); month = currentCycle(d); dialog = null
        }
        "actual" -> InputDialog("Stipendio effettivo di questo mese", false, { dialog = null }) { _, a ->
            actualHist[ms] = a; store.saveActual(actualHist); dialog = null
        }
        "salary" -> InputDialog("Stipendio previsto (minimo) da questo mese", false, { dialog = null }) { _, a ->
            salaryHist[ms] = a; store.saveSalaryHist(salaryHist); dialog = null
        }
        "fixed" -> InputDialog("Nuova spesa fissa da questo mese", true, { dialog = null }) { n, a ->
            addFixed(n, a); dialog = null
        }
        "exp" -> InputDialog("Nuova spesa", true, { dialog = null }) { n, a ->
            expenses.add(0, Item(n, a)); store.saveExpenses(ms, expenses)
            val budgetBaseNow = salary + totalIncome
            val usedPctNow = if (budgetBaseNow > 0) (((totalFixed + expenses.sumOf { it.amount }) / budgetBaseNow) * 100).toInt() else 0
            if (usedPctNow >= 80) notifyBudgetAlert(store.context(), usedPctNow, budgetBaseNow - totalFixed - expenses.sumOf { it.amount })
            dialog = null
        }
        "income" -> InputDialog("Nuovo introito", true, { dialog = null }, nameLabel = "Descrizione") { n, a ->
            incomes.add(0, Item(n, a)); store.saveIncomes(ms, incomes); dialog = null
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
            onDismiss = { dialog = null }
        )
    }

    editing?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        var amount by remember(f) { mutableStateOf(f.amount.toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifica spesa fissa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Il nome e l'importo modificati valgono da questo mese in poi; lo storico dei mesi passati resta invariato.", fontSize = 12.sp)
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it }, label = { Text("Importo €") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = amount.replace(',', '.').toDoubleOrNull()
                    if (a != null && name.isNotBlank()) {
                        val i = fixedAll.indexOf(f)
                        if (f.from == ms) fixedAll[i] = f.copy(name = name.trim(), amount = a)
                        else {
                            fixedAll[i] = f.copy(to = prev)
                            fixedAll.add(Fixed(UUID.randomUUID().toString(), name.trim(), a, ms, f.to))
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
                    expenses[index] = item
                    store.saveExpenses(ms, expenses)
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

@Composable
fun Row2(item: Item, amountColor: Color = Color.Unspecified, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(10.dp), shape = RoundedCornerShape(50), color = if (amountColor == Color.Unspecified) Color(0xFFCBD5E1) else Color(0xFF4ADE80)) {}
            Spacer(Modifier.width(12.dp))
            Text(item.name, Modifier.weight(1f), fontSize = 15.sp)
            Text((if (amountColor == Color.Unspecified) "−" else "+") + eur.format(item.amount), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (amountColor == Color.Unspecified) Color(0xFF111827) else amountColor)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Importo €") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.replace(',', '.').toDoubleOrNull()
                if (a != null && name.isNotBlank()) onSave(Item(name.trim(), a))
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text(deleteText) } }
    )
}

@Composable
fun InputDialog(title: String, askName: Boolean, onDismiss: () -> Unit, label: String = "Importo €", nameLabel: String = "Descrizione", onOk: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) OutlinedTextField(name, { name = it }, label = { Text(nameLabel) }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text(label) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.replace(',', '.').toDoubleOrNull()
                if (a != null) onOk(name.ifBlank { "Spesa" }, a)
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
    onDismiss: () -> Unit
) {
    val base = salary + income
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
