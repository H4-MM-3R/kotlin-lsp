package org.kotlinlsp.actions

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinClassFileDecompiler
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.idea.references.AbstractKtReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import org.kotlinlsp.common.warn
import java.net.URI
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import org.kotlinlsp.common.normalizeUri
import kotlin.io.path.absolutePathString


fun KtReference.mutlipleResolve(): List<PsiElement?> {
    val resolveResults: Array<ResolveResult> = this.multiResolve(false)
    val psiResults = mutableListOf<PsiElement?>()
    resolveResults.forEach { resolveResult -> resolveResult.element?.let { psiResults.add(it) }}
    return psiResults
}

fun goToDefinitionAction(ktFile: KtFile, position: Position): List<Location?>? = analyze(ktFile) {
    val offset = position.toOffset(ktFile)
    val ref = ktFile.findReferenceAt(offset) as? KtReference ?: return null
    val elements = ref.mutlipleResolve()
    
    // If no elements found, try library resolution
    if (elements.isEmpty()) {
        return tryResolveFromKotlinLibrary(ktFile, offset)
    }
    
    val locations = mutableListOf<Location?>()
    
    for (element in elements) {
        if (element == null) continue
        
        val file = element.containingFile ?: continue

        // It comes from a java .class file
        if(file.viewProvider.document == null) {
            if(file.virtualFile.url.startsWith("jrt:/")) {
                // Comes from JDK
                val classFile = File.createTempFile("jrtClass", ".class")
                classFile.writeBytes(file.virtualFile.contentsToByteArray())
                val result = tryDecompileJavaClass(classFile.toPath())
                classFile.delete()
                locations.add(result)
            } else {
                // Comes from JAR
                val classFile = extractClassFromJar("${file.containingDirectory}/${file.containingFile.name}")
                if (classFile != null) {
                    val result = tryDecompileJavaClass(classFile.toPath())
                    classFile.delete()
                    locations.add(result)
                }
            }
        } else {
            // Regular source file
            val range = element.textRange.toLspRange(file)

            locations.add(Location().apply {
                uri = file.virtualFile.url.normalizeUri()
                setRange(range)
            })
        }
    }
    
    return locations
}

private fun KaSession.tryResolveFromKotlinLibrary(ktFile: KtFile, offset: Int): List<Location?>? {
    val element = ktFile.findElementAt(offset) ?: return null
    val ref = element.parent as? KtReferenceExpression ?: return null
    val symbol = ref.mainReference.resolveToSymbols().firstOrNull()
    val packageName: String
    val symbolName: String

    when(symbol) {
        is KaCallableSymbol -> {
            packageName = symbol.callableId?.packageName?.asString() ?: return null
            symbolName = symbol.callableId?.callableName?.asString() ?: return null
        }
        is KaClassSymbol -> {
            packageName = symbol.classId?.packageFqName?.asString() ?: return null
            symbolName = symbol.classId?.shortClassName?.asString() ?: return null
        }
        else -> {
            return null
        }
    }

    val module = symbol.containingModule
    val provider =
        KotlinPackagePartProviderFactory.getInstance(ktFile.project).createPackagePartProvider(module.contentScope)
    val psiFacade = JavaPsiFacade.getInstance(ktFile.project)
    
    val candidateClasses = mutableListOf<com.intellij.psi.PsiClass>()
    
    val packagePartNames = provider.findPackageParts(packageName).map { it.replace("/", ".") }
    candidateClasses.addAll(packagePartNames.mapNotNull {
        psiFacade.findClass(it, module.contentScope)
    })
    
    val fullClassName = if (packageName.isEmpty()) symbolName else "$packageName.$symbolName"
    psiFacade.findClass(fullClassName, module.contentScope)?.let { candidateClasses.add(it) }

    val psiClass = candidateClasses.find { psiClass ->
        val fns = psiClass.methods.mapNotNull { it.name }
        val classes = psiClass.innerClasses.mapNotNull { it.name }
        val mainClass = psiClass.name?.removeSuffix("Kt")
        val props = psiClass.fields.mapNotNull { it.name }
        val all = fns + props + classes + mainClass
        return@find all.contains(symbolName)
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

    return listOf(Location().apply {
        uri = Paths.get(tmpFile.absolutePath).toUri().toString().normalizeUri()
        range = Range().apply {
            start = Position(0, 0)  // TODO Set correct position
            end = Position(0, 1)
        }
    })
}

private fun tryDecompileJavaClass(path: Path): Location? {
    val outputDir = Files.createTempDirectory("fernflower_output").toFile()
    try {
        val originalOut = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))

        val args = arrayOf(
            "-jpr=1",
            path.absolutePathString(),
            outputDir.absolutePath
        )
        ConsoleDecompiler.main(args)

        System.setOut(originalOut)

        val outName = path.fileName.replaceExtensionWith(".java")
        val outPath = outputDir.toPath().resolve(outName)
        if (!Files.exists(outPath)) return null
        outPath.toFile().setWritable(false)

        return Location().apply {
            uri = Paths.get(outPath.absolutePathString()).toUri().toString().normalizeUri()
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
