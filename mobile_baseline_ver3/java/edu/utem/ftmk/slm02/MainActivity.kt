//MainActivity.kt
package edu.utem.ftmk.slm02

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import edu.utem.ftmk.slm01.InferenceMetrics
import edu.utem.ftmk.slm01.MemoryReader
import java.io.File

/**
 * BITP 3453 Mobile Application Development
 *
 * @author Emaliana Kasmuri, FTMK, UTeM
 *
 * Purpose:
 * The app predicts simple food allergens.
 */
class MainActivity : AppCompatActivity() {


    companion object {

        // Load primary native library for JNI functions, core GGML tensor librarym CPU specific
        // implementation and library LLaMa model interaction
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            //System.loadLibrary("llama/ggml")
            System.loadLibrary("llama")
        }
    }

    // Native function declaration
    external fun inferAllergens(input: String): String

    private val allowedAllergens = setOf("milk", "egg", "peanut", "tree nut", "wheat", "soy","fish",
        "shellfish", "sesame")

    private fun buildPrompt(ingredients: String): String {
        return """
        Task: Detect food allergens.

        Ingredients:
        $ingredients

        Allowed allergens:
        milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame

        Rules:
        - Output ONLY a comma-separated list of allergens.
        - If none are present, output EMPTY.
        - Do not explain.
        - Do not add extra words.
    """.trimIndent()
    }

    /**
     * Main entry point to the application
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        copyModelIfNeeded(this)

        val inputText = findViewById<EditText>(R.id.editTextIngedients)
        val btnPredict = findViewById<Button>(R.id.btnPredict)
        val outputText = findViewById<TextView>(R.id.tvResult)

        btnPredict.setOnClickListener {

            val ingredients = inputText.text.toString()

            val prompt = buildPrompt(ingredients)

            // ---- BEFORE ----
            val javaBefore = MemoryReader.javaHeapKb()
            val nativeBefore = MemoryReader.nativeHeapKb()
            val pssBefore = MemoryReader.totalPssKb()

            val startNs = System.nanoTime()
            val rawResult = inferAllergens(prompt)
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000

            // Expected format: TTFT_MS=<value>;ITPS=<value>|<output>
            // Split metadata and output
            val parts = rawResult.split("|", limit = 2)
            val meta = parts[0]
            val rawOutput = if (parts.size > 1) parts[1] else ""

            // Parse TTFT, ITPS, OTPS
            var ttftMs = -1L
            var itps = -1L
            var otps = -1L
            var oetMs = -1L

            meta.split(";").forEach {
                when {
                    it.startsWith("TTFT_MS=") ->
                        ttftMs = it.removePrefix("TTFT_MS=").toLongOrNull() ?: -1L
                    it.startsWith("ITPS=") ->
                        itps = it.removePrefix("ITPS=").toLongOrNull() ?: -1L
                    it.startsWith("OTPS=") ->
                        otps = it.removePrefix("OTPS=").toLongOrNull() ?: -1L
                    it.startsWith("OET_MS=") ->
                        oetMs = it.removePrefix("OET_MS=").toLongOrNull() ?: -1L
                }
            }

            // ---- AFTER ----
            val javaAfter = MemoryReader.javaHeapKb()
            val nativeAfter = MemoryReader.nativeHeapKb()
            val pssAfter = MemoryReader.totalPssKb()

            val metrics = InferenceMetrics(
                latencyMs = latencyMs,
                javaHeapKb = javaAfter - javaBefore,
                nativeHeapKb = nativeAfter - nativeBefore,
                totalPssKb = pssAfter - pssBefore,
                ttft = ttftMs,
                itps = itps,
                otps = otps,
                oet = oetMs
            )

            Log.i(
                "SLM_METRICS",
                "Latency=${metrics.latencyMs}ms | TTFT=${ttftMs}ms | ITPS=${itps} tok/s"
            )

            Log.i(
                "SLM_METRICS",
                "OTPS=${otps} tok/s | OET=${oetMs}ms"
            )

            Log.i(
                "SLM_METRICS",
                "Memory → JavaΔ=${metrics.javaHeapKb}KB | " +
                        "NativeΔ=${metrics.nativeHeapKb}KB | " +
                        "PSSΔ=${metrics.totalPssKb}KB"
            )

            val allergens = rawOutput
                .replace("Ġ", "")
                .lowercase()
                .split(",")
                .map { it.trim() }
                .filter { it in allowedAllergens }

            outputText.text = if (allergens.isEmpty()) {
                "No allergens detected"
            } else {
                allergens.joinToString(", ")
            }
        }

    }

    private fun copyModelIfNeeded(context: Context) {
        val modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        val outFile = File(context.filesDir, modelName)

        if (outFile.exists()) return

        context.assets.open(modelName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
