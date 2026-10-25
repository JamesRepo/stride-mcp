package com.hammez.stride.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

/**
 * The SDK's stdio transport on {@link McpStreams}, plus a way to wait until it closes.
 * <p>
 * The SDK doesn't say when the connection ends, so this wraps the one session's transport and notices its
 * close. That happens when the AI app closes stdin, and also when the SDK stops reading for another reason (a
 * line it can't parse, a read error), so {@code serve} never hangs on a connection that is already dead. It
 * also watches stdin, to tell the two apart.
 */
public final class StdioServer implements McpServerTransportProvider {

    private final StdioServerTransportProvider stdio;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean stdinEnded = new AtomicBoolean();

    StdioServer(McpJsonMapper json, McpStreams streams) {
        this.stdio = new StdioServerTransportProvider(json, new WatchedInput(streams.in()), streams.out());
    }

    /**
     * Blocks until the connection has closed.
     *
     * @return true if stdin ended, which is how an AI app ends the session; false if the SDK stopped reading for
     *         another reason, which it logs
     */
    public boolean awaitClose() throws InterruptedException {
        closed.await();
        return stdinEnded.get();
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        stdio.setSessionFactory(transport -> sessionFactory.create(new ClosingTransport(transport)));
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return stdio.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return stdio.notifyClient(sessionId, method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return stdio.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return stdio.protocolVersions();
    }

    /** stdin, noting when it ends. */
    private final class WatchedInput extends FilterInputStream {

        WatchedInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            return noteEnd(super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return noteEnd(super.read(b, off, len));
        }

        private int noteEnd(int result) {
            if (result == -1) {
                stdinEnded.set(true);
            }
            return result;
        }
    }

    private final class ClosingTransport implements McpServerTransport {

        private final McpServerTransport delegate;

        ClosingTransport(McpServerTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            return delegate.sendMessage(message);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully().doFinally(signal -> closed.countDown());
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                closed.countDown();
            }
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }
}
