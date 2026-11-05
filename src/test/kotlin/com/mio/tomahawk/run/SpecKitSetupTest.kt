package com.mio.tomahawk.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
