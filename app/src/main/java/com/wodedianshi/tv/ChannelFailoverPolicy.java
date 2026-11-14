package com.wodedianshi.tv;

/** Pure bounds policy for one automatic pass across the channel list. */
public final class ChannelFailoverPolicy {
    private ChannelFailoverPolicy() {}

    /**
     * Returns the next channel index, or -1 when this pass has exhausted all
     * other channels. The currently selected channel is not counted as a switch.
     */
    public static int nextChannelIndex(int channelCount, int currentIndex,
                                       int alreadySwitchedCount) {
        if (channelCount <= 1 || alreadySwitchedCount >= channelCount - 1) return -1;
        int current = SourceSelectionPolicy.wrapIndex(currentIndex, channelCount);
        if (current < 0) return -1;
        return (current + 1) % channelCount;
    }
}
