package com.mio.tomahawk.delivery

import com.google.gson.JsonParser
import com.mio.tomahawk.delivery.github.RadarParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {

    private val graphQl = """
        {
          "reviewThreads": {"nodes": [
            {"id": "PRRT_1", "path": "src/App.kt", "line": null, "originalLine": 12, "isResolved": false, "isOutdated": true,
             "comments": {"nodes": [
               {"databaseId": 10, "body": "Por que não usar um Map?", "createdAt": "2026-11-04T10:00:00Z", "url": "https://x/10", "author": {"login": "ana"}, "diffHunk": "@@ -1 +1 @@\n-a\n+b"},
               {"databaseId": 11, "body": "Pensei nisso.", "createdAt": "2026-11-04T11:00:00Z", "url": "https://x/11", "author": {"login": "rafa"}, "diffHunk": "@@"}
             ]}},
            {"id": "PRRT_2", "path": "README.md", "line": 3, "originalLine": 3, "isResolved": true, "isOutdated": false,
             "comments": {"nodes": [
               {"databaseId": 20, "body": "Typo", "createdAt": "2026-11-04T09:00:00Z", "url": "https://x/20", "author": {"login": "bia"}, "diffHunk": "@@"}
             ]}}
          ]},
          "comments": {"nodes": [
            {"databaseId": 30, "body": "Pode adicionar testes?", "createdAt": "2026-11-04T12:00:00Z", "url": "https://x/30", "author": {"login": "bia"}}
          ]},
          "reviews": {"nodes": [
            {"databaseId": 40, "body": "", "state": "CHANGES_REQUESTED", "submittedAt": "2026-11-04T13:00:00Z", "createdAt": "x", "url": "https://x/40", "author": {"login": "ana"}},
            {"databaseId": 41, "body": "", "state": "COMMENTED", "submittedAt": "2026-11-04T13:30:00Z", "createdAt": "x", "url": "https://x/41", "author": {"login": "ana"}},
            {"databaseId": 42, "body": "rascunho", "state": "PENDING", "submittedAt": null, "createdAt": "x", "url": "https://x/42", "author": {"login": "ana"}}
          ]}
        }
    """.trimIndent()

    private val threads = RadarParser.threads(JsonParser.parseString(graphQl).asJsonObject)

    @Test
    fun `le as conversas de codigo, da conversa geral e das revisoes`() {
        assertEquals(listOf("r:40", "i:30", "t:10", "t:20"), threads.map { it.key })
        val code = threads.first { it.key == "t:10" }
        assertEquals(ThreadKind.CODE, code.kind)
        assertEquals(10L, code.replyTo)
        assertEquals("src/App.kt", code.path)
        assertEquals(12, code.line)
        assertTrue(code.outdated)
        assertEquals(listOf("c:10", "c:11"), code.comments.map { it.key })
        assertEquals("@@ -1 +1 @@\n-a\n+b", code.diffHunk)
        assertEquals("Solicitou alterações", threads.first { it.key == "r:40" }.reviewLabel)
    }

    @Test
    fun `aguarda resposta quando a ultima palavra e de outra pessoa e a conversa esta aberta`() {
        val byKey = threads.associateBy { it.key }
        assertFalse(byKey.getValue("t:10").awaitsReply("rafa"))
        assertTrue(byKey.getValue("t:10").awaitsReply("ana"))
        assertFalse("resolvida", byKey.getValue("t:20").awaitsReply("rafa"))
        assertTrue(byKey.getValue("i:30").awaitsReply("Rafa"))
        assertEquals(listOf("ana"), byKey.getValue("t:10").fromOthers("RAFA").map { it.author })
    }

    @Test
    fun `contexto da resposta traz o PR, o trecho, a conversa e as anotacoes`() {
        val pr = WatchedPr(PullRequestRef(7, "https://github.com/o/r/pull/7", "feat: albuns", "develop", false), "001-albuns", "", threads)
        val text = ReplyContext.render(pr, threads.first { it.key == "t:10" }, "rafa", "concordo, vou trocar")
        assertTrue(text, text.startsWith("# PR #7: feat: albuns"))
        assertTrue(text, "branch `001-albuns` → `develop`" in text)
        assertTrue(text, "## Código em `src/App.kt`, linha 12 (desatualizada" in text)
        assertTrue(text, "```diff\n@@ -1 +1 @@\n-a\n+b\n```" in text)
        assertTrue(text, "### @ana em 2026-11-04T10:00:00Z\n\nPor que não usar um Map?" in text)
        assertTrue(text, "### @rafa (autor do PR)" in text)
        assertTrue(text, "## Anotações do autor para a resposta\n\nconcordo, vou trocar" in text)

        val prompt = DeliveryWriter.replyPrompt("tomahawk/pedidos/r.md", "rafa", 7, "001-albuns", hasNotes = true)
        assertTrue("@rafa" in prompt && "#7" in prompt && "git show 001-albuns:path" in prompt && "notes of the author" in prompt)
    }
}
