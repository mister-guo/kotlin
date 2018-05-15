/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections.api

import com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isInImportDirective
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.synthetic.JavaSyntheticPropertiesScope

internal class IncompatibleAPIKotlinVisitor(
    private val holder: ProblemsHolder,
    private val forbiddenApiReferences: Map<String, IncompatibleAPIInspection.Problem>,
    private val words: Set<String>
) : KtVisitorVoid() {
    override fun visitImportList(importList: KtImportList) {
        // Do not report anything in imports
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        val nameStr = expression.text
        if (!Name.isValidIdentifier(nameStr)) {
            return
        }

        val names = HashSet<String>()
        names.add(nameStr)

        val gettersNames = JavaSyntheticPropertiesScope.possibleGetMethodNames(
            Name.identifier(nameStr)
        )
        if (!gettersNames.isEmpty()) {
            names.addAll(gettersNames.map { it.identifier })
            names.add(JavaSyntheticPropertiesScope.setMethodName(gettersNames.first()).identifier)
        }

        if (names.none { name -> name in words }) {
            return
        }

        checkReference(expression)
    }

    private fun checkReference(expression: KtSimpleNameExpression) {
        if (expression.isInImportDirective()) {
            // Ignore imports
            return
        }

        for (reference in expression.references) {
            val resolveTo = reference.resolve()
            val problem = findProblem(resolveTo, forbiddenApiReferences) ?: continue

            registerProblemForReference(reference, holder, problem)
            break
        }
    }
}