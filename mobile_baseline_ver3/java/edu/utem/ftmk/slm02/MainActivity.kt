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

import android.widget.ArrayAdapter

import android.widget.Button

import android.widget.EditText

import android.widget.ProgressBar

import android.widget.Spinner

import android.widget.TextView

import android.widget.Toast

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



    external fun inferAllergens(input: String): String



// Services

    private lateinit var csvReader: CsvReader

    private lateinit var datasetManager: DatasetManager

    private lateinit var firebaseService: FirebaseService

    private lateinit var notificationManager: NotificationManager



// UI

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



// Data

    private var allFoodItems: List<FoodItem> = emptyList()

    private var datasets: List<Dataset> = emptyList()

    private var selectedDataset: Dataset? = null

    private var predictionResults: MutableList<PredictionResult> = mutableListOf()



    private val allowedAllergens = setOf(

        "milk", "egg", "peanut", "tree nut", "wheat", "soy", "fish", "shellfish", "sesame"

    )



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)



        initializeServices()

        initializeUI()



// 1. Request Permission on Startup

        checkAndRequestPermissions()



        lifecycleScope.launch(Dispatchers.IO) {

            copyModelIfNeeded(this@MainActivity)

        }



        loadDataAsync()

    }



    private fun checkAndRequestPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (ContextCompat.checkSelfPermission(

                    this,

                    Manifest.permission.POST_NOTIFICATIONS

                ) != PackageManager.PERMISSION_GRANTED

            ) {

                ActivityCompat.requestPermissions(

                    this,

                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),

                    101 // Request Code

                )

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



        setupEventListeners()

    }



    private fun setupEventListeners() {

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

            selectedDataset?.let { dataset ->

                startBatchPrediction(dataset)

            }

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

            showProgress("Loading food data from CSV...")



            try {

// 1. Debug: Check if file exists

                val assetList = assets.list("")

                if (assetList?.contains("foodpreprocessed.csv") == true) {

                    Log.d("DATA_CHECK", "File 'foodpreprocessed.csv' FOUND in assets.")

                } else {

                    Log.e("DATA_CHECK", "File 'foodpreprocessed.csv' NOT FOUND in assets!")

                }



// 2. Read CSV

                allFoodItems = csvReader.readFoodItemsFromAssets()

                Log.d("DATA_CHECK", "Read ${allFoodItems.size} items from CSV.")



                if (allFoodItems.isEmpty()) {

                    withContext(Dispatchers.Main) {

                        hideProgress()

                        Toast.makeText(this@MainActivity, "CSV is empty or failed to read!", Toast.LENGTH_LONG).show()

                    }

                    return@launch

                }



// 3. Create Datasets

                datasets = datasetManager.createDatasets(allFoodItems)

                Log.d("DATA_CHECK", "Created ${datasets.size} datasets.")



// 4. Update UI

                withContext(Dispatchers.Main) {

                    setupDatasetSpinner()

                    hideProgress()



                    if (datasets.isNotEmpty()) {

                        Toast.makeText(this@MainActivity, "Loaded ${datasets.size} datasets", Toast.LENGTH_SHORT).show()

                    } else {

                        Toast.makeText(this@MainActivity, "No datasets created!", Toast.LENGTH_LONG).show()

                    }

                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {

                    hideProgress()

                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()

                    Log.e("DATA_CHECK", "Error loading data", e)

                }

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



            dataset.foodItems.forEachIndexed { index, item ->

                withContext(Dispatchers.Main) { showProgress("Processing ${index + 1}/${dataset.foodItems.size}") }

                try {

                    val prompt = buildPrompt(item.ingredients)



                    val javaBefore = MemoryReader.javaHeapKb()

                    val nativeBefore = MemoryReader.nativeHeapKb()

                    val pssBefore = MemoryReader.totalPssKb()

                    val startNs = System.nanoTime()



                    val rawResult = inferAllergens(prompt)



                    val latency = (System.nanoTime() - startNs) / 1_000_000

                    val javaAfter = MemoryReader.javaHeapKb()

                    val nativeAfter = MemoryReader.nativeHeapKb()

                    val pssAfter = MemoryReader.totalPssKb()



                    val (predicted, parsedMetrics) = parseRawResult(rawResult)



                    val metrics = InferenceMetrics(

                        latencyMs = latency,

                        javaHeapKb = javaAfter - javaBefore,

                        nativeHeapKb = nativeAfter - nativeBefore,

                        totalPssKb = pssAfter - pssBefore,

                        ttft = parsedMetrics.ttft,

                        itps = parsedMetrics.itps,

                        otps = parsedMetrics.otps,

                        oet = parsedMetrics.oet

                    )



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



// --- FIX APPLIED HERE ---

// Changed '0' to 'dataset.name'

                notificationManager.showCompletionNotification(

                    success,

                    dataset.foodItems.size,

                    dataset.name, // Passing the String name here

                    fbSuccess,

                    fbFail

                )



                Toast.makeText(this@MainActivity, "Batch Complete!", Toast.LENGTH_LONG).show()

            }

        }

    }



    private fun performSinglePrediction(ingredients: String) {

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val prompt = buildPrompt(ingredients)

                val rawResult = inferAllergens(prompt)

                val (predicted, _) = parseRawResult(rawResult)

                withContext(Dispatchers.Main) {

                    tvResult.text = if (predicted.isEmpty() || predicted == "EMPTY") "No allergens" else "Detected: $predicted"

                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) { tvResult.text = "Error: ${e.message}" }

            }

        }

    }



    private fun parseRawResult(rawResult: String): Pair<String, InferenceMetrics> {

        val parts = rawResult.split("|", limit = 2)

        val meta = parts[0]

        val rawOutput = if (parts.size > 1) parts[1] else ""

        var ttftMs = -1L

        var itps = -1L

        var otps = -1L

        var oetMs = -1L



        meta.split(";").forEach {

            when {

                it.startsWith("TTFT_MS=") -> ttftMs = it.removePrefix("TTFT_MS=").toLongOrNull() ?: -1L

                it.startsWith("ITPS=") -> itps = it.removePrefix("ITPS=").toLongOrNull() ?: -1L

                it.startsWith("OTPS=") -> otps = it.removePrefix("OTPS=").toLongOrNull() ?: -1L

                it.startsWith("OET_MS=") -> oetMs = it.removePrefix("OET_MS=").toLongOrNull() ?: -1L

            }

        }



        val allergens = rawOutput.replace("Ġ", "").replace("\"", "").lowercase()

            .split(",").map { it.trim() }.filter { it in allowedAllergens }

            .joinToString(", ").ifEmpty { "EMPTY" }



        return Pair(allergens, InferenceMetrics(0L, 0L, 0L, 0L, ttftMs, itps, otps, oetMs))

    }



    private fun buildPrompt(ingredients: String): String {

        return "Task: Detect food allergens.\nIngredients:\n$ingredients\nAllowed allergens:\nmilk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame\nRules:\n- Output ONLY a comma-separated list of allergens.\n- If none are present, output EMPTY."

    }



    private fun copyModelIfNeeded(context: Context) {

        val modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"

        val outFile = File(context.filesDir, modelName)

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