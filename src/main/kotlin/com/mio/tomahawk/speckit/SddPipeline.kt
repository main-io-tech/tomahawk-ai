package com.mio.tomahawk.speckit

import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.Verdict

enum class StepState(val label: String) {
    DONE("Concluída"),
    PARTIAL("Em andamento"),
    READY("Pronta"),
    RECOMMENDED("Recomendada"),
    OPTIONAL("Opcional"),
    BLOCKED("Bloqueada"),
    UNAVAILABLE("Indisponível"),
}

/** A situação de uma etapa na feature: [reason] explica o estado em uma linha. */
data class StepStatus(val step: SddStep, val state: StepState, val reason: String, val next: Boolean = false)

/**
 * O estado de cada etapa do SDD a partir do que foi gerado (os artefatos) e do que rodou (as execuções), e a
 * próxima etapa a disparar. O que não deixa artefato, como a análise e a convergência, vem das execuções.
 */
object SddPipeline {

    /**
     * [available] vazio quer dizer "não deu para saber": nenhuma etapa fica indisponível. [runs], as execuções da
     * feature, da mais recente para a mais antiga.
     */
    fun evaluate(
        constitutionReady: Boolean,
        feature: FeatureReport?,
        available: Set<SddStep>,
        runs: List<RunRecord> = emptyList(),
    ): List<StepStatus> {
        val statuses = SddStep.entries.map { step ->
            if (available.isNotEmpty() && step !in available) {
                StepStatus(step, StepState.UNAVAILABLE, "O comando ${step.invocation()} não está instalado neste projeto.")
            } else {
                status(step, constitutionReady, feature, runs)
            }
        }
        val next = statuses.firstOrNull { it.state == StepState.READY || it.state == StepState.PARTIAL || it.state == StepState.RECOMMENDED }
        return statuses.map { if (it === next) it.copy(next = true) else it }
    }

    private fun status(step: SddStep, constitutionReady: Boolean, feature: FeatureReport?, runs: List<RunRecord>): StepStatus {
        val spec = feature?.spec
        val tasks = feature?.tasks
        val tasksModified = feature?.tasksModified ?: 0L
        fun lastOk(target: SddStep) = runs.firstOrNull { it.step == target && it.ok }
        return when (step) {
            SddStep.CONSTITUTION ->
                if (constitutionReady) done(step, "Constituição definida em .specify/memory/constitution.md.")
                else StepStatus(step, StepState.READY, "A constituição ainda é o modelo: defina os princípios do projeto.")

            SddStep.SPECIFY -> when {
                spec == null && feature == null -> StepStatus(step, StepState.READY, "Nenhuma feature ainda: descreva a primeira.")
                spec == null -> StepStatus(step, StepState.READY, "A pasta da feature não tem spec.md.")
                spec.pending.isNotEmpty() -> done(step, "spec.md gerada, com ${spec.pending.size} ponto(s) [NEEDS CLARIFICATION].")
                else -> done(step, "spec.md com ${spec.stories.size} história(s) e ${spec.requirements} requisito(s).")
            }

            SddStep.CLARIFY -> when {
                spec == null -> blocked(step, "Precisa da spec.")
                spec.pending.isNotEmpty() -> StepStatus(step, StepState.RECOMMENDED, "${spec.pending.size} ponto(s) [NEEDS CLARIFICATION] na spec.")
                spec.clarifications > 0 -> done(step, "${spec.clarifications} pergunta(s) respondida(s) na spec.")
                else -> StepStatus(step, StepState.OPTIONAL, "Sem pontos marcados como vagos; rode se houver ambiguidade.")
            }

            SddStep.PLAN -> when {
                spec == null -> blocked(step, "Precisa da spec.")
                feature.plan != null -> done(step, "plan.md e ${feature.designDocs.size} artefato(s) de design.")
                else -> StepStatus(step, StepState.READY, "Spec pronta: defina a stack e a arquitetura.")
            }

            SddStep.CHECKLIST -> {
                val custom = feature?.customChecklists.orEmpty()
                val open = custom.sumOf { it.items.size - it.done }
                when {
                    spec == null -> blocked(step, "Precisa da spec.")
                    custom.isEmpty() -> StepStatus(step, StepState.OPTIONAL, "Nenhum checklist de qualidade gerado.")
                    open > 0 -> StepStatus(step, StepState.PARTIAL, "$open item(ns) de checklist a revisar.")
                    else -> done(step, "${custom.size} checklist(s) revisado(s).")
                }
            }

            SddStep.TASKS -> when {
                feature?.plan == null -> blocked(step, "Precisa do plano.")
                tasks != null -> done(step, "tasks.md com ${tasks.total} tarefa(s) em ${tasks.phases.size} fase(s).")
                else -> StepStatus(step, StepState.READY, "Plano pronto: quebre em tarefas.")
            }

            SddStep.ANALYZE -> {
                val run = lastOk(step)
                when {
                    tasks == null -> blocked(step, "Precisa das tarefas.")
                    run != null && run.startedAt >= tasksModified -> done(step, "Analisada depois da última mudança no tasks.md.")
                    else -> StepStatus(step, StepState.OPTIONAL, "Confira a consistência antes de implementar.")
                }
            }

            SddStep.IMPLEMENT -> when {
                tasks == null -> blocked(step, "Precisa das tarefas.")
                tasks.total == 0 -> StepStatus(step, StepState.READY, "tasks.md sem tarefas marcáveis.")
                tasks.done == tasks.total -> done(step, "${tasks.total} tarefa(s) concluída(s).")
                tasks.done > 0 -> StepStatus(step, StepState.PARTIAL, "${tasks.done} de ${tasks.total} tarefa(s) concluída(s).")
                else -> StepStatus(step, StepState.READY, "${tasks.total} tarefa(s) a executar.")
            }

            SddStep.CONVERGE -> {
                val run = lastOk(step)
                when {
                    tasks == null || tasks.done == 0 -> blocked(step, "Rode o Lançamento antes.")
                    tasks.done < tasks.total -> blocked(step, "Ainda há ${tasks.total - tasks.done} tarefa(s) abertas.")
                    run != null && run.verdict == Verdict.CONVERGED && run.startedAt >= tasksModified ->
                        done(step, "Convergido: a implementação atende spec, plano e tarefas.")
                    else -> StepStatus(step, StepState.READY, "Confira a implementação contra os artefatos.")
                }
            }
        }
    }

    private fun done(step: SddStep, reason: String) = StepStatus(step, StepState.DONE, reason)

    private fun blocked(step: SddStep, reason: String) = StepStatus(step, StepState.BLOCKED, reason)
}
