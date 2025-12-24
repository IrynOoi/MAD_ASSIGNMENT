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
            context.assets.open("foodpreprocessed.csv").use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                reader.readLine() // Skip header

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) continue

                    // [FIX] Do NOT use .split(","). Use this manual parser.
                    val cols = parseCsvLine(line!!)

                    // We need at least 5 columns (0 to 4) to get the Link
                    if (cols.size >= 5) {
                        fun String.clean() = this.trim().replace("\"", "")

                        val id = cols[0].clean()
                        val name = cols[1].clean()
                        val ingredients = cols[2].clean()
                        val rawAllergens = cols[3].clean()
                        val link = cols[4].clean()

                        // [FIX] If column 5 (Mapped) is missing/empty, use Raw Allergens (Col 3)
                        // This fixes the "Expected: Empty" issue
                        val mapped = if (cols.size > 5 && cols[5].isNotBlank()) cols[5].clean() else rawAllergens

                        foodItems.add(FoodItem(id, name, ingredients, rawAllergens, link, mapped))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CSV_READER", "Error", e)
        }
        return foodItems
    }

    // [FIX] This function handles "sugar, water" correctly so columns don't shift
    private fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        var sb = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes // Toggle mode
                char == ',' && !inQuotes -> {       // Only split if NOT in quotes
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