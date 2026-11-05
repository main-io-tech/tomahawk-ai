package com.mio.tomahawk.ui.web

import com.mio.tomahawk.ui.web.HunkLine.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffHunkTest {

    @Test
    fun `numera as linhas de antes e de depois a partir do cabecalho`() {
        val lines = DiffHunk.parse("@@ -10,4 +10,5 @@ fun main() {\n val a = 1\n-val b = 2\n+val b = 3\n+val c = 4\n val d = 5\n\\ No newline at end of file\n")
        assertEquals(
            listOf(
                HunkLine(Kind.HEADER, null, null, "fun main() {"),
                HunkLine(Kind.CONTEXT, 10, 10, "val a = 1"),
                HunkLine(Kind.DEL, 11, null, "val b = 2"),
                HunkLine(Kind.ADD, null, 11, "val b = 3"),
                HunkLine(Kind.ADD, null, 12, "val c = 4"),
                HunkLine(Kind.CONTEXT, 12, 13, "val d = 5"),
                HunkLine(Kind.NOTE, null, null, "No newline at end of file"),
            ),
            lines,
        )
    }
}
