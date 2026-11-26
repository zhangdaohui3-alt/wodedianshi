package com.wodedianshi.tv;

/** Pure bounds policy for one automatic pass across the channel list. */
public final class ChannelFailoverPolicy {
    private ChannelFailoverPolicy() {}

    // A failed network or regional outage must not trigger hours of unattended
    // decoder cycling across thousands of saved channels. Keep manual choice available.
    public static final int MAX_AUTOMATIC_CHANNEL_SWITCHES = 3;

    public static int nextScopedChannel(java.util.List<Integer> indexes,
            Integer currentPosition, int alreadySwitchedCount) {
        if (indexes == null || currentPosition == null || alreadySwitchedCount < 0
                || alreadySwitchedCount >= MAX_AUTOMATIC_CHANNEL_SWITCHES) return -1;
        int nextPosition = nextChannelIndex(indexes.size(), currentPosition, alreadySwitchedCount);
        return nextPosition < 0 ? -1 : indexes.get(nextPosition);
    }

    /**
     * Returns the next channel index, or -1 when this pass has exhausted all
     * other channels. The currently selected channel is not counted as a switch.
     */
    public static int nextChannelIndex(int channelCount, int currentIndex,
                                       int alreadySwitchedCount) {
        if (channelCount <= 1 || currentIndex < 0 || currentIndex >= channelCount
                || alreadySwitchedCount < 0
                || alreadySwitchedCount >= channelCount - 1) return -1;
        return (currentIndex + 1) % channelCount;
    }
}
