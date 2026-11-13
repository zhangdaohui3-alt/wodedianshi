package com.wodedianshi.tv;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class FullscreenQuickActionConfigTest {
  @Test public void firstLaunchUsesStableDefaults() {
    assertEquals(Arrays.asList(
        FullscreenQuickActionConfig.FAVORITE,
        FullscreenQuickActionConfig.SOURCE_PICKER,
        FullscreenQuickActionConfig.CHANNEL_LIST,
        FullscreenQuickActionConfig.ASPECT_RATIO),
        FullscreenQuickActionConfig.decodeLiveActions(null));
    assertEquals(FullscreenQuickActionConfig.defaultLiveActions(),
        FullscreenQuickActionConfig.decodeLiveActions(""));
  }

  @Test public void decodeKeepsCustomOrderAndRemovesDuplicatesAndUnknownIds() {
    List<String> actual = FullscreenQuickActionConfig.decodeLiveActions(
        "aspect_ratio,unknown,favorite,aspect_ratio,source_picker");
    assertEquals(Arrays.asList(
        FullscreenQuickActionConfig.ASPECT_RATIO,
        FullscreenQuickActionConfig.FAVORITE,
        FullscreenQuickActionConfig.SOURCE_PICKER), actual);
  }

  @Test public void invalidOrEmptySavedMenuFallsBackToDefaults() {
    assertEquals(FullscreenQuickActionConfig.defaultLiveActions(),
        FullscreenQuickActionConfig.decodeLiveActions("unknown,, ,not_an_action"));
    assertEquals(FullscreenQuickActionConfig.defaultLiveActions(),
        FullscreenQuickActionConfig.decodeLiveActions(",,,"));
  }

  @Test public void encodeSanitizesAndRoundTripsCustomOrder() {
    List<String> custom = Arrays.asList(
        FullscreenQuickActionConfig.CHANNEL_LIST,
        FullscreenQuickActionConfig.FAVORITE,
        FullscreenQuickActionConfig.CHANNEL_LIST,
        "obsolete");
    String encoded = FullscreenQuickActionConfig.encodeLiveActions(custom);
    assertEquals("channel_list,favorite", encoded);
    assertEquals(Arrays.asList(
        FullscreenQuickActionConfig.CHANNEL_LIST,
        FullscreenQuickActionConfig.FAVORITE),
        FullscreenQuickActionConfig.decodeLiveActions(encoded));
  }

  @Test public void optionalActionsAreAvailableWithoutChangingFirstLaunchDefaults() {
    assertTrue(FullscreenQuickActionConfig.availableLiveActions().contains(
        FullscreenQuickActionConfig.BUFFERING_SETTINGS));
    assertTrue(FullscreenQuickActionConfig.availableLiveActions().contains(
        FullscreenQuickActionConfig.DECODER_SETTINGS));
    assertTrue(FullscreenQuickActionConfig.availableLiveActions().contains(
        FullscreenQuickActionConfig.NETWORK_SPEED));
    assertTrue(FullscreenQuickActionConfig.availableLiveActions().contains(
        FullscreenQuickActionConfig.LIVE_DIAGNOSTICS));
    assertEquals(4, FullscreenQuickActionConfig.defaultLiveActions().size());
  }

  @Test public void emptySelectionCannotAccidentallyDisableEveryAction() {
    assertNotEquals("", FullscreenQuickActionConfig.encodeLiveActions(Collections.<String>emptyList()));
    assertEquals(FullscreenQuickActionConfig.defaultLiveActions(),
        FullscreenQuickActionConfig.decodeLiveActions(
            FullscreenQuickActionConfig.encodeLiveActions(Collections.<String>emptyList())));
  }
}
