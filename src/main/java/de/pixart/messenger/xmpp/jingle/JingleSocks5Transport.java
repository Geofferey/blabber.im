package de.pixart.messenger.xmpp.jingle;

import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import de.pixart.messenger.Config;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.DownloadableFile;
import de.pixart.messenger.persistance.FileBackend;
import de.pixart.messenger.services.AbstractConnectionManager;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.SocksSocketFactory;
import de.pixart.messenger.utils.WakeLockHelper;
import de.pixart.messenger.xmpp.jingle.stanzas.Content;

public class JingleSocks5Transport extends JingleTransport {
    private static final int SOCKET_TIMEOUT_DIRECT = 3000;
    private static final int SOCKET_TIMEOUT_PROXY = 5000;
    private final JingleCandidate candidate;
    private final JingleConnection connection;
    private final String destination;
    private OutputStream outputStream;
    private InputStream inputStream;
    private boolean isEstablished = false;
    private boolean activated = false;
    private ServerSocket serverSocket;
    private Socket socket;

    JingleSocks5Transport(JingleConnection jingleConnection, JingleCandidate candidate) {
        final MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        this.candidate = candidate;
        this.connection = jingleConnection;
        final StringBuilder destBuilder = new StringBuilder();
        if (jingleConnection.getFtVersion() == Content.Version.FT_3) {
            Log.d(Config.LOGTAG, this.connection.getAccount().getJid().asBareJid() + ": using session Id instead of transport Id for proxy destination");
            destBuilder.append(jingleConnection.getSessionId());
        } else {
            destBuilder.append(jingleConnection.getTransportId());
        }
        if (candidate.isOurs()) {
            destBuilder.append(jingleConnection.getAccount().getJid());
            destBuilder.append(jingleConnection.getCounterPart());
        } else {
            destBuilder.append(jingleConnection.getCounterPart());
            destBuilder.append(jingleConnection.getAccount().getJid());
        }
        messageDigest.reset();
        this.destination = CryptoHelper.bytesToHex(messageDigest.digest(destBuilder.toString().getBytes()));
        if (candidate.isOurs() && candidate.getType() == JingleCandidate.TYPE_DIRECT) {
            createServerSocket();
        }
    }

