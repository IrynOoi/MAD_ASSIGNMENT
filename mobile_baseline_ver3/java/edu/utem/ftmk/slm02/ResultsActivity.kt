//ResultsActivity.kt
package edu.utem.ftmk.slm02

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton // CHANGED: Imported ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ResultsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultsAdapter

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        // 1. Get the data passed from the previous activity
        val results = intent.getParcelableArrayListExtra<PredictionResult>("results")

        // 2. FIX: Find the RecyclerView using its ID
        recyclerView = findViewById(R.id.recyclerViewResults)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 3. Set the adapter
        adapter = ResultsAdapter(results ?: emptyList())
        recyclerView.adapter = adapter

        // 4. FIX: Find the ImageButton using its ID
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }
    }

    class ResultsAdapter(private val results: List<PredictionResult>) :
        RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFoodName: TextView = view.findViewById(R.id.tvFoodName)
            val tvIngredients: TextView = view.findViewById(R.id.tvIngredients)
            val tvExpected: TextView = view.findViewById(R.id.tvExpected)
            val tvPredicted: TextView = view.findViewById(R.id.tvPredicted)
            val tvAccuracy: TextView = view.findViewById(R.id.tvAccuracy)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = results[position]

            holder.tvFoodName.text = result.foodItem.name
            holder.tvIngredients.text =
                "Ingredients: ${result.foodItem.ingredients}" // Added label for clarity
            holder.tvExpected.text = "Expected: ${result.foodItem.allergensMapped}"
            holder.tvPredicted.text = "Predicted: ${result.predictedAllergens}"

            // Calculate accuracy
            val expectedSet = result.foodItem.allergensMapped
                .split(",")
                .map { it.trim().lowercase() } // FIX: standardized to lowercase
                .filter { it.isNotEmpty() && it != "empty" } // FIX: Filter "empty" string too
                .toSet()

            val predictedSet = result.predictedAllergens
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it != "empty" }
                .toSet()

            val accuracy = calculateAccuracy(expectedSet, predictedSet)
            holder.tvAccuracy.text = "Accuracy: ${"%.1f".format(accuracy)}%"

            holder.tvAccuracy.setTextColor(
                when {
                    accuracy >= 80 -> Color.GREEN
                    accuracy >= 50 -> Color.BLUE
                    else -> Color.RED
                }
            )
        }

        override fun getItemCount() = results.size

        private fun calculateAccuracy(expected: Set<String>, predicted: Set<String>): Float {
            // If both are truly empty (No allergens expected, none predicted) -> 100% correct
            if (expected.isEmpty() && predicted.isEmpty()) return 100f

            // If one is empty but the other is not -> 0% correct
            if (expected.isEmpty() || predicted.isEmpty()) return 0f

            val truePositives = expected.intersect(predicted).size

            val precision = truePositives.toFloat() / predicted.size
            val recall = truePositives.toFloat() / expected.size

            if (precision + recall == 0f) return 0f

            // F1 Score calculation
            return (2 * precision * recall) / (precision + recall) * 100
        }
    }
}