package at.ferdinand.heyclaude

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Claude {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    private const val SYSTEM = "Du bist ein Sprachassistent auf einem Android-Handy. Deine Antwort wird " +
        "angezeigt UND laut vorgelesen. Antworte in der Sprache der Frage (meist Deutsch), kurz und direkt – " +
        "meist 1 bis 4 Sätze, außer es wird ausdrücklich mehr verlangt. Verwende kein Markdown, keine " +
        "Aufzählungszeichen, keine Tabellen und keine Emojis. Schreibe Zahlen und Einheiten so, dass sie " +
        "sich gut vorlesen lassen."

    fun ask(apiKey: String, model: String, history: List<Pair<String, String>>, webSearch: Boolean): String {
        val msgs = JSONArray()
        for ((role, text) in history) msgs.put(JSONObject().put("role", role).put("content", text))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", SYSTEM)
            .put("messages", msgs)
        if (webSearch) {
            body.put("tools", JSONArray().put(
                JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3)
            ))
        }

        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("x-api-key", apiKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(txt).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    txt.take(300)
                }
                throw Exception("API-Fehler $code: $msg")
            }
            val content = JSONObject(txt).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            return sb.toString().trim().ifEmpty { "(leere Antwort)" }
        } finally {
            c.disconnect()
        }
    }
}
