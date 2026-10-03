package com.example.stipendio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    fun remaining(m: String) = (actual()[m] ?: effectiveSalary(salaryHist(), m)) -
        fixed().filter { it.activeIn(m) }.sumOf { it.amount } - expenses(m).sumOf { it.amount }
}

class MainActivity : ComponentActivity() {
    private var quick by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quick = intent.getBooleanExtra("quick_add", false)
        val store = Store(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)) {
                Surface(Modifier.fillMaxSize()) {
                    App(store, quick) { quick = false; intent.removeExtra("quick_add") }
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
fun App(store: Store, quickAdd: Boolean, onQuickConsumed: () -> Unit) {
    val salaryHist = remember { mutableStateMapOf<String, Double>().apply { putAll(store.salaryHist()) } }
    val actualHist = remember { mutableStateMapOf<String, Double>().apply { putAll(store.actual()) } }
    val fixedAll = remember { mutableStateListOf<Fixed>().apply { addAll(store.fixed()) } }
    var startDay by remember { mutableStateOf(store.startDay()) }
    var month by remember { mutableStateOf(currentCycle(startDay)) }
    val ms = month.toString()
    val prev = month.minusMonths(1).toString()
    val expenses = remember(month) { mutableStateListOf<Item>().apply { addAll(store.expenses(ms)) } }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Fixed?>(null) }

    LaunchedEffect(quickAdd) {
        if (quickAdd) { month = currentCycle(startDay); dialog = "exp"; onQuickConsumed() }
    }

    val planned = effectiveSalary(salaryHist, ms)
    val actual = actualHist[ms]
    val salary = actual ?: planned
    val activeFixed = fixedAll.filter { it.activeIn(ms) }
    val totalFixed = activeFixed.sumOf { it.amount }
    val totalVar = expenses.sumOf { it.amount }
    val left = salary - totalFixed - totalVar

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

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text(cycleLabel(month, startDay), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
                TextButton(onClick = { dialog = "day" }) { Text("⚙") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D))) {
                Column(Modifier.padding(20.dp)) {
                    Text("Ti rimangono", fontSize = 14.sp)
                    Text(eur.format(left), fontSize = 40.sp, fontWeight = FontWeight.Bold,
                        color = if (left >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    Spacer(Modifier.height(8.dp))
                    Text((if (actual != null) "Stipendio effettivo: " else "Stipendio previsto: ") + eur.format(salary))
                    if (actual != null) {
                        val d = actual - planned
                        Text("Previsto ${eur.format(planned)} → ${if (d >= 0) "+" else "−"}${eur.format(kotlin.math.abs(d))}",
                            fontSize = 12.sp, color = if (d >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    }
                    Text("Spese fisse: −${eur.format(totalFixed)}")
                    Text("Spese variabili: −${eur.format(totalVar)}")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = "salary" }, Modifier.weight(1f)) { Text("Stipendio previsto", fontSize = 12.sp) }
                OutlinedButton(onClick = { dialog = "actual" }, Modifier.weight(1f)) { Text("Stipendio arrivato", fontSize = 12.sp) }
            }
        }
        item { Header("Spese del mese") { dialog = "exp" } }
        itemsIndexed(expenses) { i, it ->
            Row2(it) { expenses.removeAt(i); store.saveExpenses(ms, expenses) }
        }
        item { Header("Spese fisse") { dialog = "fixed" } }
        items(activeFixed) { f -> Row2(Item(f.name, f.amount)) { editing = f } }
        item { Text("Tocca una spesa per eliminarla (variabili) o modificarla (fisse)", fontSize = 12.sp, color = Color.Gray) }
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
            expenses.add(0, Item(n, a)); store.saveExpenses(ms, expenses); dialog = null
        }
    }

    editing?.let { f ->
        var amount by remember(f) { mutableStateOf(f.amount.toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.name) },
            text = {
                Column {
                    Text("Le modifiche valgono da questo mese in poi; i mesi passati non cambiano.", fontSize = 12.sp)
                    OutlinedTextField(amount, { amount = it }, label = { Text("Nuovo importo €") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    amount.replace(',', '.').toDoubleOrNull()?.let { editFixed(f, it); editing = null }
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFixed(f); editing = null }) { Text("Elimina da qui") }
            }
        )
    }
}

@Composable
fun Header(title: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Button(onClick = onAdd) { Text("+ Aggiungi") }
    }
}

@Composable
fun Row2(item: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp)) {
        Text(item.name, Modifier.weight(1f))
        Text(eur.format(item.amount))
    }
}

@Composable
fun InputDialog(title: String, askName: Boolean, onDismiss: () -> Unit, label: String = "Importo €", onOk: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) OutlinedTextField(name, { name = it }, label = { Text("Descrizione") }, singleLine = true)
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
