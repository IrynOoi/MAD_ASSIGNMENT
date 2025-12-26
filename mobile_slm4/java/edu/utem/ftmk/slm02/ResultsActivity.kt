//ResultsActivity.kt
package edu.utem.ftmk.slm02

import android.annotation.SuppressLint
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

    @SuppressLint("MissingInflatedId")
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
            val tvIngredients: TextView = view.findViewById(R.id.tvIngredients)
            val tvRawAllergens: TextView = view.findViewById(R.id.tvRawAllergens) // [NEW]
            val tvExpected: TextView = view.findViewById(R.id.tvExpected)
            val tvPredicted: TextView = view.findViewById(R.id.tvPredicted)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = results[position]

            holder.tvFoodName.text = result.foodItem.name
            holder.tvIngredients.text = "Ingredients: ${result.foodItem.ingredients}"

            // [NEW] Bind Raw Allergens
            holder.tvRawAllergens.text = "Raw Allergens: ${result.foodItem.allergens}"

            holder.tvExpected.text = "Mapped (Expected): ${result.foodItem.allergensMapped}"
            holder.tvPredicted.text = "Predicted: ${result.predictedAllergens}"
        }

        override fun getItemCount() = results.size
    }
}