    private void createServerSocket() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(candidate.getHost()), candidate.getPort()));
            new Thread(() -> {
                try {
                    final Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try {
                            acceptIncomingSocketConnection(socket);
                        } catch (IOException e) {
                            Log.d(Config.LOGTAG, "unable to read from socket", e);

                        }
                    }).start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        Log.d(Config.LOGTAG, "unable to accept socket", e);
                    }
                }
            }).start();
        } catch (IOException e) {
            Log.d(Config.LOGTAG, "unable to bind server socket ", e);
        }
    }

    private void acceptIncomingSocketConnection(final Socket socket) throws IOException {
        Log.d(Config.LOGTAG, "accepted connection from " + socket.getInetAddress().getHostAddress());
        socket.setSoTimeout(SOCKET_TIMEOUT_DIRECT);
        final byte[] authBegin = new byte[2];
        final InputStream inputStream = socket.getInputStream();
        final OutputStream outputStream = socket.getOutputStream();
        inputStream.read(authBegin);
        if (authBegin[0] != 0x5) {
            socket.close();
        }
        final short methodCount = authBegin[1];
        final byte[] methods = new byte[methodCount];
        inputStream.read(methods);
        if (SocksSocketFactory.contains((byte) 0x00, methods)) {
            outputStream.write(new byte[]{0x05, 0x00});
        } else {
            outputStream.write(new byte[]{0x05, (byte) 0xff});
        }
        byte[] connectCommand = new byte[4];
        inputStream.read(connectCommand);
        if (connectCommand[0] == 0x05 && connectCommand[1] == 0x01 && connectCommand[3] == 0x03) {
            int destinationCount = inputStream.read();
            final byte[] destination = new byte[destinationCount];
            inputStream.read(destination);
            final byte[] port = new byte[2];
            inputStream.read(port);
            final String receivedDestination = new String(destination);
            final ByteBuffer response = ByteBuffer.allocate(7 + destination.length);
            final byte[] responseHeader;
            final boolean success;
            if (receivedDestination.equals(this.destination) && this.socket == null) {
                responseHeader = new byte[]{0x05, 0x00, 0x00, 0x03};
                success = true;
            } else {
                Log.d(Config.LOGTAG, connection.getAccount().getJid().asBareJid() + ": destination mismatch. received " + receivedDestination + " (expected " + this.destination + ")");
                responseHeader = new byte[]{0x05, 0x04, 0x00, 0x03};
                success = false;
            }
            response.put(responseHeader);
            response.put((byte) destination.length);
            response.put(destination);
            response.put(port);
            outputStream.write(response.array());
            outputStream.flush();
            if (success) {
                Log.d(Config.LOGTAG, connection.getAccount().getJid().asBareJid() + ": successfully processed connection to candidate " + candidate.getHost() + ":" + candidate.getPort());
                socket.setSoTimeout(0);
                this.socket = socket;
                this.inputStream = inputStream;
                this.outputStream = outputStream;
                this.isEstablished = true;
                FileBackend.close(serverSocket);
            } else {
                FileBackend.close(socket);
            }
        } else {
            socket.close();
        }
    }

    public void connect(final OnTransportConnected callback) {
        new Thread(() -> {
            final int timeout = candidate.getType() == JingleCandidate.TYPE_DIRECT ? SOCKET_TIMEOUT_DIRECT : SOCKET_TIMEOUT_PROXY;
            try {
                final boolean useTor = connection.getAccount().isOnion() || connection.getConnectionManager().getXmppConnectionService().useTorToConnect();
                if (useTor) {
                    socket = SocksSocketFactory.createSocketOverTor(candidate.getHost(), candidate.getPort());
                } else {
                    socket = new Socket();
                    SocketAddress address = new InetSocketAddress(candidate.getHost(), candidate.getPort());
                    socket.connect(address, timeout);
                }
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                socket.setSoTimeout(timeout);
                SocksSocketFactory.createSocksConnection(socket, destination, 0);
                socket.setSoTimeout(0);
                isEstablished = true;
                callback.established();
            } catch (Exception e) {
                callback.failed();
            }
        }).start();

    }

    public void send(final DownloadableFile file, final OnFileTransmissionStatusChanged callback) {
        new Thread(() -> {
            InputStream fileInputStream = null;
            final PowerManager.WakeLock wakeLock = connection.getConnectionManager().createWakeLock("jingle_send_" + connection.getSessionId());
            long transmitted = 0;
            try {
                wakeLock.acquire();
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.reset();
                fileInputStream = connection.getFileInputStream();
                if (fileInputStream == null) {
                    Log.d(Config.LOGTAG, connection.getAccount().getJid().asBareJid() + ": could not create input stream");
                    callback.onFileTransferAborted();
                    return;
                }
                final InputStream innerInputStream = AbstractConnectionManager.upgrade(file, fileInputStream);
                long size = file.getExpectedSize();
                int count;
                byte[] buffer = new byte[8192];
                while ((count = innerInputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    transmitted += count;
                    connection.updateProgress((int) ((((double) transmitted) / size) * 100));
                }
                outputStream.flush();
                file.setSha1Sum(digest.digest());
                if (callback != null) {
                    callback.onFileTransmitted(file);
                }
            } catch (Exception e) {
                final Account account = connection.getAccount();
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": failed sending file after " + transmitted + "/" + file.getExpectedSize() + " (" + socket.getInetAddress() + ":" + socket.getPort() + ")", e);
                callback.onFileTransferAborted();
            } finally {
                FileBackend.close(fileInputStream);
                WakeLockHelper.release(wakeLock);
            }
        }).start();

    }

    public void receive(final DownloadableFile file, final OnFileTransmissionStatusChanged callback) {
        new Thread(() -> {
            OutputStream fileOutputStream = null;
            final PowerManager.WakeLock wakeLock = connection.getConnectionManager().createWakeLock("jingle_receive_" + connection.getSessionId());
            try {
                wakeLock.acquire();
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.reset();
                //inputStream.skip(45);
                socket.setSoTimeout(30000);
                fileOutputStream = connection.getFileOutputStream();
                if (fileOutputStream == null) {
                    callback.onFileTransferAborted();
                    Log.d(Config.LOGTAG, connection.getAccount().getJid().asBareJid() + ": could not create output stream");
                    return;
                }
                double size = file.getExpectedSize();
                long remainingSize = file.getExpectedSize();
                byte[] buffer = new byte[8192];
                int count;
                while (remainingSize > 0) {
                    count = inputStream.read(buffer);
                    if (count == -1) {
                        callback.onFileTransferAborted();
                        Log.d(Config.LOGTAG, connection.getAccount().getJid().asBareJid() + ": file ended prematurely with " + remainingSize + " bytes remaining");
                        return;
                    } else {
                        fileOutputStream.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        remainingSize -= count;
                    }
                    connection.updateProgress((int) (((size - remainingSize) / size) * 100));
                }
                fileOutputStream.flush();
                fileOutputStream.close();
                file.setSha1Sum(digest.digest());
                callback.onFileTransmitted(file);
            } catch (Exception e) {
                Log.d(Config.LOGTAG, connection.getAccount().getJid().asBareJid() + ": " + e.getMessage());
                callback.onFileTransferAborted();
            } finally {
                WakeLockHelper.release(wakeLock);
                FileBackend.close(fileOutputStream);
                FileBackend.close(inputStream);
            }
        }).start();
    }

    public boolean isProxy() {
        return this.candidate.getType() == JingleCandidate.TYPE_PROXY;
    }

    public boolean needsActivation() {
        return (this.isProxy() && !this.activated);
    }

    public void disconnect() {
        FileBackend.close(inputStream);
        FileBackend.close(outputStream);
        FileBackend.close(socket);
        FileBackend.close(serverSocket);
    }

    public boolean isEstablished() {
        return this.isEstablished;
    }

    public JingleCandidate getCandidate() {
        return this.candidate;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }
}