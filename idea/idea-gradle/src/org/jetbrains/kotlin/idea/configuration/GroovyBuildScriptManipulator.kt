/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ExternalLibraryDescriptor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrAssignmentExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression
import org.jetbrains.plugins.groovy.lang.psi.api.util.GrStatementOwner

class GroovyBuildScriptManipulator(private val groovyScript: GroovyFile) : GradleBuildScriptManipulator {
    override fun isConfiguredWithOldSyntax(kotlinPluginName: String): Boolean {
        val fileText = runReadAction { groovyScript.text }
        return containsDirective(fileText, getApplyPluginDirective(kotlinPluginName)) &&
                fileText.contains("org.jetbrains.kotlin") &&
                fileText.contains("kotlin-stdlib")
    }

    override fun isConfigured(kotlinPluginName: String): Boolean {
        val fileText = runReadAction { groovyScript.text }
        val pluginsBlockText = runReadAction { groovyScript.getBlockByName("plugins")?.text ?: "" }
        return (containsDirective(pluginsBlockText, kotlinPluginName)) &&
                fileText.contains("org.jetbrains.kotlin") &&
                fileText.contains("kotlin-stdlib")
    }

    override fun configureModuleBuildScript(
        kotlinPluginName: String,
        stdlibArtifactName: String,
        version: String,
        jvmTarget: String?
    ): Boolean {
        val oldText = groovyScript.text

        groovyScript
            .getBlockOrPrepend("plugins")
            .addLastExpressionInBlockIfNeeded("$kotlinPluginName version '$version'")

        groovyScript.getRepositoriesBlock().apply {
            addRepository(version)
            addMavenCentralIfMissing()
        }

        groovyScript.getDependenciesBlock().apply {
            addExpressionInBlockIfNeeded(getGroovyDependencySnippet(stdlibArtifactName), false)
        }

        if (jvmTarget != null) {
            changeKotlinTaskParameter(groovyScript, "jvmTarget", jvmTarget, forTests = false)
            changeKotlinTaskParameter(groovyScript, "jvmTarget", jvmTarget, forTests = true)
        }

        return groovyScript.text != oldText
    }

    override fun changeCoroutineConfiguration(coroutineOption: String): PsiElement? {
        val snippet = "coroutines \"$coroutineOption\""
        val kotlinBlock = groovyScript.getBlockOrCreate("kotlin")
        kotlinBlock.getBlockOrCreate("experimental").apply {
            addOrReplaceExpression(snippet) { stmt ->
                (stmt as? GrMethodCall)?.invokedExpression?.text == "coroutines"
            }
        }

        return kotlinBlock.parent
    }

    override fun changeLanguageVersion(version: String, forTests: Boolean): PsiElement? =
        changeKotlinTaskParameter(groovyScript, "languageVersion", version, forTests)

    override fun changeApiVersion(version: String, forTests: Boolean): PsiElement? =
        changeKotlinTaskParameter(groovyScript, "apiVersion", version, forTests)

    override fun addKotlinLibraryToModuleBuildScript(
        scope: DependencyScope,
        libraryDescriptor: ExternalLibraryDescriptor,
        isAndroidModule: Boolean
    ) {
        val dependencyString = String.format(
            "%s \"%s:%s:%s\"",
            scope.toGradleCompileScope(isAndroidModule),
            libraryDescriptor.libraryGroupId,
            libraryDescriptor.libraryArtifactId,
            libraryDescriptor.maxVersion
        )

        groovyScript.getDependenciesBlock().apply {
            addLastExpressionInBlockIfNeeded(dependencyString)
        }
    }

    override fun getKotlinStdlibVersion(): String? {
        val versionProperty = "\$kotlin_version"
        groovyScript.getBlockByName("buildScript")?.let {
            if (it.text.contains("ext.kotlin_version = ")) {
                return versionProperty
            }
        }

        val dependencies = groovyScript.getBlockByName("dependencies")?.statements
        val stdlibArtifactPrefix = "org.jetbrains.kotlin:kotlin-stdlib:"
        dependencies?.forEach { dependency ->
            val dependencyText = dependency.text
            val startIndex = dependencyText.indexOf(stdlibArtifactPrefix) + stdlibArtifactPrefix.length
            val endIndex = dependencyText.length - 1
            if (startIndex != -1 && endIndex != -1) {
                return dependencyText.substring(startIndex, endIndex)
            }
        }

        return null
    }

    override fun addPluginRepository(repository: String) {
        groovyScript
            .getBlockOrPrepend("pluginManagement")
            .getBlockOrCreate("repositories")
            .addLastExpressionInBlockIfNeeded(repository)
    }

    override fun addResolutionStrategy(pluginId: String) {
        groovyScript
            .getBlockOrPrepend("pluginManagement")
            .getBlockOrCreate("resolutionStrategy")
            .addLastExpressionInBlockIfNeeded(
                    """
                        eachPlugin {
                            if (requested.id.id == "$pluginId") {
                                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}{requested.version}")
                            }
                        }
                    """.trimIndent()
            )
    }

