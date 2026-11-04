package com.mio.tomahawk.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ArtifactHtmlTest {

    private val spec = """
        # Feature Specification: Álbuns de Fotos

        **Feature Branch**: `001-photo-albums`
        **Created**: 2026-11-04
        **Status**: Draft
        **Input**: User description: "Organizar fotos em álbuns agrupados por data, com arrastar e soltar."

        ## User Scenarios & Testing *(mandatory)*

        ### User Story 1 - Organizar fotos em álbuns (Priority: P1) 🎯 MVP

        O usuário cria álbuns e move fotos entre eles para manter a biblioteca organizada.

        **Why this priority**: É o núcleo do produto; sem isso nada mais faz sentido.

        **Independent Test**: Criar um álbum, adicionar 3 fotos e ver a prévia em ladrilhos.

        **Acceptance Scenarios**:

        1. **Given** uma biblioteca com fotos, **When** o usuário cria um álbum, **Then** o álbum aparece na página principal
        2. **Given** um álbum, **When** o usuário arrasta uma foto, **Then** a foto muda de álbum

        ### User Story 2 - Reordenar álbuns (Priority: P2)

        O usuário reorganiza os álbuns arrastando-os na página principal.

        **Independent Test**: Arrastar um álbum para o topo e recarregar a página.

        ### Edge Cases

        - O que acontece com uma foto sem data? [NEEDS CLARIFICATION: usar a data do arquivo ou pedir ao usuário?]

        ## Requirements *(mandatory)*

        ### Functional Requirements

        - **FR-001**: O sistema DEVE agrupar os álbuns por data
        - **FR-002**: O sistema DEVE permitir arrastar e soltar álbuns
        - **FR-003**: O sistema DEVE manter as fotos locais, sem upload

        ### Key Entities

        - **Álbum**: nome, data, ordem
        - **Foto**: arquivo, data, álbum

        ## Success Criteria *(mandatory)*

        - **SC-001**: O usuário encontra um álbum em menos de 10 segundos
        - **SC-002**: 95% das reorganizações funcionam na primeira tentativa

        ## Clarifications

        ### Session 2026-11-04

        - Q: Álbuns podem ser aninhados? → A: Nunca
    """.trimIndent()

    private val plan = """
        # Implementation Plan: Álbuns de Fotos

        **Branch**: `001-photo-albums` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)
        **Input**: Feature specification from `/specs/001-photo-albums/spec.md`

        ## Summary

        Aplicação web local com Vite e JavaScript puro; metadados em SQLite.

        ## Technical Context

        **Language/Version**: JavaScript ES2023, Node 22
        **Primary Dependencies**: Vite 6, better-sqlite3
        **Storage**: SQLite local
        **Testing**: Vitest + Playwright
        **Target Platform**: Navegadores modernos
        **Project Type**: web
        **Performance Goals**: 60 fps ao arrastar
        **Constraints**: [NEEDS CLARIFICATION: limite de fotos por álbum?]
        **Scale/Scope**: até 50 mil fotos

        ## Constitution Check

        | Princípio | Situação |
        |---|---|
        | Testes primeiro | ✅ |
        | Simplicidade | ✅ |

        ## Project Structure

        ```text
        src/
        ├── models/
        └── services/
        ```
    """.trimIndent()

    private val tasks = """
        # Tasks: Álbuns de Fotos

        **Input**: Design documents from `/specs/001-photo-albums/`
        **Prerequisites**: plan.md (required), spec.md (required for user stories)

        ## Phase 1: Setup (Shared Infrastructure)

        - [x] T001 Create project structure per implementation plan
        - [x] T002 [P] Configure ESLint and Prettier in `.eslintrc.json`

        ## Phase 2: Foundational (Blocking Prerequisites)

        - [x] T003 Setup SQLite schema in `src/db/schema.sql`
        - [ ] T004 [P] Create base Album model in `src/models/album.js`

        **Checkpoint**: Foundation ready

        ## Phase 3: User Story 1 - Organizar fotos em álbuns (Priority: P1) 🎯 MVP

        - [ ] T005 [P] [US1] Contract test for POST /albums in `tests/contract/albums.test.js`
        - [ ] T006 [US1] Implement AlbumService in `src/services/album-service.js`
        - [ ] T007 [US1] Drag and drop between albums [NEEDS CLARIFICATION: suportar toque?]

        ## Phase 4: User Story 2 - Reordenar álbuns (Priority: P2)

        - [ ] T008 [US2] Reorder albums on the main page
    """.trimIndent()

    private val checklist = """
        # UX Requirements Quality Checklist: Álbuns de Fotos

        **Purpose**: Validar a qualidade dos requisitos de UX
        **Created**: 2026-11-04

        ## Requirement Completeness

        - [x] CHK001 Os estados de carregamento estão definidos para a grade de fotos? [Completeness, Spec §FR-001]
        - [ ] CHK002 O comportamento ao soltar fora de um álbum está especificado? [Gap]

        ## Requirement Clarity

        - [ ] CHK003 "Rápido" está quantificado com métricas? [Clarity, Spec §SC-001]
        - [x] CHK004 A hierarquia visual dos álbuns está definida? [Ambiguity]
    """.trimIndent()

    private fun render(name: String, text: String, dark: Boolean = false) =
        ArtifactHtml.render(Path.of("specs/001-photo-albums/$name"), text, if (dark) HtmlTheme.DARK else HtmlTheme.LIGHT, "specs/001-photo-albums/$name", "function(m){}")

    @Test
    fun `a spec vira cabeçalho com metadados, painel de histórias e pontos a esclarecer`() {
        val html = render("spec.md", spec)
        assertTrue(html.contains("Especificação"))
        assertTrue(html.contains("<h1>Álbuns de Fotos</h1>"))
        assertTrue(html.contains("<b>Branch</b><code>001-photo-albums</code>") || html.contains("<b>Branch</b>001-photo-albums"))
        assertTrue(html.contains("Entrada do usuário"))
        assertTrue(html.contains("histórias de usuário"))
        assertTrue(html.contains("card story"))
        assertTrue(html.contains("Teste independente"))
        assertTrue(html.contains("2 cenário(s) de aceite"))
        assertTrue(html.contains("<mark class=\"nc\""))
        assertTrue("os cenários viram fluxos", html.contains("<div class=\"step s-given\"><span class=\"sk\">Dado</span>"))
        assertTrue(html.contains("<span class=\"b t-purple\">FR-001</span>"))
        assertTrue(html.contains("<span class=\"b t-red\">P1</span>"))
        assertFalse("o título não se repete no documento", html.substringAfter("<article").contains("<h1"))
    }

    @Test
    fun `o plano mostra o contexto técnico em cartões e os artefatos da feature`() {
        val html = render("plan.md", plan)
        assertTrue(html.contains("Contexto técnico"))
        assertTrue(html.contains("<div class=\"cell-label\">Linguagem</div>"))
        assertTrue(html.contains("cell pending"))
        assertTrue(html.contains("Artefatos da feature"))
        assertTrue(html.contains("<table>"))
    }

    @Test
    fun `as tarefas mostram progresso, fases com atalhos e etiquetas de id, paralelismo e história`() {
        val html = render("tasks.md", tasks)
        assertTrue(html.contains("<div class=\"pct\">37%</div>"))
        assertTrue(html.contains("3 de 8 tarefas concluídas"))
        assertTrue(html.contains("<span class=\"b id\">T005</span>"))
        assertTrue(html.contains("∥ P"))
        assertTrue(html.contains("<span class=\"b t-purple\">US1</span>"))
        val anchor = Regex("""<a class="row" href="#([^"]+)">""").find(html)!!.groupValues[1]
        assertTrue("o atalho da fase aponta para um título do documento", html.contains("id=\"$anchor\""))
        assertTrue(html.contains("<span class=\"txt\">"))
    }

    @Test
    fun `o checklist mostra categorias e dimensões de qualidade`() {
        val html = render("checklists/ux.md", checklist)
        assertTrue(html.contains("2 de 4 itens revisados"))
        assertTrue(html.contains("Categorias"))
        assertTrue(html.contains("<span class=\"b q\">Gap</span>"))
        assertTrue(html.contains("<span class=\"b ref\">Spec §FR-001</span>"))
        assertEquals(ArtifactKind.CHECKLIST, ArtifactKind.of(Path.of("specs/001/checklists/ux.md")))
    }

    @Test
    fun `código não ganha etiquetas e os tipos de arquivo são reconhecidos`() {
        val html = ArtifactHtml.decorate(ArtifactHtml.toHtml("Use `T001 [P]` aqui e T002 fora"))
        assertTrue(html.contains("<code>T001 [P]</code>"))
        assertTrue(html.contains("<span class=\"b id\">T002</span>"))
        assertEquals(ArtifactKind.RUN, ArtifactKind.of(Path.of("tomahawk/execucoes/20261104-plan.md")))
        assertEquals(ArtifactKind.CONSTITUTION, ArtifactKind.of(Path.of(".specify/memory/constitution.md")))
        assertTrue(ArtifactKind.handles("specs/001/spec.md"))
        assertFalse(ArtifactKind.handles("README.md"))
    }

    /** Páginas de exemplo em build/preview, para conferir o visual num navegador. */
    @Test
    fun `gera as páginas de exemplo`() {
        val dir = Path.of("build", "preview")
        Files.createDirectories(dir)
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "-dark" else ""
            Files.writeString(dir.resolve("spec$suffix.html"), render("spec.md", spec, dark))
            Files.writeString(dir.resolve("plan$suffix.html"), render("plan.md", plan, dark))
            Files.writeString(dir.resolve("tasks$suffix.html"), render("tasks.md", tasks, dark))
            Files.writeString(dir.resolve("checklist$suffix.html"), render("checklists/ux.md", checklist, dark))
        }
    }
}
