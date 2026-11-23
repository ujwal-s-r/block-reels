package com.blockreels

import java.util.Locale

internal fun matchesVideoTab(label: String, viewId: String, tab: String): Boolean {
  val normalized = label.trim().lowercase(Locale.ROOT)
  val id = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
  return normalized == tab || normalized.startsWith("$tab,") || normalized.startsWith("$tab ") ||
    id == "${tab}_tab" || id == "tab_$tab" || (tab == "reels" && id == "clips_tab")
}

internal fun hasViewerPanel(ids: List<String>): Boolean = ids.any { id ->
  listOf("comment_list", "comments_recycler", "comment_thread", "comments_sheet", "comment_sheet",
    "share_sheet", "direct_share_sheet", "clips_share_sheet", "reel_share_sheet", "save_to_collection",
    "likers_list", "likes_list", "likers_recycler", "likes_recycler", "likers_sheet", "likes_sheet", "media_likers")
    .any { id.contains(it) }
}

internal fun isCarouselControl(viewId: String, hasHorizontalActions: Boolean = false): Boolean {
  val id = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
  return !id.startsWith("clips_viewer") && !id.contains("reel_recycler") &&
    (hasHorizontalActions || listOf("carousel", "sidecar", "media_pager").any { id.contains(it) })
}

internal fun isHorizontalSwipe(deltaX: Float, deltaY: Float): Boolean =
  kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 2

internal fun isPanelScrollTarget(ancestorIds: List<String>): Boolean {
  for (viewId in ancestorIds) {
    val id = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
    if (id.startsWith("clips_viewer") || id.contains("reel_recycler") ||
      id.startsWith("swipeable_tab_view_pager") || id.startsWith("swipeable_nav_view_pager")) { return false }
    if (id == "bottom_sheet_container" || hasViewerPanel(listOf(id))) { return true }
  }
  return false
}

internal fun seekFraction(x: Float, left: Float, width: Float, rtl: Boolean): Float {
  val fraction = ((x - left) / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
  return if (rtl) 1f - fraction else fraction
}

internal fun detectVideoScreen(packageName: String, ids: List<String>, hasMessageInput: Boolean): VideoScreen {
  val visibleIds = ids.map { it.substringAfterLast('/').lowercase(Locale.ROOT) }
  val hasPanel = hasViewerPanel(visibleIds) || "bottom_sheet_container" in visibleIds
  if (packageName == "com.instagram.android" && visibleIds.any {
      it.startsWith("reel_viewer") || it.startsWith("story_viewer") || it.startsWith("stories_viewer")
    }) {
    return VideoScreen.STORY
  }
  if (packageName == "com.google.android.youtube") {
    return if (visibleIds.any { it.contains("reel_recycler") || it.contains("reel_watch_player") ||
        it.contains("shorts_player") }) VideoScreen.SHORT
        else if (hasPanel) VideoScreen.PANEL else VideoScreen.YOUTUBE
  }
  if (visibleIds.any { it.startsWith("clips_viewer") }) { return VideoScreen.REEL }
      if (hasPanel) { return VideoScreen.PANEL }
  return if (hasMessageInput && visibleIds.any { it.contains("direct_thread") || it.contains("message_list") ||
      it.contains("row_thread") || it.contains("direct_text") }) VideoScreen.DM else VideoScreen.INSTAGRAM
}

data class BlockerOptions(
  val enabled: Boolean = true,
  val blockReelsButton: Boolean = true,
  val blockReelsScroll: Boolean = true,
  val allowDmReels: Boolean = true,
  val blockShortsButton: Boolean = true,
  val blockShortsScroll: Boolean = true,
)

enum class VideoScreen { OTHER, INSTAGRAM, DM, REEL, STORY, PANEL, YOUTUBE, SHORT }
enum class ViewerAction { NONE, LOCK, BLOCK }

internal class TapGesture(private val slop: Float) {
  private var startX = 0f
  private var startY = 0f
  private var eligible = false

  fun start(x: Float, y: Float) {
    startX = x
    startY = y
    eligible = true
  }

  fun move(x: Float, y: Float) {
    if ((x - startX) * (x - startX) + (y - startY) * (y - startY) > slop * slop) {
      eligible = false
    }
  }

  fun cancel() { eligible = false }

  fun finish(x: Float, y: Float): Boolean {
    move(x, y)
    val tapped = eligible
    cancel()
    return tapped
  }
}

class BlockerRules {
  private var previous = VideoScreen.OTHER
  private var dmClickAt: Long? = null
  private var dmSession = false

  fun reset() {
    previous = VideoScreen.OTHER
    dmClickAt = null
    dmSession = false
  }

  fun evaluate(screen: VideoScreen, clickedDmReel: Boolean, now: Long, options: BlockerOptions): ViewerAction {
    if (!options.enabled || screen == VideoScreen.OTHER) {
      reset()
      return ViewerAction.NONE
    }
    val current = if (screen == VideoScreen.PANEL) previous else screen
    if (clickedDmReel && (previous == VideoScreen.DM || screen == VideoScreen.DM)) {
      dmClickAt = now
    }
    if (current == VideoScreen.REEL && previous != VideoScreen.REEL) {
      dmSession = dmClickAt?.let { now - it in 0..3000 } == true
      dmClickAt = null
    } else if (current != VideoScreen.REEL) {
      dmSession = false
      if (current != VideoScreen.DM) { dmClickAt = null }
    }
    previous = current
    return when (current) {
      VideoScreen.REEL -> when {
        dmSession && !options.allowDmReels -> ViewerAction.BLOCK
        options.blockReelsScroll -> ViewerAction.LOCK
        else -> ViewerAction.NONE
      }
      VideoScreen.SHORT -> if (options.blockShortsScroll) ViewerAction.LOCK else ViewerAction.NONE
      else -> ViewerAction.NONE
    }
  }
}