    private fun changeKotlinTaskParameter(
        gradleFile: GroovyFile,
        parameterName: String,
        parameterValue: String,
        forTests: Boolean
    ): PsiElement? {
        val snippet = "$parameterName = \"$parameterValue\""
        val kotlinBlock = gradleFile.getBlockOrCreate(if (forTests) "compileTestKotlin" else "compileKotlin")

        for (stmt in kotlinBlock.statements) {
            if ((stmt as? GrAssignmentExpression)?.lValue?.text == "kotlinOptions." + parameterName) {
                return stmt.replaceWithStatementFromText("kotlinOptions." + snippet)
            }
        }

        kotlinBlock.getBlockOrCreate("kotlinOptions").apply {
            addOrReplaceExpression(snippet) { stmt ->
                (stmt as? GrAssignmentExpression)?.lValue?.text == parameterName
            }
        }

        return kotlinBlock.parent
    }

    private fun getGroovyDependencySnippet(artifactName: String) = "compile \"org.jetbrains.kotlin:$artifactName\""

    private fun getApplyPluginDirective(pluginName: String) = "apply plugin: '$pluginName'"

    private fun containsDirective(fileText: String, directive: String): Boolean {
        return fileText.contains(directive)
                || fileText.contains(directive.replace("\"", "'"))
                || fileText.contains(directive.replace("'", "\""))
    }

    private fun GrClosableBlock.addRepository(version: String): Boolean {
        val repository = getRepositoryForVersion(version)
        val snippet = when {
            repository != null -> repository.toGroovyRepositorySnippet()
            !isRepositoryConfigured(text) -> "$MAVEN_CENTRAL\n"
            else -> return false
        }
        return addLastExpressionInBlockIfNeeded(snippet)
    }

    private fun GrClosableBlock.addMavenCentralIfMissing(): Boolean =
        if (!isRepositoryConfigured(text)) addLastExpressionInBlockIfNeeded(MAVEN_CENTRAL) else false

    private fun GrStatementOwner.getRepositoriesBlock() = getBlockOrCreate("repositories")

    private fun GrStatementOwner.getDependenciesBlock(): GrClosableBlock = getBlockOrCreate("dependencies")

    private fun GrClosableBlock.addOrReplaceExpression(snippet: String, predicate: (GrStatement) -> Boolean) {
        statements.firstOrNull(predicate)?.let { stmt ->
            stmt.replaceWithStatementFromText(snippet)
            return
        }
        addLastExpressionInBlockIfNeeded(snippet)
    }

    private fun GrStatement.replaceWithStatementFromText(snippet: String): GrStatement {
        val newStatement = GroovyPsiElementFactory.getInstance(project).createExpressionFromText(snippet)
        CodeStyleManager.getInstance(project).reformat(newStatement)
        return replaceWithStatement(newStatement)
    }

    companion object {
        private fun PsiElement.getBlockByName(name: String): GrClosableBlock? {
            return getChildrenOfType<GrMethodCallExpression>()
                .filter { it.closureArguments.isNotEmpty() }
                .find { it.invokedExpression.text == name }
                ?.let { it.closureArguments[0] }
        }

        fun GrStatementOwner.getBlockOrCreate(
            name: String,
            customInsert: GrStatementOwner.(newBlock: PsiElement) -> Boolean = { false }
        ): GrClosableBlock {
            var block = getBlockByName(name)
            if (block == null) {
                val factory = GroovyPsiElementFactory.getInstance(project)
                val newBlock = factory.createExpressionFromText("$name{\n}\n")
                if (!customInsert(newBlock)) {
                    addAfter(newBlock, statements.lastOrNull() ?: firstChild)
                }
                block = getBlockByName(name)!!
            }
            return block
        }

        fun GrStatementOwner.getBlockOrPrepend(name: String) = getBlockOrCreate(name) { newBlock ->
            addAfter(newBlock, null)
            true
        }

        fun GrClosableBlock.addLastExpressionInBlockIfNeeded(expressionText: String): Boolean =
            addExpressionInBlockIfNeeded(expressionText, false)

        private fun GrClosableBlock.addExpressionInBlockIfNeeded(expressionText: String, isFirst: Boolean): Boolean {
            if (statements.any { StringUtil.equalsIgnoreWhitespaces(it.text, expressionText) }) return false
            val newStatement = GroovyPsiElementFactory.getInstance(project).createExpressionFromText(expressionText)
            CodeStyleManager.getInstance(project).reformat(newStatement)
            if (!isFirst && statements.isNotEmpty()) {
                val lastStatement = statements[statements.size - 1]
                if (lastStatement != null) {
                    addAfter(newStatement, lastStatement)
                }
            } else {
                if (firstChild != null) {
                    addAfter(newStatement, firstChild)
                }
            }
            return true
        }
    }
}