//ResultsActivity.kt
package edu.utem.ftmk.slm02

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ResultsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val results = intent.getParcelableArrayListExtra<PredictionResult>("results")

        recyclerView = findViewById(R.id.recyclerViewResults)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ResultsAdapter(results ?: emptyList())
        recyclerView.adapter = adapter

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }
    }

    class ResultsAdapter(private val results: List<PredictionResult>) :
        RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFoodName: TextView = view.findViewById(R.id.tvFoodName)
            // Note: Use the new IDs for the 'Value' textviews
            val tvIngredients: TextView = view.findViewById(R.id.tvIngredientsValue)
            val tvRawAllergens: TextView = view.findViewById(R.id.tvRawAllergensValue)
            val tvExpected: TextView = view.findViewById(R.id.tvExpectedValue)
            val tvPredicted: TextView = view.findViewById(R.id.tvPredictedValue)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = results[position]

            // 1. Food Name
            holder.tvFoodName.text = item.foodItem.name

            // 2. Ingredients (Just the value)
            holder.tvIngredients.text = item.foodItem.ingredients

            // 3. Raw Allergens
            val rawText = if (item.foodItem.allergens.isEmpty() || item.foodItem.allergens == "empty") "None" else item.foodItem.allergens
            holder.tvRawAllergens.text = rawText

            // 4. Mapped (Expected)
            val mappedText = if (item.foodItem.allergensMapped.isEmpty()) "None" else item.foodItem.allergensMapped
            holder.tvExpected.text = mappedText

            // 5. Predicted
            val predictionText = item.predictedAllergens ?: "Loading..."
            holder.tvPredicted.text = predictionText
        }

        override fun getItemCount() = results.size
    }
}