/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections.api

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.*
import com.intellij.ide.actions.QualifiedNameProvider
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.util.xmlb.annotations.Attribute
import org.jdom.Element
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.highlighter.createSuppressWarningActions
import org.jetbrains.kotlin.idea.inspections.api.IncompatibleAPIInspection.Companion.DEFAULT_REASON
import org.jetbrains.kotlin.idea.inspections.toSeverity

class IncompatibleAPIInspection : LocalInspectionTool(), CustomSuppressableInspectionTool {
    class Problem(
        @Attribute var reference: String? = "",
        @Attribute var reason: String? = ""
    )

    // Stored in inspection setting
    var problems: List<Problem> = arrayListOf()

    fun addProblem(reference: String, reason: String?) {
        problems += Problem(reference, reason)
        updateCaches(problems)
    }

    override fun readSettings(node: Element) {
        super.readSettings(node)
        updateCaches(problems)
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (forbiddenApiReferencesCache.isEmpty()) {
            return super.buildVisitor(holder, isOnTheFly)
        }

        return when (holder.file.language) {
            KotlinLanguage.INSTANCE -> {
                IncompatibleAPIKotlinVisitor(holder, forbiddenApiReferencesCache, referenceWordsCaches)
            }

            JavaLanguage.INSTANCE -> {
                IncompatibleAPIJavaVisitor(holder, this)
            }

            else -> {
                super.buildVisitor(holder, isOnTheFly)
            }
        }
    }

    override fun getSuppressActions(element: PsiElement?): Array<SuppressIntentionAction>? {
        return when {
            element == null -> emptyArray()

            element.language == JavaLanguage.INSTANCE -> {
                val key = HighlightDisplayKey.find(shortName)
                        ?: throw AssertionError("HighlightDisplayKey.find($shortName) is null. Inspection: $javaClass")
                return SuppressManager.getInstance().createSuppressActions(key)
            }

            element.language == KotlinLanguage.INSTANCE -> {
                createSuppressWarningActions(element, toSeverity(defaultLevel), suppressionKey).toTypedArray()
            }

            else -> emptyArray()
        }
    }

    override fun isSuppressedFor(element: PsiElement): Boolean {
        if (SuppressManager.getInstance()!!.isSuppressedFor(element, id)) {
            return true
        }

        val project = element.project
        if (KotlinCacheService.getInstance(project).getSuppressionCache().isSuppressed(element, suppressionKey, toSeverity(defaultLevel))) {
            return true
        }

        return false
    }

    private val suppressionKey: String get() = this.shortName

    private var forbiddenApiReferencesCache: Map<String, Problem> = emptyMap()
    private var referenceWordsCaches: Set<String> = emptySet()

    private fun updateCaches(problems: List<Problem>) {
        val validProblems = problems.filter { !it.reference.isNullOrBlank() }

        forbiddenApiReferencesCache = validProblems.map { it.reference!! to it }.toMap()

        referenceWordsCaches = validProblems.mapTo(HashSet()) {
            val reference = it.reference!!
            if (reference.contains('#')) {
                reference.substringAfterLast('#').substringBefore('(')
            } else {
                reference.substringAfterLast('.')
            }
        }
    }

    fun findProblem(resolvedTo: PsiElement) = findProblem(resolvedTo, forbiddenApiReferencesCache)

    companion object {
        const val SHORT_NAME = "IncompatibleAPI"
        const val DEFAULT_REASON = "Incompatible API"
    }
}

fun registerProblemForReference(
    reference: PsiReference?,
    holder: ProblemsHolder,
    problem: IncompatibleAPIInspection.Problem
) {
    if (reference == null) return
    holder.registerProblem(
        reference,
        problem.reason ?: DEFAULT_REASON,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    )
}

fun findProblem(
    resolvedTo: PsiElement?,
    forbiddenApiReferences: Map<String, IncompatibleAPIInspection.Problem>
): IncompatibleAPIInspection.Problem? {
    if (resolvedTo == null) return null
    val referenceStr = getQualifiedNameFromProviders(resolvedTo) ?: return null

    return forbiddenApiReferences[referenceStr] ?: run {
        // check constructor
        val lastPart = referenceStr.substringAfterLast('#', "")
        if (lastPart.isNotEmpty()) {
            val classFqName = referenceStr.substringBeforeLast('#')
            val className = classFqName.substringAfterLast('.')
            if (lastPart.startsWith("$className(")) {
                return@run forbiddenApiReferences[classFqName]
            }
        }

        null
    } ?: run {
        // Check reference without params
        val beforeParams = referenceStr.substringBeforeLast('(', "")
        if (beforeParams.isNotEmpty()) {
            val overloadSignatureKey = "$beforeParams("
            val particularOverloadPresent = forbiddenApiReferences.keys.any { it.startsWith(overloadSignatureKey) }
            if (!particularOverloadPresent) {
                return@run forbiddenApiReferences[beforeParams]
            }
        }

        null
    }
}

fun getQualifiedNameFromProviders(element: PsiElement): String? {
    DumbService.getInstance(element.project).isAlternativeResolveEnabled = true
    try {
        for (provider in Extensions.getExtensions(QualifiedNameProvider.EP_NAME)) {
            val result = provider.getQualifiedName(element)
            if (result != null) return result
        }
    } finally {
        DumbService.getInstance(element.project).isAlternativeResolveEnabled = false
    }
    return null
}