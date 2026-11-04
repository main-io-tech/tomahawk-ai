package com.mio.tomahawk.ui.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mio.tomahawk.run.LogKind
import com.mio.tomahawk.run.LogLine
import com.mio.tomahawk.run.MissionProgress
import com.mio.tomahawk.ui.elapsed
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** O cartão da missão em voo, comum ao Lançador, à Avaliação e à Telemetria, e o que a página recebe para atualizá-lo. */
object Flight {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun card(p: MissionProgress, console: Boolean = false): String = buildString {
        append("<div class=\"flight\" id=\"flight\"><div class=\"flight-top\">")
        append("<span class=\"flight-badge\"><span class=\"dot-live\"></span>").append(if (p.cancelled) "Abortando…" else "Missão em voo").append("</span>")
        append("<span class=\"flight-title\">").append(WebUi.attr(p.mission.step.codename))
        append(" <span class=\"st-cmd\">").append(WebUi.attr(p.invocation)).append("</span></span>")
        append("<span class=\"tags\"><span class=\"tag\">").append(WebUi.attr(p.provider.title)).append("</span>")
        p.mission.feature?.let { append("<span class=\"tag\">").append(WebUi.attr(it.substringAfterLast('/'))).append("</span>") }
        append("</span>")
        append(WebUi.button("⏹ Abortar", "abort", "danger", enabled = !p.cancelled)).append("</div>")
        append("<div class=\"flight-step\" data-f=\"step\">").append(WebUi.attr(p.step)).append("</div>")
        append("<div class=\"flight-trail\" data-f=\"trail\"").append(if (p.trail.isEmpty()) " style=\"display:none\"" else "").append(">")
        append(WebUi.attr(trail(p))).append("</div>")
        append("<div class=\"flight-detail\" data-f=\"detail\"").append(if (p.detail.isBlank()) " style=\"display:none\"" else "").append(">")
        append(WebUi.attr(p.detail)).append("</div>")
        append("<div class=\"flight-stats\"><span class=\"fs\"><b data-f=\"elapsed\">").append(elapsedOf(p)).append("</b>de voo</span>")
        append("<span class=\"fs\"><b data-f=\"actions\">${p.actions}</b>ações</span>")
        append("<span class=\"fs\"><b data-f=\"files\">${p.filesTouched}</b>arquivos alterados</span></div>")
        append("<div class=\"pulse-bar\"></div>")
        if (console) append("<div class=\"console\" id=\"console\">").append(logHtml(p)).append("</div>")
        append("</div>")
    }

    fun logHtml(p: MissionProgress): String =
        if (p.log.isEmpty()) "<div class=\"console-empty\">Aguardando a primeira resposta do CLI…</div>" else p.log.joinToString("") { line(it) }

    fun state(p: MissionProgress): String = JsonObject().apply {
        addProperty("step", p.step)
        addProperty("trail", trail(p))
        addProperty("detail", p.detail)
        addProperty("elapsed", elapsedOf(p))
        addProperty("actions", p.actions.toString())
        addProperty("files", p.filesTouched.toString())
    }.toString()

    /** As linhas de log a partir de [from], no formato que `tmk.log` espera. */
    fun lines(p: MissionProgress, from: Int): String = JsonArray().apply {
        p.log.drop(from).forEach { l ->
            add(JsonObject().apply {
                addProperty("k", l.kind.name)
                addProperty("t", time(l))
                addProperty("g", glyph(l.kind))
                addProperty("x", l.text)
            })
        }
    }.toString()

    private fun line(l: LogLine) = "<div class=\"ln k-${l.kind.name}\"><span class=\"lt\">${time(l)}</span><span class=\"lg\">${glyph(l.kind)}</span>" +
        "<span class=\"lx\">${WebUi.attr(l.text)}</span></div>"

    private fun glyph(kind: LogKind) = when (kind) {
        LogKind.INFO -> "●"
        LogKind.TOOL -> "→"
        LogKind.TEXT -> "✦"
        LogKind.ERROR -> "✖"
        LogKind.RESULT -> "✔"
    }

    private fun time(l: LogLine) = LocalDateTime.ofInstant(Instant.ofEpochMilli(l.at), ZoneId.systemDefault()).format(CLOCK)

    private fun trail(p: MissionProgress) = if (p.trail.isEmpty()) "" else "Antes: " + p.trail.joinToString("  ‹  ")

    private fun elapsedOf(p: MissionProgress) = elapsed((System.currentTimeMillis() - p.startedAt) / 1000)
}
