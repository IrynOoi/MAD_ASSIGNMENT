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

    // [MODIFIED] Added 'reportProgress' parameter
    external fun inferAllergens(input: String, modelPath: String, reportProgress: Boolean): String

    private lateinit var csvReader: CsvReader
    private lateinit var datasetManager: DatasetManager
    private lateinit var firebaseService: FirebaseService
    private lateinit var notificationManager: NotificationManager

    // UI Components
    private lateinit var spinnerDataset: Spinner
    private lateinit var spinnerFoodItem: Spinner
    private lateinit var tvDatasetInfo: TextView
    private lateinit var btnLoadDataset: Button
    private lateinit var btnPredictItem: Button
    private lateinit var btnPredictAll: Button
    private lateinit var btnViewResults: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

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

    // [NEW] Callback method called from C++ Native Code
    fun updateNativeProgress(percent: Int) {
        runOnUiThread {
            if (progressBar.visibility == View.VISIBLE && !progressBar.isIndeterminate) {
                // Ensure we don't exceed max
                val safePercent = percent.coerceIn(0, 100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progressBar.setProgress(safePercent, true)
                } else {
                    progressBar.progress = safePercent
                }
                tvProgress.text = "Predicting: $safePercent%"
            }
        }
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
        spinnerFoodItem = findViewById(R.id.spinnerFoodItem)
        tvDatasetInfo = findViewById(R.id.tvDatasetInfo)

        btnLoadDataset = findViewById(R.id.btnLoadDataset)
        btnPredictItem = findViewById(R.id.btnPredictItem)
        btnPredictAll = findViewById(R.id.btnPredictAll)
        btnViewResults = findViewById(R.id.btnViewResults)

        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)

        btnLoadDataset.setOnClickListener {
            val selectedPosition = spinnerDataset.selectedItemPosition
            if (selectedPosition >= 0 && selectedPosition < datasets.size) {
                selectedDataset = datasets[selectedPosition]
                updateDatasetInfo()
                btnPredictAll.isEnabled = true
                setupFoodItemSpinner(selectedDataset!!)
                btnPredictItem.isEnabled = true
                Toast.makeText(this, "Dataset loaded: ${selectedDataset?.name}", Toast.LENGTH_SHORT).show()
            }
        }

        btnPredictItem.setOnClickListener {
            val dataset = selectedDataset
            val position = spinnerFoodItem.selectedItemPosition
            if (dataset != null && position >= 0 && position < dataset.foodItems.size) {
                val item = dataset.foodItems[position]
                predictAndShowSingleItem(item)
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
    }

    private fun setupFoodItemSpinner(dataset: Dataset) {
        val itemNames = dataset.foodItems.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, itemNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFoodItem.adapter = adapter
    }

    private fun predictAndShowSingleItem(item: FoodItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            // [MODIFIED] Setup progress bar for single item (0% to 100%)
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = 0
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = "Predicting: 0%"
            }

            try {
                val prompt = buildPrompt(item.ingredients)
                val modelFile = File(filesDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")

                // [MODIFIED] Pass true to enable internal percentage updates from C++
                val rawResult = inferAllergens(prompt, modelFile.absolutePath, true)

                val (predicted, _) = parseRawResult(rawResult)
                val result = PredictionResult(item, predicted, metrics = InferenceMetrics(0,0,0,0,0,0,0,0))

                withContext(Dispatchers.Main) {
                    hideProgress()
                    val intent = Intent(this@MainActivity, ResultsActivity::class.java).apply {
                        putParcelableArrayListExtra("results", ArrayList(listOf(result)))
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadDataAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = "Loading data..."
            }
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

            withContext(Dispatchers.Main) {
                btnPredictAll.isEnabled = false
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.max = dataset.foodItems.size
                progressBar.progress = 0
                tvProgress.visibility = View.VISIBLE
            }

            val results = mutableListOf<PredictionResult>()
            var success = 0
            var fail = 0
            val modelFile = File(filesDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
            val modelPath = modelFile.absolutePath

            dataset.foodItems.forEachIndexed { index, item ->
                // [MODIFIED] Calculate batch percentage
                val batchPercent = ((index + 1) * 100) / dataset.foodItems.size

                withContext(Dispatchers.Main) {
                    progressBar.progress = index + 1
                    // Show "Processing 5/10 (50%)"
                    tvProgress.text = "Processing ${index + 1}/${dataset.foodItems.size} ($batchPercent%): ${item.name}"
                }

                try {
                    val prompt = buildPrompt(item.ingredients)
                    // [MODIFIED] Pass false to disable single-item progress (avoids conflict)
                    val rawResult = inferAllergens(prompt, modelPath, false)
                    val (predicted, _) = parseRawResult(rawResult)
                    results.add(PredictionResult(item, predicted, metrics = InferenceMetrics(0, 0, 0, 0, 0, 0, 0, 0)))
                    success++
                    notificationManager.showProgressNotification(index + 1, dataset.foodItems.size, item.name)
                    delay(10)
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

    private fun parseRawResult(rawResult: String): Pair<String, InferenceMetrics> {
        val allowedAllergens = setOf("milk", "egg", "peanut", "tree nut", "wheat", "soy", "fish", "shellfish", "sesame")
        Log.d("ALLERGEN_DEBUG", "Raw String: $rawResult")

        val parts = rawResult.split("|", limit = 2)
        val meta = parts[0]
        val rawOutput = if (parts.size > 1) parts[1] else ""

        var ttftMs = -1L
        meta.split(";").forEach {
            if (it.startsWith("TTFT_MS=")) ttftMs = it.removePrefix("TTFT_MS=").toLongOrNull() ?: -1L
        }

        val cleanedString = rawOutput
            .replace("Assistant:", "", true)
            .replace("System:", "", true)
            .replace("User:", "", true)
            .lowercase()

        val allergensList = cleanedString
            .split(",", ".", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val detectedSet = mutableSetOf<String>()
        for (candidate in allergensList) {
            for (allergen in allowedAllergens) {
                if (candidate.contains(allergen, ignoreCase = true)) {
                    detectedSet.add(allergen)
                }
            }
        }

        val finalAllergens = detectedSet.joinToString(", ").ifEmpty { "EMPTY" }
        return Pair(finalAllergens, InferenceMetrics(0L, 0L, 0L, 0L, ttftMs, 0L, 0L, 0L))
    }

    private fun buildPrompt(ingredients: String): String {
        return "Ingredients: $ingredients\nIdentify allergens from this list: milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame.\nOutput ONLY the allergen names separated by commas. Do not write sentences. If none, output EMPTY."
    }

    private fun copyModelIfNeeded(context: Context) {
        val modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        val outFile = File(context.filesDir, modelName)
        if (outFile.exists() && outFile.length() < 10 * 1024 * 1024) outFile.delete()
        if (outFile.exists()) return

        try {
            context.assets.open(modelName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e("MODEL", "Failed to copy model", e)
        }
    }

    private fun hideProgress() {
        runOnUiThread {
            progressBar.visibility = View.GONE
            tvProgress.visibility = View.GONE
            tvProgress.text = "Ready"
        }
    }
}