package com.mio.tomahawk.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DiagramsTest {

    private val dataModel = """
        # Data Model: Álbuns de Fotos

        ## Entities

        ### User

        Pessoa dona da biblioteca de fotos.

        | Field | Type | Constraints | Description |
        |-------|------|-------------|-------------|
        | id | UUID | PK | Identificador |
        | email | string | unique, required | E-mail de login |
        | display_name | string | required, max 80 | Nome exibido |
        | created_at | timestamp | not null, default now() | Criação |

        **Relationships**:
        - User has many Album (one-to-many)

        ### Album

        Agrupa fotos por data.

        - **id**: UUID (primary key)
        - **owner_id**: UUID, required — foreign key → User
        - **title**: string, required, max 100
        - **status**: enum (draft | published | archived), default `draft`
        - **position**: integer, indexed — ordem na página principal

        **Validation Rules**:
        - `title` não pode ser vazio
        - `position` é única por usuário

        **State Transitions**: draft → published → archived

        ### Photo

        | Campo | Tipo | Restrições |
        |---|---|---|
        | id | UUID | PK |
        | album_id | UUID | FK, required |
        | path | text | required |
        | taken_at | datetime | optional |
        | tags | string[] | |

        ## Relationships

        - Album has many Photo (1:N)
    """.trimIndent()

    private val plan = """
        # Implementation Plan: Álbuns de Fotos

        **Branch**: `001-photo-albums` | **Date**: 2026-11-04

        ## Technical Context

        **Language/Version**: TypeScript 5.6, Node 22
        **Primary Dependencies**: Fastify, Prisma, React 19
        **Storage**: PostgreSQL 16
        **Testing**: Vitest, Playwright
        **Target Platform**: Navegadores modernos
        **Project Type**: web
        **Performance Goals**: p95 < 200 ms
        **Constraints**: offline-first
        **Scale/Scope**: 10 mil usuários

        ## Project Structure

        ```text
        backend/
        ├── src/
        │   ├── models/      # entidades Prisma
        │   ├── services/    # regras de negócio
        │   └── api/         # rotas Fastify
        └── tests/
            ├── contract/
            └── integration/
        frontend/
        ├── src/
        │   ├── components/
        │   ├── pages/
        │   └── services/
        └── tests/
        ```
    """.trimIndent()

    private val research = """
        # Research: Álbuns de Fotos

        ## Fluxo de upload

        ```mermaid
        flowchart LR
            A[Usuário escolhe fotos] --> B{Formato válido?}
            B -- sim --> C[Gera miniatura]
            B -- não --> D[Mostra erro]
            C --> E[(Salva no álbum)]
        ```

        ## Sequência de reordenação

        ```mermaid
        sequenceDiagram
            participant U as Usuário
            participant F as Front-end
            participant A as API
            U->>F: Arrasta o álbum
            F->>A: PATCH /albums/{id}/position
            A-->>F: 200 OK
            F-->>U: Atualiza a grade
        ```

        ## Desenho antigo

        ```text
        +---------+      +---------+
        | Browser | ---> |   API   |
        +---------+      +---------+
        ```
    """.trimIndent()

    private val openApi = """
        openapi: 3.1.0
        info:
          title: Photo Albums API
          version: 1.0.0
          description: API dos álbuns de fotos
        paths:
          /albums:
            get:
              tags: [Albums]
              summary: Lista os álbuns do usuário
              parameters:
                - name: page
                  in: query
                  schema: { type: integer }
              responses:
                "200":
                  description: Lista de álbuns
                  content:
                    application/json:
                      schema: { type: array, items: { ${'$'}ref: '#/components/schemas/Album' } }
            post:
              tags: [Albums]
              summary: Cria um álbum
              requestBody:
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/AlbumInput' }
              responses:
                "201": { description: Criado }
                "422": { description: Dados inválidos }
          /albums/{id}:
            delete:
              tags: [Albums]
              summary: Remove um álbum
              parameters:
                - name: id
                  in: path
                  required: true
                  schema: { type: string, format: uuid }
              responses:
                "204": { description: Removido }
                "404": { description: Não encontrado }
        components:
          schemas:
            Album:
              type: object
              required: [id, title]
              properties:
                id: { type: string, format: uuid }
                title: { type: string }
                photos: { type: array, items: { ${'$'}ref: '#/components/schemas/Photo' } }
            Photo:
              type: object
              properties:
                id: { type: string, format: uuid }
                path: { type: string }
            AlbumInput:
              type: object
              required: [title]
              properties:
                title: { type: string, description: Nome do álbum }
                status: { type: string, enum: [draft, published] }
    """.trimIndent()

    @Test
    fun `lê entidades em tabela e em lista, chaves, relacionamentos, regras e estados`() {
        val model = DataModel.parse(dataModel)
        assertEquals(listOf("User", "Album", "Photo"), model.entities.map { it.name })
        val user = model.entities[0]
        assertTrue(user.fields.first { it.name == "id" }.primaryKey)
        assertTrue(user.fields.first { it.name == "email" }.unique)
        val album = model.entities[1]
        assertEquals("User", album.fields.first { it.name == "owner_id" }.foreignKey)
        assertEquals(listOf("draft", "published", "archived"), album.fields.first { it.name == "status" }.enumValues)
        assertTrue(album.fields.first { it.name == "position" }.indexed)
        assertEquals(2, album.rules.size)
        assertEquals(listOf("draft" to "published", "published" to "archived"), album.transitions.map { it.from to it.to })
        val photo = model.entities[2]
        assertEquals("Album", photo.fields.first { it.name == "album_id" }.foreignKey)
        assertEquals("string[]", photo.fields.first { it.name == "tags" }.type)
        assertTrue(model.relations.any { it.from == "User" && it.to == "Album" && it.cardinality == Cardinality.ONE_TO_MANY })
        assertTrue(model.relations.any { it.from == "Album" && it.to == "Photo" })
        val er = DataModel.mermaidEr(model)
        assertTrue(er.contains("User ||--o{ Album"))
        assertTrue(er.contains("UUID id PK"))
    }

    @Test
    fun `o modelo de dados ganha diagrama ER, cartões de tabela e ciclo de vida`() {
        val html = ArtifactHtml.render(Path.of("specs/001/data-model.md"), dataModel, HtmlTheme.LIGHT, bridge = "function(m){}")
        assertTrue(html.contains("Diagrama ER gerado do modelo"))
        assertTrue(html.contains("class=\"table-card\""))
        assertTrue(html.contains("FK → User"))
        assertTrue(html.contains("Ciclo de vida"))
        assertTrue(html.contains("stateDiagram-v2"))
        assertTrue("a biblioteca entra quando há diagramas", html.contains("mermaid.initialize") && html.length > 3_000_000)
    }

    @Test
    fun `o plano ganha a árvore do projeto e o diagrama em camadas`() {
        val entries = Architecture.parseTree(plan.substringAfter("```text\n").substringBefore("```"))
        assertEquals("backend", entries.first().name)
        assertEquals(0, entries.first { it.name == "frontend" }.depth)
        assertEquals(2, entries.first { it.name == "models" }.depth)
        assertEquals("entidades Prisma", entries.first { it.name == "models" }.note)
        val html = ArtifactHtml.render(Path.of("specs/001/plan.md"), plan, HtmlTheme.LIGHT)
        assertTrue(html.contains("class=\"arch\""))
        assertTrue(html.contains("Interface"))
        assertTrue(html.contains("PostgreSQL 16"))
        assertTrue(html.contains("class=\"ftree\""))
    }

    @Test
    fun `blocos mermaid viram diagramas e o desenho em ASCII ganha moldura`() {
        val html = ArtifactHtml.render(Path.of("specs/001/research.md"), research, HtmlTheme.DARK)
        assertEquals(2, Regex("""class="diagram"""").findAll(html).count())
        assertTrue(html.contains("Fluxograma"))
        assertTrue(html.contains("Diagrama de sequência"))
        assertTrue(html.contains("A[Usuário escolhe fotos] --&gt; B{Formato válido?}"))
        assertTrue(html.contains("class=\"ascii\""))
    }

    @Test
    fun `o contrato OpenAPI vira endpoints e schemas`() {
        val spec = OpenApi.parse(openApi, json = false)
        assertNotNull(spec)
        assertEquals(listOf("GET", "POST", "DELETE"), spec!!.endpoints.map { it.method })
        assertEquals("Album[]", spec.endpoints[0].responses.first().second.substringAfter("→ "))
        assertEquals(3, spec.schemas.entities.size)
        assertTrue(spec.schemas.relations.any { it.from == "Album" && it.to == "Photo" })
        val html = ArtifactHtml.render(Path.of("specs/001/contracts/albums.yaml"), openApi, HtmlTheme.LIGHT)
        assertTrue(html.contains("Contrato de API"))
        assertTrue(html.contains("<span class=\"method\">DELETE</span>"))
        assertTrue(html.contains("<span class=\"param\">{id}</span>"))
        assertTrue(ArtifactKind.handles("specs/001/contracts/albums.yaml"))
        assertFalse(ArtifactKind.handles("config/app.yaml"))
    }

    @Test
    fun `cenários Given When Then viram fluxos e as fases viram fluxo de execução`() {
        val spec = "## Cenários\n\n1. **Given** um álbum, **When** o usuário arrasta, **Then** a foto muda\n2. **Given** x, **When** y, **Then** z\n"
        val html = ArtifactHtml.render(Path.of("specs/001/spec.md"), spec, HtmlTheme.LIGHT)
        assertEquals(2, Regex("""class="flow"""").findAll(html).count())
        assertTrue(html.contains("<span class=\"sk\">Quando</span>"))
        val tasks = "## Phase 1: Setup\n- [x] T001 a\n## Phase 2: Foundational\n- [ ] T002 b\n## Phase 3: User Story 1 - A\n- [ ] T003 [US1] c\n## Phase 4: User Story 2 - B\n- [ ] T004 [US2] d\n## Phase 5: Polish\n- [ ] T005 e\n"
        val flow = ArtifactHtml.render(Path.of("specs/001/tasks.md"), tasks, HtmlTheme.LIGHT)
        assertTrue(flow.contains("Fluxo de execução das fases"))
        assertTrue(flow.contains("P1 --&gt; P2") && flow.contains("P1 --&gt; P3") && flow.contains("P3 --&gt; P4"))
    }

    @Test
    fun `gera as páginas de exemplo com diagramas`() {
        val dir = Path.of("build", "preview")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("data-model.html"), ArtifactHtml.render(Path.of("specs/001/data-model.md"), dataModel, HtmlTheme.LIGHT, "specs/001/data-model.md"))
        Files.writeString(dir.resolve("data-model-dark.html"), ArtifactHtml.render(Path.of("specs/001/data-model.md"), dataModel, HtmlTheme.DARK, "specs/001/data-model.md"))
        Files.writeString(dir.resolve("plan-arch.html"), ArtifactHtml.render(Path.of("specs/001/plan.md"), plan, HtmlTheme.DARK, "specs/001/plan.md"))
        Files.writeString(dir.resolve("research.html"), ArtifactHtml.render(Path.of("specs/001/research.md"), research, HtmlTheme.LIGHT, "specs/001/research.md"))
        Files.writeString(dir.resolve("contract.html"), ArtifactHtml.render(Path.of("specs/001/contracts/albums.yaml"), openApi, HtmlTheme.DARK, "specs/001/contracts/albums.yaml"))
    }
}
