package com.eventmanager.app.setup

/** Picks the Cloud Functions region that matches a Firestore location. */
object RegionMapper {
    private val multiRegions = mapOf(
        "eur3" to "europe-west1",
        "nam5" to "us-central1",
        "nam7" to "us-central1",
    )
    private val preferred = listOf("europe-west6", "eur3")

    fun functionsRegion(firestoreLocation: String): String {
        val loc = firestoreLocation.trim().lowercase()
        return multiRegions[loc] ?: loc
    }

    fun orderLocations(all: List<String>): List<String> =
        preferred.filter { it in all } + all.filterNot { it in preferred }
}
