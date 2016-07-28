/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.intentions.conventionNameCalls

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.idea.intentions.*
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch
import org.jetbrains.kotlin.resolve.calls.model.isReallySuccess
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.OperatorNameConventions

class ReplaceCallWithBinaryOperatorIntention : SelfTargetingRangeIntention<KtDotQualifiedExpression>(
        KtDotQualifiedExpression::class.java,
        "Replace call with binary operator"
), HighPriorityAction {

    private fun IElementType.inverted(): KtSingleValueToken? = when (this) {
        KtTokens.LT -> KtTokens.GT
        KtTokens.GT -> KtTokens.LT

        KtTokens.GTEQ -> KtTokens.LTEQ
        KtTokens.LTEQ -> KtTokens.GTEQ

        else -> null
    }

    override fun applicabilityRange(element: KtDotQualifiedExpression): TextRange? {
        val calleeExpression = element.callExpression?.calleeExpression as? KtSimpleNameExpression ?: return null
        val operation = operation(calleeExpression) ?: return null

        val resolvedCall = element.toResolvedCall(BodyResolveMode.PARTIAL) ?: return null
        if (!resolvedCall.isReallySuccess()) return null
        if (resolvedCall.call.typeArgumentList != null) return null
        val argument = resolvedCall.call.valueArguments.singleOrNull() ?: return null
        if ((resolvedCall.getArgumentMapping(argument) as ArgumentMatch).valueParameter.index != 0) return null

        if (!element.isReceiverExpressionWithValue()) return null

        text = "Replace with '${operation.value}' operator"
        return calleeExpression.textRange
    }

    override fun applyTo(element: KtDotQualifiedExpression, editor: Editor?) {
        val callExpression = element.callExpression ?: return
        val operation = operation(callExpression.calleeExpression as? KtSimpleNameExpression ?: return) ?: return
        val argument = callExpression.valueArguments.single().getArgumentExpression() ?: return
        val receiver = element.receiverExpression

        val factory = KtPsiFactory(element)
        when (operation) {
            KtTokens.EQEQ -> {
                val parent = element.getStrictParentOfType<KtPrefixExpression>()
                if (parent != null && parent.operationToken == KtTokens.EXCL
                    && parent.baseExpression?.let { KtPsiUtil.safeDeparenthesize(it) } === element) {
                    val newExpression = factory.createExpressionByPattern("$0 != $1", receiver, argument)
                    parent.replace(newExpression)
                    return
                }
            }
            in OperatorConventions.COMPARISON_OPERATIONS -> {
                val parent = element.parent as? KtBinaryExpression ?: return
                val newExpression = factory.createExpressionByPattern("$0 ${operation.value} $1", receiver, argument)
                parent.replace(newExpression)
                return
            }
        }

        val newExpression = factory.createExpressionByPattern("$0 ${operation.value} $1", receiver, argument)
        element.replace(newExpression)
    }

    private fun operation(calleeExpression: KtSimpleNameExpression): KtSingleValueToken? {
        val identifier = calleeExpression.getReferencedNameAsName()
        return when (identifier) {
            OperatorNameConventions.EQUALS -> KtTokens.EQEQ
            OperatorNameConventions.COMPARE_TO -> {
                // callee -> call -> DotQualified -> Binary
                val dotQualified = calleeExpression.parent?.parent
                val parent = dotQualified?.parent as? KtBinaryExpression ?: return null
                val notZero = when {
                    parent.right?.text == "0" -> parent.left
                    parent.left?.text == "0" -> parent.right
                    else -> return null
                }
                if (notZero != dotQualified) return null
                val token = parent.operationToken as? KtSingleValueToken ?: return null
                if (token in OperatorConventions.COMPARISON_OPERATIONS) {
                    if (notZero == parent.left) token else token.inverted()
                }
                else {
                    null
                }
            }
            else -> OperatorConventions.BINARY_OPERATION_NAMES.inverse()[identifier]
        }
    }
}
