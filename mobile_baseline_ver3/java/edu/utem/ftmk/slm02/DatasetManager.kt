// DatasetManager.kt
package edu.utem.ftmk.slm02


class DatasetManager {

    companion object {
        const val TOTAL_SETS = 20
    }

    fun createDatasets(foodItems: List<FoodItem>): List<Dataset> {
        val totalItems = foodItems.size
        val itemsPerSet = totalItems / TOTAL_SETS

        val datasets = mutableListOf<Dataset>()

        for (i in 0 until TOTAL_SETS) {
            val start = i * itemsPerSet
            val end = if (i == TOTAL_SETS - 1) totalItems else start + itemsPerSet

            val items = foodItems.subList(start, end)

            val dataset = Dataset(
                id = i + 1,
                name = "Dataset ${i + 1}",
                description = "${items.size} items (${start + 1}-$end)",
                foodItems = items
            )
            datasets.add(dataset)
        }

        return datasets
    }
}

