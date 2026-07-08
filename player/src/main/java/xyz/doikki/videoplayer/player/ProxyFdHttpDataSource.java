package xyz.doikki.videoplayer.player;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;
import java.util.Map;

final class ProxyFdHttpDataSource extends ProxyFileDescriptorCallback {
    private static final String TAG = "ProxyFdHttpDataSource";

    private final HttpRangeMediaDataSource delegate;

    ProxyFdHttpDataSource(String url, Map<String, String> headers) {
        delegate = HttpRangeMediaDataSource.createForStreamingPlayback(url, headers);
    }

    @Override
    public long onGetSize() throws ErrnoException {
        try {
            return delegate.getSize();
        } catch (IOException e) {
            throw toErrno("onGetSize", e);
        }
    }

    @Override
    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
        try {
            int read = delegate.readAt(offset, data, 0, size);
            return Math.max(read, 0);
        } catch (IOException e) {
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
