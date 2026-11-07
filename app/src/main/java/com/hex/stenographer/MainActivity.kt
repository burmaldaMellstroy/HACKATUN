package com.hex.stenographer

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF0B52B8)
private val BlueSoft = Color(0xFFE8F0FC)
private val Bg = Color(0xFFF6F8FC)
private val Ink = Color(0xFF1B2330)
private val Muted = Color(0xFF6B7686)
private val Red = Color(0xFFD6004B)
private val SpeakerColors = listOf(
    Color(0xFF0B52B8), Color(0xFFD6004B), Color(0xFF2E9E6B),
    Color(0xFFEF8A00), Color(0xFF7B3FC4), Color(0xFF0097A7)
)
private fun spkColor(id: String) = SpeakerColors[(Exporters.speakerNum(id) - 1).coerceAtLeast(0) % SpeakerColors.size]

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue, onPrimary = Color.White, secondary = Red,
                    background = Bg, surface = Color.White, onSurface = Ink,
                    primaryContainer = BlueSoft, onPrimaryContainer = Blue
                )
            ) { Screen() }
        }
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) { }
        return "Аудиофайл"
    }

    private fun nowTitle(prefix: String) =
        prefix + " " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())

    @Composable
    fun Screen() {
        val scope = rememberCoroutineScope()
        val ctx = this
        val handler = remember { Handler(Looper.getMainLooper()) }
        val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }

        var tab by remember { mutableIntStateOf(0) }
        var status by remember { mutableStateOf("Выберите файл или запишите разговор") }
        var progress by remember { mutableFloatStateOf(0f) }
        var busy by remember { mutableStateOf(false) }
        var items by remember { mutableStateOf(listOf<Utterance>()) }
        var names by remember { mutableStateOf(mapOf<String, String>()) }
        var info by remember { mutableStateOf<String?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var procSamples by remember { mutableStateOf<FloatArray?>(null) }
        var currentId by remember { mutableStateOf<String?>(null) }
        var historyList by remember { mutableStateOf(listOf<HistoryEntry>()) }
        var deleteId by remember { mutableStateOf<String?>(null) }

        var recording by remember { mutableStateOf(false) }
        var recSec by remember { mutableIntStateOf(0) }
        val recorder = remember { Recorder() }

        // настройки (запоминаются)
        var showSettings by remember { mutableStateOf(false) }
        var useLlm by remember { mutableStateOf(prefs.getBoolean("llm", true)) }
        var denoise by remember { mutableStateOf(prefs.getBoolean("denoise", false)) }
        var useVad by remember { mutableStateOf(prefs.getBoolean("vad", true)) }
        var mergeMinor by remember { mutableStateOf(prefs.getBoolean("merge", true)) }
        var numSpeakers by remember { mutableIntStateOf(prefs.getInt("spk", -1)) }
        var threshold by remember { mutableFloatStateOf(prefs.getFloat("th", 0.65f)) }
        val hasLlm = remember { ModelStore.exists(ctx, ModelStore.LLM_MODEL) }
        val hasVad = remember { ModelStore.exists(ctx, ModelStore.VAD_MODEL) }
        val hasDenoise = remember { ModelStore.exists(ctx, ModelStore.DENOISE_MODEL) }

        // диалоги и поиск
        var editIdx by remember { mutableIntStateOf(-1) }
        var editText by remember { mutableStateOf("") }
        var reBusy by remember { mutableStateOf(false) }
        var spkDialog by remember { mutableStateOf<String?>(null) }
        var spkName by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var exportMenu by remember { mutableStateOf(false) }

        // воспроизведение
        var playing by remember { mutableStateOf(false) }
        var posMs by remember { mutableIntStateOf(0) }

        fun nameOf(id: String): String = names[id] ?: Exporters.defaultName(id)

        LaunchedEffect(useLlm, denoise, useVad, mergeMinor, numSpeakers, threshold) {
            prefs.edit().putBoolean("llm", useLlm).putBoolean("denoise", denoise).putBoolean("vad", useVad)
                .putBoolean("merge", mergeMinor).putInt("spk", numSpeakers).putFloat("th", threshold).apply()
        }
        LaunchedEffect(Unit) { historyList = withContext(Dispatchers.IO) { HistoryStore.list(ctx) } }
        LaunchedEffect(recording) { while (recording) { delay(1000); recSec++ } }
        LaunchedEffect(busy, recording) {
            if (busy || recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        LaunchedEffect(Unit) {
            while (true) {
                val p = player
                val on = try { p != null && p.isPlaying } catch (e: Exception) { false }
                if (on && p != null) { posMs = try { p.currentPosition } catch (e: Exception) { 0 } }
                playing = on
                delay(150)
            }
        }

        fun persist() {
            val id = currentId ?: return
            val snapItems = items
            val snapNames = names
            scope.launch(Dispatchers.IO) { HistoryStore.update(ctx, id, snapItems, snapNames) }
        }

        fun runPipeline(title: String, load: () -> FloatArray) {
            busy = true; items = emptyList(); names = emptyMap(); info = null
            progress = 0f; procSamples = null; currentId = null; query = ""
            player?.release(); player = null
            scope.launch(Dispatchers.Default) {
                try {
                    status = "Подготовка моделей…"
                    ModelStore.ensure(ctx) { m -> status = m }
                    status = "Чтение аудио…"
                    val samples = load()
                    val st = Settings(useLlm = useLlm, denoise = denoise, useVad = useVad,
                        numSpeakers = numSpeakers, threshold = threshold, threads = 4, mergeMinor = mergeMinor)
                    val res = Pipeline(ctx).run(samples, st) { s, p -> status = s; progress = p }
                    items = res.utterances; procSamples = res.samples
                    val speakers = res.utterances.map { it.speakerId }.toSet().size
                    info = "Говорящих: $speakers · аудио %.0f с · обработка %.0f с · RTF %.2f"
                        .format(res.audioSec, res.procSec, res.rtf)
                    if (res.utterances.isNotEmpty()) {
                        val id = HistoryStore.save(ctx, title, res.utterances, emptyMap(), res.samples)
                        currentId = id
                        val wav = HistoryStore.wavFile(ctx, id)
                        withContext(Dispatchers.Main) { player = MediaPlayer.create(ctx, Uri.fromFile(wav)) }
                        historyList = HistoryStore.list(ctx)
                        status = "Готово"
                    } else status = "Речь не найдена"
                } catch (e: Throwable) {
                    status = "Ошибка: ${e.message}"
                } finally { busy = false }
            }
        }

        fun startRec() {
            try {
                player?.release(); player = null
                recorder.start(); recSec = 0; recording = true
                status = "Идёт запись…"
            } catch (e: Throwable) { status = "Ошибка записи: ${e.message}" }
        }

        fun stopRec() {
            recording = false
            val r = try { recorder.stop(cacheDir) } catch (e: Throwable) { status = "Ошибка: ${e.message}"; return }
            if (r.samples.size < 16000) { status = "Запись слишком короткая"; return }
            runPipeline(nowTitle("Запись")) { r.samples }
        }

        fun play(u: Utterance) {
            val p = player ?: return
            handler.removeCallbacksAndMessages(null)
            p.seekTo(u.startMs); p.start()
            handler.postDelayed({ try { if (p.isPlaying) p.pause() } catch (e: Exception) { } },
                (u.endMs - u.startMs).toLong() + 200)
        }

        fun playAll() {
            val p = player ?: return
            handler.removeCallbacksAndMessages(null)
            if (p.isPlaying) p.pause() else {
                if (p.currentPosition >= p.duration - 300) p.seekTo(0)
                p.start()
            }
        }

        fun mergeSpeakers(src: String, dst: String) {
            items = Exporters.mergeAdjacent(items.map { if (it.speakerId == src) it.copy(speakerId = dst) else it })
            names = names - src
            persist()
        }

        fun openEntry(e: HistoryEntry) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { HistoryStore.load(ctx, e.id) }
                if (loaded == null) { status = "Не удалось открыть запись"; return@launch }
                val wav = HistoryStore.wavFile(ctx, e.id)
                val samples = withContext(Dispatchers.IO) { if (wav.exists()) WavIO.read(wav) else null }
                player?.release()
                player = if (wav.exists()) MediaPlayer.create(ctx, Uri.fromFile(wav)) else null
                items = loaded.items; names = loaded.names; procSamples = samples
                currentId = e.id; query = ""
                info = "Из истории: ${e.title}"; status = "Готово"; tab = 0
            }
        }

        fun writeTo(uri: Uri?, text: String) {
            if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startRec() else status = "Нужно разрешение на микрофон"
        }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) runPipeline(fileName(uri)) { AudioDecoder.decode(ctx, uri) }
        }
        val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            writeTo(uri, TranscriptJson.toJson(items))
        }
        val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            writeTo(uri, Exporters.txt(items) { nameOf(it) })
        }
        val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            writeTo(uri, Exporters.srt(items) { nameOf(it) })
        }

        val idle = !busy && !recording
        val activeIdx = if (playing) items.indexOfFirst { posMs >= it.startMs && posMs <= it.endMs } else -1

        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
            // ---------- шапка ----------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Нейро-стенографист", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Blue)
                    Text("Всё работает на устройстве, без интернета", fontSize = 12.sp, color = Muted)
                }
                if (tab == 0) TextButton(onClick = { showSettings = !showSettings }) {
                    Text(if (showSettings) "Скрыть" else "Настройки", color = Blue)
                }
            }

            Box(Modifier.weight(1f)) {
                if (tab == 0) {
                    val shown = items.withIndex().filter {
                        query.isBlank() || it.value.text.contains(query, ignoreCase = true)
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // источник
                        item {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedButton(
                                            enabled = idle,
                                            onClick = { pick.launch(arrayOf("audio/*")) },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.weight(1f).height(52.dp)
                                        ) { Text("Выбрать файл") }
                                        Button(
                                            enabled = !busy,
                                            onClick = {
                                                if (recording) stopRec() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (recording) Red else Blue),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.weight(1f).height(52.dp)
                                        ) { Text(if (recording) "Стоп · ${fmtTime(recSec * 1000)}" else "Записать") }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(status, color = Ink, fontSize = 14.sp)
                                    if (busy) {
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                            color = Blue, trackColor = BlueSoft
                                        )
                                    }
                                    info?.let {
                                        Spacer(Modifier.height(6.dp))
                                        Text(it, color = Muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // настройки
                        if (showSettings) item {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    SettingRow("Фильтрация пауз (VAD)", if (hasVad) null else "нет модели", useVad && hasVad, idle && hasVad) { useVad = it }
                                    SettingRow("Шумоподавление", if (hasDenoise) null else "нет модели", denoise && hasDenoise, idle && hasDenoise) { denoise = it }
                                    SettingRow("Пунктуация (LLM)", if (hasLlm) null else "нет модели", useLlm && hasLlm, idle && hasLlm) { useLlm = it }
                                    SettingRow("Убирать «лишних» говорящих", "Присоединяет случайные голоса к основным", mergeMinor, idle) { mergeMinor = it }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Число говорящих (если знаете точно — укажите, так надёжнее)", fontSize = 13.sp, color = Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(-1 to "Авто", 2 to "2", 3 to "3", 4 to "4").forEach { (v, label) ->
                                            FilterChip(
                                                selected = numSpeakers == v, enabled = idle,
                                                onClick = { numSpeakers = v },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Порог различения голосов: %.2f (больше — меньше говорящих)".format(threshold),
                                        fontSize = 13.sp, color = Muted
                                    )
                                    Slider(
                                        value = threshold, onValueChange = { threshold = it },
                                        valueRange = 0.3f..0.9f, enabled = idle && numSpeakers == -1
                                    )
                                }
                            }
                        }

                        // говорящие + управление
                        if (items.isNotEmpty()) {
                            item {
                                val stats = items.groupBy { it.speakerId }
                                    .map { e -> e.key to e.value.sumOf { (it.endMs - it.startMs).toLong() } }
                                    .sortedBy { Exporters.speakerNum(it.first) }
                                val total = stats.sumOf { it.second }.coerceAtLeast(1L)
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Говорящие (нажмите, чтобы переименовать или объединить)", fontSize = 12.sp, color = Muted)
                                        Spacer(Modifier.height(8.dp))
                                        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)) {
                                            stats.forEach { (id, ms) ->
                                                Box(Modifier.weight(ms.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(spkColor(id)))
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            stats.forEach { (id, ms) ->
                                                val c = spkColor(id)
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = c.copy(alpha = 0.12f),
                                                    modifier = Modifier.clickable(enabled = idle) {
                                                        spkDialog = id; spkName = names[id] ?: ""
                                                    }
                                                ) {
                                                    Row(
                                                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("${nameOf(id)} · ${ms * 100 / total}%", fontSize = 13.sp, color = Ink)
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(
                                                enabled = player != null,
                                                onClick = { playAll() },
                                                shape = RoundedCornerShape(12.dp)
                                            ) { Text(if (playing) "⏸ Пауза" else "▶ Слушать всё") }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = query, onValueChange = { query = it },
                                            placeholder = { Text("Поиск по тексту") },
                                            singleLine = true, shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        // реплики
                        items(shown, key = { it.index }) { iv ->
                            val idx = iv.index
                            val u = iv.value
                            val color = spkColor(u.speakerId)
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (idx == activeIdx) BlueSoft else Color.White
                                ),
                                modifier = Modifier.fillMaxWidth().clickable { play(u) }
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape).background(color),
                                        contentAlignment = Alignment.Center
                                    ) { Text("${Exporters.speakerNum(u.speakerId)}", color = Color.White, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${nameOf(u.speakerId)} · ${fmtTime(u.startMs)}–${fmtTime(u.endMs)}",
                                            fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(u.text, fontSize = 15.sp, color = Ink)
                                    }
                                    TextButton(
                                        enabled = idle,
                                        onClick = { editIdx = idx; editText = u.text }
                                    ) { Text("Изм.", color = Muted) }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                } else {
                    // ---------- история ----------
                    if (historyList.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("История пока пуста", color = Muted)
                        }
                    } else LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(historyList, key = { it.id }) { e ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(e.title, fontWeight = FontWeight.Medium, color = Ink)
                                    Text(
                                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(e.created)) +
                                            " · ${e.count} реплик · ${e.durationSec} с",
                                        fontSize = 12.sp, color = Muted
                                    )
                                    Row {
                                        TextButton(enabled = idle, onClick = { openEntry(e) }) { Text("Открыть", color = Blue) }
                                        TextButton(enabled = idle, onClick = { deleteId = e.id }) { Text("Удалить", color = Red) }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }

            // ---------- экспорт ----------
            if (tab == 0 && items.isNotEmpty()) {
                Surface(color = Color.White, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            Button(
                                enabled = idle, onClick = { exportMenu = true },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("Экспорт ▾") }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                DropdownMenuItem(text = { Text("JSON (формат по ТЗ)") },
                                    onClick = { exportMenu = false; saveJson.launch("transcript.json") })
                                DropdownMenuItem(text = { Text("Текст (.txt)") },
                                    onClick = { exportMenu = false; saveTxt.launch("transcript.txt") })
                                DropdownMenuItem(text = { Text("Субтитры (.srt)") },
                                    onClick = { exportMenu = false; saveSrt.launch("transcript.srt") })
                            }
                        }
                        OutlinedButton(
                            enabled = idle,
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, Exporters.txt(items) { nameOf(it) })
                                }
                                startActivity(Intent.createChooser(send, "Поделиться расшифровкой"))
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Поделиться") }
                    }
                }
            }

            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("🎙") }, label = { Text("Расшифровка") })
                NavigationBarItem(selected = tab == 1, onClick = {
                    tab = 1
                    scope.launch { historyList = withContext(Dispatchers.IO) { HistoryStore.list(ctx) } }
                }, icon = { Text("🕘") }, label = { Text("История") })
            }
        }

        // ---------- диалог правки реплики ----------
        if (editIdx in items.indices) {
            val u = items[editIdx]
            AlertDialog(
                onDismissRequest = { if (!reBusy) editIdx = -1 },
                title = { Text("Правка реплики") },
                text = {
                    Column {
                        Text("${nameOf(u.speakerId)} · ${fmtTime(u.startMs)}–${fmtTime(u.endMs)}", fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth())
                        if (reBusy) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (items.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Text("Говорящий:", fontSize = 12.sp, color = Muted)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items.map { it.speakerId }.distinct().sortedBy { Exporters.speakerNum(it) }.forEach { sid ->
                                    FilterChip(
                                        selected = sid == u.speakerId, enabled = !reBusy,
                                        onClick = {
                                            items = items.toMutableList().also { it[editIdx] = u.copy(speakerId = sid) }
                                            persist()
                                        },
                                        label = { Text(nameOf(sid)) }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !reBusy, onClick = {
                        val cur = items[editIdx]
                        items = items.toMutableList().also { it[editIdx] = cur.copy(text = editText.trim()) }
                        persist()
                        editIdx = -1
                    }) { Text("Сохранить") }
                },
                dismissButton = {
                    Row {
                        TextButton(enabled = !reBusy && procSamples != null, onClick = {
                            val s = procSamples ?: return@TextButton
                            reBusy = true
                            scope.launch(Dispatchers.Default) {
                                try {
                                    val t = Pipeline(ctx).reRecognize(s, u.startMs, u.endMs, 4)
                                    if (t.isNotEmpty()) editText = t
                                } catch (e: Throwable) { }
                                finally { reBusy = false }
                            }
                        }) { Text("Заново") }
                        TextButton(enabled = !reBusy, onClick = { editIdx = -1 }) { Text("Отмена") }
                    }
                }
            )
        }

        // ---------- диалог говорящего ----------
        val sd = spkDialog
        if (sd != null) {
            val others = items.map { it.speakerId }.distinct().filter { it != sd }.sortedBy { Exporters.speakerNum(it) }
            AlertDialog(
                onDismissRequest = { spkDialog = null },
                title = { Text(Exporters.defaultName(sd)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = spkName, onValueChange = { spkName = it },
                            label = { Text("Имя") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (others.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Это тот же человек, что:", fontSize = 12.sp, color = Muted)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                others.forEach { o ->
                                    OutlinedButton(onClick = { mergeSpeakers(sd, o); spkDialog = null }) { Text(nameOf(o)) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val n = spkName.trim()
                        names = if (n.isEmpty()) names - sd else names + (sd to n)
                        persist()
                        spkDialog = null
                    }) { Text("Сохранить") }
                },
                dismissButton = { TextButton(onClick = { spkDialog = null }) { Text("Отмена") } }
            )
        }

        // ---------- подтверждение удаления ----------
        val did = deleteId
        if (did != null) {
            AlertDialog(
                onDismissRequest = { deleteId = null },
                title = { Text("Удалить запись?") },
                text = { Text("Текст и звук будут удалены с устройства.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { HistoryStore.delete(ctx, did) }
                            if (currentId == did) currentId = null
                            historyList = withContext(Dispatchers.IO) { HistoryStore.list(ctx) }
                        }
                        deleteId = null
                    }) { Text("Удалить", color = Red) }
                },
                dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } }
            )
        }
    }

    @Composable
    private fun SettingRow(title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = Ink)
                if (hint != null) Text(hint, fontSize = 11.sp, color = Muted)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}
