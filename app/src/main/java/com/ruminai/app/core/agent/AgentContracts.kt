package com.ruminai.app.core.agent

import com.ruminai.app.core.model.AgentPlan
import com.ruminai.app.core.model.AgentStep

/** All executable actions must be registered here; never execute model-produced code. */
interface AgentPlanner {
    suspend fun createPlan(userRequest: String): Result<AgentPlan>
}

interface AgentTool {
    val name: String
    val requiresConfirmation: Boolean
    suspend fun execute(step: AgentStep): Result<String>
}

class ToolRegistry(tools: List<AgentTool>) {
    private val byName = tools.associateBy { it.name }

    fun find(name: String): AgentTool? = byName[name]
    fun registeredNames(): Set<String> = byName.keys
}

/** Enforces explicit approval before sensitive tools are invoked. */
class ApprovalGate {
    fun mayExecute(tool: AgentTool, userApproved: Boolean): Boolean =
        !tool.requiresConfirmation || userApproved
}
