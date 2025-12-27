// FoodItem.kt
package edu.utem.ftmk.slm02

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize // 1. Add Annotation
data class FoodItem(
    val id: String,
    val name: String,
    val ingredients: String,
    val allergens: String,
    val link: String,
    val allergensMapped: String,
) : Parcelable // 2. Implement interface
