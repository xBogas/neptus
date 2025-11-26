package pt.lsts.neptus.plugins.server;

import jdk.internal.org.jline.reader.EndOfFileException;
import pt.lsts.imc.IMCDefinition;
import pt.lsts.imc.IMCInputStream;
import pt.lsts.imc.IMCMessage;
import pt.lsts.imc.IMCOutputStream;
import pt.lsts.neptus.NeptusLog;

import java.io.Closeable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal IMC-over-TCP client wrapper for outbound connections.
 * - connect(host, port, timeoutMs)
 * - send(IMCMessage)
 * - addListener(MessageListener)
 * - close()
 */
public class ImcTcpClient {

    private final IMCDefinition imcDef;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private String remoteHost;
    private Socket socket;
    private IMCInputStream imcIn;
    private IMCOutputStream imcOut;
    private BufferedOutputStream bufferedOut;
    private Thread readerThread;

    public ImcTcpClient(IMCDefinition imcDef) {
        this.imcDef = imcDef;
    }

    public void addListener(MessageListener l) {
        if (l == null) {
            return;
        }

        listeners.add(l);
    }

    public void removeListener(MessageListener l) {
        listeners.remove(l);
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized void connect(String host, int port, int ms) throws IOException {
        if (isConnected()) {
            return;
        }

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), ms);
        socket.setTcpNoDelay(true);

        remoteHost = host;

        bufferedOut = new BufferedOutputStream(socket.getOutputStream());
        imcOut = new IMCOutputStream(imcDef, bufferedOut);

        imcIn = new IMCInputStream(socket.getInputStream(), imcDef);

        startReader(host + ":" + port);
        NeptusLog.pub().info("ImcTcpClient connected to {}:{}", host, port);
    }

    private void startReader(String host) {
        readerThread = new Thread(this::readerMain, "ImcTcpClient-reader-" + host);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readerMain() {
        Exception cause = null;
        try {
            while (isConnected()) {
                IMCMessage msg = imcIn.readMessage();
                if (msg == null) // Assume stream closed
                {
                    break;
                }

                for (MessageListener l : listeners) {
                    try {
                        l.onMessage(msg, imcDef.getName());
                    }
                    catch (Throwable e) {
                        NeptusLog.pub().warn("Listener failed to read {}", e.toString());
                    }
                }
            }
        }
        catch (IOException e) {
            cause = e;
        }
        finally {
            notifyDisconnect(cause);
            close();
        }
    }

    public synchronized void send(IMCMessage msg) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected");
        }

        imcOut.writeMessage(msg);
        bufferedOut.flush();
    }

    public synchronized void notifyDisconnect(Exception cause) {
        for (MessageListener l : listeners) {
            try {
                l.onDisconnect(remoteHost, cause);
            }
            catch (Throwable e) {
                NeptusLog.pub().warn("Listener failed to disconnect {}", e.toString());
            }
        }
    }

    private void close(Closeable io) {
        if (io == null) {
            return;
        }

        try {
            io.close();
        }
        catch (IOException ignored) {

        }
    }

    public synchronized void close() {
        try {
            if (readerThread != null) {
                readerThread.interrupt();
                readerThread = null;
            }
        }
        catch (Throwable ignored) {
        }

        try {
            if (imcOut != null) {
                imcOut.close();
                imcOut = null;
            }
        }
        catch (IOException ignored) {
        }

        close(imcIn);
        close(bufferedOut);
        close(socket);

        Exception cause = new EndOfFileException("Closing socket!");
        for (MessageListener l : listeners) {
            try {
                l.onDisconnect(remoteHost, cause);
            }
            catch (Exception ignored) {

            }

            removeListener(l);
        }
    }

    public interface MessageListener {
        void onMessage(IMCMessage msg, String remote);

        default void onDisconnect(String remote, Exception e) {
        }
    }
}
