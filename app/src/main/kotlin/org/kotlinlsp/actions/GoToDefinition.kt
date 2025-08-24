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
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import com.intellij.psi.PsiJavaFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.FqName
import org.kotlinlsp.index.Index
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.textRangeWithoutComments
import org.kotlinlsp.common.findSourceSymbols
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
import org.kotlinlsp.index.queries.sourcesForPackage
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString


fun KtReference.mutlipleResolve(): List<PsiElement?> {
    val resolveResults: Array<ResolveResult> = this.multiResolve(false)
    val psiResults = mutableListOf<PsiElement?>()
    resolveResults.forEach { resolveResult -> resolveResult.element?.let { psiResults.add(it) }}
    return psiResults
}

fun goToDefinitionAction(ktFile: KtFile, position: Position, index: Index): List<Location?>? = analyze(ktFile) {
    val offset = position.toOffset(ktFile)
    val ref = ktFile.findReferenceAt(offset) as? KtReference ?: return null
    val elements = ref.mutlipleResolve()

    val isFromSource = elements.map { it?.containingFile }.distinct().any { ele -> ele?.viewProvider?.document != null }
    if(!isFromSource) {
        val possibleLocations = getFromSourcesJar(ktFile, ref, index)
        if (possibleLocations.isNotEmpty()) return possibleLocations
    }

    // If no elements found, try library resolution
    if (elements.isEmpty()) {
        return tryResolveFromKotlinLibrary(ktFile, offset)
    }
    
    val locations = mutableListOf<Location?>()
    
    for (element in elements) {
        if (element == null) continue

        val file = element.containingFile ?: continue


        if(file.viewProvider.document == null) {
            // It comes from a java .class file
                val classFile = File.createTempFile("javaClass", ".class")
                classFile.writeBytes(file.virtualFile.contentsToByteArray())
                val path = tryDecompileJavaClass(classFile.toPath(), file)
                val result = Location().apply {
                    uri = Paths.get(path?.absolutePathString() ?: classFile.path.toString()).toUri().toString().normalizeUri()
                    range = Range().apply {
                        start = Position(0, 0)  // TODO Set correct position
                        end = Position(0, 1)
                    }
                }
                classFile.delete()
                locations.add(result)
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

private fun KaSession.getFromSourcesJar(ktFile: KtFile, ref: KtReference,  index: Index): List<Location> {
        val possibleVal = findSourceSymbols(ktFile, ref, index);
        if(possibleVal.isEmpty()) return emptyList()
        val referenceFile = possibleVal.first().containingFile
        val newFile = File.createTempFile("temp", ".${referenceFile.virtualFile.extension}")
        newFile.writeBytes(referenceFile.virtualFile.contentsToByteArray())

        possibleVal.first().let {
            val uri = newFile.toURI().toString().normalizeUri()
            val range = it.textRangeWithoutComments.toLspRange(it.containingFile)
            return listOf(Location().apply {
                this.uri = uri
                this.range = range
            })
        }
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

private fun tryDecompileJavaClass(path: Path, file: PsiFile): Path? {
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

        val outName = Path(file.name.replace(".class", ".java"))
        val outPath = outputDir.toPath().resolve(outName)
        if (!Files.exists(outPath)) return null
        outPath.toFile().setWritable(false)
        return outPath
    } catch (e: Exception) {
        warn(e.message ?: "Unknown fernflower error")
        return null
    }
}