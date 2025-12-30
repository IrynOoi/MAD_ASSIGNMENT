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
import com.google.firebase.firestore.BuildConfig
import edu.utem.ftmk.slm01.InferenceMetrics
import edu.utem.ftmk.slm02.MemoryReader
import kotlinx.coroutines.Dispatchers
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
        Log.d("BUILD_CHECK", "Current Build Type: ${BuildConfig.BUILD_TYPE}")
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

    // Safety guard for progress updates coming from C++
    fun updateNativeProgress(percent: Int) {
        runOnUiThread {
            if (progressBar.visibility == View.VISIBLE && !progressBar.isIndeterminate) {
                // If we are predicting a single item, we want detailed progress
                // If we are predicting ALL, we generally ignore this specific update
                // because the batch loop handles the main progress bar.
                if (!btnPredictAll.isEnabled) {
                    // Batch running: Ignore single token updates to prevent flickering
                    // or update a secondary bar if you had one.
                } else {
                    // Single item running: Show token generation progress
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        progressBar.setProgress(percent, true)
                    } else {
                        progressBar.progress = percent
                    }
                    tvProgress.text = "Predicting: $percent%"
                }
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

                val javaBefore = MemoryReader.javaHeapKb()
                val nativeBefore = MemoryReader.nativeHeapKb()
                val pssBefore = MemoryReader.totalPssKb()
                val startNs = System.nanoTime()

                // reportProgress = true for single item
                val rawResult = inferAllergens(prompt, modelFile.absolutePath, true)

                val latencyMs = (System.nanoTime() - startNs) / 1_000_000
                val javaAfter = MemoryReader.javaHeapKb()
                val nativeAfter = MemoryReader.nativeHeapKb()
                val pssAfter = MemoryReader.totalPssKb()

                val (predicted, cppMetrics) = parseRawResult(rawResult)

                val finalMetrics = InferenceMetrics(
                    latencyMs = latencyMs,
                    javaHeapKb = javaAfter - javaBefore,
                    nativeHeapKb = nativeAfter - nativeBefore,
                    totalPssKb = pssAfter - pssBefore,
                    ttft = cppMetrics.ttft,
                    itps = cppMetrics.itps,
                    otps = cppMetrics.otps,
                    oet = cppMetrics.oet
                )

                val result = PredictionResult(item, predicted, metrics = finalMetrics)
                firebaseService.savePredictionResult(result)

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

    // [MODIFIED] Faster Batch Prediction with correct Progress Bar
    private fun startBatchPrediction(dataset: Dataset) {
        lifecycleScope.launch(Dispatchers.IO) {

            withContext(Dispatchers.Main) {
                btnPredictAll.isEnabled = false
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = 0
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = "Preparing..."
            }

            val results = mutableListOf<PredictionResult>()
            var success = 0
            var fail = 0
            val modelFile = File(filesDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
            val modelPath = modelFile.absolutePath

            val totalItems = dataset.foodItems.size
            var itemsProcessed = 0

            for ((index, item) in dataset.foodItems.withIndex()) {

                val currentStatusText = "Processing ${index + 1}/$totalItems: ${item.name}"
                withContext(Dispatchers.Main) {
                    tvProgress.text = "$currentStatusText..."
                }

                try {
                    val prompt = buildPrompt(item.ingredients)

                    val javaBefore = MemoryReader.javaHeapKb()
                    val nativeBefore = MemoryReader.nativeHeapKb()
                    val pssBefore = MemoryReader.totalPssKb()
                    val startNs = System.nanoTime()

                    // reportProgress = false because we are updating the bar per ITEM, not per token
                    val rawResult = inferAllergens(prompt, modelPath, false)

                    val latencyMs = (System.nanoTime() - startNs) / 1_000_000
                    val javaAfter = MemoryReader.javaHeapKb()
                    val nativeAfter = MemoryReader.nativeHeapKb()
                    val pssAfter = MemoryReader.totalPssKb()

                    val (predicted, cppMetrics) = parseRawResult(rawResult)

                    val finalMetrics = InferenceMetrics(
                        latencyMs = latencyMs,
                        javaHeapKb = javaAfter - javaBefore,
                        nativeHeapKb = nativeAfter - nativeBefore,
                        totalPssKb = pssAfter - pssBefore,
                        ttft = cppMetrics.ttft,
                        itps = cppMetrics.itps,
                        otps = cppMetrics.otps,
                        oet = cppMetrics.oet
                    )

                    results.add(PredictionResult(item, predicted, metrics = finalMetrics))

                    success++
                    notificationManager.showProgressNotification(index + 1, totalItems, item.name)

                    // [REMOVED] delay(500) - Removed artificial delay for maximum speed

                    itemsProcessed++
                    val targetPercentage = (itemsProcessed.toFloat() / totalItems.toFloat()) * 100

                    withContext(Dispatchers.Main) {
                        // Use native setProgress with animation (API 24+)
                        // This prevents the "jumping back" issue of custom ObjectAnimators
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            progressBar.setProgress(targetPercentage.toInt(), true)
                        } else {
                            progressBar.progress = targetPercentage.toInt()
                        }
                    }

                } catch (e: Exception) {
                    fail++
                    Log.e("BATCH_ERROR", "Failed item ${item.name}: ${e.message}")
                }
            }

            val (fbSuccess, fbFail) = firebaseService.saveBatchResults(results)
            predictionResults.clear()
            predictionResults.addAll(results)

            withContext(Dispatchers.Main) {
                // Ensure bar is full at the end
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progressBar.setProgress(100, true)
                } else {
                    progressBar.progress = 100
                }
                tvProgress.text = "Completed!"

                // Small delay just so the user sees "Completed" before it disappears
                kotlinx.coroutines.delay(500)

                hideProgress()
                btnPredictAll.isEnabled = true
                btnViewResults.visibility = View.VISIBLE
                notificationManager.showCompletionNotification(success, totalItems, dataset.name, fbSuccess, fbFail)
                Toast.makeText(this@MainActivity, "Batch Completed! Success: $success, Fail: $fail", Toast.LENGTH_LONG).show()
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

    private fun parseRawResult(rawResult: String): Pair<String, InferenceMetrics> {
        val allowedAllergens = setOf(
            "milk", "egg", "peanut", "tree nut",
            "wheat", "soy", "fish", "shellfish", "sesame"
        )

        val parts = rawResult.split("|", limit = 2)
        val meta = parts[0]
        val rawOutput = if (parts.size > 1) parts[1] else ""

        var ttft = 0L
        var itps = 0L
        var otps = 0L
        var oet = 0L

        meta.split(";").forEach {
            when {
                it.startsWith("TTFT_MS=") -> ttft = it.removePrefix("TTFT_MS=").toLongOrNull() ?: 0L
                it.startsWith("ITPS=") -> itps = it.removePrefix("ITPS=").toLongOrNull() ?: 0L
                it.startsWith("OTPS=") -> otps = it.removePrefix("OTPS=").toLongOrNull() ?: 0L
                it.startsWith("OET_MS=") -> oet = it.removePrefix("OET_MS=").toLongOrNull() ?: 0L
            }
        }

        val cleanedString = rawOutput
            .replace("Assistant:", "", ignoreCase = true)
            .replace("System:", "", ignoreCase = true)
            .replace("User:", "", ignoreCase = true)
            .lowercase()

        val detectedSet = mutableSetOf<String>()

        for (allergen in allowedAllergens) {
            val regex = "\\b${Regex.escape(allergen)}\\b".toRegex()
            if (regex.containsMatchIn(cleanedString)) {
                detectedSet.add(allergen)
            }
        }

        val finalAllergens = detectedSet.joinToString(", ").ifEmpty { "EMPTY" }

        return Pair(
            finalAllergens,
            InferenceMetrics(0, 0, 0, 0, ttft, itps, otps, oet)
        )
    }

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
