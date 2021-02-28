package eu.siacs.conversations.ui;

import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.io.File;

import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.UIHelper;

import static eu.siacs.conversations.persistance.FileBackend.AUDIOS;
import static eu.siacs.conversations.persistance.FileBackend.FILES;
import static eu.siacs.conversations.persistance.FileBackend.IMAGES;
import static eu.siacs.conversations.persistance.FileBackend.VIDEOS;

public class MemoryManagementActivity extends XmppActivity {

    private TextView disk_storage;
    private TextView media_usage;
    private ImageButton delete_media;
    private TextView pictures_usage;
    private ImageButton delete_pictures;
    private TextView videos_usage;
    private ImageButton delete_videos;
    private TextView files_usage;
    private ImageButton delete_files;
    private TextView audios_usage;
    private ImageButton delete_audios;

    String totalMemory = "...";
    String mediaUsage = "...";
    String picturesUsage = "...";
    String videosUsage = "...";
    String filesUsage = "...";
    String audiosUsage = "...";

    @Override
    protected void refreshUiReal() {
    }

    @Override
    void onBackendConnected() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(ThemeHelper.find(this));
        setContentView(R.layout.activity_memory_management);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        disk_storage = findViewById(R.id.disk_storage);
        media_usage = findViewById(R.id.media_usage);
        delete_media = findViewById(R.id.action_delete_media);
        pictures_usage = findViewById(R.id.pictures_usage);
        delete_pictures = findViewById(R.id.action_delete_pictures);
        videos_usage = findViewById(R.id.videos_usage);
        delete_videos = findViewById(R.id.action_delete_videos);
        files_usage = findViewById(R.id.files_usage);
        delete_files = findViewById(R.id.action_delete_files);
        audios_usage = findViewById(R.id.audios_usage);
        delete_audios = findViewById(R.id.action_delete_audios);
    }

    @Override
    protected void onStart() {
        super.onStart();
        new getMemoryUsages().execute();
        delete_media.setOnClickListener(view -> {
            deleteMedia(new File(FileBackend.getAppMediaDirectory()));
        });
        delete_pictures.setOnClickListener(view -> {
            deleteMedia(new File(FileBackend.getConversationsDirectory(IMAGES)));
        });
        delete_videos.setOnClickListener(view -> {
            deleteMedia(new File(FileBackend.getConversationsDirectory(VIDEOS)));
        });
        delete_files.setOnClickListener(view -> {
            deleteMedia(new File(FileBackend.getConversationsDirectory(FILES)));
        });
        delete_audios.setOnClickListener(view -> {
            deleteMedia(new File(FileBackend.getConversationsDirectory(AUDIOS)));
        });
    }


    private void deleteMedia(File dir) {
        final String file;
        if (dir.toString().endsWith(IMAGES)) {
            file = getString(R.string.images);
        } else if (dir.toString().endsWith(VIDEOS)) {
            file = getString(R.string.videos);
        } else if (dir.toString().endsWith(FILES)) {
            file = getString(R.string.files);
        } else if (dir.toString().endsWith(AUDIOS)) {
            file = getString(R.string.audios);
        } else {
            file = getString(R.string.all_media_files);
        }
        final androidx.appcompat.app.AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_files_dialog);
        builder.setMessage(getResources().getString(R.string.delete_files_dialog_msg, file));
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            Thread t = new Thread(() -> {
                xmppConnectionService.getFileBackend().deleteFilesInDir(dir);
                runOnUiThread(() -> new getMemoryUsages().execute());
            });
            t.start();
        });
        builder.create().show();
    }

    class getMemoryUsages extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            disk_storage.setText(totalMemory);
            media_usage.setText(mediaUsage);
            pictures_usage.setText(picturesUsage);
            videos_usage.setText(videosUsage);
            files_usage.setText(filesUsage);
            audios_usage.setText(audiosUsage);
        }

        @Override
        protected Void doInBackground(Void... params) {
            totalMemory = UIHelper.filesizeToString(FileBackend.getDiskSize());
            mediaUsage = UIHelper.filesizeToString(FileBackend.getDirectorySize(new File(FileBackend.getAppMediaDirectory())));
            picturesUsage = UIHelper.filesizeToString(FileBackend.getDirectorySize(new File(FileBackend.getConversationsDirectory(IMAGES))));
            videosUsage = UIHelper.filesizeToString(FileBackend.getDirectorySize(new File(FileBackend.getConversationsDirectory(VIDEOS))));
            filesUsage = UIHelper.filesizeToString(FileBackend.getDirectorySize(new File(FileBackend.getConversationsDirectory(FILES))));
            audiosUsage = UIHelper.filesizeToString(FileBackend.getDirectorySize(new File(FileBackend.getConversationsDirectory(AUDIOS))));
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            disk_storage.setText(totalMemory);
            media_usage.setText(mediaUsage);
            pictures_usage.setText(picturesUsage);
            videos_usage.setText(videosUsage);
            files_usage.setText(filesUsage);
            audios_usage.setText(audiosUsage);
        }
    }
}