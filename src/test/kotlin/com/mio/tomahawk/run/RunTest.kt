package com.mio.tomahawk.run

import com.mio.tomahawk.speckit.SddStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class RunTest {

    @Test
    fun `lê o stream-json do Cursor`() {
        assertEquals(listOf(AgentEvent.Init("gpt-5")), AgentEvents.parse("""{"type":"system","subtype":"init","model":"gpt-5"}"""))
        assertEquals(
            listOf(AgentEvent.Tool("edit", "/p/specs/001/spec.md")),
            AgentEvents.parse("""{"type":"tool_call","subtype":"started","tool_call":{"editToolCall":{"args":{"path":"/p/specs/001/spec.md"}}}}"""),
        )
        assertEquals(emptyList<AgentEvent>(), AgentEvents.parse("""{"type":"tool_call","subtype":"completed","tool_call":{"editToolCall":{}}}"""))
        assertEquals(
            listOf(AgentEvent.Result("pronto", false, null)),
            AgentEvents.parse("""{"type":"result","subtype":"success","is_error":false,"result":"pronto"}"""),
        )
    }

    @Test
    fun `lê o stream-json do Claude Code, com texto e ferramentas na mesma mensagem`() {
        val events = AgentEvents.parse(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Criando a spec"},""" +
                """{"type":"tool_use","name":"Write","input":{"file_path":"specs/001/spec.md"}},""" +
                """{"type":"tool_use","name":"Bash","input":{"command":"pwsh .specify/scripts/powershell/create-new-feature.ps1"}}]}}""",
        )
        assertEquals(
            listOf(
                AgentEvent.Text("Criando a spec"),
                AgentEvent.Tool("write", "specs/001/spec.md"),
                AgentEvent.Tool("shell", "pwsh .specify/scripts/powershell/create-new-feature.ps1"),
            ),
            events,
        )
        assertEquals(
            listOf(AgentEvent.Result("feito", false, 0.12)),
            AgentEvents.parse("""{"type":"result","subtype":"success","is_error":false,"result":"feito","total_cost_usd":0.12}"""),
        )
        assertEquals(listOf(AgentEvent.Other("texto solto")), AgentEvents.parse("texto solto"))
    }

    @Test
    fun `monta o comando sem janela de cada CLI com o prompt numa linha sem acentos`() {
        val cursor = AgentCommand.headless(AgentProvider.CURSOR, "agent", "Especificação\n\"rápida\" & cia", "C:/p", "sonnet", listOf("--approve-mcps"))
        assertEquals(listOf("agent", "-p", "--output-format", "stream-json", "--trust", "--force", "--workspace", "C:/p", "--model", "sonnet", "--approve-mcps"), cursor.dropLast(1))
        assertEquals("Especificacao rapida cia", cursor.last())
        val claude = AgentCommand.headless(AgentProvider.CLAUDE, "claude", "x", "C:/p", null, emptyList())
        assertEquals(listOf("claude", "-p", "--output-format", "stream-json", "--verbose", "--permission-mode", "bypassPermissions", "x"), claude)
    }

    @Test
    fun `no modo so leitura o CLI nao ganha permissao para alterar nada, nem pelos argumentos extras`() {
        val extra = listOf("--approve-mcps", "--force", "--yolo", "--permission-mode", "bypassPermissions", "--sandbox=disabled", "--dangerously-skip-permissions")
        val cursor = AgentCommand.headless(AgentProvider.CURSOR, "agent", "x", "C:/p", null, extra, readOnly = true)
        assertEquals(listOf("agent", "-p", "--output-format", "stream-json", "--trust", "--mode", "ask", "--workspace", "C:/p", "--approve-mcps", "x"), cursor)
        val claude = AgentCommand.headless(AgentProvider.CLAUDE, "claude", "x", "C:/p", "sonnet", extra, readOnly = true)
        assertEquals(
            listOf("claude", "-p", "--output-format", "stream-json", "--verbose", "--allowedTools", "Read,Grep,Glob", "--permission-mode", "dontAsk", "--model", "sonnet", "--approve-mcps", "x"),
            claude,
        )
    }

    @Test
    fun `monta o comando do terminal com as variáveis da feature na sintaxe do shell`() {
        val env = mapOf("SPECIFY_FEATURE" to "001-x")
        assertEquals("\$env:SPECIFY_FEATURE='001-x'; & 'agent' 'go'", AgentCommand.terminal(AgentProvider.CURSOR, "agent", "go", null, emptyList(), ShellKind.POWERSHELL, env))
        assertEquals(
            "SPECIFY_FEATURE='001-x' 'claude' '--permission-mode' 'acceptEdits' 'go'",
            AgentCommand.terminal(AgentProvider.CLAUDE, "claude", "go", null, emptyList(), ShellKind.POSIX, env),
        )
        assertEquals(listOf("--a", "b c", "d"), AgentCommand.splitArgs("--a \"b c\" d"))
    }

    @Test
    fun `no terminal a IA nunca ganha permissao total, nem pelos argumentos extras`() {
        val extra = listOf("--approve-mcps", "--force", "-f", "--yolo", "--permission-mode", "bypassPermissions", "--permission-mode=bypassPermissions", "--dangerously-skip-permissions")
        assertEquals("'agent' '--approve-mcps' 'go'", AgentCommand.terminal(AgentProvider.CURSOR, "agent", "go", null, extra, ShellKind.POSIX))
        assertEquals(
            "'claude' '--permission-mode' 'acceptEdits' '--approve-mcps' 'go'",
            AgentCommand.terminal(AgentProvider.CLAUDE, "claude", "go", null, extra, ShellKind.POSIX),
        )
        assertEquals(
            "& 'agent' '-p' '--trust' '--mode' 'ask' '--output-format' 'text' '--approve-mcps' 'OK' | Out-File -Encoding utf8 'tomahawk/testes/t.md'",
            AgentCommand.terminal(AgentProvider.CURSOR, "agent", "OK", null, extra, ShellKind.POWERSHELL, interactive = false, output = "tomahawk/testes/t.md"),
        )
        assertEquals(
            "'claude' '-p' '--allowedTools' 'Read,Grep,Glob' '--permission-mode' 'dontAsk' '--approve-mcps' 'OK' > 't.md'",
            AgentCommand.terminal(AgentProvider.CLAUDE, "claude", "OK", null, extra, ShellKind.POSIX, interactive = false, output = "t.md"),
        )
    }

    @Test
    fun `o prompt aponta o comando, o arquivo de entrada e a feature`() {
        val prompt = MissionPrompt.build(
            Mission(SddStep.PLAN, "Kotlin", "specs/001-x"),
            "/speckit-plan",
            ".claude/skills/speckit-plan/SKILL.md",
            "tomahawk/pedidos/x.md",
            interactive = false,
        )
        assertTrue(prompt.contains("/speckit-plan"))
        assertTrue(prompt.contains(".claude/skills/speckit-plan/SKILL.md"))
        assertTrue(prompt.contains("tomahawk/pedidos/x.md"))
        assertTrue(prompt.contains("specs/001-x"))
        assertTrue(prompt.contains("non-interactive"))
        val specify = MissionPrompt.build(Mission(SddStep.SPECIFY, "", null), "/speckit.specify", null, null, interactive = true)
        assertTrue(specify.contains("empty"))
        assertFalse(specify.contains("non-interactive"))
    }

    @Test
    fun `lê o veredito da convergência e da análise`() {
        assertEquals(Verdict.CONVERGED, Verdict.of(SddStep.CONVERGE, "✅ Converged — the implementation satisfies the spec"))
        assertEquals(Verdict.TASKS_APPENDED, Verdict.of(SddStep.CONVERGE, "Found 2 gaps. Tasks appended to tasks.md; run implement until it reports Converged."))
        assertEquals(Verdict.ISSUES, Verdict.of(SddStep.ANALYZE, "| A1 | CRITICAL | spec vs plan |"))
        assertEquals(null, Verdict.of(SddStep.PLAN, "Converged"))
    }

    @Test
    fun `o registro volta a ser lido com os mesmos dados`() {
        val entry = Transcript.Entry(
            step = SddStep.CONVERGE,
            invocation = "/speckit-converge",
            feature = "specs/001-x",
            status = RunStatus.OK,
            error = null,
            verdict = Verdict.CONVERGED,
            startedAt = 1_762_250_000_000,
            durationSeconds = 42,
            provider = "Cursor",
            model = "gpt-5",
            arguments = "",
            prompt = "p",
            files = listOf("a.kt", "b.kt"),
            steps = listOf("Lendo spec.md"),
            answer = "Converged",
            stderr = "",
            command = listOf("agent", "-p", "p"),
        )
        val record = Transcript.parse(Path.of("x.md"), Transcript.render(entry).lines().take(Transcript.HEADER_LINES))!!
        assertEquals(SddStep.CONVERGE, record.step)
        assertEquals("specs/001-x", record.feature)
        assertEquals(RunStatus.OK, record.status)
        assertEquals(Verdict.CONVERGED, record.verdict)
        assertEquals(1_762_250_000_000, record.startedAt)
        assertEquals(42, record.durationSeconds)
        assertEquals(2, record.filesChanged)
        assertEquals("Avaliação de Danos (/speckit-converge)", record.title)

        val failed = Transcript.render(Transcript.Entry(
            SddStep.PLAN, "/speckit-plan", null, RunStatus.FAILED, "timeout\nmais", null, 0, 0, "Claude Code", null, "", "", emptyList(), emptyList(), "", "", emptyList(),
        ))
        val parsed = Transcript.parse(Path.of("y.md"), failed.lines())!!
        assertEquals(RunStatus.FAILED, parsed.status)
        assertEquals("timeout", parsed.error)
        assertEquals(null, parsed.feature)
    }
}
