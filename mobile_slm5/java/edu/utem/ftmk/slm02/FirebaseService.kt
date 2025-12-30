// FirebaseService.kt
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import edu.utem.ftmk.slm02.PredictionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class FirebaseService {

    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("allergen_prediction")

    suspend fun savePredictionResult(result: PredictionResult): String
    {
        return try {
            // [MODIFIED] Only include the specific fields required by the assignment (a-h)
            val data = hashMapOf<String, Any>(
                "dataId" to result.foodItem.id,               // a. Data id
                "name" to result.foodItem.name,               // b. Name
                "ingredients" to result.foodItem.ingredients, // c. Ingredients
                "allergens" to result.foodItem.allergens,     // d. Allergens (Raw)
                "mappedAllergens" to result.foodItem.allergensMapped, // e. Mapped Allergens
                "predictedAllergens" to result.predictedAllergens,    // f. Predicted Allergens
                "timestamp" to FieldValue.serverTimestamp()   // g. Timestamp
            )

            // h. All inference metrics
            result.metrics?.let { metrics ->
                data["metrics"] = hashMapOf(
                    "latencyMs" to metrics.latencyMs,
                    "ttft" to metrics.ttft,
                    "itps" to metrics.itps,
                    "otps" to metrics.otps,
                    "oet" to metrics.oet,
                    "javaHeapKb" to metrics.javaHeapKb,
                    "nativeHeapKb" to metrics.nativeHeapKb,
                    "totalPssKb" to metrics.totalPssKb
                )
            }

            // Save to Firestore
            val documentRef = collection.add(data).await()
            documentRef.id
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error saving to Firestore: ${e.message}")
            ""
        }
    }

    suspend fun saveBatchResults(results: List<PredictionResult>): Pair<Int, Int> {
        var successCount = 0
        var failureCount = 0

        results.forEach { result ->
            try {
                val docId = savePredictionResult(result)
                if (docId.isNotEmpty()) {
                    successCount++
                } else {
                    failureCount++
                }
                delay(50) // Prevent rate limiting
            } catch (e: Exception) {
                failureCount++
                Log.e("FIREBASE", "Failed to save result: ${e.message}")
            }
        }

        return Pair(successCount, failureCount)
    }
}