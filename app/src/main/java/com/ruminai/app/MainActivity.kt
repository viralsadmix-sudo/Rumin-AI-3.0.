package com.ruminai.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ruminai.app.core.memory.LocalMemoryStore

class MainActivity : Activity() {
    private lateinit var memoryStore: LocalMemoryStore
    private lateinit var memoryCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memoryStore = LocalMemoryStore(this)
        renderHome()
    }

    override fun onResume() {
        super.onResume()
        if (::memoryCount.isInitialized) refreshMemoryCount()
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

    private fun refreshMemoryCount() {
        memoryCount.text = "সংরক্ষিত মেমোরি: ${memoryStore.readAll().size}"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
