package castech.emvtxn.atm.host;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.Test;

/**
 * Regression tests for the post-response handshake in {@link AtmHostConnection}.
 *
 * <p>Root cause of the "approved withdrawal got reversed" bug: the host sends its
 * transaction/reversal RESPONSE and then closes the connection (the normal
 * "connection per transaction" behaviour). By the time {@code completeHandshake()}
 * runs, we have already received and parsed the authoritative response. If sending
 * the courtesy ACK (or reading the EOT) then failed with an {@link IOException},
 * the old code threw {@code ConnectionException} — which unwound back into the
 * transaction manager's catch block and PROMOTED THE PRE-SEND REVERSAL, reversing
 * an approval we already held.</p>
 *
 * <p>Contract under test: once the response is in hand, a handshake failure must be
 * non-fatal — logged and swallowed, exactly like {@code completeTritonHandshake()}.</p>
 */
public class AtmHostConnectionHandshakeTest {

    private static AtmHostConnection newConnection() {
        // forDns gives a fully-populated Hyosung config (builder/parser/timeouts);
        // no socket is opened until ensureConnected(), which we never call here.
        return new AtmHostConnection(ProcessorConfig.forDns("127.0.0.1", "TERM01"));
    }

    /** An OutputStream that fails every write, simulating a host that closed the
     *  socket immediately after sending its approval (broken pipe on the ACK). */
    private static OutputStream brokenPipeStream() {
        return new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("Broken pipe (host closed after approval)");
            }
            @Override public void write(byte[] b) throws IOException {
                throw new IOException("Broken pipe (host closed after approval)");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe (host closed after approval)");
            }
        };
    }

    /**
     * The core regression: an ACK write that fails after the response was received
     * must NOT throw. Before the fix this threw ConnectionException, which caused
     * an approved withdrawal to be reversed.
     */
    @Test
    public void completeHandshake_ackWriteFails_isNonFatal() {
        AtmHostConnection conn = newConnection();
        conn.injectStreamsForTest(brokenPipeStream(), new ByteArrayInputStream(new byte[0]));

        try {
            conn.completeHandshake();
        } catch (AtmHostConnection.ConnectionException e) {
            fail("completeHandshake() threw on a post-response ACK failure — this "
                    + "discards an approval already in hand and reverses approved "
                    + "withdrawals. It must be non-fatal. Got: " + e.getMessage());
        }
    }

    /**
     * A null output stream (host already fully gone) must likewise be non-fatal —
     * we still hold the parsed response.
     */
    @Test
    public void completeHandshake_nullOutputStream_isNonFatal() {
        AtmHostConnection conn = newConnection();
        conn.injectStreamsForTest(null, null);

        try {
            conn.completeHandshake();
        } catch (AtmHostConnection.ConnectionException e) {
            fail("completeHandshake() threw on a null output stream — must be "
                    + "non-fatal once the response is received. Got: " + e.getMessage());
        }
    }

    /** An OutputStream that accepts every write (the ACK goes out fine). */
    private static OutputStream sinkStream() {
        return new OutputStream() {
            @Override public void write(int b) { }
        };
    }

    /**
     * The gap the first fix left open: the ACK is written successfully, but the
     * host then closes the socket WITHOUT sending EOT (what a MUX restart looks
     * like). readResponse() sees read() == -1 and throws ConnectionException —
     * which is not an IOException, so the old {@code catch (IOException)} let it
     * escape and reverse the approval already in hand.
     */
    @Test
    public void completeHandshake_hostClosesWithoutEot_isNonFatal() throws Exception {
        AtmHostConnection conn = newConnection();
        conn.injectStreamsForTest(sinkStream(), new ByteArrayInputStream(new byte[0]));
        conn.injectSocketForTest(new java.net.Socket()); // unconnected; setSoTimeout() works

        try {
            conn.completeHandshake();
        } catch (Throwable t) {
            fail("completeHandshake() threw when the host closed without EOT after "
                    + "responding — the response is authoritative and this must be "
                    + "non-fatal. Got: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * A concurrent disconnect() nulls the socket field between the ACK write and
     * the EOT wait. That used to NPE on socket.setSoTimeout() — a RuntimeException,
     * also not caught by the old handler.
     */
    @Test
    public void completeHandshake_socketRacedToNull_isNonFatal() {
        AtmHostConnection conn = newConnection();
        conn.injectStreamsForTest(sinkStream(), new ByteArrayInputStream(new byte[0]));
        conn.injectSocketForTest(null);

        try {
            conn.completeHandshake();
        } catch (Throwable t) {
            fail("completeHandshake() threw when the socket was nulled by a concurrent "
                    + "disconnect — must be non-fatal. Got: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
