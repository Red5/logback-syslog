package org.red5.syslog.impl.unix.socket;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;

import org.red5.syslog.AbortableSyslog;
import org.red5.syslog.SyslogConstants;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.AbstractSyslog;
import org.red5.syslog.impl.AbstractSyslogWriter;

/**
 * Syslog over a unix domain socket (default path /dev/log), without JNA. Two socket types are supported, chosen by
 * {@link UnixSocketSyslogConfig#setType(int)}:
 *
 * <ul>
 * <li>{@code SOCK_DGRAM} (the default): one datagram per message, no framing, through {@link UnixDatagramSocket}. This
 * is what /dev/log (journald, rsyslog) and /var/run/syslog (macOS) expect. It needs {@code java.lang.foreign} (used
 * reflectively; a preview API in JDK 21, final from JDK 22) on Linux or macOS/BSD; Windows is unsupported. If it is not
 * available {@link #initialize()} fails with a message naming the reason and the alternatives. Sends never block: a
 * full receiver buffer is a failed write.</li>
 * <li>{@code SOCK_STREAM}: a JDK {@link SocketChannel}, each message written newline-terminated, for stream listeners
 * (syslog-ng unix-stream, rsyslog stream input and similar).</li>
 * </ul>
 *
 * <p>The socket is connected lazily on the first write and again after any failure; a failed write throws a
 * SyslogRuntimeException wrapping the IOException, which sends the message to the backlog handlers.
 * {@link #flush()} and {@link #shutdown()} close it.</p>
 *
 * <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
 * of the LGPL license is available in the META-INF folder in all
 * distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
 */
public class UnixSocketSyslog extends AbstractSyslog implements AbortableSyslog {

    private static final long serialVersionUID = 1L;

    protected UnixSocketSyslogConfig unixConfig;
    private transient volatile WritableByteChannel channel;
    private transient volatile UnixDatagramSocket datagram;
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
        int type = unixConfig.getType();
        if (type == SyslogConstants.SOCK_DGRAM) {
            if (!UnixDatagramSocket.isAvailable()) {
                throw new SyslogRuntimeException(unavailableMessage(UnixDatagramSocket.unavailableReason()));
            }
        } else if (type != SyslogConstants.SOCK_STREAM) {
            throw new SyslogRuntimeException("unsupported unix socket type " + type + "; use SOCK_DGRAM (" + SyslogConstants.SOCK_DGRAM
                    + ") or SOCK_STREAM (" + SyslogConstants.SOCK_STREAM + ")");
        }
    }

    /** The error text used when the datagram type is selected but not available. */
    private static String unavailableMessage(String reason) {
        return "unix datagram sockets are unavailable: " + reason + "; use the STREAM socket type, or UDP/TCP to 127.0.0.1";
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

    private synchronized UnixDatagramSocket datagram() throws IOException {
        if (aborted) {
            throw new IOException("transport aborted");
        }
        UnixDatagramSocket d = datagram;
        if (d == null) {
            d = UnixDatagramSocket.open(unixConfig.getPath());
            datagram = d;
            if (aborted) {
                closeQuietly();
                throw new IOException("transport aborted");
            }
        }
        return d;
    }

    @Override
    protected synchronized void write(int level, byte[] message) throws SyslogRuntimeException {
        try {
            checkType();
            if (unixConfig.getType() == SyslogConstants.SOCK_DGRAM) {
                // one datagram per message, exactly the message bytes
                datagram().send(message, 0, message.length);
                return;
            }
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

    /** Closes the channel or socket without taking the monitor held by a blocked write, and refuses new connections. */
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
        UnixDatagramSocket d = datagram;
        if (d != null) {
            d.close();
        }
        datagram = null;
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
