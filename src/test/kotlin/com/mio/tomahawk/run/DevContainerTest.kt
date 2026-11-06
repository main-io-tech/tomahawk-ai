package com.mio.tomahawk.run

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevContainerTest {

    private val root = "C:\\Users\\ana\\projeto"

    @Test
    fun `o devcontainer padrao passa na conferencia e protege o git`() {
        val text = DevContainer.template(git = true)
        assertEquals(emptyList<String>(), DevContainer.audit(text))
        val json = JsonParser.parseString(DevContainer.stripJsonc(text)).asJsonObject
        val mounts = json.getAsJsonArray("mounts").map { it.asString }
        assertTrue(mounts.any { "/.git/config" in it && it.endsWith("readonly") })
        assertTrue(mounts.any { "/.git/hooks" in it && it.endsWith("readonly") })
        assertTrue("claude-code" in json.get("postCreateCommand").asString)
        assertFalse(DevContainer.template(git = false).contains(".git/hooks"))
        val preview = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "preview")
        java.nio.file.Files.createDirectories(preview)
        java.nio.file.Files.writeString(preview.resolve("devcontainer.json"), text)
    }

    @Test
    fun `a conferencia recusa o que deixa o container alcancar a maquina`() {
        val text = """
            // comentário
            {
              "image": "x", /* outro */
              "initializeCommand": "curl x | sh",
              "privileged": true,
              "capAdd": ["SYS_ADMIN"],
              "runArgs": ["--network=host", "-v", "/:/host", "--init"],
              "features": { "ghcr.io/devcontainers/features/docker-outside-of-docker:1": {} },
              "mounts": [
                "source=/var/run/docker.sock,target=/var/run/docker.sock,type=bind",
                "source=${'$'}{localEnv:HOME}/.ssh,target=/root/.ssh,type=bind",
                "source=${'$'}{localWorkspaceFolder}/../outro,target=/o,type=bind",
                "source=${'$'}{localWorkspaceFolder}/cache,target=/c,type=bind",
                { "type": "volume", "source": "dados", "target": "/d" },
              ],
            }
        """.trimIndent()
        val problems = DevContainer.audit(text)
        assertTrue(problems.any { "initializeCommand" in it })
        assertTrue(problems.any { "privilegiado" in it })
        assertTrue(problems.any { "capAdd" in it })
        assertTrue(problems.any { "--network=host" in it })
        assertTrue(problems.any { it.endsWith(" -v") })
        assertFalse(problems.any { "--init" in it })
        assertTrue(problems.any { "docker-outside-of-docker" in it })
        assertTrue(problems.any { "docker.sock" in it })
        assertTrue(problems.any { ".ssh" in it })
        assertTrue(problems.any { "../outro" in it })
        assertFalse(problems.any { "/cache" in it || "dados" in it })
        assertEquals(listOf("o devcontainer.json não é um JSON válido"), DevContainer.audit("{ não"))
    }

    @Test
    fun `o container de pe so pode montar o projeto, com o git so leitura`() {
        val ok = inspect(
            """{"Type":"bind","Source":"/run/desktop/mnt/host/c/Users/ana/projeto","Destination":"/workspaces/projeto","RW":true}""",
            """{"Type":"bind","Source":"/run/desktop/mnt/host/c/Users/ana/projeto/.git/config","Destination":"/workspaces/projeto/.git/config","RW":false}""",
            """{"Type":"bind","Source":"C:\\Users\\ana\\projeto/.git/hooks","Destination":"/workspaces/projeto/.git/hooks","RW":false}""",
            """{"Type":"volume","Name":"tomahawk-claude","Source":"/var/lib/docker/volumes/x","Destination":"/home/vscode/.claude","RW":true}""",
        )
        assertEquals(emptyList<String>(), DevContainer.isolation(ok, root, git = true))

        val bad = inspect(
            """{"Type":"bind","Source":"/run/desktop/mnt/host/c/Users/ana/projeto","Destination":"/workspaces/projeto","RW":true}""",
            """{"Type":"bind","Source":"/run/desktop/mnt/host/c/Users/ana/projeto/.git/config","Destination":"/workspaces/projeto/.git/config","RW":true}""",
            """{"Type":"bind","Source":"/var/run/docker.sock","Destination":"/var/run/docker.sock","RW":true}""",
            """{"Type":"bind","Source":"/run/desktop/mnt/host/c/Users/ana","Destination":"/home","RW":true}""",
            host = """"Privileged":true,"NetworkMode":"host","CapAdd":["SYS_ADMIN"],"SecurityOpt":["seccomp=unconfined"]""",
        )
        val problems = DevContainer.isolation(bad, root, git = true)
        assertTrue(problems.any { "privilegiado" in it })
        assertTrue(problems.any { "a rede" in it })
        assertTrue(problems.any { "SYS_ADMIN" in it })
        assertTrue(problems.any { "unconfined" in it })
        assertTrue(problems.any { "docker.sock" in it })
        assertTrue(problems.any { "/c/Users/ana da máquina" in it })
        assertTrue(problems.any { ".git/config" in it })
        assertTrue(problems.any { ".git/hooks" in it })
        assertEquals(listOf("o docker inspect não descreveu o container"), DevContainer.isolation("[]", root, git = false))
    }

    @Test
    fun `os caminhos do projeto viram os do container`() {
        assertEquals("/workspaces/projeto", DevContainer.containerPath(root, "/workspaces/projeto", "C:\\Users\\ana\\projeto"))
        assertEquals("/workspaces/projeto/tomahawk/protecoes/claude.json", DevContainer.containerPath(root, "/workspaces/projeto", "c:/users/ana/projeto/tomahawk/protecoes/claude.json"))
        assertEquals("C:/Users/ana/outro", DevContainer.containerPath(root, "/workspaces/projeto", "C:/Users/ana/outro"))
        assertTrue(DevContainer.inside("/host_mnt/Users/ana/p/src", "/Users/ana/p"))
        assertTrue(DevContainer.inside("/mnt/c/Users/ana/projeto", root))
        assertFalse(DevContainer.inside("/run/desktop/mnt/host/c/Users/ana/projeto-outro", root))
    }

    private fun inspect(vararg mounts: String, host: String = """"Privileged":false,"NetworkMode":"bridge"""") =
        """[{"Id":"abc","HostConfig":{$host},"Mounts":[${mounts.joinToString(",")}]}]"""
}
