package com.mio.tomahawk.speckit

/**
 * As etapas do Spec-Driven Development do Spec Kit, na ordem do fluxo. [command] é o nome do comando sem o
 * prefixo (`specify` vira `/speckit-specify` ou `/speckit.specify`, conforme a integração); [codename] é o nome
 * da etapa na missão do Tomahawk.
 */
enum class SddStep(
    val command: String,
    val codename: String,
    val purpose: String,
    val argsHint: String,
    val optional: Boolean = false,
    val argsRequired: Boolean = false,
    val readOnly: Boolean = false,
    /** A etapa conversa com o usuário: roda melhor no terminal, onde dá para responder. */
    val interactive: Boolean = false,
) {
    CONSTITUTION(
        "constitution",
        "Regras de Engajamento",
        "Cria ou atualiza a constituição do projeto: os princípios contra os quais todas as etapas seguintes são avaliadas. Uma vez por projeto.",
        "Princípios do projeto, como: qualidade de código, testes obrigatórios, consistência de UX, requisitos de desempenho…",
    ),
    SPECIFY(
        "specify",
        "Designar Alvo",
        "Cria a especificação da feature a partir de uma descrição em linguagem natural: o quê e por quê, sem a stack técnica. Cria a pasta specs/NNN-nome e a torna a feature ativa.",
        "Descreva a feature: o que o usuário faz, por que, regras e limites. A stack técnica vai no Plano de Voo.",
        argsRequired = true,
    ),
    CLARIFY(
        "clarify",
        "Reconhecimento",
        "Faz até cinco perguntas sobre os pontos vagos da spec e grava as respostas nela. Rode antes do plano para não planejar em cima de ambiguidade.",
        "Opcional: área de foco, como \"regras de permissão e limites de comentários\".",
        optional = true,
        interactive = true,
    ),
    PLAN(
        "plan",
        "Plano de Voo",
        "Gera o plano técnico e os artefatos de design (research, data-model, contracts, quickstart) a partir da spec.",
        "Stack, arquitetura e restrições técnicas, como: Kotlin + Spring Boot, Postgres, API REST…",
    ),
    CHECKLIST(
        "checklist",
        "Pré-voo",
        "Gera checklists de qualidade dos requisitos, os \"testes unitários da spec\". Os itens são marcados pelo revisor, não pela IA.",
        "Opcional: foco do checklist, como \"segurança e permissões\" ou \"UX\".",
        optional = true,
    ),
    TASKS(
        "tasks",
        "Waypoints",
        "Quebra o plano em tarefas ordenadas por dependência: Setup, Fundação, uma fase por história de usuário e Polimento.",
        "Opcional: instruções extras para a quebra das tarefas.",
    ),
    ANALYZE(
        "analyze",
        "Simulação de Rota",
        "Análise somente leitura da consistência entre spec, plano e tarefas: conflitos, lacunas e ambiguidades, antes de implementar.",
        "Opcional: foco da análise.",
        optional = true,
        readOnly = true,
    ),
    IMPLEMENT(
        "implement",
        "Lançamento",
        "Executa as tarefas do tasks.md em ordem de dependência, respeitando os marcadores de paralelismo. Em features grandes, rode por fases.",
        "Opcional: escopo da rodada, como \"só as fases Setup e Fundação\".",
    ),
    CONVERGE(
        "converge",
        "Avaliação de Danos",
        "Confere o código contra spec, plano e tarefas. Se achar lacunas, só acrescenta tarefas ao tasks.md; repita Lançamento e Avaliação de Danos até convergir.",
        "Opcional: foco da avaliação.",
    ),
    ;

    /** O comando como o usuário digita no chat, com o separador da integração (`-` nas skills, `.` nos comandos). */
    fun invocation(separator: String = "-"): String = "/speckit$separator$command"

    val number: Int get() = ordinal + 1

    companion object {
        fun byCommand(command: String?): SddStep? = entries.firstOrNull { it.command == command?.trim()?.lowercase() }
    }
}
