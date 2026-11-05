package com.mio.tomahawk.delivery.github

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mio.tomahawk.delivery.PrRadarService

/** Liga o Rádio ao abrir o projeto, para os comentários dos PRs chegarem mesmo com a aba fechada. */
class RadarStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        PrRadarService.getInstance(project).start()
    }
}
