// CsvReader.kt
package edu.utem.ftmk.slm02

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class CsvReader(private val context: Context) {

    fun readFoodItemsFromAssets(): List<FoodItem> {
        val foodItems = mutableListOf<FoodItem>()

        context.assets.open("foodpreprocessed.csv").use { inputStream ->
            val reader = BufferedReader(InputStreamReader(inputStream))

            // Skip header
            reader.readLine()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val cols = line!!.split(",")

                if (cols.size >= 6) {
                    val foodItem = FoodItem(
                        id = cols[0].trim(),
                        name = cols[1].trim(),
                        ingredients = cols[2].trim(),
                        allergens = cols[3].trim(),
                        link = cols[4].trim(),
                        allergensMapped = cols[5].trim()
                    )
                    foodItems.add(foodItem)
                }
            }
        }

        Log.d("CSV_READER", "Loaded ${foodItems.size} food items from CSV")
        return foodItems
    }
}
