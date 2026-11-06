package com.mio.tomahawk.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecKitSetupTest {

    @Test
    fun `instala o specify com o uv que ja existe`() {
        assertEquals("& 'C:\\u\\uv.exe' 'tool' 'install' 'specify-cli'", SpecKitSetup.installCommand(ShellKind.POWERSHELL, "C:\\u\\uv.exe"))
        assertEquals("'/home/a/.local/bin/uv' 'tool' 'install' 'specify-cli'", SpecKitSetup.installCommand(ShellKind.POSIX, "/home/a/.local/bin/uv"))
    }

    @Test
    fun `sem o uv, roda antes o instalador oficial e so segue se ele der certo`() {
        val ps = SpecKitSetup.installCommand(ShellKind.POWERSHELL, null)
        assertEquals(
            "powershell -NoProfile -ExecutionPolicy ByPass -c \"irm https://astral.sh/uv/install.ps1 | iex\"; " +
                "if (\$LASTEXITCODE -eq 0) { \$env:Path = \"\$env:USERPROFILE\\.local\\bin;\$env:Path\"; uv tool install specify-cli }",
            ps,
        )
        assertEquals(
            "curl -LsSf https://astral.sh/uv/install.sh | sh && export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool install specify-cli",
            SpecKitSetup.installCommand(ShellKind.POSIX, null),
        )
    }

    @Test
    fun `le a versao instalada e a nova anunciada pelo self check`() {
        assertEquals("0.11.8", SpecKitSetup.versionIn("specify 0.11.8\n"))
        val check = "Update available: 0.11.8 → v1.3.0\n\nTo upgrade:\n  specify self upgrade\n"
        assertEquals("1.3.0", SpecKitSetup.latestIn(check))
        assertNull(SpecKitSetup.latestIn("specify-cli is up to date (1.3.0)"))
    }

    @Test
    fun `reconhece o specify com defeito e reinstala por cima`() {
        val crash = "Traceback (most recent call last):\n  File \"specify.exe\\__main__.py\", line 4\nModuleNotFoundError: No module named 'specify_cli'"
        assertTrue(SpecKitSetup.brokenIn(crash))
        assertFalse(SpecKitSetup.brokenIn("specify 1.3.1"))
        assertEquals("& 'C:\\u\\uv.exe' 'tool' 'install' '--force' 'specify-cli'", SpecKitSetup.installCommand(ShellKind.POWERSHELL, "C:\\u\\uv.exe", force = true))
    }

    @Test
    fun `o init roda sem perguntas e com o Python em UTF-8, na sintaxe de cada shell`() {
        assertEquals(
            "\$env:PYTHONUTF8='1'; \$env:PYTHONIOENCODING='utf-8'; & 'C:\\s\\specify.exe' 'init' '--here' '--force' '--integration' 'cursor-agent' '--script' 'ps' '--ignore-agent-tools'",
            SpecKitSetup.initCommand(ShellKind.POWERSHELL, "C:\\s\\specify.exe", "cursor-agent", "ps"),
        )
        assertEquals(
            "set \"PYTHONUTF8=1\" && set \"PYTHONIOENCODING=utf-8\" && \"C:\\s\\specify.exe\" \"init\" \"--here\" \"--force\" \"--integration\" \"claude\" \"--script\" \"ps\" \"--ignore-agent-tools\"",
            SpecKitSetup.initCommand(ShellKind.CMD, "C:\\s\\specify.exe", "claude", "ps"),
        )
    }

    @Test
    fun `o PowerShell abre ja rodando o comando, mostrado antes`() {
        val shell = WindowsShell.POWERSHELL.startup("& 'specify' 'init'")
        assertEquals(listOf("powershell.exe", "-NoLogo", "-NoExit", "-EncodedCommand"), shell.dropLast(1))
        val script = String(java.util.Base64.getDecoder().decode(shell.last()), Charsets.UTF_16LE)
        assertEquals("Write-Host '> & ''specify'' ''init''' -ForegroundColor DarkGray; & 'specify' 'init'", script)
        val cmd = WindowsShell.CMD.startup("\"specify\" \"init\"")
        assertEquals(listOf("cmd.exe", "/k"), cmd.dropLast(1))
        assertEquals("@chcp 65001 > nul\r\n\"specify\" \"init\"\r\n", java.nio.file.Files.readString(java.nio.file.Path.of(cmd.last())))
    }
}
