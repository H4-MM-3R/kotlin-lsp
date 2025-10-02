package org.kotlinlsp.analysis.registration

import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.codeInsight.InferredAnnotationsManager
import com.intellij.core.CoreJavaFileManager
import com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.project.DefaultProjectFactory
import com.intellij.psi.ClassTypePointerFactory
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartTypePointerManager
import com.intellij.psi.impl.PsiElementFinderImpl
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.impl.smartPointers.PsiClassReferenceTypePointerFactory
import com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl
import com.intellij.psi.impl.smartPointers.SmartTypePointerManagerImpl
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAccessibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAnnotationsProvider
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinReadActionConfinementLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.analysis.api.symbols.AdditionalKDocResolutionProvider
import org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProvider
import org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProviderCliImpl
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.analysis.decompiler.stub.file.DummyFileAttributeService
import org.jetbrains.kotlin.analysis.decompiler.stub.file.FileAttributeService
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.kotlinlsp.analysis.services.*

@OptIn(KaExperimentalApi::class)
fun Registrar.lspPlatform() {
    analysisApiFir()

    // These are defined in standalone platform xml
    project.registerService(
        KotlinDirectInheritorsProvider::class.java,
        DirectInheritorsProvider::class.java
    )
    app.registerService(
        BuiltinsVirtualFileProvider::class.java,
        BuiltinsVirtualFileProviderCliImpl::class.java
    )

    // These are our platform services
    KotlinCoreEnvironment.registerProjectExtensionPoints(project.extensionArea)
    appExtensionPoint(
        AdditionalKDocResolutionProvider.EP_NAME.toString(),
        AdditionalKDocResolutionProvider::class.java
    )
    appExtensionPoint(
        ClassTypePointerFactory.EP_NAME.toString(),
        ClassTypePointerFactory::class.java
    )
    app.extensionArea.getExtensionPoint(ClassTypePointerFactory.EP_NAME)
        .registerExtension(PsiClassReferenceTypePointerFactory(), app)
    projectExtensionPoint(
        KaResolveExtensionProvider.EP_NAME.toString(),
        KaResolveExtensionProvider::class.java
    )
    appExtensionPoint(
        DocumentWriteAccessGuard.EP_NAME.toString(),
        WriteAccessGuard::class.java
    )
    with(project) {
        registerService(
            CoreJavaFileManager::class.java,
            project.getService(JavaFileManager::class.java) as CoreJavaFileManager
        )
        registerService(ExternalAnnotationsManager::class.java, MockExternalAnnotationsManager())
        registerService(InferredAnnotationsManager::class.java, MockInferredAnnotationsManager())
        registerService(
            KotlinLifetimeTokenFactory::class.java,
            KotlinReadActionConfinementLifetimeTokenFactory::class.java
        )
        registerService(KotlinPlatformSettings::class.java, PlatformSettings::class.java)
        registerService(SmartTypePointerManager::class.java, SmartTypePointerManagerImpl::class.java)
        registerService(SmartPointerManager::class.java, SmartPointerManagerImpl::class.java)
        registerService(KotlinProjectStructureProvider::class.java, ProjectStructureProvider::class.java)
        registerService(KotlinModuleDependentsProvider::class.java, ModuleDependentsProvider::class.java)
        registerService(KotlinModificationTrackerFactory::class.java, ModificationTrackerFactory::class.java)
        registerService(KotlinAnnotationsResolverFactory::class.java, AnnotationsResolverFactory::class.java)
        registerService(
            KotlinDeclarationProviderFactory::class.java,
            DeclarationProviderFactory::class.java
        )
        registerService(KotlinDeclarationProviderMerger::class.java, DeclarationProviderMerger::class.java)
        registerService(KotlinPackageProviderFactory::class.java, PackageProviderFactory::class.java)
        registerService(KotlinPackageProviderMerger::class.java, PackageProviderMerger::class.java)
        registerService(KotlinPackagePartProviderFactory::class.java, PackagePartProviderFactory::class.java)
        registerService(KotlinStubBuilder::class.java, KotlinStubBuilder(project))
        registerService(JavaStubBuilder::class.java, JavaStubBuilder(project))
    }
    with(PsiElementFinder.EP.getPoint(project)) {
        registerExtension(JavaElementFinder(project), disposable)
        registerExtension(PsiElementFinderImpl(project), disposable)
    }
    with(app) {
        registerService(FileAttributeService::class.java, DummyFileAttributeService::class.java)
        registerService(KotlinAnalysisPermissionOptions::class.java, AnalysisPermissionOptions::class.java)
        registerService(ClsKotlinBinaryClassCache::class.java)
        registerService(BinaryFileTypeDecompilers::class.java, BinaryFileTypeDecompilers::class.java)
        registerService(DefaultProjectFactory::class.java, DefaultProjectFactoryImpl(project))
    }
}

fun Registrar.lspPlatformPostInit(
    cliJavaModuleResolver: CliJavaModuleResolver,
    cliVirtualFileFinderFactory: CliVirtualFileFinderFactory
) {
    with(project) {
        registerService(
            KotlinJavaModuleAccessibilityChecker::class.java,
            JavaModuleAccessibilityChecker(cliJavaModuleResolver)
        )
        registerService(
            KotlinJavaModuleAnnotationsProvider::class.java,
            JavaModuleAnnotationsProvider(cliJavaModuleResolver),
        )
        registerService(VirtualFileFinderFactory::class.java, cliVirtualFileFinderFactory)
        registerService(MetadataFinderFactory::class.java, CliMetadataFinderFactory(cliVirtualFileFinderFactory))
    }
}