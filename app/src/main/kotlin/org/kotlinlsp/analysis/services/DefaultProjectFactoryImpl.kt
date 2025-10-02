package org.kotlinlsp.analysis.services

import com.intellij.mock.MockProject
import com.intellij.openapi.project.DefaultProjectFactory
import com.intellij.openapi.project.Project

class DefaultProjectFactoryImpl(private val project: MockProject) : DefaultProjectFactory() {
    override fun getDefaultProject(): Project {
        return project
    }
}

