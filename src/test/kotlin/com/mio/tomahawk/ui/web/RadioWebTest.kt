package com.mio.tomahawk.ui.web

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mio.tomahawk.delivery.DeliveryRequest
import com.mio.tomahawk.delivery.DeliveryResult
import com.mio.tomahawk.delivery.LocalState
import com.mio.tomahawk.delivery.PrComment
import com.mio.tomahawk.delivery.PrRadarService
import com.mio.tomahawk.delivery.PrThread
import com.mio.tomahawk.delivery.PullRequestGateway
import com.mio.tomahawk.delivery.PullRequestRef
import com.mio.tomahawk.delivery.RadarSnapshot
import com.mio.tomahawk.delivery.RemoteInfo
import com.mio.tomahawk.delivery.ThreadKind
import com.mio.tomahawk.delivery.WatchedPr
import java.nio.file.Files
import java.nio.file.Path

/** Monta o Rádio sobre um PR de exemplo e grava a página em build/preview. */
class RadioWebTest : BasePlatformTestCase() {

    private class FakeGateway(private val snap: RadarSnapshot) : PullRequestGateway {
        override fun local(project: Project): LocalState? = null
        override fun remote(project: Project, branch: String?): RemoteInfo = error("sem rede")
        override fun deliver(project: Project, request: DeliveryRequest, step: (String) -> Unit): DeliveryResult = error("sem rede")
        override fun changesContext(project: Project) = ""
        override fun branchContext(project: Project, base: String) = ""
        override fun radar(project: Project) = snap
        override fun reply(project: Project, number: Int, thread: PrThread, body: String): PrComment = error("sem rede")
        override fun login(project: Project) = false
    }

    private fun comment(key: String, author: String, body: String, at: String) = PrComment(key, key.hashCode().toLong(), author, body, at, "https://x/$key")

    private val snap = RadarSnapshot(
        "rafa", "mio/tomahawk",
        listOf(
            WatchedPr(
                PullRequestRef(42, "https://x/42", "Rádio: comentários dos PRs", "main", false), "feature/radio", "2026-11-04T12:00:00Z",
                listOf(
                    PrThread(
                        "t:1", ThreadKind.CODE,
                        listOf(
                            comment("c:1", "ana", "Por que não usar um `Map` aqui?\n\n```kotlin\nval byKey = threads.associateBy { it.key }\n```", "2026-11-04T10:00:00Z"),
                            comment("c:2", "rafa", "Boa, vou trocar.", "2026-11-04T11:00:00Z"),
                            comment("c:3", "ana", "Lembra de cobrir com teste também.", "2026-11-04T11:30:00Z"),
                        ),
                        1, "src/main/kotlin/com/mio/tomahawk/delivery/PrRadarService.kt", 130,
                        "@@ -120,12 +120,13 @@ class PrRadarService {\n     }\n \n-    fun unread(thread: PrThread): Int =\n-        thread.comments.count { it.key !in seen }\n+    /** Os comentários de outros que o usuário ainda não viu. */\n+    fun unread(thread: PrThread, me: String): Int =\n+        thread.comments.count { isNew(it, me) }\n \n     fun markSeen(threads: List<PrThread>) {\n         synchronized(lock) {\n             threads.forEach { thread -> saved.seen += thread.comments.map { it.key } }\n         }\n+        publish()\n     }",
                    ),
                    PrThread("i:2", ThreadKind.CONVERSATION, listOf(comment("i:2", "bia", "Pode adicionar testes para o Rádio?", "2026-11-04T12:00:00Z")), 2),
                    PrThread(
                        "t:3", ThreadKind.CODE, listOf(comment("c:4", "bia", "Typo", "2026-11-04T09:00:00Z"), comment("c:5", "rafa", "Corrigido.", "2026-11-04T09:10:00Z")),
                        4, "plugin.xml", 3, "@@ -1,3 +1,3 @@\n <idea-plugin>\n-  <name>Tomahwk</name>\n+  <name>Tomahawk</name>", resolved = true,
                    ),
                ),
            ),
        ),
    )

    fun testRendersTheConversations() {
        ExtensionTestUtil.maskExtensions(PullRequestGateway.EP, listOf(FakeGateway(snap)), testRootDisposable)
        val radar = PrRadarService.getInstance(project)
        PrRadarService::class.java.getDeclaredField("snapshot").apply { isAccessible = true }.set(radar, snap)
        val tab = RadioWeb(project)
        Disposer.register(testRootDisposable, tab)
        RadioWeb::class.java.getDeclaredField("filter").apply { isAccessible = true }.set(tab, "all")
        val html = tab.html()
        val out = Path.of(System.getProperty("user.dir"), "build", "preview")
        Files.createDirectories(out)
        Files.writeString(out.resolve("web-radio.html"), html)
        assertTrue(html.contains("class=\"code folded\""))
        assertTrue(html.contains("data-fold=\"t|t:1\""))
        assertTrue(html.contains("done folded\" data-form=\"reply\" data-fold=\"t|t:3\""))
        assertTrue(html.contains("<td class=\"gt\"><span>121</span><span>121</span></td>"))
    }
}
