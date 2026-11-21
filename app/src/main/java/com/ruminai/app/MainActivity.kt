package com.ruminai.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ruminai.app.core.memory.LocalMemoryStore
import com.ruminai.app.core.phone.PhoneAccessibilityService

class MainActivity : Activity() {
    private lateinit var memoryStore: LocalMemoryStore
    private lateinit var memoryCount: TextView
    private lateinit var phoneControlStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memoryStore = LocalMemoryStore(this)
        renderHome()
    }

    override fun onResume() {
        super.onResume()
        if (::memoryCount.isInitialized) refreshMemoryCount()
        if (::phoneControlStatus.isInitialized) refreshPhoneControlStatus()
    }

    private fun renderHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 48, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0B1020.toInt())
        }

        val brand = TextView(this).apply {
            text = "RUMIN AI 3.0"
            textSize = 27f
            setTextColor(0xFFFF4FCB.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(brand, matchWidth())

        val greeting = TextView(this).apply {
            text = "আসসালামু আলাইকুম, বস!\nআমি রুমিন।"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 12)
        }
        root.addView(greeting, matchWidth())

        val status = TextView(this).apply {
            text = "নতুন সিস্টেমের ভিত্তি তৈরি হচ্ছে।\nAI Agent ও Live Voice এখনো সংযুক্ত হয়নি।"
            textSize = 15f
            setTextColor(0xFFB9C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        root.addView(status, matchWidth())

        memoryCount = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8DE1FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }
        root.addView(memoryCount, matchWidth())
        refreshMemoryCount()

        val memoryButton = Button(this).apply {
            text = "অনুমোদিত তথ্য মেমোরিতে রাখুন"
            setOnClickListener { showSaveMemoryDialog() }
        }
        root.addView(memoryButton, matchWidth())

        phoneControlStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB9C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        root.addView(phoneControlStatus, matchWidth())
        refreshPhoneControlStatus()

        val phoneControlButton = Button(this).apply {
            text = "ফোন নিয়ন্ত্রণের অনুমতি সেট করুন"
            setOnClickListener { showAccessibilityDisclosure() }
        }
        root.addView(phoneControlButton, matchWidth())

        val screenReadButton = Button(this).apply {
            text = "বর্তমান স্ক্রিন পড়ে পরীক্ষা করুন"
            setOnClickListener { confirmAndReadScreen() }
        }
        root.addView(screenReadButton, matchWidth())

        val info = TextView(this).apply {
            text = "পরবর্তী ধাপ: Agent Planner → অনুমতি → Tool Execution → যাচাই।\nতারপর নতুন Live Voice অডিও ইঞ্জিন।"
            textSize = 14f
            setTextColor(0xFFB9C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        root.addView(info, matchWidth())
        setContentView(root)
    }

    private fun showSaveMemoryDialog() {
        val field = EditText(this).apply { hint = "যে তথ্যটি মনে রাখতে বলছেন লিখুন" }
        AlertDialog.Builder(this)
            .setTitle("নতুন মেমোরি")
            .setMessage("শুধু আপনার অনুমোদিত তথ্য এই ফোনে সংরক্ষণ হবে।")
            .setView(field)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) {
                    memoryStore.addApprovedFact(value)
                    refreshMemoryCount()
                }
            }
            .show()
    }



    private fun confirmAndReadScreen() {
        AlertDialog.Builder(this)
            .setTitle("স্ক্রিন পড়ার অনুমতি")
            .setMessage("Rumin শুধু এখনকার দৃশ্যমান স্ক্রিনের লেখা এই ফোনেই পড়বে। কোনো তথ্য সার্ভারে পাঠানো বা সংরক্ষণ করা হবে না। চালাবেন?")
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("পড়ুন") { _, _ ->
                val service = PhoneAccessibilityService.current()
                if (service == null) {
                    AlertDialog.Builder(this)
                        .setMessage("Accessibility Service এখনো সংযুক্ত নয়। Android Settings-এ অনুমতি চালু আছে কি না দেখুন।")
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                } else {
                    val screenText = runCatching { service.readVisibleText() }
                        .getOrElse { "স্ক্রিন পড়তে সমস্যা হয়েছে: ${it.message ?: "অজানা সমস্যা"}" }
                        .ifBlank { "বর্তমান স্ক্রিনে পড়ার মতো লেখা পাওয়া যায়নি।" }
                    AlertDialog.Builder(this)
                        .setTitle("স্ক্রিন পড়ার ফলাফল")
                        .setMessage(screenText)
                        .setPositiveButton("বন্ধ", null)
                        .show()
                }
            }
            .show()
    }

    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Rumin AI ফোন নিয়ন্ত্রণ")
            .setMessage(
                "এই ঐচ্ছিক সুবিধা চালু করলে, আপনার নির্দেশে Rumin দৃশ্যমান স্ক্রিনের লেখা পড়তে, " +
                    "নির্দিষ্ট লেখা ট্যাপ করতে, ফোকাস করা ফিল্ডে টাইপ করতে এবং স্ক্রল করতে পারবে। " +
                    "এখন কোনো স্ক্রিন ডেটা ব্যাকগ্রাউন্ডে সংরক্ষণ বা পাঠানো হয় না। " +
                    "প্রতিটি ফোন-নিয়ন্ত্রণ কাজের আগে অ্যাপের ভেতরে আপনার অনুমতি নিতে হবে। " +
                    "আপনি চাইলে Android Settings থেকে যেকোনো সময় এটি বন্ধ করতে পারবেন।"
            )
            .setNegativeButton("এখন নয়", null)
            .setPositiveButton("Android Settings খুলুন") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun refreshPhoneControlStatus() {
        phoneControlStatus.text = if (PhoneAccessibilityService.isEnabledInProcess()) {
            "ফোন নিয়ন্ত্রণ: Accessibility Service সংযুক্ত"
        } else {
            "ফোন নিয়ন্ত্রণ: এখনো চালু/সংযুক্ত নয়"
        }
    }

    private fun refreshMemoryCount() {
        memoryCount.text = "সংরক্ষিত মেমোরি: ${memoryStore.readAll().size}"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
