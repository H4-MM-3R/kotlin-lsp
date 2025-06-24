package org.kotlinlsp.actions

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinClassFileDecompiler
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.common.warn
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString

fun goToDefinitionAction(ktFile: KtFile, position: Position): Location? = analyze(ktFile) {
    val offset = position.toOffset(ktFile)
    val ref = ktFile.findReferenceAt(offset) ?: return null
    val element = ref.resolve() ?: return tryResolveFromKotlinLibrary(ktFile, offset)
    val file = element.containingFile ?: return null

    // It comes from a java .class file
    if(file.viewProvider.document == null) {
        if(file.virtualFile.url.startsWith("jrt:/")) {
            // Comes from JDK
            val classFile = File.createTempFile("jrtClass", ".class")
            classFile.writeBytes(file.virtualFile.contentsToByteArray())
            val result = tryDecompileJavaClass(classFile.toPath())
            classFile.delete()
            return result
        } else {
            // Comes from JAR
            val classFile = extractClassFromJar("${file.containingDirectory}/${file.containingFile.name}") ?: return null
            val result = tryDecompileJavaClass(classFile.toPath())
            classFile.delete()
            return result
        }
    }
    val range = element.textRange.toLspRange(file)
    val folder = file.containingDirectory.toString().removePrefix("PsiDirectory:")

    return Location().apply {
        uri = "file://${folder}/${file.containingFile.name}"
        setRange(range)
    }
}

private fun KaSession.tryResolveFromKotlinLibrary(ktFile: KtFile, offset: Int): Location? {
    // TODO Test class methods, properties and types to see if they work, just tested with top level functions
    val element = ktFile.findElementAt(offset) ?: return null
    val ref = element.parent as? KtReferenceExpression ?: return null
    val symbol = ref.mainReference.resolveToSymbol() as? KaCallableSymbol ?: return null
    val packageName = symbol.callableId?.packageName?.asString() ?: return null
    val callableName = symbol.callableId?.callableName?.asString() ?: return null
    val module = symbol.containingModule

    val provider =
        KotlinPackagePartProviderFactory.getInstance(ktFile.project).createPackagePartProvider(module.contentScope)
    val names = provider.findPackageParts(packageName).map { it.replace("/", ".") }
    val psiFacade = JavaPsiFacade.getInstance(ktFile.project)
    val psiClass = names.mapNotNull {
        psiFacade.findClass(it, module.contentScope)
    }.find {
        val fns = it.methods.mapNotNull { it.name }
        return@find fns.contains(callableName)
    } ?: return null

    // Decompile the kotlin .class file
    val decompiledView = KotlinClassFileDecompiler().createFileViewProvider(
        psiClass.containingFile.virtualFile,
        PsiManager.getInstance(ktFile.project),
        physical = true
    )
    val decompiledContent = decompiledView.content.get()
    val tmpFile = File.createTempFile("KtDecompiledFile", ".kt")
    tmpFile.writeText(decompiledContent)
    tmpFile.setWritable(false)

    return Location().apply {
        uri = "file://${tmpFile.absolutePath}"
        range = Range().apply {
            start = Position(0, 0)  // TODO Set correct position
            end = Position(0, 1)
        }
    }
}

private fun tryDecompileJavaClass(path: Path): Location? {
    val outputDir = Files.createTempDirectory("fernflower_output").toFile()
    try {
        val args = arrayOf(
            "-jpr=1",
            path.absolutePathString(),
            outputDir.absolutePath
        )
        ConsoleDecompiler.main(args)

        val outName = path.fileName.replaceExtensionWith(".java")
        val outPath = outputDir.toPath().resolve(outName)
        if (!Files.exists(outPath)) return null
        outPath.toFile().setWritable(false)

        return Location().apply {
            uri = "file://${outPath.absolutePathString()}"
            range = Range().apply {
                start = Position(0, 0)  // TODO Set correct position
                end = Position(0, 1)
            }
        }
    } catch (e: Exception) {
        warn(e.message ?: "Unknown fernflower error")
        return null
    }
}

private fun extractClassFromJar(jarPathWithEntry: String): File? {
    try {
        val path = jarPathWithEntry.removePrefix("PsiDirectory:")
        val (jarPath, entryPath) = path.split("!/")
        val jarFile = JarFile(jarPath)
        val entry = jarFile.getEntry(entryPath)
        val inputStream = jarFile.getInputStream(entry)
        val tempFile = File.createTempFile("JavaClass", ".class")
        tempFile.outputStream().use { output -> inputStream.copyTo(output) }
        jarFile.close()
        return tempFile
    } catch (e: Exception) {
        warn("Error extracting class from jar: $jarPathWithEntry")
        return null
    }
}

private fun Path.replaceExtensionWith(newExtension: String): Path {
    val oldName = fileName.toString()
    val newName = oldName.substring(0, oldName.lastIndexOf(".")) + newExtension
    return resolveSibling(newName)
}
