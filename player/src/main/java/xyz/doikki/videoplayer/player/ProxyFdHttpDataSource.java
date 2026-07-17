package xyz.doikki.videoplayer.player;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;
import java.util.Map;

@SuppressLint("NewApi")
final class ProxyFdHttpDataSource extends ProxyFileDescriptorCallback {
    private static final String TAG = "ProxyFdHttpDataSource";

    private final HttpRangeMediaDataSource delegate;
    private volatile boolean released;

    ProxyFdHttpDataSource(String url, Map<String, String> headers) {
        delegate = HttpRangeMediaDataSource.createForProxyFileDescriptor(url, headers);
    }

    void requestPlaybackPrebuffer() {
        if (!released) {
            delegate.requestPlaybackPrebuffer();
        }
    }

    void finishPlaybackPrebuffer() {
        if (!released) {
            delegate.finishPlaybackPrebuffer();
        }
    }

    void onPlaybackSeekStarted() {
        if (!released) {
            delegate.onPlaybackSeekStarted();
        }
    }

    void onPlaybackSeekFinished() {
        if (!released) {
            delegate.onPlaybackSeekFinished();
        }
    }

    long getBufferedAheadBytes() {
        return released ? 0L : delegate.getBufferedAheadBytes();
    }

    long getPlaybackStartBufferTargetBytes() {
        return released ? 0L : delegate.getPlaybackStartBufferTargetBytes();
    }

    long getCachedBytes() {
        return released ? 0L : delegate.getCachedBytes();
    }

    @Override
    public long onGetSize() throws ErrnoException {
        if (released) {
            return 0L;
        }
        try {
            return delegate.getSize();
        } catch (IOException e) {
            if (released) {
                return 0L;
            }
            throw toErrno("onGetSize", e);
        }
    }

    @Override
    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
        if (released) {
            return 0;
        }
        try {
            int read = delegate.readAt(offset, data, 0, size);
            return Math.max(read, 0);
        } catch (HttpRangeMediaDataSource.PlaybackReadSupersededException e) {
            // The old read belongs to the decoder position abandoned by seek. EINTR asks
            // AppFuse/native code to retry instead of turning cancellation into a false EOF.
            throw new ErrnoException("onRead superseded", OsConstants.EINTR);
        } catch (IOException e) {
            // AppFuse can dispatch an already queued read after MediaPlayer releases the
            // descriptor. EOF is the only valid response once this backing source is gone.
            if (released) {
                return 0;
            }
            throw toErrno("onRead offset=" + offset + " size=" + size, e);
        }
    }

    @Override
    public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
        throw new ErrnoException("onWrite", OsConstants.EBADF);
    }

    @Override
    public void onFsync() {
    }

    @Override
    public void onRelease() {
        if (released) {
            return;
        }
        released = true;
        try {
            delegate.close();
        } catch (Throwable th) {
            Log.w(TAG, "release failed", th);
        }
    }

    private ErrnoException toErrno(String operation, IOException error) {
        Log.e(TAG, operation + " failed: " + error.getMessage(), error);
        return new ErrnoException(operation, OsConstants.EIO);
    }
}
