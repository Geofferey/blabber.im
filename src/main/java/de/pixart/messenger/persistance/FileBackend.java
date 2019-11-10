package de.pixart.messenger.persistance;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.StructStat;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.acl.LastOwnerException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.entities.DownloadableFile;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.services.AttachFileToConversationRunnable;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.ui.util.Attachment;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.ExifHelper;
import de.pixart.messenger.utils.FileUtils;
import de.pixart.messenger.utils.FileWriterException;
import de.pixart.messenger.utils.MimeUtils;
import de.pixart.messenger.xmpp.pep.Avatar;
import ezvcard.Ezvcard;
import ezvcard.VCard;

public class FileBackend {

    private static final Object THUMBNAIL_LOCK = new Object();

    private static final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US);

    private static final String FILE_PROVIDER = ".files";
    private static final String APP_DIRECTORY = "Pix-Art Messenger";

    private XmppConnectionService mXmppConnectionService;

    public FileBackend(XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    private void createNoMedia() {
        final File nomedia_files = new File(getConversationsDirectory("Files") + ".nomedia");
        final File nomedia_audios = new File(getConversationsDirectory("Audios") + ".nomedia");
        final File nomedia_videos_sent = new File(getConversationsDirectory("Videos/Sent") + ".nomedia");
        final File nomedia_files_sent = new File(getConversationsDirectory("Files/Sent") + ".nomedia");
        final File nomedia_audios_sent = new File(getConversationsDirectory("Audios/Sent") + ".nomedia");
        final File nomedia_images_sent = new File(getConversationsDirectory("Images/Sent") + ".nomedia");
        if (!nomedia_files.exists()) {
            try {
                nomedia_files.createNewFile();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "could not create nomedia file for files directory");
            }
        }
        if (!nomedia_audios.exists()) {
            try {
                nomedia_audios.createNewFile();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "could not create nomedia file for audio directory");
            }
        }
        if (!nomedia_videos_sent.exists()) {
            try {
                nomedia_videos_sent.createNewFile();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "could not create nomedia file for videos sent directory");
            }
        }
        if (!nomedia_files_sent.exists()) {
            try {
                nomedia_files_sent.createNewFile();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "could not create nomedia file for files sent directory");
            }
        }
        if (!nomedia_audios_sent.exists()) {
            try {
                nomedia_audios_sent.createNewFile();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "could not create nomedia file for audios sent directory");
            }
        }
        if (!nomedia_images_sent.exists()) {
            try {
                nomedia_images_sent.createNewFile();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "could not create nomedia file for images sent directory");
            }
        }
    }

    public static Uri getMediaUri(Context context, File file) {
        final String filePath = file.getAbsolutePath();
        final Cursor cursor;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    MediaStore.Images.Media.DATA + "=? ",
                    new String[]{filePath}, null);
        } catch (SecurityException e) {
            return null;
        }
        if (cursor != null && cursor.moveToFirst()) {
            final int id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
            cursor.close();
            return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
        } else {
            return null;
        }
    }

    public void updateMediaScanner(File file) {
        updateMediaScanner(file, null);
    }

    public void updateMediaScanner(File file, final Runnable callback) {
        if (file.getAbsolutePath().startsWith(getConversationsDirectory("Images"))
                || file.getAbsolutePath().startsWith(getConversationsDirectory("Videos"))) {
            MediaScannerConnection.scanFile(mXmppConnectionService, new String[]{file.getAbsolutePath()}, null, new MediaScannerConnection.MediaScannerConnectionClient() {
                @Override
                public void onMediaScannerConnected() {

                }

                @Override
                public void onScanCompleted(String path, Uri uri) {
                    if (callback != null && file.getAbsolutePath().equals(path)) {
                        callback.run();
                    } else {
                        Log.d(Config.LOGTAG, "media scanner scanned wrong file");
                        if (callback != null) {
                            callback.run();
                        }
                    }
                }
            });
            return;
            /*Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            intent.setData(Uri.fromFile(file));
            mXmppConnectionService.sendBroadcast(intent);*/
        } else {
            createNoMedia();
        }
        if (callback != null) {
            callback.run();
        }
    }

    public boolean deleteFile(File file) {
        if (file.delete()) {
            updateMediaScanner(file);
            return true;
        } else {
            return false;
        }
    }

    public boolean deleteFile(Message message) {
        File file = getFile(message);
        return deleteFile(file);
    }

    public DownloadableFile getFile(Message message) {
        return getFile(message, true);
    }

    public DownloadableFile getFileForPath(String path) {
        return getFileForPath(path, MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(path)));
    }

    public DownloadableFile getFileForPath(String path, String mime) {
        final DownloadableFile file;
        if (path.startsWith("/")) {
            file = new DownloadableFile(path);
        } else {
            if (mime != null && mime.startsWith("image")) {
                file = new DownloadableFile(getConversationsDirectory("Images") + path);
            } else if (mime != null && mime.startsWith("video")) {
                file = new DownloadableFile(getConversationsDirectory("Videos") + path);
            } else if (mime != null && mime.startsWith("audio")) {
                file = new DownloadableFile(getConversationsDirectory("Audios") + path);
            } else {
                file = new DownloadableFile(getConversationsDirectory("Files") + path);
            }
        }
        return file;
    }

    public boolean isInternalFile(final File file) {
        final File internalFile = getFileForPath(file.getName());
        return file.getAbsolutePath().equals(internalFile.getAbsolutePath());
    }

    public DownloadableFile getFile(Message message, boolean decrypted) {
        final boolean encrypted = !decrypted
                && (message.getEncryption() == Message.ENCRYPTION_PGP
                || message.getEncryption() == Message.ENCRYPTION_DECRYPTED);
        String path = message.getRelativeFilePath();
        if (path == null) {
            path = fileDateFormat.format(new Date(message.getTimeSent())) + "_" + message.getUuid().substring(0, 4);
        }
        final DownloadableFile file = getFileForPath(path, message.getMimeType());
        if (encrypted) {
            return new DownloadableFile(getConversationsDirectory("Files") + file.getName() + ".pgp");
        } else {
            return file;
        }
    }

    public static long getFileSize(Context context, Uri uri) {
        try {
            final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long size = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE));
                cursor.close();
                return size;
            } else {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean allFilesUnderSize(Context context, List<Attachment> attachments, long max) {
        final boolean compressVideo = !AttachFileToConversationRunnable.getVideoCompression(context).equals("uncompressed");
        if (max <= 0) {
            Log.d(Config.LOGTAG, "server did not report max file size for http upload");
            return true; //exception to be compatible with HTTP Upload < v0.2
        }
        for (Attachment attachment : attachments) {
            if (attachment.getType() != Attachment.Type.FILE) {
                continue;
            }
            String mime = attachment.getMime();
            if (mime != null && mime.startsWith("video/") && compressVideo) {
                try {
                    Dimensions dimensions = FileBackend.getVideoDimensions(context, attachment.getUri());
                    if (dimensions.getMin() >= 720) {
                        Log.d(Config.LOGTAG, "do not consider video file with min width larger or equal than 720 for size check");
                        continue;
                    }
                } catch (NotAVideoFile notAVideoFile) {
                    //ignore and fall through
                }
            }
            if (FileBackend.getFileSize(context, attachment.getUri()) > max) {
                Log.d(Config.LOGTAG, "not all files are under " + max + " bytes. suggesting falling back to jingle");
                return false;
            }
        }
        return true;
    }

    public List<Attachment> convertToAttachments(List<DatabaseBackend.FilePath> relativeFilePaths) {
        List<Attachment> attachments = new ArrayList<>();
        for (DatabaseBackend.FilePath relativeFilePath : relativeFilePaths) {
            final String mime = MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(relativeFilePath.path));
            final File file = getFileForPath(relativeFilePath.path, mime);
            if (file.exists() && mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                attachments.add(Attachment.of(relativeFilePath.uuid, file, mime));
            }
        }
        return attachments;
    }

    public static String getConversationsDirectory(final String type) {
        if (type.equalsIgnoreCase("null")) {
            return Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + APP_DIRECTORY + File.separator;
        } else {
            return getAppMediaDirectory() + APP_DIRECTORY + " " + type + File.separator;
        }
    }

    public static String getAppMediaDirectory() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + APP_DIRECTORY + File.separator + "Media" + File.separator;
    }

    public static String getBackupDirectory() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + APP_DIRECTORY + File.separator + "Database" + File.separator;
    }

    public static String getAppLogsDirectory() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + APP_DIRECTORY + File.separator + "Chats" + File.separator;
    }

    public static String getAppUpdateDirectory() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + APP_DIRECTORY + File.separator + "Update" + File.separator;
    }

    private Bitmap resize(final Bitmap originalBitmap, int size) throws IOException {
        int w = originalBitmap.getWidth();
        int h = originalBitmap.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IOException("Decoded bitmap reported bounds smaller 0");
        } else if (Math.max(w, h) > size) {
            int scalledW;
            int scalledH;
            if (w <= h) {
                scalledW = Math.max((int) (w / ((double) h / size)), 1);
                scalledH = size;
            } else {
                scalledW = size;
                scalledH = Math.max((int) (h / ((double) w / size)), 1);
            }
            final Bitmap result = Bitmap.createScaledBitmap(originalBitmap, scalledW, scalledH, true);
            if (!originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }
            return result;
        } else {
            return originalBitmap;
        }
    }

    private static Bitmap rotate(Bitmap bitmap, int degree) {
        if (degree == 0) {
            return bitmap;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        Matrix mtx = new Matrix();
        mtx.postRotate(degree);
        Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, w, h, mtx, true);
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return result;
    }

    public boolean useImageAsIs(Uri uri) {
        String path = getOriginalPath(uri);
        if (path == null || isPathBlacklisted(path)) {
            return false;
        }
        File file = new File(path);
        long size = file.length();
        if ((size == 0 || size >= mXmppConnectionService.getCompressImageSizePreference()) && mXmppConnectionService.getCompressImageSizePreference() != 0) {
            return false;
        }
        if (mXmppConnectionService.getCompressImageResolutionPreference() == 0 && mXmppConnectionService.getCompressImageSizePreference() == 0) {
            return true;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeStream(mXmppConnectionService.getContentResolver().openInputStream(uri), null, options);
            if (options.outMimeType == null || options.outHeight <= 0 || options.outWidth <= 0) {
                return false;
            }
            return (options.outWidth <= mXmppConnectionService.getCompressImageResolutionPreference() && options.outHeight <= mXmppConnectionService.getCompressImageResolutionPreference() && options.outMimeType.contains(Config.IMAGE_FORMAT.name().toLowerCase()));
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    public boolean useFileAsIs(Uri uri) {
        String path = getOriginalPath(uri);
        if (path == null) {
            Log.d(Config.LOGTAG, "File path = null");
            return false;
        }
        if (path.contains(getConversationsDirectory("null"))) {
            Log.d(Config.LOGTAG, "File " + path + " is in our directory");
            return true;
        }
        Log.d(Config.LOGTAG, "File " + path + " is not in our directory");
        return false;
    }

    public static boolean isPathBlacklisted(String path) {
        Environment.getDataDirectory();
        final String androidDataPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/";
        return path.startsWith(androidDataPath);
    }

    public String getOriginalPath(Uri uri) {
        return FileUtils.getPath(mXmppConnectionService, uri);
    }

    private void copyFileToPrivateStorage(File file, Uri uri) throws FileCopyException {
        Log.d(Config.LOGTAG, "copy file (" + uri.toString() + ") to private storage " + file.getAbsolutePath());
        file.getParentFile().mkdirs();
        OutputStream os = null;
        InputStream is = null;
        try {
            file.createNewFile();
            os = new FileOutputStream(file);
            is = mXmppConnectionService.getContentResolver().openInputStream(uri);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is != null ? is.read(buffer) : 0) > 0) {
                try {
                    os.write(buffer, 0, length);
                } catch (IOException e) {
                    throw new FileWriterException();
                } catch (Exception e) {
                    throw new FileWriterException();
                }
            }
            try {
                os.flush();
            } catch (IOException e) {
                throw new FileWriterException();
            }
        } catch (FileNotFoundException e) {
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (FileWriterException e) {
            throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
        } catch (IOException e) {
            e.printStackTrace();
            throw new FileCopyException(R.string.error_io_exception);
        } catch (Exception e) {
            e.printStackTrace();
            throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
        } finally {
            close(os);
            close(is);
        }
    }

    public void copyFileToPrivateStorage(Message message, Uri uri, String type) throws FileCopyException {
        String mime = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        Log.d(Config.LOGTAG, "copy " + uri.toString() + " to private storage (mime=" + mime + ")");
        String extension = MimeUtils.guessExtensionFromMimeType(mime);
        if (extension == null) {
            Log.d(Config.LOGTAG, "extension from mime type was null");
            extension = getExtensionFromUri(uri);
        }
        if ("ogg".equals(extension) && type != null && type.startsWith("audio/")) {
            extension = "oga";
        }
        String filename = "Sent/" + fileDateFormat.format(new Date(message.getTimeSent())) + "_" + message.getUuid().substring(0, 4);
        message.setRelativeFilePath(filename + "." + extension);
        copyFileToPrivateStorage(mXmppConnectionService.getFileBackend().getFile(message), uri);
    }

    private String getExtensionFromUri(Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DATA};
        String filename = null;
        Cursor cursor;
        try {
            cursor = mXmppConnectionService.getContentResolver().query(uri, projection, null, null, null);
        } catch (IllegalArgumentException e) {
            cursor = null;
        }
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    filename = cursor.getString(0);
                }
            } catch (Exception e) {
                filename = null;
            } finally {
                cursor.close();
            }
        }
        if (filename == null) {
            final List<String> segments = uri.getPathSegments();
            if (segments.size() > 0) {
                filename = segments.get(segments.size() - 1);
            }
        }
        int pos = filename == null ? -1 : filename.lastIndexOf('.');
        return pos > 0 ? filename.substring(pos + 1) : null;
    }

    private void copyImageToPrivateStorage(File file, Uri image, int sampleSize) throws FileCopyException {
        file.getParentFile().mkdirs();
        InputStream is = null;
        OutputStream os = null;
        try {
            if (!file.exists() && !file.createNewFile()) {
                throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
            }
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            if (is == null) {
                throw new FileCopyException(R.string.error_not_an_image_file);
            }
            Bitmap originalBitmap;
            BitmapFactory.Options options = new BitmapFactory.Options();
            int inSampleSize = (int) Math.pow(2, sampleSize);
            Log.d(Config.LOGTAG, "reading bitmap with sample size " + inSampleSize);
            options.inSampleSize = inSampleSize;
            originalBitmap = BitmapFactory.decodeStream(is, null, options);
            is.close();
            if (originalBitmap == null) {
                throw new FileCopyException(R.string.error_not_an_image_file);
            }
            int size;
            if (mXmppConnectionService.getCompressImageResolutionPreference() == 0) {
                int height = originalBitmap.getHeight();
                int width = originalBitmap.getWidth();
                size = height > width ? height : width;
            } else {
                size = mXmppConnectionService.getCompressImageResolutionPreference();
            }
            Bitmap scaledBitmap = resize(originalBitmap, size);
            int rotation = getRotation(image);
            scaledBitmap = rotate(scaledBitmap, rotation);
            boolean targetSizeReached = false;
            int quality = Config.IMAGE_QUALITY;
            while (!targetSizeReached) {
                os = new FileOutputStream(file);
                boolean success = scaledBitmap.compress(Config.IMAGE_FORMAT, quality, os);
                if (!success) {
                    throw new FileCopyException(R.string.error_compressing_image);
                }
                os.flush();
                targetSizeReached = (file.length() <= mXmppConnectionService.getCompressImageSizePreference() && mXmppConnectionService.getCompressImageSizePreference() != 0) || quality <= 50;
                quality -= 5;
            }
            scaledBitmap.recycle();
        } catch (FileNotFoundException e) {
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (IOException e) {
            e.printStackTrace();
            throw new FileCopyException(R.string.error_io_exception);
        } catch (SecurityException e) {
            throw new FileCopyException(R.string.error_security_exception_during_image_copy);
        } catch (OutOfMemoryError e) {
            ++sampleSize;
            if (sampleSize <= 3) {
                copyImageToPrivateStorage(file, image, sampleSize);
            } else {
                throw new FileCopyException(R.string.error_out_of_memory);
            }
        } finally {
            close(os);
            close(is);
        }
    }

    public void copyImageToPrivateStorage(File file, Uri image) throws FileCopyException {
        Log.d(Config.LOGTAG, "copy image (" + image.toString() + ") to private storage " + file.getAbsolutePath());
        copyImageToPrivateStorage(file, image, 0);
    }

    public void copyImageToPrivateStorage(Message message, Uri image) throws FileCopyException {
        String filename = "Sent/" + fileDateFormat.format(new Date(message.getTimeSent())) + "_" + message.getUuid().substring(0, 4);
        switch (Config.IMAGE_FORMAT) {
            case JPEG:
                message.setRelativeFilePath(filename + ".jpg");
                break;
            case PNG:
                message.setRelativeFilePath(filename + ".png");
                break;
            case WEBP:
                message.setRelativeFilePath(filename + ".webp");
                break;
        }
        copyImageToPrivateStorage(getFile(message), image);
        updateFileParams(message);
    }

    public boolean unusualBounds(Uri image) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(mXmppConnectionService.getContentResolver().openInputStream(image), null, options);
            float ratio = (float) options.outHeight / options.outWidth;
            return ratio > (21.0f / 9.0f) || ratio < (9.0f / 21.0f);
        } catch (Exception e) {
            return false;
        }
    }

    private int getRotation(File file) {
        return getRotation(Uri.parse("file://" + file.getAbsolutePath()));
    }

    private int getRotation(Uri image) {
        InputStream is = null;
        try {
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            return ExifHelper.getOrientation(is);
        } catch (FileNotFoundException e) {
            return 0;
        } finally {
            close(is);
        }
    }

    public Bitmap getThumbnail(Message message, int size, boolean cacheOnly) throws IOException {
        // The key for getting a cached thumbnail contains the UUID and the size
        // since this method is used for thumbnails of (bigger) normal image messages and (smaller) image message references.
        // If only the UUID were used, the first loaded thumbnail would be cached and the next loading
        // would get that thumbnail which would have the size of the first cached thumbnail
        // possibly leading to undesirable appearance of the displayed thumbnail.
        final String key = message.getUuid() + String.valueOf(size);
        final String uuid = message.getUuid();
        final LruCache<String, Bitmap> cache = mXmppConnectionService.getBitmapCache();
        Bitmap thumbnail = cache.get(key);
        if ((thumbnail == null) && (!cacheOnly)) {
            synchronized (THUMBNAIL_LOCK) {
                thumbnail = cache.get(key);
                if (thumbnail != null) {
                    return thumbnail;
                }
                DownloadableFile file = getFile(message);
                final String mime = file.getMimeType();
                if (mime.startsWith("video/")) {
                    thumbnail = getVideoPreview(file, size);
                } else {
                    Bitmap fullsize = getFullsizeImagePreview(file, size);
                    if (fullsize == null) {
                        throw new FileNotFoundException();
                    }
                    thumbnail = resize(fullsize, size);
                    thumbnail = rotate(thumbnail, getRotation(file));
                    if (mime.equals("image/gif")) {
                        Bitmap withGifOverlay = thumbnail.copy(Bitmap.Config.ARGB_8888, true);
                        drawOverlay(withGifOverlay, R.drawable.play_gif, 1.0f);
                        thumbnail.recycle();
                        thumbnail = withGifOverlay;
                    }
                }
                cache.put(key, thumbnail);
            }
        }
        return thumbnail;
    }

    private Bitmap getFullsizeImagePreview(File file, int size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calcSampleSize(file, size);
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            options.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
    }

    public void drawOverlay(final Bitmap bitmap, final int resource, final float factor) {
        drawOverlay(bitmap, resource, factor, false);
    }

    public void drawOverlay(final Bitmap bitmap, final int resource, final float factor, final boolean corner) {
        Bitmap overlay = BitmapFactory.decodeResource(mXmppConnectionService.getResources(), resource);
        Canvas canvas = new Canvas(bitmap);
        float targetSize = Math.min(canvas.getWidth(), canvas.getHeight()) * factor;
        Log.d(Config.LOGTAG, "target size overlay: " + targetSize + " overlay bitmap size was " + overlay.getHeight());
        float left;
        float top;
        if (corner) {
            left = canvas.getWidth() - targetSize;
            top = canvas.getHeight() - targetSize;
        } else {
            left = (canvas.getWidth() - targetSize) / 2.0f;
            top = (canvas.getHeight() - targetSize) / 2.0f;
        }
        RectF dst = new RectF(left, top, left + targetSize - 1, top + targetSize - 1);
        canvas.drawBitmap(overlay, null, dst, createAntiAliasingPaint());
    }

    public void drawOverlayFromDrawable(final Drawable drawable, final int resource, final float factor) {
        Bitmap overlay = BitmapFactory.decodeResource(mXmppConnectionService.getResources(), resource);
        Bitmap original = drawableToBitmap(drawable);
        Canvas canvas = new Canvas(original);
        float targetSize = Math.min(canvas.getWidth(), canvas.getHeight()) * factor;
        Log.d(Config.LOGTAG, "target size overlay: " + targetSize + " overlay bitmap size was " + overlay.getHeight());
        float left = (canvas.getWidth() - targetSize) / 2.0f;
        float top = (canvas.getHeight() - targetSize) / 2.0f;
        RectF dst = new RectF(left, top, left + targetSize - 1, top + targetSize - 1);
        canvas.drawBitmap(overlay, null, dst, createAntiAliasingPaint());
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = null;
        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private static Paint createAntiAliasingPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        return paint;
    }

    private Bitmap cropCenterSquareVideo(Uri uri, int size) {
        MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        Bitmap frame;
        try {
            metadataRetriever.setDataSource(mXmppConnectionService, uri);
            frame = metadataRetriever.getFrameAtTime(0);
            metadataRetriever.release();
            return cropCenterSquare(frame, size);
        } catch (Exception e) {
            frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff000000);
            return frame;
        }
    }

    private Bitmap getVideoPreview(File file, int size) throws IOException {
        MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        Bitmap frame;
        try {
            metadataRetriever.setDataSource(file.getAbsolutePath());
            frame = metadataRetriever.getFrameAtTime(0);
            metadataRetriever.release();
            frame = resize(frame, size);
        } catch (IOException e) {
            frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff000000);
        } catch (RuntimeException e) {
            frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff000000);
        }
        drawOverlay(frame, R.drawable.play_video, 0.75f);
        return frame;
    }

    private static String getTakeFromCameraPath() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/";
    }

    public Uri getTakePhotoUri() {
        File file = new File(getTakeFromCameraPath() + "IMG_" + fileDateFormat.format(new Date()) + ".jpg");
        file.getParentFile().mkdirs();
        return getUriForFile(mXmppConnectionService, file);
    }

    public static Uri getUriForFile(Context context, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return FileProvider.getUriForFile(context, getAuthority(context), file);
            } catch (IllegalArgumentException e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    throw new SecurityException(e);
                } else {
                    return Uri.fromFile(file);
                }
            }
        } else {
            return Uri.fromFile(file);
        }
    }

    public static String getAuthority(Context context) {
        return context.getPackageName() + FILE_PROVIDER;
    }

    public Uri getTakeVideoUri() {
        File file = new File(getTakeFromCameraPath() + "VID_" + fileDateFormat.format(new Date()) + ".mp4");
        file.getParentFile().mkdirs();
        return getUriForFile(mXmppConnectionService, file);
    }


    private static boolean hasAlpha(final Bitmap bitmap) {
        for (int x = 0; x < bitmap.getWidth(); ++x) {
            for (int y = 0; y < bitmap.getWidth(); ++y) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    public Avatar getPepAvatar(Uri image, int size, Bitmap.CompressFormat format) {
        final Avatar uncompressAvatar = getUncompressedAvatar(image);
        if (uncompressAvatar != null && uncompressAvatar.image.length() <= Config.AVATAR_CHAR_LIMIT) {
            return uncompressAvatar;
        }
        if (uncompressAvatar != null) {
            Log.d(Config.LOGTAG, "uncompressed avatar exceeded char limit by " + (uncompressAvatar.image.length() - Config.AVATAR_CHAR_LIMIT));
        }

        Bitmap bm = cropCenterSquare(image, size);
        if (bm == null) {
            return null;
        }
        if (hasAlpha(bm)) {
            Log.d(Config.LOGTAG, "alpha in avatar detected; uploading as PNG");
            bm.recycle();
            bm = cropCenterSquare(image, 96);
            return getPepAvatar(bm, Bitmap.CompressFormat.PNG, 100);
        }
        return getPepAvatar(bm, format, 100);
    }

    private Avatar getUncompressedAvatar(Uri uri) {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeStream(mXmppConnectionService.getContentResolver().openInputStream(uri));
            return getPepAvatar(bitmap, Bitmap.CompressFormat.PNG, 100);
        } catch (Exception e) {
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private Avatar getPepAvatar(Bitmap bitmap, Bitmap.CompressFormat format, int quality) {
        try {
            ByteArrayOutputStream mByteArrayOutputStream = new ByteArrayOutputStream();
            Base64OutputStream mBase64OutputStream = new Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            DigestOutputStream mDigestOutputStream = new DigestOutputStream(mBase64OutputStream, digest);
            if (!bitmap.compress(format, quality, mDigestOutputStream)) {
                return null;
            }
            mDigestOutputStream.flush();
            mDigestOutputStream.close();
            long chars = mByteArrayOutputStream.size();
            if (format != Bitmap.CompressFormat.PNG && quality >= 50 && chars >= Config.AVATAR_CHAR_LIMIT) {
                int q = quality - 2;
                Log.d(Config.LOGTAG, "avatar char length was " + chars + " reducing quality to " + q);
                return getPepAvatar(bitmap, format, q);
            }
            Log.d(Config.LOGTAG, "settled on char length " + chars + " with quality=" + quality);
            final Avatar avatar = new Avatar();
            avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest());
            avatar.image = new String(mByteArrayOutputStream.toByteArray());
            if (format.equals(Bitmap.CompressFormat.WEBP)) {
                avatar.type = "image/webp";
            } else if (format.equals(Bitmap.CompressFormat.JPEG)) {
                avatar.type = "image/jpeg";
            } else if (format.equals(Bitmap.CompressFormat.PNG)) {
                avatar.type = "image/png";
            }
            avatar.width = bitmap.getWidth();
            avatar.height = bitmap.getHeight();
            return avatar;
        } catch (OutOfMemoryError e) {
            Log.d(Config.LOGTAG, "unable to convert avatar to base64 due to low memory");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Avatar getStoredPepAvatar(String hash) {
        if (hash == null) {
            return null;
        }
        Avatar avatar = new Avatar();
        File file = new File(getAvatarPath(hash));
        FileInputStream is = null;
        try {
            avatar.size = file.length();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            is = new FileInputStream(file);
            ByteArrayOutputStream mByteArrayOutputStream = new ByteArrayOutputStream();
            Base64OutputStream mBase64OutputStream = new Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            DigestOutputStream os = new DigestOutputStream(mBase64OutputStream, digest);
            byte[] buffer = new byte[4096];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            os.flush();
            os.close();
            avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest());
            avatar.image = new String(mByteArrayOutputStream.toByteArray());
            avatar.height = options.outHeight;
            avatar.width = options.outWidth;
            avatar.type = options.outMimeType;
            return avatar;
        } catch (NoSuchAlgorithmException e) {
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            close(is);
        }
    }

    public boolean isAvatarCached(Avatar avatar) {
        File file = new File(getAvatarPath(avatar.getFilename()));
        return file.exists();
    }

    public boolean save(final Avatar avatar) {
        File file;
        if (isAvatarCached(avatar)) {
            file = new File(getAvatarPath(avatar.getFilename()));
            avatar.size = file.length();
        } else {
            file = new File(mXmppConnectionService.getCacheDir().getAbsolutePath() + File.separator + UUID.randomUUID().toString());
            if (file.getParentFile().mkdirs()) {
                Log.d(Config.LOGTAG, "created cache directory");
            }
            OutputStream os = null;
            try {
                if (!file.createNewFile()) {
                    Log.d(Config.LOGTAG, "unable to create temporary file " + file.getAbsolutePath());
                }
                os = new FileOutputStream(file);
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.reset();
                DigestOutputStream mDigestOutputStream = new DigestOutputStream(os, digest);
                final byte[] bytes = avatar.getImageAsBytes();
                mDigestOutputStream.write(bytes);
                mDigestOutputStream.flush();
                mDigestOutputStream.close();
                String sha1sum = CryptoHelper.bytesToHex(digest.digest());
                if (sha1sum.equals(avatar.sha1sum)) {
                    File outputFile = new File(getAvatarPath(avatar.getFilename()));
                    if (outputFile.getParentFile().mkdirs()) {
                        Log.d(Config.LOGTAG, "created avatar directory");
                    }
                    String filename = getAvatarPath(avatar.getFilename());
                    if (!file.renameTo(new File(filename))) {
                        Log.d(Config.LOGTAG, "unable to rename " + file.getAbsolutePath() + " to " + outputFile);
                        return false;
                    }
                } else {
                    Log.d(Config.LOGTAG, "sha1sum mismatch for " + avatar.owner);
                    if (!file.delete()) {
                        Log.d(Config.LOGTAG, "unable to delete temporary file");
                    }
                    return false;
                }
                avatar.size = bytes.length;
            } catch (IllegalArgumentException e) {
                return false;
            } catch (IOException e) {
                return false;
            } catch (NoSuchAlgorithmException e) {
                return false;
            } finally {
                close(os);
            }
        }
        return true;
    }

    private String getAvatarPath(String avatar) {
        return mXmppConnectionService.getFilesDir().getAbsolutePath() + "/avatars/" + avatar;
    }

    public Uri getAvatarUri(String avatar) {
        return Uri.parse("file:" + getAvatarPath(avatar));
    }

    public Bitmap cropCenterSquare(Uri image, int size) {
        if (image == null) {
            return null;
        }
        InputStream is = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calcSampleSize(image, size);
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            if (is == null) {
                return null;
            }
            Bitmap input = BitmapFactory.decodeStream(is, null, options);
            if (input == null) {
                return null;
            } else {
                input = rotate(input, getRotation(image));
                return cropCenterSquare(input, size);
            }
        } catch (FileNotFoundException e) {
            Log.d(Config.LOGTAG, "unable to open file " + image.toString(), e);
            return null;
        } catch (SecurityException e) {
            Log.d(Config.LOGTAG, "unable to open file " + image.toString(), e);
            return null;
        } finally {
            close(is);
        }
    }

    public Bitmap cropCenter(Uri image, int newHeight, int newWidth) {
        if (image == null) {
            return null;
        }
        InputStream is = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calcSampleSize(image, Math.max(newHeight, newWidth));
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            if (is == null) {
                return null;
            }
            Bitmap source = BitmapFactory.decodeStream(is, null, options);
            if (source == null) {
                return null;
            }
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            float xScale = (float) newWidth / sourceWidth;
            float yScale = (float) newHeight / sourceHeight;
            float scale = Math.max(xScale, yScale);
            float scaledWidth = scale * sourceWidth;
            float scaledHeight = scale * sourceHeight;
            float left = (newWidth - scaledWidth) / 2;
            float top = (newHeight - scaledHeight) / 2;

            RectF targetRect = new RectF(left, top, left + scaledWidth, top + scaledHeight);
            Bitmap dest = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(dest);
            canvas.drawBitmap(source, null, targetRect, createAntiAliasingPaint());
            if (source.isRecycled()) {
                source.recycle();
            }
            return dest;
        } catch (SecurityException e) {
            return null; //android 6.0 with revoked permissions for example
        } catch (FileNotFoundException e) {
            return null;
        } finally {
            close(is);
        }
    }

    public Bitmap cropCenterSquare(Bitmap input, int size) {
        int w = input.getWidth();
        int h = input.getHeight();

        float scale = Math.max((float) size / h, (float) size / w);

        float outWidth = scale * w;
        float outHeight = scale * h;
        float left = (size - outWidth) / 2;
        float top = (size - outHeight) / 2;
        RectF target = new RectF(left, top, left + outWidth, top + outHeight);

        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(input, null, target, createAntiAliasingPaint());
        if (!input.isRecycled()) {
            input.recycle();
        }
        return output;
    }

    private int calcSampleSize(Uri image, int size) throws FileNotFoundException, SecurityException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(mXmppConnectionService.getContentResolver().openInputStream(image), null, options);
        return calcSampleSize(options, size);
    }

    private static int calcSampleSize(File image, int size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), options);
        return calcSampleSize(options, size);
    }

    private static int calcSampleSize(BitmapFactory.Options options, int size) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > size || width > size) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) > size
                    && (halfWidth / inSampleSize) > size) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public void updateFileParams(Message message) {
        updateFileParams(message, null);
    }

    public void updateFileParams(Message message, URL url) {
        DownloadableFile file = getFile(message);
        final String mime = file.getMimeType();
        final boolean privateMessage = message.isPrivateMessage();
        final boolean image = message.getType() == Message.TYPE_IMAGE || (mime != null && mime.startsWith("image/"));
        final boolean video = mime != null && mime.startsWith("video/");
        final boolean audio = mime != null && mime.startsWith("audio/");
        final boolean vcard = mime != null && mime.contains("vcard");
        final boolean apk = mime != null && mime.equals("application/vnd.android.package-archive");
        final StringBuilder body = new StringBuilder();
        if (url != null) {
            body.append(url.toString());
        }
        body.append('|').append(file.getSize());
        if (image || video) {
            try {
                Dimensions dimensions = image ? getImageDimensions(file) : getVideoDimensions(file);
                if (dimensions.valid()) {
                    body.append('|').append(dimensions.width).append('|').append(dimensions.height);
                }
            } catch (NotAVideoFile notAVideoFile) {
                Log.d(Config.LOGTAG, "file with mime type " + file.getMimeType() + " was not a video file");
                //fall threw
            }
        } else if (audio) {
            body.append("|0|0|").append(getMediaRuntime(file));
        } else if (vcard) {
            body.append("|0|0|0|").append(getVCard(file));
        } else if (apk) {
            body.append("|0|0|0|").append(getAPK(file, mXmppConnectionService.getApplicationContext()));
        }
        message.setBody(body.toString());
        message.setFileDeleted(false);
        message.setType(privateMessage ? Message.TYPE_PRIVATE_FILE : (image ? Message.TYPE_IMAGE : Message.TYPE_FILE));
    }

    private int getMediaRuntime(File file) {
        try {
            MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(file.toString());
            return Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private String getAPK(File file, Context context) {
        String APKName;
        final PackageManager pm = context.getPackageManager();
        final PackageInfo pi = pm.getPackageArchiveInfo(file.toString(), 0);
        String AppName;
        String AppVersion;
        try {
            pi.applicationInfo.sourceDir = file.toString();
            pi.applicationInfo.publicSourceDir = file.toString();
            AppName = (String) pi.applicationInfo.loadLabel(pm);
            AppVersion = pi.versionName;
            Log.d(Config.LOGTAG, "APK name: " + AppName);
            APKName = " (" + AppName + " " + AppVersion + ")";
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(Config.LOGTAG, "no APK name detected");
            APKName = "";
        }

        try {
            byte[] data = APKName.getBytes("UTF-8");
            APKName = Base64.encodeToString(data, Base64.DEFAULT);
        } catch (UnsupportedEncodingException e) {
            APKName = "";
            e.printStackTrace();
        }
        return APKName;
    }

    private String getVCard(File file) {
        VCard VCard = new VCard();
        String VCardName = "";
        try {
            VCard = Ezvcard.parse(file).first();
            if (VCard != null) {
                final String version = VCard.getVersion().toString();
                Log.d(Config.LOGTAG, "VCard version: " + version);
                final String name = VCard.getFormattedName().getValue();
                VCardName = " (" + name + ")";
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            byte[] data = VCardName.getBytes("UTF-8");
            VCardName = Base64.encodeToString(data, Base64.DEFAULT);

        } catch (UnsupportedEncodingException e) {
            VCardName = "";
            e.printStackTrace();
        }
        return VCardName;
    }

    private Dimensions getImageDimensions(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        int rotation = getRotation(file);
        boolean rotated = rotation == 90 || rotation == 270;
        int imageHeight = rotated ? options.outWidth : options.outHeight;
        int imageWidth = rotated ? options.outHeight : options.outWidth;
        return new Dimensions(imageHeight, imageWidth);
    }

    private Dimensions getVideoDimensions(File file) throws NotAVideoFile {
        MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        try {
            metadataRetriever.setDataSource(file.getAbsolutePath());
        } catch (RuntimeException e) {
            throw new NotAVideoFile(e);
        }
        return getVideoDimensions(metadataRetriever);
    }

    public Bitmap getPreviewForUri(Attachment attachment, int size, boolean cacheOnly) {
        final String key = "attachment_" + attachment.getUuid().toString() + "_" + String.valueOf(size);
        final LruCache<String, Bitmap> cache = mXmppConnectionService.getBitmapCache();
        Bitmap bitmap = cache.get(key);
        if (bitmap != null || cacheOnly) {
            return bitmap;
        }
        if (attachment.getMime() != null && attachment.getMime().startsWith("video/")) {
            bitmap = cropCenterSquareVideo(attachment.getUri(), size);
            drawOverlay(bitmap, R.drawable.play_video, 0.75f);
        } else {
            bitmap = cropCenterSquare(attachment.getUri(), size);
            if (bitmap != null && "image/gif".equals(attachment.getMime())) {
                Bitmap withGifOverlay = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                drawOverlay(withGifOverlay, R.drawable.play_gif, 1.0f);
                bitmap.recycle();
                bitmap = withGifOverlay;
            }
        }
        if (bitmap != null) {
            cache.put(key, bitmap);
        }
        return bitmap;
    }

    private static Dimensions getVideoDimensions(Context context, Uri uri) throws NotAVideoFile {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        try {
            try {
                mediaMetadataRetriever.setDataSource(context, uri);
            } catch (RuntimeException e) {
                throw new NotAVideoFile(e);
            }
        } catch (Exception e) {
            throw new NotAVideoFile();
        }
        return getVideoDimensions(mediaMetadataRetriever);
    }

    private static Dimensions getVideoDimensionsOfFrame(MediaMetadataRetriever mediaMetadataRetriever) {
        Bitmap bitmap = null;
        try {
            bitmap = mediaMetadataRetriever.getFrameAtTime();
            return new Dimensions(bitmap.getHeight(), bitmap.getWidth());
        } catch (Exception e) {
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static Dimensions getVideoDimensions(MediaMetadataRetriever metadataRetriever) throws NotAVideoFile {
        String hasVideo = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
        if (hasVideo == null) {
            throw new NotAVideoFile();
        }
        Dimensions dimensions = getVideoDimensionsOfFrame(metadataRetriever);
        if (dimensions != null) {
            return dimensions;
        }
        final int rotation;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            rotation = extractRotationFromMediaRetriever(metadataRetriever);
        } else {
            rotation = 0;
        }
        boolean rotated = rotation == 90 || rotation == 270;
        int height;
        try {
            String h = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            height = Integer.parseInt(h);
        } catch (Exception e) {
            height = -1;
        }
        int width;
        try {
            String w = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            width = Integer.parseInt(w);
        } catch (Exception e) {
            width = -1;
        }
        metadataRetriever.release();
        Log.d(Config.LOGTAG, "extracted video dims " + width + "x" + height);
        return rotated ? new Dimensions(width, height) : new Dimensions(height, width);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private static int extractRotationFromMediaRetriever(MediaMetadataRetriever metadataRetriever) {
        String r = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        try {
            return Integer.parseInt(r);
        } catch (Exception e) {
            return 0;
        }
    }

    private static class Dimensions {
        public final int width;
        public final int height;

        Dimensions(int height, int width) {
            this.width = width;
            this.height = height;
        }

        public int getMin() {
            return Math.min(width, height);
        }

        public boolean valid() {
            return width > 0 && height > 0;
        }
    }

    private static class NotAVideoFile extends Exception {
        public NotAVideoFile(Throwable t) {
            super(t);
        }

        public NotAVideoFile() {
            super();
        }
    }

    public class FileCopyException extends Exception {
        private static final long serialVersionUID = -1010013599132881427L;
        private int resId;

        public FileCopyException(int resId) {
            this.resId = resId;
        }

        public int getResId() {
            return resId;
        }
    }

    public Bitmap getAvatar(String avatar, int size) {
        if (avatar == null) {
            return null;
        }
        Bitmap bm = cropCenter(getAvatarUri(avatar), size, size);
        if (bm == null) {
            return null;
        }
        return bm;
    }

    public boolean isFileAvailable(Message message) {
        return getFile(message).exists();
    }

    public static void close(final Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "unable to close stream", e);
            }
        }
    }

    public static void close(final Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.d(Config.LOGTAG, "unable to close socket", e);
            }
        }
    }

    public static void close(final ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.d(Config.LOGTAG, "unable to close socket", e);
            }
        }
    }

    public static boolean weOwnFile(Context context, Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return false;
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return fileIsInFilesDir(context, uri);
        } else {
            return weOwnFileLollipop(uri);
        }
    }


    /**
     * This is more than hacky but probably way better than doing nothing
     * Further 'optimizations' might contain to get the parents of CacheDir and NoBackupDir
     * and check against those as well
     */
    private static boolean fileIsInFilesDir(Context context, Uri uri) {
        try {
            final String haystack = context.getFilesDir().getParentFile().getCanonicalPath();
            final String needle = new File(uri.getPath()).getCanonicalPath();
            return needle.startsWith(haystack);
        } catch (IOException e) {
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static boolean weOwnFileLollipop(Uri uri) {
        try {
            File file = new File(uri.getPath());
            FileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).getFileDescriptor();
            StructStat st = Os.fstat(fd);
            return st.st_uid == android.os.Process.myUid();
        } catch (FileNotFoundException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static Bitmap rotateBitmap(File file, Bitmap bitmap, int orientation) {

        if (orientation == 1) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2:
                matrix.setScale(-1, 1);
                break;
            case 3:
                matrix.setRotate(180);
                break;
            case 4:
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                break;
            case 5:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case 6:
                matrix.setRotate(90);
                break;
            case 7:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case 8:
                matrix.setRotate(-90);
                break;
            default:
                return bitmap;
        }

        try {
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return oriented;
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return bitmap;
        }
    }
}
