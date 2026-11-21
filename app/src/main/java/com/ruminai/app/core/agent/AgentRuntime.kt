package com.ruminai.app.core.agent

import com.ruminai.app.core.model.AgentPlan
import com.ruminai.app.core.model.AgentStep
import com.ruminai.app.core.model.ExecutionStatus
import java.util.UUID

data class StepExecution(
    val step: AgentStep,
    val status: ExecutionStatus,
    val output: String? = null,
    val error: String? = null
)

data class AgentRun(
    val id: String,
    val plan: AgentPlan,
    val status: ExecutionStatus,
    val steps: List<StepExecution>,
    val summary: String? = null
)

interface AgentObserver {
    fun onRunUpdated(run: AgentRun)
}

/**
 * Coordinates planning and allow-listed tools.
 *
 * The planner may propose tool names, but it cannot execute anything. Every name is
 * resolved through ToolRegistry, and sensitive tools require an explicit approval
 * supplied by the UI for this run.
 */
class AgentRuntime(
    private val planner: AgentPlanner,
    private val registry: ToolRegistry,
    private val approvalGate: ApprovalGate = ApprovalGate(),
    private val observer: AgentObserver? = null
) {
    suspend fun prepare(userRequest: String): Result<AgentRun> {
        if (userRequest.isBlank()) return Result.failure(IllegalArgumentException("কাজের নির্দেশ লিখুন।"))
        return planner.createPlan(userRequest.trim()).mapCatching { plan ->
            require(plan.steps.isNotEmpty()) { "পরিকল্পনায় কোনো কাজের ধাপ নেই।" }
            plan.steps.forEach { step ->
                val tool = registry.find(step.toolName)
                    ?: error("অনুমোদিত নয় এমন Tool: ${step.toolName}")
                require(step.requiresConfirmation == tool.requiresConfirmation) {
                    "Tool approval policy mismatch: ${step.toolName}"
                }
            }
            val initial = plan.steps.map { step ->
                StepExecution(step, if (step.requiresConfirmation) ExecutionStatus.AWAITING_APPROVAL else ExecutionStatus.DRAFT)
            }
            AgentRun(UUID.randomUUID().toString(), plan, ExecutionStatus.DRAFT, initial)
                .also { observer?.onRunUpdated(it) }
        }
    }

    suspend fun execute(
        prepared: AgentRun,
        approvedStepIds: Set<String>,
        isCancelled: () -> Boolean = { false }
    ): AgentRun {
        require(prepared.status == ExecutionStatus.DRAFT || prepared.status == ExecutionStatus.AWAITING_APPROVAL) {
            "এই Agent run আর চালানো যাবে না।"
        }

        val results = prepared.steps.toMutableList()
        var runStatus = ExecutionStatus.RUNNING
        var summary = "কাজ শুরু হয়েছে।"

        fun snapshot(status: ExecutionStatus, note: String? = null) =
            AgentRun(prepared.id, prepared.plan, status, results.toList(), note)
                .also { observer?.onRunUpdated(it) }

        snapshot(runStatus)
        for (index in results.indices) {
            if (isCancelled()) {
                results[index] = results[index].copy(status = ExecutionStatus.CANCELLED)
                runStatus = ExecutionStatus.CANCELLED
                summary = "ব্যবহারকারী কাজটি বন্ধ করেছেন।"
                break
            }

            val current = results[index]
            val tool = registry.find(current.step.toolName)
            if (tool == null) {
                results[index] = current.copy(status = ExecutionStatus.FAILED, error = "Tool পাওয়া যায়নি।")
                runStatus = ExecutionStatus.FAILED
                summary = "নিরাপত্তার কারণে অচেনা Tool চালানো হয়নি।"
                break
            }

            if (!approvalGate.mayExecute(tool, current.step.id in approvedStepIds)) {
                results[index] = current.copy(status = ExecutionStatus.AWAITING_APPROVAL)
                runStatus = ExecutionStatus.AWAITING_APPROVAL
                summary = "পরবর্তী ধাপের জন্য আপনার অনুমতি প্রয়োজন।"
                break
            }

            results[index] = current.copy(status = ExecutionStatus.RUNNING)
            snapshot(runStatus)
            val outcome = runCatching { tool.execute(current.step).getOrThrow() }
            results[index] = outcome.fold(
                onSuccess = { current.copy(status = ExecutionStatus.SUCCEEDED, output = it) },
                onFailure = { current.copy(status = ExecutionStatus.FAILED, error = it.message ?: "অজানা সমস্যা") }
            )
            if (results[index].status == ExecutionStatus.FAILED) {
                runStatus = ExecutionStatus.FAILED
                summary = "একটি ধাপ ব্যর্থ হয়েছে; পরের ধাপগুলো চালানো হয়নি।"
                break
            }
            snapshot(runStatus)
        }

        if (runStatus == ExecutionStatus.RUNNING) {
            runStatus = if (results.all { it.status == ExecutionStatus.SUCCEEDED }) {
                ExecutionStatus.VERIFYING
            } else ExecutionStatus.AWAITING_APPROVAL
        }
        // Tool output is not proof of the real-world result. A separate verifier must
        // confirm success before the UI may report the whole task as completed.
        if (runStatus == ExecutionStatus.VERIFYING) {
            summary = "সব Tool ধাপ শেষ। ফলাফল যাচাই প্রয়োজন।"
        }
        return snapshot(runStatus, summary)
    }

    fun markVerified(run: AgentRun, verified: Boolean, evidence: String? = null): AgentRun {
        require(run.status == ExecutionStatus.VERIFYING) { "শুধু যাচাই অপেক্ষমাণ কাজ যাচাই করা যাবে।" }
        val status = if (verified) ExecutionStatus.SUCCEEDED else ExecutionStatus.FAILED
        val note = if (verified) {
            evidence ?: "ফলাফল যাচাই হয়েছে।"
        } else {
            evidence ?: "কাজের ফলাফল যাচাই করা যায়নি।"
        }
        return AgentRun(run.id, run.plan, status, run.steps, note)
            .also { observer?.onRunUpdated(it) }
    }
}
