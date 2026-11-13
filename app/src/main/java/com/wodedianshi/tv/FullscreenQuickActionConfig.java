package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure configuration policy for the editable fullscreen live quick-action menu.
 * UI code owns labels and click handlers; this class only stores stable action IDs.
 */
public final class FullscreenQuickActionConfig {
  public static final String MODE_LIVE = "live";

  public static final String FAVORITE = "favorite";
  public static final String SOURCE_PICKER = "source_picker";
  public static final String CHANNEL_LIST = "channel_list";
  public static final String ASPECT_RATIO = "aspect_ratio";

  private static final String SEPARATOR = ",";

  private FullscreenQuickActionConfig() {}

  /** The supported live actions, in the order shown by the editor. */
  public static List<String> availableLiveActions() {
    ArrayList<String> ids = new ArrayList<>();
    ids.add(FAVORITE);
    ids.add(SOURCE_PICKER);
    ids.add(CHANNEL_LIST);
    ids.add(ASPECT_RATIO);
    return Collections.unmodifiableList(ids);
  }

  /** Default order for a first launch or an explicit restore-defaults request. */
  public static List<String> defaultLiveActions() {
    ArrayList<String> ids = new ArrayList<>();
    ids.add(FAVORITE);
    ids.add(SOURCE_PICKER);
    ids.add(CHANNEL_LIST);
    ids.add(ASPECT_RATIO);
    return Collections.unmodifiableList(ids);
  }

  /**
   * Parse and sanitize a persisted comma-separated action list.
   * Unknown IDs and duplicates are discarded while user-defined order is retained.
   * A completely empty/invalid result falls back to the safe default menu.
   */
  public static List<String> decodeLiveActions(String encoded) {
    if (encoded == null || encoded.trim().isEmpty()) return defaultLiveActions();

    Set<String> allowed = new HashSet<>(availableLiveActions());
    Set<String> seen = new HashSet<>();
    ArrayList<String> result = new ArrayList<>();
    String[] parts = encoded.split(SEPARATOR, -1);
    for (String part : parts) {
      String id = part == null ? "" : part.trim();
      if (!id.isEmpty() && allowed.contains(id) && seen.add(id)) result.add(id);
    }
    if (result.isEmpty()) return defaultLiveActions();
    return Collections.unmodifiableList(result);
  }

  /** Encode only supported, unique IDs; preserve the supplied order. */
  public static String encodeLiveActions(List<String> ids) {
    if (ids == null || ids.isEmpty()) return encode(defaultLiveActions());
    Set<String> allowed = new HashSet<>(availableLiveActions());
    Set<String> seen = new HashSet<>();
    StringBuilder out = new StringBuilder();
    for (String raw : ids) {
      String id = raw == null ? "" : raw.trim();
      if (id.isEmpty() || !allowed.contains(id) || !seen.add(id)) continue;
      if (out.length() > 0) out.append(SEPARATOR);
      out.append(id);
    }
    return out.length() == 0 ? encode(defaultLiveActions()) : out.toString();
  }

  private static String encode(List<String> ids) {
    StringBuilder out = new StringBuilder();
    for (String id : ids) {
      if (out.length() > 0) out.append(SEPARATOR);
      out.append(id);
    }
    return out.toString();
  }
}
