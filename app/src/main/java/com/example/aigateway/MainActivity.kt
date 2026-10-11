package com.example.aigateway

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

data class AiSite(
    val name: String,
    val category: String,
    val url: String,
    val description: String
)

enum class SiteState { UNKNOWN, CHECKING, AVAILABLE, LIMITED, UNAVAILABLE }

data class SiteStatus(
    val state: SiteState = SiteState.UNKNOWN,
    val detail: String = "Ещё не проверено",
    val httpCode: Int? = null,
    val latencyMs: Long? = null
)

private val sites = listOf(
    AiSite("ChatGPT", "Ассистент", "https://chatgpt.com/", "Чат и универсальный помощник"),
    AiSite("Claude", "Ассистент", "https://claude.ai/", "Письмо, анализ и программирование"),
    AiSite("Gemini", "Ассистент", "https://gemini.google.com/", "ИИ-помощник Google"),
    AiSite("Microsoft Copilot", "Ассистент", "https://copilot.microsoft.com/", "Помощник Microsoft"),
    AiSite("Perplexity", "Поиск", "https://www.perplexity.ai/", "Поиск с ответами и источниками"),
    AiSite("DeepSeek", "Ассистент", "https://chat.deepseek.com/", "Чат и задачи по коду"),
    AiSite("Mistral Le Chat", "Ассистент", "https://chat.mistral.ai/", "ИИ-чат Mistral"),
    AiSite("Poe", "Агрегатор", "https://poe.com/", "Разные ИИ-модели в одном месте"),
    AiSite("HuggingChat", "Открытые модели", "https://huggingface.co/chat/", "Чат с открытыми моделями"),
    AiSite("You.com", "Поиск", "https://you.com/", "ИИ-поиск и помощник")
)

