//MainActivity.kt
package edu.utem.ftmk.slm02

import FirebaseService
import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.BuildConfig
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

        // ===== Build Type Debug/Release Log =====
        Log.d("BUILD_CHECK", "Current Build Type: ${BuildConfig.BUILD_TYPE}")
        // ========================================
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

    // [FIXED] Safety guard for progress updates
    fun updateNativeProgress(percent: Int) {
        runOnUiThread {
            // Only update if "Predict All" is enabled (meaning NOT currently running a batch)
            // OR if we specifically want to force it for single item.
            // The logic here is: If batch is running (btn disabled), we ignore this to prevent UI stutter.
            // If single item is running, we usually want to see it.
            if (progressBar.visibility == View.VISIBLE && !progressBar.isIndeterminate) {
                val safePercent = percent.coerceIn(0, 100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progressBar.setProgress(safePercent, true)
                } else {
                    progressBar.progress = safePercent
                }

                // Only update text if NOT in batch mode (Batch mode handles its own text)
                if (btnPredictAll.isEnabled) {
                    tvProgress.text = "Predicting: $safePercent%"
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

    // [UPDATED] Single Item Prediction with Full Metrics
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

                // 1. MEASURE MEMORY BEFORE
                val javaBefore = MemoryReader.javaHeapKb()
                val nativeBefore = MemoryReader.nativeHeapKb()
                val pssBefore = MemoryReader.totalPssKb()
                val startNs = System.nanoTime()

                // 2. RUN INFERENCE
                // reportProgress = true so we see the bar moving for single items
                val rawResult = inferAllergens(prompt, modelFile.absolutePath, true)

                // 3. MEASURE AFTER
                val latencyMs = (System.nanoTime() - startNs) / 1_000_000
                val javaAfter = MemoryReader.javaHeapKb()
                val nativeAfter = MemoryReader.nativeHeapKb()
                val pssAfter = MemoryReader.totalPssKb()

                // 4. PARSE RESULT (Get TTFT, ITPS, OTPS, OET from C++ output)
                val (predicted, cppMetrics) = parseRawResult(rawResult)

                // 5. MERGE METRICS
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

                // 6. SAVE TO FIREBASE
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

    // [UPDATED] Batch Prediction with Full Metrics
    private fun startBatchPrediction(dataset: Dataset) {
        lifecycleScope.launch(Dispatchers.IO) {

            // 1. Initial UI Setup
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

                    // --- START MEASURING ---
                    val javaBefore = MemoryReader.javaHeapKb()
                    val nativeBefore = MemoryReader.nativeHeapKb()
                    val pssBefore = MemoryReader.totalPssKb()
                    val startNs = System.nanoTime()

                    // A. THE REAL WORK
                    // reportProgress = false (Batch manages its own progress bar)
                    val rawResult = inferAllergens(prompt, modelPath, false)

                    // --- STOP MEASURING ---
                    val latencyMs = (System.nanoTime() - startNs) / 1_000_000
                    val javaAfter = MemoryReader.javaHeapKb()
                    val nativeAfter = MemoryReader.nativeHeapKb()
                    val pssAfter = MemoryReader.totalPssKb()

                    // Parse Result & C++ Metrics
                    val (predicted, cppMetrics) = parseRawResult(rawResult)

                    // Calculate Final Metrics
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

                    // Add to results list with REAL metrics
                    results.add(PredictionResult(item, predicted, metrics = finalMetrics))

                    success++
                    notificationManager.showProgressNotification(index + 1, totalItems, item.name)

                    // B. SMOOTH ANIMATION DELAY
                    delay(500)

                    // C. UPDATE UI
                    itemsProcessed++
                    val targetPercentage = (itemsProcessed.toFloat() / totalItems.toFloat()) * 100
                    withContext(Dispatchers.Main) {
                        animateProgress(targetPercentage.toInt(), currentStatusText)
                    }

                } catch (e: Exception) {
                    fail++
                    Log.e("BATCH_ERROR", "Failed item ${item.name}: ${e.message}")
                }
            }

            // Save results to Firebase
            val (fbSuccess, fbFail) = firebaseService.saveBatchResults(results)
            predictionResults.clear()
            predictionResults.addAll(results)

            // Final Completion State
            withContext(Dispatchers.Main) {
                animateProgress(100, "Finishing up")
                delay(800)

                hideProgress()
                btnPredictAll.isEnabled = true
                btnViewResults.visibility = View.VISIBLE
                notificationManager.showCompletionNotification(success, totalItems, dataset.name, fbSuccess, fbFail)
                Toast.makeText(this@MainActivity, "Batch Completed! Success: $success, Fail: $fail", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Helper for smooth animation
    private fun animateProgress(targetProgress: Int, messagePrefix: String) {
        val animation = ObjectAnimator.ofInt(
            progressBar,
            "progress",
            progressBar.progress,
            targetProgress
        )

        animation.duration = 800
        animation.interpolator = DecelerateInterpolator()

        animation.addUpdateListener { valueAnimator ->
            val animatedValue = valueAnimator.animatedValue as Int
            tvProgress.text = "$messagePrefix ($animatedValue%)"
        }

        animation.start()
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
        // List of supported allergens we want to detect
        val allowedAllergens = setOf(
            "milk", "egg", "peanut", "tree nut",
            "wheat", "soy", "fish", "shellfish", "sesame"
        )

        // 1. Parse metadata section (TTFT, ITPS, OTPS, OET)
        // Format example: "TTFT_MS=12;ITPS=34;OTPS=56;OET_MS=78|<model output>"
        val parts = rawResult.split("|", limit = 2)
        val meta = parts[0]
        val rawOutput = if (parts.size > 1) parts[1] else ""

        var ttft = 0L
        var itps = 0L
        var otps = 0L
        var oet = 0L

        // Extract numeric values from metadata
        meta.split(";").forEach {
            when {
                it.startsWith("TTFT_MS=") ->
                    ttft = it.removePrefix("TTFT_MS=").toLongOrNull() ?: 0L
                it.startsWith("ITPS=") ->
                    itps = it.removePrefix("ITPS=").toLongOrNull() ?: 0L
                it.startsWith("OTPS=") ->
                    otps = it.removePrefix("OTPS=").toLongOrNull() ?: 0L
                it.startsWith("OET_MS=") ->
                    oet = it.removePrefix("OET_MS=").toLongOrNull() ?: 0L
            }
        }

        // 2. Clean the model output and prepare for allergen matching
        // Remove role prefixes and normalize to lowercase
        val cleanedString = rawOutput
            .replace("Assistant:", "", ignoreCase = true)
            .replace("System:", "", ignoreCase = true)
            .replace("User:", "", ignoreCase = true)
            .lowercase()

        val detectedSet = mutableSetOf<String>()

        // Use regex with word boundaries to avoid overlap issues
        // Example: prevent "shellfish" from matching "fish"
        for (allergen in allowedAllergens) {
            // \b ensures full-word matching only
            val regex = "\\b${Regex.escape(allergen)}\\b".toRegex()
            if (regex.containsMatchIn(cleanedString)) {
                detectedSet.add(allergen)
            }
        }

        // Join detected allergens into a comma-separated string
        // If none found, return "EMPTY"
        val finalAllergens = detectedSet.joinToString(", ").ifEmpty { "EMPTY" }

        // Return parsed allergens and inference metrics
        // Note: latency and memory values are calculated externally
        return Pair(
            finalAllergens,
            InferenceMetrics(0, 0, 0, 0, ttft, itps, otps, oet)
        )
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