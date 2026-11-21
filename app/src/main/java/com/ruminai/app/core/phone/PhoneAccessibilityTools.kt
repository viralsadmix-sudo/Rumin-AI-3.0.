package com.ruminai.app.core.phone

import com.ruminai.app.core.agent.AgentTool
import com.ruminai.app.core.model.AgentStep

/**
 * Explicit, confirmation-gated Android UI tools.
 * The caller must pass these tools into the Agent's allow-listed ToolRegistry.
 * Step descriptions are treated as literal text, never as code or coordinates.
 */
class PhoneAccessibilityTools(
    private val serviceProvider: () -> PhoneAccessibilityService? =
        { PhoneAccessibilityService.current() }
) {
    fun asAgentTools(): List<AgentTool> = listOf(
        ReadVisibleScreenTool(serviceProvider),
        ClickVisibleTextTool(serviceProvider),
        ScrollActiveScreenTool(serviceProvider),
        TypeFocusedTextTool(serviceProvider)
    )
}

private class ReadVisibleScreenTool(
    private val service: () -> PhoneAccessibilityService?
) : AgentTool {
    override val name = "phone.read_visible_screen"
    override val requiresConfirmation = true

    override suspend fun execute(step: AgentStep): Result<String> = runCatching {
        val active = service() ?: error("Rumin Accessibility চালু নেই।")
        active.readVisibleText().ifBlank { "স্ক্রিনে পড়ার মতো দৃশ্যমান লেখা পাওয়া যায়নি।" }
    }
}

private class ClickVisibleTextTool(
    private val service: () -> PhoneAccessibilityService?
) : AgentTool {
    override val name = "phone.click_visible_text"
    override val requiresConfirmation = true

    override suspend fun execute(step: AgentStep): Result<String> = runCatching {
        val active = service() ?: error("Rumin Accessibility চালু নেই।")
        require(step.description.isNotBlank()) { "যে লেখায় ট্যাপ করতে হবে তা নির্দিষ্ট নয়।" }
        check(active.clickUniqueText(step.description)) {
            "লেখাটি একবারই দৃশ্যমান বা ক্লিকযোগ্য নয়; নিরাপত্তার জন্য ট্যাপ করা হয়নি।"
        }
        "নির্দিষ্ট লেখার বোতামে ট্যাপের অনুরোধ পাঠানো হয়েছে; পরের ধাপে ফলাফল যাচাই করতে হবে।"
    }
}

private class ScrollActiveScreenTool(
    private val service: () -> PhoneAccessibilityService?
) : AgentTool {
    override val name = "phone.scroll_screen_forward"
    override val requiresConfirmation = true

    override suspend fun execute(step: AgentStep): Result<String> = runCatching {
        val active = service() ?: error("Rumin Accessibility চালু নেই।")
        check(active.scrollActiveWindowForward()) { "বর্তমান স্ক্রিন স্ক্রল করা যায়নি।" }
        "স্ক্রল অ্যাকশন পাঠানো হয়েছে; দৃশ্যমান পরিবর্তন যাচাই করতে হবে।"
    }
}

private class TypeFocusedTextTool(
    private val service: () -> PhoneAccessibilityService?
) : AgentTool {
    override val name = "phone.type_focused_text"
    override val requiresConfirmation = true

    override suspend fun execute(step: AgentStep): Result<String> = runCatching {
        val active = service() ?: error("Rumin Accessibility চালু নেই।")
        require(step.description.isNotEmpty()) { "লেখার বিষয়বস্তু ফাঁকা।" }
        check(active.typeIntoFocusedField(step.description)) {
            "ফোকাস করা নিরাপদ editable field পাওয়া যায়নি; লেখা প্রবেশ করানো হয়নি।"
        }
        "ফোকাস করা ফিল্ডে লেখা পাঠানো হয়েছে; পরের ধাপে লেখা যাচাই করতে হবে।"
    }
}
