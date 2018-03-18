/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections.api

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.util.ObjectUtils

class JavaHighlightApiVisitor internal constructor(
    private val myHolder: ProblemsHolder,
    private val incompatibleAPIInspection: IncompatibleAPIInspection
) : JavaElementVisitor() {
    private val isIgnored: Boolean
        get() = false

    override fun visitDocComment(comment: PsiDocComment) {
        // No references inside doc comment are of interest.
    }

    override fun visitClass(aClass: PsiClass) {}

    override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        visitReferenceElement(expression)
    }

    override fun visitNameValuePair(pair: PsiNameValuePair) {
        super.visitNameValuePair(pair)
        val reference = pair.reference ?: return

        val resolve = reference.resolve()
        if (resolve !is PsiCompiledElement || resolve !is PsiAnnotationMethod) return

        ModuleUtilCore.findModuleForPsiElement(pair) ?: return

        val problem = incompatibleAPIInspection.findProblem(resolve)
        if (problem != null) {
            registerProblemForElement(ObjectUtils.notNull(pair.nameIdentifier, pair), myHolder, problem)
        }
    }

    override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
        super.visitReferenceElement(reference)

        ModuleUtilCore.findModuleForPsiElement(reference.element) ?: return

        val psiMember = reference.resolve() as? PsiMember ?: return

        val problem = incompatibleAPIInspection.findProblem(psiMember) ?: return

        var psiClass: PsiClass? = null
        val qualifier = reference.qualifier
        if (qualifier != null) {
            if (qualifier is PsiExpression) {
                psiClass = PsiUtil.resolveClassInType(qualifier.type)
            }
        } else {
            psiClass = PsiTreeUtil.getParentOfType(reference, PsiClass::class.java)
        }
        if (psiClass != null) {
            if (isIgnored) return
        }

        registerProblemForReference(reference, myHolder, problem)
    }

    override fun visitNewExpression(expression: PsiNewExpression) {
        super.visitNewExpression(expression)
        val constructor = expression.resolveConstructor()
        ModuleUtilCore.findModuleForPsiElement(expression) ?: return

        if (constructor is PsiCompiledElement) {
            val problem = incompatibleAPIInspection.findProblem(constructor)
            if (problem != null) {
                registerProblemForReference(expression.classReference, myHolder, problem)
            }
        }
    }

    override fun visitMethod(method: PsiMethod) {
        super.visitMethod(method)
        val annotation =
            (if (!method.isConstructor) AnnotationUtil.findAnnotation(method, CommonClassNames.JAVA_LANG_OVERRIDE) else null) ?: return

        ModuleUtilCore.findModuleForPsiElement(annotation) ?: return

        val methods = method.findSuperMethods()
        for (superMethod in methods) {
            if (superMethod is PsiCompiledElement) {
                val problem = incompatibleAPIInspection.findProblem(superMethod)
                if (problem != null) {
                    registerProblemForReference(annotation.nameReferenceElement, myHolder, problem)
                    return
                }
            } else {
                return
            }
        }
    }

    companion object {
        fun getSignature(member: PsiMember?): String? {
            return when {
                member == null -> null

                member is PsiAnonymousClass -> null

                member.containingClass is PsiAnonymousClass -> null

                member is PsiClass -> {
                    member.qualifiedName
                }

                member is PsiField -> {
                    val containingClass = getSignature(member.containingClass)
                    return if (containingClass == null) null else containingClass + "#" + member.name
                }

                member is PsiMethod -> {
                    val method = member as PsiMethod?
                    val containingClass = getSignature(member.containingClass) ?: return null

                    val buf = StringBuilder()
                    buf.append(containingClass)
                    buf.append('#')
                    buf.append(method!!.name)

                    buf.append('(')
                    for (type in method.getSignature(PsiSubstitutor.EMPTY).parameterTypes) {
                        buf.append(type.canonicalText)
                        buf.append(";")
                    }
                    buf.append(')')

                    return buf.toString()
                }

                else -> null
            }
        }
    }
}