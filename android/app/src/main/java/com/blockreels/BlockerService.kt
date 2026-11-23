package com.blockreels

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class BlockerService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
  private val handler = Handler(Looper.getMainLooper())
  private val rules = BlockerRules()
  private val overlays = mutableListOf<View>()
  private var overlaySignature = ""
  private var pendingDmClick = false
  private var lastTrace = ""
  private var refreshAt = Long.MAX_VALUE
  private var replayingTap = false
  private val refresh = Runnable { refreshAt = Long.MAX_VALUE; render() }
  private val restoreTouches = Runnable {
    replayingTap = false
    render()
  }
  private lateinit var preferences: SharedPreferences
  private val screenOff = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      cancelRefresh()
      pendingDmClick = false
      rules.reset()
      clearOverlays()
    }
  }

  override fun onServiceConnected() {
    preferences = getSharedPreferences("blocker", MODE_PRIVATE)
    preferences.registerOnSharedPreferenceChangeListener(this)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    running = true
    scheduleRefresh()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!running) { return }
    val packageName = event.packageName?.toString()
    if (packageName == this.packageName) { return }
    if (packageName != null && packageName !in targets && packageName != this.packageName) {
      scheduleRefresh(16)
      return
    }
    if (packageName in targets && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
      val source = event.source
      val label = "${source?.text?.toString().orEmpty()} ${source?.contentDescription?.toString().orEmpty()} ${source?.viewIdResourceName.orEmpty()}"
        .lowercase(Locale.ROOT)
      if (packageName == INSTAGRAM) {
        pendingDmClick = pendingDmClick || label.contains("reel") || label.contains("clip")
      }
      val tab = if (packageName == INSTAGRAM) "reels" else "shorts"
      val blockTab = preferences.getBoolean(if (packageName == INSTAGRAM) "blockReelsButton" else "blockShortsButton", true)
      val rectangle = Rect()
      source?.getBoundsInScreen(rectangle)
      val windowBounds = Rect()
      source?.window?.getBoundsInScreen(windowBounds)
      if (preferences.getBoolean("enabled", true) && blockTab && !windowBounds.isEmpty &&
        rectangle.top >= windowBounds.top + windowBounds.height() * 0.65 &&
        (matchesVideoTab(source?.text?.toString().orEmpty(), source?.viewIdResourceName.orEmpty(), tab) ||
          matchesVideoTab(source?.contentDescription?.toString().orEmpty(), source?.viewIdResourceName.orEmpty(), tab))) {
        pendingDmClick = false
        performGlobalAction(GLOBAL_ACTION_BACK)
      }
    }
    scheduleRefresh(16)
  }

  private fun scheduleRefresh(delay: Long = 0) {
    val due = SystemClock.uptimeMillis() + delay
    if (due >= refreshAt) { return }
    handler.removeCallbacks(refresh)
    refreshAt = due
    handler.postDelayed(refresh, delay)
  }

  private fun cancelRefresh() {
    handler.removeCallbacks(refresh)
    refreshAt = Long.MAX_VALUE
  }

  private fun trace(message: String) {
    if (BuildConfig.DEBUG && message != lastTrace) {
      Log.d("BlockReels", "${SystemClock.elapsedRealtime()} $message")
      lastTrace = message
    }
  }

  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
    handler.post {
      cancelRefresh()
      render()
    }
  }

  private fun render() {
    if (!running || replayingTap) { return }
    try {
      val window = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive)
      }
      val root = window?.root
      val packageName = root?.packageName?.toString()
      if (root == null || packageName !in targets || !preferences.getBoolean("enabled", true)) {
        trace("release=no-target-window root=${root != null} target=${packageName in targets} windows=${windows.size}")
        pendingDmClick = false
        rules.reset()
        clearOverlays()
        return
      }
      val bounds = Rect()
      window.getBoundsInScreen(bounds)
      if (bounds.isEmpty) { trace("release=empty-bounds"); clearOverlays(); return }
      val nodes = mutableListOf<AccessibilityNodeInfo>()
      val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
      queue.add(root)
      // ponytail: inspect at most 400 nodes per event; use targeted subtree traversal for larger layouts.
      while (queue.isNotEmpty() && nodes.size < 400) {
        val node = queue.removeFirst()
        nodes.add(node)
        for (index in 0 until node.childCount) { node.getChild(index)?.let { queue.add(it) } }
      }
      val options = BlockerOptions(
        enabled = preferences.getBoolean("enabled", true),
        blockReelsButton = preferences.getBoolean("blockReelsButton", true),
        blockReelsScroll = preferences.getBoolean("blockReelsScroll", true),
        allowDmReels = preferences.getBoolean("allowDmReels", true),
        blockShortsButton = preferences.getBoolean("blockShortsButton", true),
        blockShortsScroll = preferences.getBoolean("blockShortsScroll", true),
      )
      val screen = detectScreen(packageName!!, nodes)
      val action = rules.evaluate(screen, pendingDmClick, SystemClock.elapsedRealtime(), options)
      val panelOpen = shouldPassThroughPanel(action,
        nodes.filter { it.isVisibleToUser }.mapNotNull { it.viewIdResourceName })
      if (BuildConfig.DEBUG) {
        val ids = nodes.filter { it.isVisibleToUser }.mapNotNull { it.viewIdResourceName }
          .map { it.substringAfterLast('/') }.distinct().filter { id ->
            listOf("clip", "reel", "short", "sheet", "comment", "share", "story", "liker", "likes").any { id.contains(it) }
          }.take(40)
        val lists = nodes.filter { node ->
          node.isVisibleToUser && (node.isScrollable || listOf("RecyclerView", "ListView", "ScrollView")
            .any { node.className?.toString()?.contains(it) == true })
        }.take(12).map { node ->
          val parents = mutableListOf<String>()
          var parent = node.parent
          repeat(8) { parent?.let { parents.add(it.viewIdResourceName.orEmpty().substringAfterLast('/')); parent = it.parent } }
          "${node.viewIdResourceName.orEmpty().substringAfterLast('/')} scrollable=${node.isScrollable} actions=${node.actionList.map { it.id }} parents=$parents"
        }
        trace("render screen=$screen action=$action nodes=${nodes.size} window=${window.id} bounds=$bounds ids=$ids lists=$lists")
      }
      pendingDmClick = false
      val tab = if (packageName == INSTAGRAM) "reels" else "shorts"
      val blockTab = if (packageName == INSTAGRAM) options.blockReelsButton else options.blockShortsButton
      val tabBounds = if (blockTab && action == ViewerAction.NONE && screen != VideoScreen.STORY) {
        nodes.filter { node ->
          node.isVisibleToUser &&
            (matchesVideoTab(node.text?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), tab) ||
              matchesVideoTab(node.contentDescription?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), tab))
        }.mapNotNull { node ->
          var clickable: AccessibilityNodeInfo? = node
          repeat(4) { if (clickable?.isClickable == false) { clickable = clickable?.parent } }
          clickable = clickable?.takeIf { it.isClickable }
          clickable?.let {
            val rectangle = Rect()
            it.getBoundsInScreen(rectangle)
            if (!rectangle.isEmpty && rectangle.top >= bounds.top + bounds.height() * 0.65 &&
              rectangle.width() < bounds.width() / 2 && rectangle.height() < bounds.height() / 4 &&
              rectangle.intersect(bounds)) rectangle else null
          }
        }.distinct()
      } else emptyList()
      val signature = "$action:$bounds:$tabBounds"
      if (signature == overlaySignature) {
        setOverlaysTouchable(!panelOpen)
        if (panelOpen) { scheduleRefresh(50) }
        return
      }
      clearOverlays()
      if (action != ViewerAction.NONE) {
        addViewerOverlay(bounds, action)
      } else {
        tabBounds.forEach { rectangle ->
          val shield = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "$tab button blocked"
            isClickable = true
            setOnTouchListener { _, _ -> true }
          }
          addOverlay(shield, rectangle)
        }
      }
      overlaySignature = signature
      setOverlaysTouchable(!panelOpen)
      if (panelOpen) { scheduleRefresh(50) }
    } catch (exception: Exception) {
      trace("release=exception type=${exception.javaClass.simpleName}")
      pendingDmClick = false
      rules.reset()
      clearOverlays()
    }
  }

  private fun detectScreen(packageName: String, nodes: List<AccessibilityNodeInfo>): VideoScreen {
    val ids = nodes.filter { it.isVisibleToUser }.map { it.viewIdResourceName.orEmpty().lowercase(Locale.ROOT) }
    val hasMessageInput = nodes.any { it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" }
    return detectVideoScreen(packageName, ids, hasMessageInput)
  }

  private fun addViewerOverlay(bounds: Rect, action: ViewerAction) {
    val gesture = TapGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
    var startX = 0f
    var startY = 0f
    var cancelled = false
    val frame = android.widget.FrameLayout(this).apply {
      setBackgroundColor(if (action == ViewerAction.BLOCK) Color.BLACK else Color.TRANSPARENT)
      isClickable = true
      setOnTouchListener { _, event ->
        if (action == ViewerAction.LOCK) {
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              startX = event.rawX
              startY = event.rawY
              cancelled = false
              gesture.start(startX, startY)
            }
            MotionEvent.ACTION_MOVE -> {
              gesture.move(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
              cancelled = true
              gesture.cancel()
            }
            MotionEvent.ACTION_UP -> {
              if (gesture.finish(event.rawX, event.rawY)) {
                forwardTap(event.rawX, event.rawY)
              } else if (!cancelled && isHorizontalSwipe(event.rawX - startX, event.rawY - startY)) {
                forwardHorizontalControl(startX, startY, event.rawX)
              }
            }
          }
        }
        true
      }
    }
    if (action == ViewerAction.LOCK) {
      addOverlay(frame, bounds)
      return
    }
    val toolbar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(16, 8, 16, 8)
      setBackgroundColor(Color.BLACK)
    }
    toolbar.addView(TextView(this).apply {
      text = "DM Reel blocked"
      setTextColor(Color.WHITE)
      textSize = 14f
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    toolbar.addView(Button(this).apply {
      text = "Exit"
      setTextColor(Color.WHITE)
      setBackgroundColor(Color.BLACK)
      setOnClickListener {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
          scheduleRefresh(120)
        }
      }
    })
    frame.addView(toolbar, android.widget.FrameLayout.LayoutParams(
      android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
      android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM,
    ))
    addOverlay(frame, bounds)
  }

  private fun forwardHorizontalControl(startX: Float, startY: Float, endX: Float) {
    if (replayingTap || !running) { return }
    val root = windows.firstOrNull {
      it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive)
    }?.root ?: return
    if (root.packageName?.toString() !in targets) { return }
    val controls = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
    val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var visited = 0
    val setProgress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
    while (queue.isNotEmpty() && visited++ < 400) {
      val node = queue.removeFirst()
      val rectangle = Rect()
      node.getBoundsInScreen(rectangle)
      val seekable = node.rangeInfo != null && node.actionList.any { it.id == setProgress }
      val horizontalActions = node.actionList.any {
        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id ||
          it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
      }
      val hitBounds = Rect(rectangle)
      if (seekable) { hitBounds.inset(0, -ViewConfiguration.get(this).scaledTouchSlop * 2) }
      if (node.isVisibleToUser && node.isEnabled && !rectangle.isEmpty &&
        hitBounds.contains(startX.toInt(), startY.toInt()) &&
        (seekable || isCarouselControl(node.viewIdResourceName.orEmpty(), horizontalActions))) {
        controls.add(node to rectangle)
      }
      for (index in 0 until node.childCount) { node.getChild(index)?.let { queue.add(it) } }
    }
    for ((node, rectangle) in controls.sortedBy { it.second.width().toLong() * it.second.height() }) {
      val range = node.rangeInfo
      if (range != null && node.actionList.any { it.id == setProgress }) {
        val fraction = seekFraction(endX, rectangle.left.toFloat(), rectangle.width().toFloat(),
          resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        val arguments = Bundle().apply {
          putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, range.min + fraction * (range.max - range.min))
        }
        if (node.performAction(setProgress, arguments)) { scheduleRefresh(16); return }
      } else {
        val directional = if (endX < startX) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
          else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        val fallback = if (endX < startX) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val action = listOf(directional, fallback).firstOrNull { candidate -> node.actionList.any { it.id == candidate } }
        if (action != null && node.performAction(action)) { scheduleRefresh(16); return }
        if (node.viewIdResourceName.orEmpty().substringAfterLast('/') == "clips_carousel_viewpager") {
          val inset = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
          val left = rectangle.left + inset
          val right = rectangle.right - inset
          if (left < right && rectangle.height() > inset * 2) {
            val path = Path().apply {
              moveTo(startX.coerceIn(left, right), startY.coerceIn(rectangle.top + inset, rectangle.bottom - inset))
              lineTo(endX.coerceIn(left, right), startY.coerceIn(rectangle.top + inset, rectangle.bottom - inset))
            }
            replayGesture(path, 160)
            return
          }
        }
      }
    }
    trace("horizontal=no-supported-control")
  }

  private fun forwardTap(x: Float, y: Float) {
    if (replayingTap || !running) { return }
    val root = windows.firstOrNull {
      it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive)
    }?.root ?: return
    if (root.packageName?.toString() !in targets) { return }
    val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Long>>()
    val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var visited = 0
    while (queue.isNotEmpty() && visited++ < 400) {
      val node = queue.removeFirst()
      val rectangle = Rect()
      node.getBoundsInScreen(rectangle)
      if (node.isVisibleToUser && node.isEnabled && node.isClickable && rectangle.contains(x.toInt(), y.toInt())) {
        candidates.add(node to rectangle.width().toLong() * rectangle.height())
      }
      for (index in 0 until node.childCount) { node.getChild(index)?.let { queue.add(it) } }
    }
    val target = candidates.minByOrNull { it.second }?.first
    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
      scheduleRefresh(16)
      return
    }
    val path = Path().apply { moveTo(x, y) }
    replayGesture(path, 40, 50)
  }

  private fun replayGesture(path: Path, duration: Long, startDelay: Long = 0) {
    val stroke = GestureDescription.StrokeDescription(path, startDelay, duration)
    val tap = GestureDescription.Builder()
      .addStroke(stroke).build()
    replayingTap = true
    trace("tap=replay-start")
    setOverlaysTouchable(false)
    handler.postDelayed(restoreTouches, startDelay + duration + 600)
    val completion = object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) { finishReplay() }
      override fun onCancelled(gestureDescription: GestureDescription?) { finishReplay() }
    }
    try {
      if (!dispatchGesture(tap, completion, handler)) { finishReplay() }
    } catch (_: Exception) {
      finishReplay()
    }
  }

  private fun finishReplay() {
    trace("tap=replay-end")
    handler.removeCallbacks(restoreTouches)
    restoreTouches.run()
  }

  private fun setOverlaysTouchable(touchable: Boolean) {
    overlays.forEach { view ->
      val parameters = view.layoutParams as WindowManager.LayoutParams
      val flags = if (touchable) {
        parameters.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
      } else {
        parameters.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
      }
      if (flags != parameters.flags) {
        parameters.flags = flags
        getSystemService(WindowManager::class.java).updateViewLayout(view, parameters)
      }
    }
  }

  private fun addOverlay(view: View, bounds: Rect) {
    val parameters = WindowManager.LayoutParams(
      bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.LEFT
      x = bounds.left
      y = bounds.top
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
      if (Build.VERSION.SDK_INT >= 30) {
        setFitInsetsTypes(WindowInsets.Type.ime())
        setFitInsetsIgnoringVisibility(false)
      }
    }
    getSystemService(WindowManager::class.java).addView(view, parameters)
    overlays.add(view)
  }

  private fun clearOverlays() {
    handler.removeCallbacks(restoreTouches)
    replayingTap = false
    overlays.forEach { view ->
      try { getSystemService(WindowManager::class.java).removeViewImmediate(view) } catch (_: IllegalArgumentException) { }
    }
    overlays.clear()
    overlaySignature = ""
  }

  override fun onInterrupt() {
    cancelRefresh()
    pendingDmClick = false
    rules.reset()
    clearOverlays()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    running = false
    onInterrupt()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    running = false
    onInterrupt()
    if (::preferences.isInitialized) {
      preferences.unregisterOnSharedPreferenceChangeListener(this)
      unregisterReceiver(screenOff)
    }
    super.onDestroy()
  }

  companion object {
    @Volatile var running = false
      private set
    private const val INSTAGRAM = "com.instagram.android"
    private const val YOUTUBE = "com.google.android.youtube"
    private val targets = setOf(INSTAGRAM, YOUTUBE)
  }
}