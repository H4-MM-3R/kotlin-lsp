/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.registration

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockApplication
import com.intellij.mock.MockComponentManager
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.util.messages.Topic
import org.jetbrains.kotlin.load.kotlin.JvmType
import kotlin.reflect.full.primaryConstructor

class Registrar(val project: MockProject, val app: MockApplication, val disposable: Disposable) {
    private val pluginDescriptor = DefaultPluginDescriptor("analysis-api-plugin-descriptor")

    fun <T: Any> projectListener(className: String, topic: Topic<T>) {
        val listenerClass = loadClass(project, className, pluginDescriptor) as Class<T>
        project.messageBus.connect().subscribe(
            topic,
            listenerClass.kotlin.primaryConstructor!!.call(project)
        )
    }

    fun appServiceClass(className: String) {
        val implClass = app.loadClass<JvmType.Object>(className, pluginDescriptor)
        app.registerService(implClass)
    }

    fun projectServiceClass(className: String) {
        val implClass = project.loadClass<JvmType.Object>(className, pluginDescriptor)
        project.registerService(implClass)
    }

    fun projectService(interfaceName: String, className: String) {
        val interfaceClass = project.loadClass<JvmType.Object>(interfaceName, pluginDescriptor)
        val implClass = project.loadClass<JvmType.Object>(className, pluginDescriptor)
        project.registerService(interfaceClass, implClass)
    }

    fun <T : Any> projectServiceSingleton(interfaceName: String, className: String) {
        val interfaceClass = project.loadClass<T>(interfaceName, pluginDescriptor)
        val implClass = project.loadClass<T>(className, pluginDescriptor)
        val implClassInstance = implClass.getDeclaredConstructor().newInstance()
        project.registerService(interfaceClass, implClassInstance)
    }

    fun appService(interfaceName: String, className: String) {
        val interfaceClass = app.loadClass<JvmType.Object>(interfaceName, pluginDescriptor)
        val implClass = app.loadClass<JvmType.Object>(className, pluginDescriptor)
        app.registerService(interfaceClass, implClass)
    }

    fun projectExtensionPoint(interfaceName: String, className: String) {
        val loadedClass = loadClass(
            app,
            className,
            pluginDescriptor
        )
        this.projectExtensionPoint(interfaceName, loadedClass)
    }

    fun <T: Any> projectExtensionPoint(interfaceName: String, classObject: Class<T>) {
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            interfaceName,
            classObject
        )
    }

    fun <T: Any> appExtensionPoint(interfaceName: String, classObject: Class<T>) {
        CoreApplicationEnvironment.registerExtensionPoint(
            app.extensionArea,
            interfaceName,
            classObject
        )
    }
}

fun loadClass(componentManager: MockComponentManager, className: String, pluginDescriptor: DefaultPluginDescriptor): Class<JvmType.Object> {
    return componentManager.loadClass(className, pluginDescriptor)
}