class SiteChecker {
    suspend fun check(site: AiSite): SiteStatus = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(site.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AIGatewayAndroid/0.2")
                setRequestProperty("Range", "bytes=0-0")
            }
            val code = connection.responseCode
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val state = when {
                code in 200..399 -> SiteState.AVAILABLE
                code in 401..403 -> SiteState.LIMITED
                code == 429 -> SiteState.LIMITED
                code in 400..499 -> SiteState.LIMITED
                code >= 500 -> SiteState.LIMITED
                else -> SiteState.UNKNOWN
            }
            val detail = when (code) {
                in 200..299 -> "Сайт ответил успешно"
                in 300..399 -> "Сайт перенаправляет запрос"
                401, 403 -> "Сайт ответил: доступ требует авторизации или ограничен"
                429 -> "Сайт ограничивает частоту запросов"
                in 400..499 -> "HTTP-ответ $code"
                in 500..599 -> "Ошибка на стороне сайта: HTTP $code"
                else -> "HTTP-ответ $code"
            }
            SiteStatus(state, detail, code, elapsed)
        } catch (_: SocketTimeoutException) {
            SiteStatus(SiteState.UNAVAILABLE, "Тайм-аут: сайт не ответил за 5 секунд")
        } catch (_: SSLException) {
            SiteStatus(SiteState.UNAVAILABLE, "Ошибка TLS/сертификата")
        } catch (e: java.net.UnknownHostException) {
            SiteStatus(SiteState.UNAVAILABLE, "Не удалось разрешить DNS-имя")
        } catch (e: java.io.IOException) {
            SiteStatus(SiteState.UNAVAILABLE, "Сетевая ошибка: ${e.localizedMessage ?: "соединение не установлено"}")
        } catch (e: Exception) {
            SiteStatus(SiteState.UNKNOWN, "Ошибка проверки: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }
}

class GatewayViewModel : ViewModel() {
    private val checker = SiteChecker()
    var statuses by mutableStateOf(sites.associate { it.name to SiteStatus() })
        private set
    var checking by mutableStateOf(false)
        private set
    var checkedCount by mutableIntStateOf(0)
        private set
    var summary by mutableStateOf("Нажмите «Проверить все», чтобы проверить сайты")
        private set

    fun checkAll() {
        if (checking) return
        viewModelScope.launch {
            checking = true
            checkedCount = 0
            summary = "Проверяем сайты по HTTPS…"
            sites.forEach { site ->
                statuses = statuses + (site.name to SiteStatus(SiteState.CHECKING, "Проверка…"))
            }
            try {
                coroutineScope {
                    sites.map { site ->
                        async {
                            val result = checker.check(site)
                            site.name to result
                        }
                    }.awaitAll().forEach { (name, status) ->
                        statuses = statuses + (name to status)
                        checkedCount++
                    }
                }
                val reachable = statuses.values.count { it.state == SiteState.AVAILABLE }
                val limited = statuses.values.count { it.state == SiteState.LIMITED }
                val failed = statuses.values.count { it.state == SiteState.UNAVAILABLE }
                summary = "Готово: доступно $reachable · ограничения/ответы $limited · ошибки соединения $failed"
            } finally {
                checking = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GatewayApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayApp(vm: GatewayViewModel = viewModel()) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(setOf<String>()) }
    var showFavorites by remember { mutableStateOf(false) }

    val filtered = sites.filter {
        (it.name.contains(query, ignoreCase = true) ||
            it.category.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)) &&
            (!showFavorites || it.name in favorites)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("AI Gateway", fontWeight = FontWeight.Bold)
                            Text("Каталог нейросетей · v0.2", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showFavorites = !showFavorites }) {
                            Icon(
                                if (showFavorites) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Избранное"
                            )
                        }
                        IconButton(onClick = { vm.checkAll() }, enabled = !vm.checking) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Проверить все сайты")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Найти нейросеть…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Диагностика HTTPS", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (vm.checking) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        Text(vm.summary, style = MaterialTheme.typography.bodyMedium)
                        if (vm.checking) {
                            LinearProgressIndicator(
                                progress = { vm.checkedCount.toFloat() / sites.size.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Button(
                            enabled = !vm.checking,
                            onClick = { vm.checkAll() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (vm.checking) "Проверка…" else "Проверить все сайты") }
                        Text(
                            "Проверка показывает сетевой HTTP-ответ, но не подтверждает вход в аккаунт или доступность всех функций сервиса.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    if (showFavorites) "Избранное (${filtered.size})" else "Популярные сервисы (${filtered.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(filtered, key = { it.name }) { site ->
                        val status = vm.statuses[site.name] ?: SiteStatus()
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(site.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(site.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    StatusIcon(status.state)
                                }
                                Text(site.description, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        statusLabel(status.state),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor(status.state)
                                    )
                                    status.httpCode?.let {
                                        Text(" · HTTP $it", style = MaterialTheme.typography.labelSmall)
                                    }
                                    status.latencyMs?.let {
                                        Text(" · ${it} мс", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (status.state != SiteState.UNKNOWN && status.state != SiteState.CHECKING) {
                                    Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (status.state == SiteState.CHECKING) {
                                    Text("Проверка соединения…", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            favorites = if (site.name in favorites) favorites - site.name else favorites + site.name
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            if (site.name in favorites) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (site.name in favorites) "В избранном" else "В избранное")
                                    }
                                    Button(
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.url)))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Открыть сайт")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(state: SiteState) {
    val icon = when (state) {
        SiteState.AVAILABLE -> Icons.Filled.CheckCircle
        SiteState.LIMITED, SiteState.UNAVAILABLE -> Icons.Filled.ErrorOutline
        else -> Icons.Filled.HelpOutline
    }
    val tint = statusColor(state)
    Icon(icon, contentDescription = statusLabel(state), tint = tint)
}

@Composable
private fun statusColor(state: SiteState) = when (state) {
    SiteState.AVAILABLE -> MaterialTheme.colorScheme.primary
    SiteState.LIMITED -> MaterialTheme.colorScheme.tertiary
    SiteState.UNAVAILABLE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabel(state: SiteState): String = when (state) {
    SiteState.UNKNOWN -> "Не проверено"
    SiteState.CHECKING -> "Проверяется"
    SiteState.AVAILABLE -> "Ответ получен"
    SiteState.LIMITED -> "Ограниченный HTTP-ответ"
    SiteState.UNAVAILABLE -> "Ошибка соединения"
}
