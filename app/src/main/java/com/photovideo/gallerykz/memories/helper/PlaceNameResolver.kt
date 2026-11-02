package com.photovideo.gallerykz.memories.helper

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.round

/**
 * Turns the position of a place into a readable name ("Surat, Gujarat") with the system's [Geocoder].
 *
 * It is asked once per place (never per photo) and the answer is kept in Room by the caller, so a place is looked
 * up again only if the lookup failed. Only a rounded position (about 1 km) of the place is sent, never a photo or
 * its exact coordinates. When the device has no geocoder, is offline or the lookup fails, the place keeps showing
 * "Unknown Location" and is tried again later (not more often than every [RETRY_MS]).
 */
object PlaceNameResolver {

    private const val TAG = "PlaceName"
    private const val RETRY_MS = 10 * 60 * 1000L

    /** What a lookup gave. */
    sealed interface Result {
        /** A readable name. */
        data class Named(val name: String) : Result

        /** The lookup worked, but the position has no name (open sea, unmapped area): not asked again. */
        object Unknown : Result

        /** The lookup could not be made (offline, no geocoder, service error): asked again later. */
        object Failed : Result
    }

    private val failedAt = HashMap<Long, Long>()

    /** Looks up the name of the place [cell] at ([latitude], [longitude]). Suspends off the main thread. */
    suspend fun resolve(context: Context, cell: Long, latitude: Double, longitude: Double): Result {
        val last = synchronized(failedAt) { failedAt[cell] }
        if (last != null && System.currentTimeMillis() - last < RETRY_MS) return Result.Failed
        val result = withContext(Dispatchers.IO) { lookup(context.applicationContext, latitude, longitude) }
        synchronized(failedAt) {
            if (result == Result.Failed) failedAt[cell] = System.currentTimeMillis() else failedAt.remove(cell)
        }
        return result
    }

    private suspend fun lookup(context: Context, latitude: Double, longitude: Double): Result {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "no geocoder on this device")
            return Result.Failed
        }
        // About 1 km: enough to name a town, too little to point at a house.
        val lat = round(latitude * 100.0) / 100.0
        val lon = round(longitude * 100.0) / 100.0
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                fromLocationAsync(geocoder, lat, lon)
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)
            }
            when {
                addresses == null -> Result.Failed
                else -> addresses.firstOrNull()?.let(::nameOf)?.let { Result.Named(it) } ?: Result.Unknown
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "lookup failed: offline or service error")
            Result.Failed
        } catch (e: IllegalArgumentException) {
            Result.Unknown // a position that cannot exist
        } catch (e: RuntimeException) {
            Log.w(TAG, "lookup failed: ${e.javaClass.simpleName}")
            Result.Failed
        }
    }

    /** Android 13+: the lookup is asynchronous. Null means it failed. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun fromLocationAsync(geocoder: Geocoder, lat: Double, lon: Double): List<Address>? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses)
                }

                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    /** "City, State" from what the address has (the town or the district, and the state or else the country). */
    private fun nameOf(address: Address): String? {
        val place = address.locality ?: address.subAdminArea ?: address.subLocality ?: address.adminArea
        val region = address.adminArea ?: address.countryName
        return listOfNotNull(place, region).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ").takeIf { it.isNotEmpty() }
    }
}
