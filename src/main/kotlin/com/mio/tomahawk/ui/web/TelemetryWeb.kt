package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.RunStatus
import com.mio.tomahawk.run.Verdict
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.ui.elapsed
import com.mio.tomahawk.ui.openArtifact
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Timer

/**
 * A Telemetria na versão web: os números das execuções com a taxa de sucesso, a missão em voo com o console ao vivo,
 * o gráfico das durações e o histórico por dia. Clicar numa execução abre o registro formatado.
 */
class TelemetryWeb(project: Project) : WebTab(project) {

    override val accent = "blue"

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private var filter = "all"
    private var onlyActive = false
    private var sentLines = 0
    private val timer = Timer(TICK_MS) { tick() }

    init {
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { refresh() })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener {
                ApplicationManager.getApplication().invokeLater({
                    if (runner.progress != null) timer.start() else timer.stop()
                    refresh(force = true)
                }, project.disposed)
            },
        )
        Disposer.register(this) { timer.stop() }
        if (runner.progress != null) timer.start()
        refresh()
    }

    private fun tick() {
        val p = runner.progress ?: return
        js("tmk.flight(${Flight.state(p)})")
        if (p.log.size > sentLines) {
            js("tmk.log(${Flight.lines(p, sentLines)})")
            sentLines = p.log.size
        }
    }

    override fun body(): String {
        val s = service.snapshot
        val records = if (onlyActive) s.runsOf(s.active) else s.runs
        val shown = records.filter {
            when (filter) {
                "ok" -> it.status == RunStatus.OK
                "failed" -> it.status == RunStatus.FAILED || it.status == RunStatus.CANCELLED
                "terminal" -> it.status == RunStatus.TERMINAL
                else -> true
            }
        }
        val counts = records.groupingBy { it.status }.eachCount()
        val finished = records.count { it.status == RunStatus.OK || it.status == RunStatus.FAILED }
        val ok = counts[RunStatus.OK] ?: 0
        val timed = records.filter { it.status == RunStatus.OK || it.status == RunStatus.FAILED }
        val average = timed.takeIf { it.isNotEmpty() }?.map { it.durationSeconds }?.average()?.toLong()
        return buildString {
            val chips = buildString {
                append(WebUi.chip("Execuções", "${records.size}"))
                if (ok > 0) append(WebUi.chip("Concluídas", "$ok", "green"))
                counts[RunStatus.FAILED]?.let { append(WebUi.chip("Falharam", "$it", "red")) }
                counts[RunStatus.CANCELLED]?.let { append(WebUi.chip("Abortadas", "$it")) }
                counts[RunStatus.TERMINAL]?.let { append(WebUi.chip("No terminal", "$it", "purple")) }
                average?.let { append(WebUi.chip("Duração média", elapsed(it))) }
                records.firstOrNull { it.verdict == Verdict.CONVERGED }?.let { append(WebUi.chip("Convergiu", "✓", "green")) }
            }
            val flying = runner.progress
            val title = when {
                flying != null -> "Em voo: ${esc(flying.mission.step.codename)}"
                records.isEmpty() -> "Sem telemetria ainda"
                else -> "Última: ${esc(records.first().title.substringBefore(" ("))}"
            }
            val subtitle = if (records.isEmpty()) "Lance uma etapa no Lançador; cada passo da IA aparece aqui." else lastLine(records.first())
            append(WebUi.header("📡", "Telemetria das missões", title, subtitle, chips,
                WebUi.ring(ok, finished, if (finished == 0 || ok * 100 / finished >= 70) "green" else "orange") + "<small>taxa de sucesso</small>"))
            if (flying != null) {
                sentLines = flying.log.size
                append(Flight.card(flying, console = true))
            } else {
                runner.latest?.let { last ->
                    append("<details class=\"source\"><summary>Log da última missão desta sessão · ${esc(last.mission.step.codename)}</summary>")
                    append("<div class=\"console\" style=\"margin:0;border-radius:0 0 10px 10px\">").append(Flight.logHtml(last)).append("</div></details>")
                }
            }
            if (records.isNotEmpty()) {
                append(WebUi.section("Durações · últimas ${minOf(records.size, CHART_SIZE)}"))
                append(chart(records.take(CHART_SIZE).reversed()))
            }
            append(WebUi.section("Histórico · ${shown.size}"))
            append("<div class=\"row-btns\" style=\"margin-bottom:8px\">")
            append(WebUi.seg("filter", listOf("all" to "Todas", "ok" to "Concluídas", "failed" to "Falhas", "terminal" to "Terminal"), filter, send = "filter"))
            append(WebUi.seg("scope", listOf("all" to "Todas as features", "active" to "Feature ativa"), if (onlyActive) "active" else "all", send = "scope"))
            append("</div>")
            if (shown.isEmpty()) {
                append("<div class=\"empty2\"><div class=\"big\">🛰️</div><h3>Nada por aqui</h3><p>Nenhuma execução com esse filtro.</p></div>")
            } else {
                shown.groupBy { day(it.startedAt) }.forEach { (day, runs) ->
                    append("<div class=\"runs-day\">").append(day).append(" · ${runs.size}</div>")
                    runs.forEach { append(run(it)) }
                }
            }
        }
    }

    private fun lastLine(r: RunRecord) = listOfNotNull(r.status.label, r.feature?.substringAfterLast('/'), time(r.startedAt)).joinToString(" · ")

    private fun chart(runs: List<RunRecord>): String = buildString {
        val max = runs.maxOf { it.durationSeconds }.coerceAtLeast(1)
        append("<div class=\"chart\">")
        runs.forEach { r ->
            val height = if (r.status == RunStatus.TERMINAL) 12 else (12 + 88 * r.durationSeconds / max).toInt()
            append("<div class=\"cb ${css(r.status)}\" style=\"height:$height%\" data-cmd=\"open\" data-arg=\"").append(WebUi.attr(r.file.toString())).append("\" title=\"")
            append(WebUi.attr("${r.title} · ${r.status.label} · ${elapsed(r.durationSeconds)} · ${time(r.startedAt)}")).append("\"></div>")
        }
        append("</div><div class=\"chart-legend\"><span>mais antiga</span><span>pico: ${elapsed(max)}</span><span>mais recente</span></div>")
    }

    private fun run(r: RunRecord): String = buildString {
        append("<div class=\"run ${css(r.status)}\" data-cmd=\"open\" data-arg=\"").append(WebUi.attr(r.file.toString())).append("\" title=\"Abrir o registro formatado\">")
        append("<div class=\"run-icon\">").append(
            when (r.status) {
                RunStatus.OK -> "✓"
                RunStatus.FAILED -> "✖"
                RunStatus.CANCELLED -> "⏹"
                RunStatus.TERMINAL -> "›_"
            },
        ).append("</div><div class=\"run-body\"><div class=\"run-title\">")
        append(esc(r.step?.codename ?: r.title.substringBefore(" (")))
        r.title.substringAfter(" (", "").removeSuffix(")").takeIf { it.isNotBlank() }?.let { append("<span class=\"st-cmd\">").append(esc(it)).append("</span>") }
        r.verdict?.let { append("<span class=\"b ${verdictTone(it)}\">").append(esc(it.label)).append("</span>") }
        append("<span class=\"b t-${statusTone(r.status)}\">").append(esc(r.status.label)).append("</span></div>")
        append("<div class=\"run-meta\"><span>🕒 ").append(time(r.startedAt)).append("</span>")
        if (r.status != RunStatus.TERMINAL) append("<span>⏱ ").append(elapsed(r.durationSeconds)).append("</span>")
        r.feature?.let { append("<span>📁 ").append(esc(it.substringAfterLast('/'))).append("</span>") }
        r.provider?.let { append("<span>🤖 ").append(esc(it)).append("</span>") }
        if (r.filesChanged > 0) append("<span>✏️ ${r.filesChanged} arquivo(s)</span>")
        append("</div>")
        r.error?.let { append("<div class=\"run-err\">").append(esc(it.take(240))).append("</div>") }
        append("</div></div>")
    }

    private fun css(status: RunStatus) = when (status) {
        RunStatus.OK -> "r-ok"
        RunStatus.FAILED -> "r-failed"
        RunStatus.CANCELLED -> "r-cancelled"
        RunStatus.TERMINAL -> "r-terminal"
    }

    private fun statusTone(status: RunStatus) = when (status) {
        RunStatus.OK -> "green"
        RunStatus.FAILED -> "red"
        RunStatus.CANCELLED -> "gray"
        RunStatus.TERMINAL -> "purple"
    }

    private fun verdictTone(v: Verdict) = when (v) {
        Verdict.CONVERGED, Verdict.CLEAN -> "t-green"
        Verdict.TASKS_APPENDED -> "t-yellow"
        Verdict.ISSUES -> "t-red"
    }

    private fun day(millis: Long): String {
        if (millis <= 0) return "Sem data"
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "Hoje"
            today.minusDays(1) -> "Ontem"
            else -> date.format(DAY)
        }
    }

    private fun time(millis: Long) = if (millis <= 0) "—" else LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(CLOCK)

    override fun onMessage(command: String, data: JsonObject) {
        when (command) {
            "open" -> data.str("arg")?.let { openArtifact(project, Path.of(it)) }
            "filter" -> {
                filter = data.str("value") ?: "all"
                refresh(force = true)
            }
            "scope" -> {
                onlyActive = data.str("value") == "active"
                refresh(force = true)
            }
            "abort" -> runner.progress?.cancel()
        }
    }

    private companion object {
        const val TICK_MS = 600
        const val CHART_SIZE = 30
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
