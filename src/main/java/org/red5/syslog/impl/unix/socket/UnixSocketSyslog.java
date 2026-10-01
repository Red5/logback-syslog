package org.red5.syslog.impl.unix.socket;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;

import org.red5.syslog.AbortableSyslog;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.AbstractSyslog;
import org.red5.syslog.impl.AbstractSyslogWriter;

/**
 * Syslog over a unix domain socket (default path /dev/log). Uses JDK 16+ native support, no JNA.
 *
 * <p>Limitation: only stream sockets are supported (the JDK has no unix datagram channel), so this
 * works with stream listeners (syslog-ng unix-stream, rsyslog stream input and similar) but not with
 * datagram-only /dev/log. Each message is written newline-terminated.</p>
 *
 * <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
 * of the LGPL license is available in the META-INF folder in all
 * distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
 */
public class UnixSocketSyslog extends AbstractSyslog implements AbortableSyslog {

    private static final long serialVersionUID = 1L;
    private static final int SOCK_STREAM = 1;

    protected UnixSocketSyslogConfig unixConfig;
    private transient volatile WritableByteChannel channel;
    private transient volatile boolean aborted;

    @Override
    public void initialize() throws SyslogRuntimeException {
        try {
            unixConfig = (UnixSocketSyslogConfig) this.syslogConfig;
        } catch (ClassCastException e) {
            throw new SyslogRuntimeException("config must be of type UnixSocketSyslogConfig");
        }
        checkType();
    }

    private void checkType() {
        if (unixConfig.getType() != SOCK_STREAM) {
            throw new SyslogRuntimeException("unix datagram sockets are not supported on JDK 21; use a stream socket");
        }
    }

    private synchronized WritableByteChannel channel() throws IOException {
        if (aborted) {
            throw new IOException("transport aborted");
        }
        if (channel == null) {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(Path.of(unixConfig.getPath()));
            channel = SocketChannel.open(addr);
            if (aborted) {   // abort() ran while connecting and could not see this channel yet
                closeQuietly();
                throw new IOException("transport aborted");
            }
        }
        return channel;
    }

    @Override
    protected synchronized void write(int level, byte[] message) throws SyslogRuntimeException {
        try {
            checkType();
            byte[] framed = new byte[message.length + 1];
            System.arraycopy(message, 0, framed, 0, message.length);
            framed[message.length] = '\n';
            ByteBuffer bb = ByteBuffer.wrap(framed);
            WritableByteChannel ch = channel();
            while (bb.hasRemaining()) {
                ch.write(bb);
            }
        } catch (IOException e) {
            closeQuietly();
            throw new SyslogRuntimeException(e);
        }
    }

    /** Closes the channel without taking the monitor held by a blocked write, and refuses new connections. */
    @Override
    public void abort() {
        aborted = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            WritableByteChannel ch = channel;
            if (ch != null) {
                ch.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        channel = null;
    }

    @Override
    public synchronized void flush() throws SyslogRuntimeException {
        closeQuietly();
    }

    @Override
    public synchronized void shutdown() throws SyslogRuntimeException {
        closeQuietly();
    }

    @Override
    public AbstractSyslogWriter getWriter() {
        return null;
    }

    @Override
    public void returnWriter(AbstractSyslogWriter syslogWriter) {
        // no writers: writes are direct
    }
}
