package eu.siacs.conversations.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.RequiresApi;

import net.ypresto.androidtranscoder.MediaTranscoder;
import net.ypresto.androidtranscoder.format.MediaFormatStrategyPresets;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.utils.MimeUtils;

public class AttachFileToConversationRunnable implements Runnable, MediaTranscoder.Listener {

    private final XmppConnectionService mXmppConnectionService;
    private final Message message;
    private final Conversation conversation;
    private final Uri uri;
    private final String type;
    private final UiCallback<Message> callback;
    private final long maxUploadSize;
    private final boolean isVideoMessage;
    private final long originalFileSize;
    private int currentProgress = -1;
    public static String isCompressingVideo = null;

    public AttachFileToConversationRunnable(XmppConnectionService xmppConnectionService, Uri uri, String type, Message message, Conversation conversation, UiCallback<Message> callback, long maxUploadSize) {
        this.uri = uri;
        this.type = type;
        this.mXmppConnectionService = xmppConnectionService;
        this.message = message;
        this.conversation = conversation;
        this.callback = callback;
        this.maxUploadSize = maxUploadSize;
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        final int autoAcceptFileSize = Config.FILE_SIZE;
        this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService, uri);
        this.isVideoMessage = !getFileBackend().useFileAsIs(uri)
                && (mimeType != null && mimeType.startsWith("video/")
                && (mXmppConnectionService.getCompressVideoBitratePreference() != 0 && mXmppConnectionService.getCompressVideoResolutionPreference() != 0))
                && originalFileSize > autoAcceptFileSize;
    }

    boolean isVideoMessage() {
        return this.isVideoMessage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    }

    private void processAsFile() {
        final String path = mXmppConnectionService.getFileBackend().getOriginalPath(uri);
        if (path != null && !FileBackend.isPathBlacklisted(path)) {
            message.setRelativeFilePath(path);
            mXmppConnectionService.getFileBackend().updateFileParams(message);
            if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                mXmppConnectionService.getPgpEngine().encrypt(message, callback);
            } else {
                mXmppConnectionService.sendMessage(message);
                callback.success(message);
            }
        } else {
            try {
                mXmppConnectionService.getFileBackend().copyFileToPrivateStorage(message, uri, type);
                mXmppConnectionService.getFileBackend().updateFileParams(message);
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    final PgpEngine pgpEngine = mXmppConnectionService.getPgpEngine();
                    if (pgpEngine != null) {
                        pgpEngine.encrypt(message, callback);
                    } else if (callback != null) {
                        callback.error(R.string.unable_to_connect_to_keychain, null);
                    }
                } else {
                    mXmppConnectionService.sendMessage(message);
                    callback.success(message);
                }
            } catch (FileBackend.FileCopyException e) {
                callback.error(e.getResId(), message);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void processAsVideo() throws Exception {
        Log.d(Config.LOGTAG, "processing file as video");
        mXmppConnectionService.startForcingForegroundNotification();
        isCompressingVideo = conversation.getUuid();
        SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        message.setRelativeFilePath("Sent/" + fileDateFormat.format(new Date(message.getTimeSent())) + "_" + message.getUuid().substring(0, 4) + "_komp.mp4");
        final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message);
        file.getParentFile().mkdirs();
        final ParcelFileDescriptor parcelFileDescriptor = mXmppConnectionService.getContentResolver().openFileDescriptor(uri, "r");
        if (parcelFileDescriptor == null) {
            throw new FileNotFoundException("Parcel File Descriptor was null");
        }
        FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
        Future<Void> future = getVideoCompressor(fileDescriptor, file, maxUploadSize);
        try {
            future.get();
        } catch (InterruptedException e) {
            mXmppConnectionService.stopForcingForegroundNotification();
            isCompressingVideo = null;
            throw new AssertionError(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error) {
                mXmppConnectionService.stopForcingForegroundNotification();
                isCompressingVideo = null;
                processAsFile();
            } else {
                Log.d(Config.LOGTAG, "ignoring execution exception. Should get handled by onTranscodeFiled() instead", e);
            }
        }
    }

    private Future<Void> getVideoCompressor(final FileDescriptor fileDescriptor, final File file, final long maxUploadSize) throws Exception {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        try {
            mediaMetadataRetriever.setDataSource(fileDescriptor);
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception(e);
        }
        long videoDuration;
        long estimatedFileSize = maxUploadSize / 2;  // keep estimated filesize half as big as maxUploadSize
        try {
            videoDuration = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000; //in seconds
        } catch (NumberFormatException e) {
            videoDuration = -1;
        }
        int bitrateAfterCompression = safeLongToInt(mXmppConnectionService.getCompressVideoBitratePreference() / 8); //in bytes
        long size = videoDuration * bitrateAfterCompression;
        if (estimatedFileSize >= size) {
            return MediaTranscoder.getInstance().transcodeVideo(fileDescriptor, file.getAbsolutePath(), MediaFormatStrategyPresets.createAndroidStandardStrategy(mXmppConnectionService.getCompressVideoBitratePreference(), mXmppConnectionService.getCompressVideoResolutionPreference()), this);
        } else {
            int newBitrate = safeLongToInt((estimatedFileSize / videoDuration) * 8); // in bits/sec
            int newResoloution = 0;
            if (newBitrate <= mXmppConnectionService.getResources().getInteger(R.integer.verylow_video_bitrate)) {
                newResoloution = mXmppConnectionService.getResources().getInteger(R.integer.verylow_video_res);
            } else if (newBitrate > mXmppConnectionService.getResources().getInteger(R.integer.verylow_video_bitrate) && newBitrate <= mXmppConnectionService.getResources().getInteger(R.integer.low_video_bitrate)) {
                newResoloution = mXmppConnectionService.getResources().getInteger(R.integer.low_video_res);
            } else if (newBitrate > mXmppConnectionService.getResources().getInteger(R.integer.low_video_bitrate) && newBitrate <= mXmppConnectionService.getResources().getInteger(R.integer.mid_video_bitrate)) {
                newResoloution = mXmppConnectionService.getResources().getInteger(R.integer.mid_video_res);
            } else if (newBitrate > mXmppConnectionService.getResources().getInteger(R.integer.mid_video_bitrate) && newBitrate <= mXmppConnectionService.getResources().getInteger(R.integer.high_video_bitrate)) {
                newResoloution = mXmppConnectionService.getResources().getInteger(R.integer.high_video_res);
            } else {
                newResoloution = mXmppConnectionService.getResources().getInteger(R.integer.high_video_res);
            }
            return MediaTranscoder.getInstance().transcodeVideo(fileDescriptor, file.getAbsolutePath(), MediaFormatStrategyPresets.createAndroidStandardStrategy(newBitrate, newResoloution), this);
        }
    }

    private static int safeLongToInt(long l) {
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(l + " cannot be cast to int without changing its value.");
        }
        return (int) l;
    }

    @Override
    public void onTranscodeProgress(double progress) {
        final int p = (int) Math.round(progress * 100);
        if (p > currentProgress) {
            currentProgress = p;
            mXmppConnectionService.getNotificationService().updateFileAddingNotification(p, message);
            isCompressingVideo = conversation.getUuid();
        }
    }

    @Override
    public void onTranscodeCompleted() {
        mXmppConnectionService.stopForcingForegroundNotification();
        isCompressingVideo = null;
        final File file = mXmppConnectionService.getFileBackend().getFile(message);
        long convertedFileSize = mXmppConnectionService.getFileBackend().getFile(message).getSize();
        Log.d(Config.LOGTAG, "originalFileSize = " + originalFileSize + " convertedFileSize = " + convertedFileSize);
        if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
            if (file.delete()) {
                Log.d(Config.LOGTAG, "original file size was smaller. Deleting and processing as file");
                processAsFile();
                return;
            } else {
                Log.d(Config.LOGTAG, "unable to delete converted file");
            }
        }
        mXmppConnectionService.getFileBackend().updateFileParams(message);
        if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            mXmppConnectionService.getPgpEngine().encrypt(message, callback);
        } else {
            mXmppConnectionService.sendMessage(message);
            callback.success(message);
        }
    }

    @Override
    public void onTranscodeCanceled() {
        mXmppConnectionService.stopForcingForegroundNotification();
        isCompressingVideo = null;
        processAsFile();
    }

    @Override
    public void onTranscodeFailed(Exception e) {
        mXmppConnectionService.stopForcingForegroundNotification();
        isCompressingVideo = null;
        Log.d(Config.LOGTAG, "video transcoding failed", e);
        processAsFile();
    }

    @Override
    public void run() {
        if (this.isVideoMessage()) {
            try {
                processAsVideo();
            } catch (Exception e) {
                processAsFile();
                e.printStackTrace();
            }
        } else {
            processAsFile();
        }
    }

    public static String getVideoCompression(final Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString("video_compression", context.getResources().getString(R.string.video_compression));
    }

    public FileBackend getFileBackend() {
        return mXmppConnectionService.fileBackend;
    }
}