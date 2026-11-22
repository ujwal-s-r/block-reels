package com.blockreels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager

class BlockerModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  override fun getName() = "Blocker"

  @ReactMethod
  fun getState(promise: Promise) {
    try {
      val preferences = context.getSharedPreferences("blocker", Context.MODE_PRIVATE)
      val state = Arguments.createMap()
      keys.forEach { state.putBoolean(it, preferences.getBoolean(it, true)) }
      val manager = context.getSystemService(AccessibilityManager::class.java)
      val component = ComponentName(context, BlockerService::class.java)
      val authorized = manager.getEnabledAccessibilityServiceList(-1).any {
        ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == component
      }
      state.putBoolean("serviceEnabled", authorized)
      state.putBoolean("serviceRunning", BlockerService.running)
      promise.resolve(state)
    } catch (error: Exception) {
      promise.reject("STATE_ERROR", "Unable to read settings", error)
    }
  }

  @ReactMethod
  fun setSetting(key: String, value: Boolean, promise: Promise) {
    if (key !in keys) {
      promise.reject("INVALID_SETTING", "Unknown setting")
      return
    }
    val saved = context.getSharedPreferences("blocker", Context.MODE_PRIVATE)
      .edit().putBoolean(key, value).commit()
    if (!saved) {
      promise.reject("STORAGE_ERROR", "Unable to save setting")
      return
    }
    getState(promise)
  }

  @ReactMethod
  fun openAccessibilitySettings(promise: Promise) {
    try {
      context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("SETTINGS_ERROR", "Unable to open accessibility settings", error)
    }
  }

  companion object {
    val keys = setOf("enabled", "blockReelsButton", "blockReelsScroll", "allowDmReels", "blockShortsButton", "blockShortsScroll")
  }
}

class BlockerPackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(BlockerModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}