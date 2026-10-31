package com.ruminai.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only public GitHub diagnostics for the Rumin project. No token and no write endpoints. */
internal class GitHubAgent {
    fun repoStatus(): JSONObject {
        return try {
            val repo = getJson("$API/repos/$OWNER/$REPO")
            val prs = getJsonArray("$API/repos/$OWNER/$REPO/pulls?state=open&per_page=10")
            val safePrs = JSONArray()
            for (i in 0 until prs.length()) {
                val pr = prs.optJSONObject(i) ?: continue
                val head = pr.optJSONObject("head")?.optJSONObject("repo")
                safePrs.put(JSONObject()
                    .put("number", pr.optInt("number"))
                    .put("title", pr.optString("title").take(180))
                    .put("state", pr.optString("state"))
                    .put("head_branch", pr.optJSONObject("head")?.optString("ref").orEmpty().take(100))
                    .put("base_branch", pr.optJSONObject("base")?.optString("ref").orEmpty().take(100))
                    .put("html_url", pr.optString("html_url")))
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repo.optString("full_name"))
                .put("default_branch", repo.optString("default_branch"))
                .put("private", repo.optBoolean("private"))
                .put("open_issues_count", repo.optInt("open_issues_count"))
                .put("updated_at", repo.optString("updated_at"))
                .put("open_pull_requests", safePrs)
                .put("message", "GitHub-এর public repository ও open PR তথ্য পড়েছি; কোনো ফাইল বা branch পরিবর্তন করিনি।")
                .put("verification", "github_read_only")
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("message", "GitHub public status পড়া যায়নি: ${e.message ?: "নেটওয়ার্ক সমস্যা"}")
                .put("verification", "github_read_failed")
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = open(url)
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("GitHub HTTP $status")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun getJsonArray(url: String): JSONArray {
        val connection = open(url)
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("GitHub HTTP $status")
            return JSONArray(body)
        } finally { connection.disconnect() }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "Rumin-AI-3.0")
    }

    companion object {
        private const val API = "https://api.github.com"
        private const val OWNER = "viralsadmix-sudo"
        private const val REPO = "Rumin-AI-3.0."
    }
}
