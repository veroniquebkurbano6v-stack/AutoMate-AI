package com.palmagent.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 位置服务：获取设备当前经纬度，供高德地图 MCP 工具调用时使用。
 *
 * 使用纯 LocationManager（不依赖 Google Play Services），兼容国内设备。
 * 优先级：GPS > Network
 */
object LocationService {

    private const val TAG = "LocationService"
    private const val LOCATION_TIMEOUT_MS = 8000L
    private const val MIN_DISTANCE_M = 0f
    private const val MIN_TIME_MS = 0L

    // 缓存最近一次成功定位结果，避免频繁请求
    @Volatile
    private var cachedLocation: LocationData? = null
    private const val CACHE_VALID_MS = 60_000L // 缓存有效期 60 秒

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long
    )

    /**
     * 检查是否有位置权限
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 获取当前位置（挂起函数，带超时）
     * 优先使用缓存，缓存过期则重新定位
     */
    suspend fun getCurrentLocation(context: Context): LocationData? {
        // 检查缓存
        cachedLocation?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_VALID_MS) {
                Log.d(TAG, "使用缓存位置: lat=${cached.latitude}, lng=${cached.longitude}")
                return cached
            }
        }

        if (!hasLocationPermission(context)) {
            Log.w(TAG, "无位置权限，无法获取位置")
            return null
        }

        // 优先使用 lastKnownLocation（最快，无需等待）
        val lastKnown = getLastKnownLocation(context)
        if (lastKnown != null) {
            // 如果 lastKnown 比较新（5分钟内），直接返回
            if (System.currentTimeMillis() - lastKnown.timestamp < 300_000L) {
                cachedLocation = lastKnown
                return lastKnown
            }
        }

        // 请求实时定位（带超时）
        val realTimeResult = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            requestRealtimeLocation(context)
        }

        // 优先返回实时定位，否则返回 lastKnown
        val result = realTimeResult ?: lastKnown
        if (result != null) {
            cachedLocation = result
        }
        return result
    }

    /**
     * 获取 lastKnownLocation（最快，无需等待定位）
     */
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(context: Context): LocationData? {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // 优先 GPS，回退 Network
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )

            for (provider in providers) {
                if (!locationManager.isProviderEnabled(provider)) continue
                val location = locationManager.getLastKnownLocation(provider) ?: continue
                return LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = System.currentTimeMillis() // 使用当前时间，因为 lastKnown 的时间可能很旧
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "getLastKnownLocation 权限异常", e)
        } catch (e: Exception) {
            Log.e(TAG, "getLastKnownLocation 异常", e)
        }
        return null
    }

    /**
     * 请求实时定位（监听一次位置更新）
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestRealtimeLocation(context: Context): LocationData? {
        return suspendCancellableCoroutine { cont ->
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                )

                val listener = object : LocationListener {
                    private var resolved = false

                    override fun onLocationChanged(location: Location) {
                        if (resolved) return
                        resolved = true
                        val data = LocationData(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = System.currentTimeMillis()
                        )
                        Log.d(TAG, "实时定位成功: lat=${data.latitude}, lng=${data.longitude}, accuracy=${data.accuracy}")
                        if (cont.isActive) cont.resume(data)
                        providers.forEach { provider ->
                            runCatching { locationManager.removeUpdates(this) }
                        }
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                // 注册所有可用 provider 的监听
                var registered = false
                for (provider in providers) {
                    if (locationManager.isProviderEnabled(provider)) {
                        runCatching {
                            locationManager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
                            registered = true
                        }
                    }
                }

                if (!registered) {
                    Log.w(TAG, "无可用位置 provider")
                    cont.resume(null)
                }

                cont.invokeOnCancellation {
                    providers.forEach { provider ->
                        runCatching { locationManager.removeUpdates(listener) }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "requestRealtimeLocation 权限异常", e)
                cont.resume(null)
            } catch (e: Exception) {
                Log.e(TAG, "requestRealtimeLocation 异常", e)
                cont.resume(null)
            }
        }
    }

    /**
     * 获取位置信息字符串，用于传递给高德 MCP
     * 格式: "经度,纬度"（高德 API 标准坐标顺序）或 null
     */
    suspend fun getLocationString(context: Context): String? {
        val location = getCurrentLocation(context) ?: return null
        return "${location.longitude},${location.latitude}"
    }
}
