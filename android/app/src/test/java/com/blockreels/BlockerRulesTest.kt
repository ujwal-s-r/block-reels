package com.blockreels

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockerRulesTest {
  @Test fun siblingPanelListsAreAllowedButUnderlyingVideoPagersAreNot() {
    assertEquals(true, isPanelScrollTarget(listOf("recycler_view", "bottom_sheet_container")))
    assertEquals(true, isPanelScrollTarget(listOf("list", "likes_list")))
    assertEquals(true, isPanelScrollTarget(listOf("recipients_row", "bottom_sheet_container", "clips_viewer_root")))
    assertEquals(false, isPanelScrollTarget(listOf("clips_viewer_view_pager", "bottom_sheet_container")))
    assertEquals(false, isPanelScrollTarget(listOf("recycler_view", "clips_viewer_root", "bottom_sheet_container")))
    assertEquals(false, isPanelScrollTarget(listOf("reel_recycler", "bottom_sheet_container")))
    assertEquals(false, isPanelScrollTarget(listOf("list", "swipeable_tab_view_pager", "bottom_sheet_container")))
    assertEquals(false, isPanelScrollTarget(listOf("recycler_view", "content")))
  }

  @Test fun horizontalControlsNeverIncludeTheVerticalReelPager() {
    assertEquals(true, isCarouselControl("com.instagram.android:id/carousel_media_pager"))
    assertEquals(true, isCarouselControl("sidecar_view_pager"))
    assertEquals(false, isCarouselControl("clips_viewer_view_pager"))
    assertEquals(false, isCarouselControl("clips_viewer_carousel"))
    assertEquals(false, isCarouselControl("reel_recycler"))
    assertEquals(true, isCarouselControl("", true))
    assertEquals(false, isCarouselControl("", false))
    assertEquals(false, isCarouselControl("clips_viewer_view_pager", true))
    assertEquals(true, isHorizontalSwipe(100f, 5f))
    assertEquals(true, isHorizontalSwipe(-100f, 5f))
    assertEquals(false, isHorizontalSwipe(5f, -100f))
    assertEquals(false, isHorizontalSwipe(100f, 90f))
  }

  @Test fun timelineSeekingClampsToTheControlAndSupportsRtl() {
    assertEquals(0.5f, seekFraction(150f, 100f, 100f, false))
    assertEquals(0f, seekFraction(0f, 100f, 100f, false))
    assertEquals(1f, seekFraction(300f, 100f, 100f, false))
    assertEquals(0.75f, seekFraction(125f, 100f, 100f, true))
  }

  @Test fun likesPanelsScrollWithoutReleasingViewerProtection() {
    val rules = BlockerRules()
    rules.evaluate(VideoScreen.REEL, false, 0, BlockerOptions())
    for (panel in listOf("likers_list", "likes_recycler", "media_likers_list", "likes_sheet")) {
      assertEquals(true, hasViewerPanel(listOf(panel)))
      val screen = detectVideoScreen("com.instagram.android", listOf(panel), false)
      assertEquals(VideoScreen.PANEL, screen)
      assertEquals(ViewerAction.LOCK, rules.evaluate(screen, false, 1, BlockerOptions()))
    }
    assertEquals(false, hasViewerPanel(listOf("like_button", "like_count")))
  }

  @Test fun recordedCommentAndShareAnimationsNeverReleaseReelProtection() {
    val rules = BlockerRules()
    val snapshots = listOf(
      listOf("clips_viewer_view_pager", "comment_button", "direct_share_button"),
      listOf("bottom_sheet_container"),
      listOf("bottom_sheet_container", "layout_comment_thread_edittext_multiline"),
      listOf("bottom_sheet_container"),
      listOf("clips_viewer_view_pager"),
      listOf("bottom_sheet_container"),
      listOf("bottom_sheet_container", "direct_share_sheet"),
      listOf("bottom_sheet_container"),
      listOf("clips_viewer_view_pager"),
    )
    snapshots.forEachIndexed { index, ids ->
      val screen = detectVideoScreen("com.instagram.android", ids, false)
      assertEquals(ViewerAction.LOCK, rules.evaluate(screen, false, index.toLong(), BlockerOptions()))
    }
    assertEquals(false, hasViewerPanel(listOf("bottom_sheet_container")))
    rules.evaluate(VideoScreen.INSTAGRAM, false, 20, BlockerOptions())
    val unrelatedSheet = detectVideoScreen("com.instagram.android", listOf("bottom_sheet_container"), false)
    assertEquals(ViewerAction.NONE, rules.evaluate(unrelatedSheet, false, 21, BlockerOptions()))
  }

  @Test fun dmReelInsideGenericSheetStillBlocksNextVideoSwipes() {
    val screen = detectVideoScreen("com.instagram.android",
      listOf("com.instagram.android:id/bottom_sheet", "com.instagram.android:id/clips_viewer_root"), false)
    assertEquals(VideoScreen.REEL, screen)
    for (clickedDmReel in listOf(false, true)) {
      val rules = BlockerRules()
      rules.evaluate(VideoScreen.DM, clickedDmReel, 100, BlockerOptions())
      assertEquals(ViewerAction.LOCK, rules.evaluate(screen, false, 200, BlockerOptions()))
    }
  }

  @Test fun storiesAndEmbeddedClipPreviewsNeverGetViewerShield() {
    for (ids in listOf(listOf("reel_viewer_root", "clips_video"),
        listOf("story_viewer", "clips_viewer_root"), listOf("stories_viewer", "bottom_sheet"))) {
      val screen = detectVideoScreen("com.instagram.android", ids, false)
      assertEquals(VideoScreen.STORY, screen)
      assertEquals(ViewerAction.NONE, BlockerRules().evaluate(screen, false, 100, BlockerOptions()))
    }
    assertEquals(VideoScreen.INSTAGRAM, detectVideoScreen("com.instagram.android", listOf("clips_video"), false))
  }

  @Test fun reelShieldStaysActiveThroughoutPanelOpeningAndClosing() {
    val rules = BlockerRules()
    assertEquals(ViewerAction.LOCK, rules.evaluate(VideoScreen.REEL, false, 0, BlockerOptions()))
    for (panel in listOf("comment_thread_list", "direct_share_sheet", "save_to_collection")) {
      val opened = detectVideoScreen("com.instagram.android", listOf("clips_viewer_root", panel), false)
      assertEquals(ViewerAction.LOCK, rules.evaluate(opened, false, 100, BlockerOptions()))
      val hiddenViewer = detectVideoScreen("com.instagram.android", listOf(panel), false)
      assertEquals(VideoScreen.PANEL, hiddenViewer)
      assertEquals(ViewerAction.LOCK, rules.evaluate(hiddenViewer, false, 101, BlockerOptions()))
      assertEquals(ViewerAction.LOCK, rules.evaluate(hiddenViewer, false, 102, BlockerOptions()))
      val closed = detectVideoScreen("com.instagram.android", listOf("clips_viewer_root", "bottom_sheet"), false)
      assertEquals(ViewerAction.LOCK, rules.evaluate(closed, false, 103, BlockerOptions()))
    }
    assertEquals(VideoScreen.SHORT, detectVideoScreen("com.google.android.youtube", listOf("reel_watch_player"), false))
  }

  @Test fun panelsOnlyKeepProtectionForTheViewerTheyBelongTo() {
    val rules = BlockerRules()
    for (screen in listOf(VideoScreen.INSTAGRAM, VideoScreen.DM, VideoScreen.STORY, VideoScreen.YOUTUBE)) {
      rules.evaluate(screen, false, 0, BlockerOptions())
      assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.PANEL, false, 1, BlockerOptions()))
    }
    rules.evaluate(VideoScreen.SHORT, false, 2, BlockerOptions())
    assertEquals(ViewerAction.LOCK, rules.evaluate(VideoScreen.PANEL, false, 3, BlockerOptions()))
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.PANEL, false, 4, BlockerOptions(enabled = false)))
  }

  @Test fun panelTransitionsRespectDmRestrictionAndScrollToggle() {
    val rules = BlockerRules()
    val blockedDm = BlockerOptions(allowDmReels = false)
    rules.evaluate(VideoScreen.DM, true, 0, blockedDm)
    assertEquals(ViewerAction.BLOCK, rules.evaluate(VideoScreen.REEL, false, 1, blockedDm))
    assertEquals(ViewerAction.BLOCK, rules.evaluate(VideoScreen.PANEL, false, 2, blockedDm))
    assertEquals(ViewerAction.BLOCK, rules.evaluate(VideoScreen.REEL, false, 3, blockedDm))
    rules.reset()
    val scrollingAllowed = BlockerOptions(blockReelsScroll = false)
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 4, scrollingAllowed))
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.PANEL, false, 5, scrollingAllowed))
  }

  @Test fun tabsMatchNavigationLabelsAndKnownIdsButNotUnrelatedText() {
    assertEquals(true, matchesVideoTab("Reels, tab 3 of 5", "", "reels"))
    assertEquals(true, matchesVideoTab("Shorts tab", "", "shorts"))
    assertEquals(true, matchesVideoTab("", "com.instagram.android:id/clips_tab", "reels"))
    assertEquals(false, matchesVideoTab("Watch reels", "", "reels"))
    assertEquals(false, matchesVideoTab("Reels", "", "shorts"))
  }

  @Test fun panelsAreDistinguishedFromViewerActionButtons() {
    assertEquals(true, hasViewerPanel(listOf("com.instagram.android:id/comment_thread_list")))
    assertEquals(true, hasViewerPanel(listOf("com.instagram.android:id/direct_share_sheet")))
    assertEquals(false, hasViewerPanel(listOf("com.instagram.android:id/clips_viewer", "comment_button", "share_button")))
  }

  @Test fun tapsAreAllowedButSwipesStayBlockedEvenWhenTheyReturnToTheirStart() {
    val gesture = TapGesture(8f)
    gesture.start(100f, 100f)
    assertEquals(true, gesture.finish(103f, 104f))
    gesture.start(100f, 100f)
    gesture.move(100f, 80f)
    assertEquals(false, gesture.finish(100f, 100f))
    gesture.start(100f, 100f)
    assertEquals(false, gesture.finish(120f, 100f))
    gesture.start(100f, 100f)
    gesture.cancel()
    assertEquals(false, gesture.finish(100f, 100f))
    gesture.start(100f, 100f)
    assertEquals(true, gesture.finish(100f, 100f))
  }

  @Test fun scrollingOptionsAreIndependentOfButtonOptions() {
    val rules = BlockerRules()
    val options = BlockerOptions(blockReelsScroll = false, blockShortsScroll = true)
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 0, options))
    assertEquals(ViewerAction.LOCK, rules.evaluate(VideoScreen.SHORT, false, 1, options))
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.YOUTUBE, false, 2, options))
  }

  @Test fun dmOpeningCanBeBlockedWithoutBlockingOtherReels() {
    val rules = BlockerRules()
    val options = BlockerOptions(allowDmReels = false, blockReelsScroll = false)
    rules.evaluate(VideoScreen.DM, true, 100, options)
    assertEquals(ViewerAction.BLOCK, rules.evaluate(VideoScreen.REEL, false, 200, options))
    assertEquals(ViewerAction.BLOCK, rules.evaluate(VideoScreen.REEL, false, 300, options))
    rules.evaluate(VideoScreen.INSTAGRAM, false, 400, options)
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 500, options))
  }

  @Test fun dmPermissionDoesNotDisableScrollProtection() {
    val rules = BlockerRules()
    val options = BlockerOptions(allowDmReels = true)
    rules.evaluate(VideoScreen.DM, true, 100, options)
    assertEquals(ViewerAction.LOCK, rules.evaluate(VideoScreen.REEL, false, 200, options))
  }

  @Test fun staleDmClicksAndAppSwitchesDoNotCreateExceptions() {
    val rules = BlockerRules()
    val options = BlockerOptions(allowDmReels = false, blockReelsScroll = false)
    rules.evaluate(VideoScreen.DM, true, 100, options)
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 3200, options))
    rules.evaluate(VideoScreen.DM, true, 3300, options)
    rules.evaluate(VideoScreen.OTHER, false, 3400, options)
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 3500, options))
  }

  @Test fun pausingClearsTheCurrentSession() {
    val rules = BlockerRules()
    rules.evaluate(VideoScreen.DM, true, 0, BlockerOptions())
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 1, BlockerOptions(enabled = false)))
    assertEquals(ViewerAction.NONE, rules.evaluate(VideoScreen.REEL, false, 2,
      BlockerOptions(allowDmReels = false, blockReelsScroll = false)))
  }
}