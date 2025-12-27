//Dataset.kt
package edu.utem.ftmk.slm02

data class Dataset(
    val id: Int,
    val name: String,
    val description: String,
    val foodItems: List<FoodItem>
)
