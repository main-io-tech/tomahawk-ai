package com.mio.tomahawk.speckit

import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.RunStatus
import com.mio.tomahawk.run.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class SddPipelineTest {

    private val dir = Path.of("specs/001-x")

    private fun spec(pending: Int = 0, clarifications: Int = 0) = SpecSummary(
        dir.resolve("spec.md"),
        listOf(UserStory(1, "A", "P1", 3)),
        2,
        1,
        clarifications,
        List(pending) { Marker(dir.resolve("spec.md"), it, "[NEEDS CLARIFICATION]") },
    )

    private fun tasks(done: Int, total: Int) = TasksSummary(
        dir.resolve("tasks.md"),
        listOf(TaskPhase("Phase 1", 0, List(total) { TaskItem("T00$it", it < done, false, null, "task", it) })),
    )

    private fun feature(spec: SpecSummary? = null, plan: Boolean = false, tasks: TasksSummary? = null, modified: Long = 1_000L) = FeatureReport(
        dir,
        spec,
        if (plan) PlanSummary(dir.resolve("plan.md"), emptyList(), emptyList()) else null,
        tasks,
        emptyList(),
        emptyList(),
        modified,
    )

    private fun run(step: SddStep, at: Long, verdict: Verdict? = null) =
        RunRecord(Path.of("r.md"), step, "specs/001-x", RunStatus.OK, null, verdict, at, 10, "Cursor", 0, step.codename)

    private fun List<StepStatus>.of(step: SddStep) = first { it.step == step }

    @Test
    fun `projeto novo começa pela constituição`() {
        val statuses = SddPipeline.evaluate(constitutionReady = false, feature = null, available = emptySet())
        assertEquals(SddStep.CONSTITUTION, statuses.single { it.next }.step)
        assertEquals(StepState.BLOCKED, statuses.of(SddStep.PLAN).state)
    }

    @Test
    fun `com constituição e sem feature, a próxima é designar o alvo`() {
        val statuses = SddPipeline.evaluate(true, null, emptySet())
        assertEquals(SddStep.SPECIFY, statuses.single { it.next }.step)
    }

    @Test
    fun `pendências na spec recomendam o reconhecimento antes do plano`() {
        val statuses = SddPipeline.evaluate(true, feature(spec(pending = 2)), emptySet())
        assertEquals(StepState.RECOMMENDED, statuses.of(SddStep.CLARIFY).state)
        assertEquals(SddStep.CLARIFY, statuses.single { it.next }.step)
    }

    @Test
    fun `spec limpa leva ao plano e o plano às tarefas`() {
        assertEquals(SddStep.PLAN, SddPipeline.evaluate(true, feature(spec()), emptySet()).single { it.next }.step)
        assertEquals(SddStep.TASKS, SddPipeline.evaluate(true, feature(spec(), plan = true), emptySet()).single { it.next }.step)
    }

    @Test
    fun `tarefas parciais deixam o lançamento em andamento e a convergência bloqueada`() {
        val statuses = SddPipeline.evaluate(true, feature(spec(), true, tasks(done = 1, total = 3)), emptySet())
        assertEquals(StepState.PARTIAL, statuses.of(SddStep.IMPLEMENT).state)
        assertEquals(SddStep.IMPLEMENT, statuses.single { it.next }.step)
        assertEquals(StepState.BLOCKED, statuses.of(SddStep.CONVERGE).state)
    }

    @Test
    fun `convergência só vale se rodou depois da última mudança nas tarefas`() {
        val done = feature(spec(), true, tasks(3, 3), modified = 5_000L)
        val stale = SddPipeline.evaluate(true, done, emptySet(), listOf(run(SddStep.CONVERGE, 4_000L, Verdict.CONVERGED)))
        assertEquals(StepState.READY, stale.of(SddStep.CONVERGE).state)
        val fresh = SddPipeline.evaluate(true, done, emptySet(), listOf(run(SddStep.CONVERGE, 6_000L, Verdict.CONVERGED)))
        assertEquals(StepState.DONE, fresh.of(SddStep.CONVERGE).state)
        assertEquals(null, fresh.firstOrNull { it.next })
    }

    @Test
    fun `comandos que não estão instalados ficam indisponíveis`() {
        val statuses = SddPipeline.evaluate(true, null, available = setOf(SddStep.CONSTITUTION, SddStep.SPECIFY))
        assertEquals(StepState.UNAVAILABLE, statuses.of(SddStep.CONVERGE).state)
        assertEquals(StepState.READY, statuses.of(SddStep.SPECIFY).state)
    }
}
