package xyz.doikki.videoplayer.player;

/**
 * Rejects callbacks emitted by a retired native player after a reset or source switch.
 */
final class NativePlayerCallbackPolicy {
    private NativePlayerCallbackPolicy() {
    }

    static boolean isCurrent(Object callbackOwner, Object currentOwner) {
        return callbackOwner != null && callbackOwner == currentOwner;
    }
}
