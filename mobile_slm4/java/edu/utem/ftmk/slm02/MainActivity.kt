//MainActivity.kt
package edu.utem.ftmk.slm02

import FirebaseService
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import edu.utem.ftmk.slm01.InferenceMetrics
import edu.utem.ftmk.slm01.MemoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
        }
    }

    // [FIX] Updated signature to accept model path
    external fun inferAllergens(input: String, modelPath: String): String

    private lateinit var csvReader: CsvReader
    private lateinit var datasetManager: DatasetManager
    private lateinit var firebaseService: FirebaseService
    private lateinit var notificationManager: NotificationManager

    private lateinit var spinnerDataset: Spinner
    private lateinit var tvDatasetInfo: TextView
    private lateinit var btnLoadDataset: Button
    private lateinit var btnPredictAll: Button
    private lateinit var btnViewResults: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var editTextIngredients: EditText
    private lateinit var btnPredict: Button
    private lateinit var tvResult: TextView

    private var allFoodItems: List<FoodItem> = emptyList()
    private var datasets: List<Dataset> = emptyList()
    private var selectedDataset: Dataset? = null
    private var predictionResults: MutableList<PredictionResult> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeServices()
        initializeUI()
        checkAndRequestPermissions()

        lifecycleScope.launch(Dispatchers.IO) {
            copyModelIfNeeded(this@MainActivity)
        }
        loadDataAsync()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun initializeServices() {
        csvReader = CsvReader(this)
        datasetManager = DatasetManager()
        firebaseService = FirebaseService()
        notificationManager = NotificationManager(this)
    }

    private fun initializeUI() {
        spinnerDataset = findViewById(R.id.spinnerDataset)
        tvDatasetInfo = findViewById(R.id.tvDatasetInfo)
        btnLoadDataset = findViewById(R.id.btnLoadDataset)
        btnPredictAll = findViewById(R.id.btnPredictAll)
        btnViewResults = findViewById(R.id.btnViewResults)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        editTextIngredients = findViewById(R.id.editTextIngredients)
        btnPredict = findViewById(R.id.btnPredict)
        tvResult = findViewById(R.id.tvResult)

        btnLoadDataset.setOnClickListener {
            val selectedPosition = spinnerDataset.selectedItemPosition
            if (selectedPosition >= 0 && selectedPosition < datasets.size) {
                selectedDataset = datasets[selectedPosition]
                updateDatasetInfo()
                btnPredictAll.isEnabled = true
                Toast.makeText(this, "Dataset loaded: ${selectedDataset?.name}", Toast.LENGTH_SHORT).show()
            }
        }

        btnPredictAll.setOnClickListener {
            selectedDataset?.let { dataset -> startBatchPrediction(dataset) }
        }

        btnViewResults.setOnClickListener {
            if (predictionResults.isNotEmpty()) {
                val intent = Intent(this, ResultsActivity::class.java).apply {
                    putParcelableArrayListExtra("results", ArrayList(predictionResults))
                }
                startActivity(intent)
            }
        }

        btnPredict.setOnClickListener {
            val ingredients = editTextIngredients.text.toString()
            if (ingredients.isNotEmpty()) {
                performSinglePrediction(ingredients)
            }
        }
    }

    private fun loadDataAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            showProgress("Loading data...")
            try {
                allFoodItems = csvReader.readFoodItemsFromAssets()
                if (allFoodItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        hideProgress()
                        Toast.makeText(this@MainActivity, "CSV Error!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                datasets = datasetManager.createDatasets(allFoodItems)
                withContext(Dispatchers.Main) {
                    setupDatasetSpinner()
                    hideProgress()
                    Toast.makeText(this@MainActivity, "Loaded ${datasets.size} datasets", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hideProgress() }
            }
        }
    }

    private fun setupDatasetSpinner() {
        val datasetNames = datasets.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, datasetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDataset.adapter = adapter
    }

    private fun updateDatasetInfo() {
        selectedDataset?.let {
            tvDatasetInfo.text = "Selected: ${it.name}\nItems: ${it.foodItems.size}"
        }
    }

    private fun startBatchPrediction(dataset: Dataset) {
        lifecycleScope.launch(Dispatchers.IO) {
            showProgress("Starting batch...")
            withContext(Dispatchers.Main) { btnPredictAll.isEnabled = false }

            val results = mutableListOf<PredictionResult>()
            var success = 0
            var fail = 0

            // [FIX] GET MODEL PATH DYNAMICALLY
            val modelFile = File(filesDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
            val modelPath = modelFile.absolutePath

            dataset.foodItems.forEachIndexed { index, item ->
                withContext(Dispatchers.Main) { showProgress("Processing ${index + 1}/${dataset.foodItems.size}") }
                try {
                    val prompt = buildPrompt(item.ingredients)

                    val javaBefore = MemoryReader.javaHeapKb()
                    val nativeBefore = MemoryReader.nativeHeapKb()
                    val pssBefore = MemoryReader.totalPssKb()
                    val startNs = System.nanoTime()

                    // [FIX] PASS PATH TO NATIVE
                    val rawResult = inferAllergens(prompt, modelPath)

                    val latency = (System.nanoTime() - startNs) / 1_000_000
                    val (predicted, _) = parseRawResult(rawResult)

                    val metrics = InferenceMetrics(latency, 0, 0, 0, 0, 0, 0, 0)

                    results.add(PredictionResult(item, predicted, metrics = metrics))
                    success++
                    notificationManager.showProgressNotification(index + 1, dataset.foodItems.size, item.name)
                    delay(50)
                } catch (e: Exception) {
                    fail++
                }
            }

            val (fbSuccess, fbFail) = firebaseService.saveBatchResults(results)
            predictionResults.clear()
            predictionResults.addAll(results)

            withContext(Dispatchers.Main) {
                hideProgress()
                btnPredictAll.isEnabled = true
                btnViewResults.visibility = View.VISIBLE
                notificationManager.showCompletionNotification(success, dataset.foodItems.size, dataset.name, fbSuccess, fbFail)
                Toast.makeText(this@MainActivity, "Batch Complete!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performSinglePrediction(ingredients: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(ingredients)

                // [FIX] GET MODEL PATH DYNAMICALLY
                val modelFile = File(filesDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")

                // [FIX] PASS PATH TO NATIVE
                val rawResult = inferAllergens(prompt, modelFile.absolutePath)

                val (predicted, _) = parseRawResult(rawResult)
                withContext(Dispatchers.Main) {
                    tvResult.text = if (predicted.isEmpty() || predicted == "EMPTY") "No allergens" else "Detected: $predicted"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvResult.text = "Error: ${e.message}" }
            }
        }
    }

    // 位於 MainActivity.kt
    // 位於 MainActivity.kt

    private fun parseRawResult(rawResult: String): Pair<String, InferenceMetrics> {
        val allowedAllergens = setOf("milk", "egg", "peanut", "tree nut", "wheat", "soy", "fish", "shellfish", "sesame")

        Log.d("ALLERGEN_DEBUG", "Raw String: $rawResult")

        val parts = rawResult.split("|", limit = 2)
        val meta = parts[0]
        val rawOutput = if (parts.size > 1) parts[1] else ""

        // 解析 Metrics (保留原本邏輯)
        var ttftMs = -1L
        meta.split(";").forEach {
            if (it.startsWith("TTFT_MS=")) ttftMs = it.removePrefix("TTFT_MS=").toLongOrNull() ?: -1L
        }

        // [修改] 增強清洗邏輯
        val cleanedString = rawOutput
            .replace("Assistant:", "", true)
            .replace("System:", "", true)
            .replace("User:", "", true)
            .lowercase()

        val allergensList = cleanedString
            .split(",", ".", "\n") // 增加分隔符號，防止模型用句號結尾
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // [修改] 放寬過濾邏輯：只要包含關鍵字就算選中 (Partial Match)
        val detectedSet = mutableSetOf<String>()

        for (candidate in allergensList) {
            for (allergen in allowedAllergens) {
                // 如果模型輸出 "peanuts" (candidate), 而清單有 "peanut" (allergen)
                // candidate.contains(allergen) 會是 true
                if (candidate.contains(allergen, ignoreCase = true)) {
                    detectedSet.add(allergen)
                    Log.d("ALLERGEN_DEBUG", "Matched: '$candidate' -> '$allergen'")
                }
            }
        }

        val finalAllergens = detectedSet.joinToString(", ").ifEmpty { "EMPTY" }
        Log.d("ALLERGEN_DEBUG", "Final Prediction: $finalAllergens")

        // 這裡回傳 metrics 只有 TTFT 範例，請保留你原本完整的 Metrics 建構
        return Pair(finalAllergens, InferenceMetrics(0L, 0L, 0L, 0L, ttftMs, 0L, 0L, 0L))
    }
    private fun buildPrompt(ingredients: String): String {
        // [修改] 使用更嚴格的 Prompt，要求不要輸出句子
        return "Ingredients: $ingredients\nIdentify allergens from this list: milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame.\nOutput ONLY the allergen names separated by commas. Do not write sentences. If none, output EMPTY."
    }

    private fun copyModelIfNeeded(context: Context) {
        val modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        val outFile = File(context.filesDir, modelName)

        // [FIX] DELETE CORRUPTED FILES
        if (outFile.exists() && outFile.length() < 10 * 1024 * 1024) {
            outFile.delete()
        }

        if (outFile.exists()) return

        try {
            context.assets.open(modelName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e("MODEL", "Failed to copy model", e)
        }
    }

    private fun showProgress(message: String) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            tvProgress.text = message
        }
    }

    private fun hideProgress() {
        runOnUiThread {
            progressBar.visibility = View.GONE
            tvProgress.text = "Ready"
        }
    }
}