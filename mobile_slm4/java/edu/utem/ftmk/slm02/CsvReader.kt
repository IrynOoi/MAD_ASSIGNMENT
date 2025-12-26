// CsvReader.kt
package edu.utem.ftmk.slm02

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class CsvReader(private val context: Context) {

    fun readFoodItemsFromAssets(): List<FoodItem> {
        val foodItems = mutableListOf<FoodItem>()
        try {
            // Make sure the filename matches your uploaded file
            context.assets.open("foodpreprocessed.csv").use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                reader.readLine() // Skip the header row

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) continue

                    val cols = parseCsvLine(line!!)

                    // We now expect at least 6 columns based on your CSV structure
                    if (cols.size >= 5) {
                        fun String.clean() = this.trim().replace("\"", "")

                        // --- NEW MAPPING BASED ON YOUR CSV ORDER ---
                        val id = cols[0].clean()
                        val name = cols[1].clean()
                        val link = cols[2].clean()          // Column 2 is Link
                        val ingredients = cols[3].clean()   // Column 3 is Ingredients
                        val rawAllergens = cols[4].clean()  // Column 4 is AllergensRaw

                        // Handle column 5 (Mapped) safely in case it's missing or empty
                        var mapped = ""
                        if (cols.size > 5) {
                            mapped = cols[5].clean()
                        }

                        // Pass to FoodItem in the order defined in your FoodItem.kt data class:
                        // (id, name, ingredients, allergens, link, allergensMapped)
                        foodItems.add(FoodItem(id, name, ingredients, rawAllergens, link, mapped))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CSV", "Error reading CSV", e)
        }
        return foodItems
    }

    // [FIX] Manual Parsing Logic
    private fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        var sb = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    tokens.add(sb.toString())
                    sb = StringBuilder()
                }
                else -> sb.append(char)
            }
        }
        tokens.add(sb.toString())
        return tokens
    }
}