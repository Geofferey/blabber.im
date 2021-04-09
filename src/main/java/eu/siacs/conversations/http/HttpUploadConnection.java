package eu.siacs.conversations.http;

import android.util.Log;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.Checksum;
import eu.siacs.conversations.utils.CryptoHelper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static eu.siacs.conversations.http.HttpConnectionManager.FileTransferExecutor;

public class HttpUploadConnection implements Transferable {

    static final List<String> WHITE_LISTED_HEADERS = Arrays.asList(
            "Authorization",
            "Cookie",
            "Expires"
    );

    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private final SlotRequester mSlotRequester;
    private final Method method;
    private final boolean mUseTor;
    private boolean cancelled = false;
    private boolean delayed = false;
    private DownloadableFile file;
    private final Message message;
    private String mime;
    private SlotRequester.Slot slot;
    private byte[] key = null;
    private int mStatus = Transferable.STATUS_UNKNOWN;

    private long transmitted = 0;

    public HttpUploadConnection(Message message, Method method, HttpConnectionManager httpConnectionManager) {
        this.message = message;
        this.method = method;
        this.mHttpConnectionManager = httpConnectionManager;
        this.mXmppConnectionService = httpConnectionManager.getXmppConnectionService();
        this.mSlotRequester = new SlotRequester(this.mXmppConnectionService);
        this.mUseTor = mXmppConnectionService.useTorToConnect();
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public long getFileSize() {
        return file == null ? 0 : file.getExpectedSize();
    }

    @Override
    public int getProgress() {
        if (file == null) {
            return 0;
        }
        return (int) ((((double) transmitted) / file.getExpectedSize()) * 100);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    private void fail(String errorMessage) {
        finish();
        mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED, cancelled ? Message.ERROR_MESSAGE_CANCELLED : errorMessage);
    }

    private void finish() {
        mHttpConnectionManager.finishUploadConnection(this);
        message.setTransferable(null);
    }

    public void init(boolean delay) {
        final Account account = message.getConversation().getAccount();
        this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        if (message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            this.mime = "application/pgp-encrypted";
        } else {
            this.mime = this.file.getMimeType();
        }
        final long originalFileSize = file.getSize();
        this.delayed = delay;
        if (Config.ENCRYPT_ON_HTTP_UPLOADED
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                || message.getEncryption() == Message.ENCRYPTION_OTR) {
            this.key = new byte[44];
            mXmppConnectionService.getRNG().nextBytes(this.key);
            this.file.setKeyAndIv(this.key);
        }

        final String md5;

        if (method == Method.P1_S3) {
            try {
                md5 = Checksum.md5(AbstractConnectionManager.upgrade(file, new FileInputStream(file)));
            } catch (Exception e) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to calculate md5()", e);
                fail(e.getMessage());
                return;
            }
        } else {
            md5 = null;
        }

        this.file.setExpectedSize(originalFileSize + (file.getKey() != null ? 16 : 0));
        message.resetFileParams();
        this.mSlotRequester.request(method, account, file, mime, md5, new SlotRequester.OnSlotRequested() {
            @Override
            public void success(SlotRequester.Slot slot) {
                if (!cancelled) {
                    changeStatus(STATUS_WAITING);
                    FileTransferExecutor.execute(() -> {
                        changeStatus(STATUS_UPLOADING);
                        HttpUploadConnection.this.slot = slot;
                        HttpUploadConnection.this.upload();
                    });
                }
            }

            @Override
            public void failure(String message) {
                fail(message);
            }
        });
        message.setTransferable(this);
        mXmppConnectionService.markMessage(message, Message.STATUS_UNSEND);
    }

    private void upload() {
        final String slotHostname = slot.getPutUrl().host();
        final boolean onionSlot = slotHostname.endsWith(".onion");
        try {
            final OkHttpClient client;
            if (mUseTor || message.getConversation().getAccount().isOnion() || onionSlot) {
                client = new OkHttpClient.Builder().proxy(HttpConnectionManager.getProxy()).build();
            } else {
                client = new OkHttpClient();
            }
            final RequestBody requestBody = AbstractConnectionManager.requestBody(file);
            final Request request = new Request.Builder()
                    .url(slot.getPutUrl())
                    .put(requestBody)
                    .headers(slot.getHeaders())
                    .build();
            Log.d(Config.LOGTAG, "uploading file to " + slot.getPutUrl());
            final Response response = client.newCall(request).execute();
            final int code = response.code();
            if (code == 200 || code == 201) {
                Log.d(Config.LOGTAG, "finished uploading file");
                final String get;
                if (key != null) {
                    get = CryptoHelper.toAesGcmUrl(slot.getGetUrl().newBuilder().fragment(CryptoHelper.bytesToHex(key)).build());
                    } else {
                    get = slot.getGetUrl().toString();
                }
                mXmppConnectionService.getFileBackend().updateFileParams(message, get);
                mXmppConnectionService.getFileBackend().updateMediaScanner(file);
                finish();
                if (!message.isPrivateMessage()) {
                    message.setCounterpart(message.getConversation().getJid().asBareJid());
                }
                mXmppConnectionService.resendMessage(message, delayed);
            } else {
                Log.d(Config.LOGTAG, "http upload failed because response code was " + code);
                fail("http upload failed because response code was " + code);
            }
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "http upload failed", e);
            fail(e.getMessage());
        }
    }

    private void changeStatus(int status) {
        this.mStatus = status;
        mHttpConnectionManager.updateConversationUi(true);
    }

    public Message getMessage() {
        return message;
    }
}