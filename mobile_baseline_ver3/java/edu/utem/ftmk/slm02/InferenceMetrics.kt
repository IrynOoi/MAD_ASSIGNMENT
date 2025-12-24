//InferenceMetrics.kt
package edu.utem.ftmk.slm01

import android.os.Parcelable // 1. Add Import
import kotlinx.parcelize.Parcelize // 2. Add Import
/**
 * BITP 3453 Mobile Application Development
 *
 * @author Emaliana Kasmuri FTMK, UTeM
 *
 * Purpose:
 * Represent the metrics to measure the inference performance
 */
@Parcelize // 3. Add Annotation
data class InferenceMetrics (
    // Total time taken to complete the inference
    val latencyMs: Long,

    // Memory snapshot
    val javaHeapKb: Long,
    val nativeHeapKb: Long,
    val totalPssKb: Long,

    // Efficiency metrics
    val ttft: Long,
    val itps: Long,
    val otps: Long,
    val oet: Long

) : Parcelable // 4. Implement Interface
