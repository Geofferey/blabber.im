package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.WelcomeActivity;
import me.drakeet.support.toast.ToastCompat;

public class UpdateHelper {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
    private static final String INSTALL_DATE = "2020-11-01";
    private static final String blabber_message = "BLABBER.IM_UPDATE_MESSAGE";
    private static boolean moveData = true;
    private static boolean dataMoved = false;

    private static final File oldMainDirectory = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pix-Art Messenger/");
    private static final File newMainDirectory = new File(Environment.getExternalStorageDirectory() + "/blabber-im/");
    private static final File oldPicturesDirectory = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pix-Art Messenger/Media/Pix-Art Messenger Images/");
    private static final File oldFilesDirectory = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pix-Art Messenger/Media/Pix-Art Messenger Files/");
    private static final File oldAudiosDirectory = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pix-Art Messenger/Media/Pix-Art Messenger Audios/");
    private static final File oldVideosDirectory = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pix-Art Messenger/Media/Pix-Art Messenger Videos/");

    public static void showPopup(Activity activity) {
        Thread t = new Thread(() -> {
            final SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
            final String Message = "message_shown_" + blabber_message;
            final boolean SHOW_MESSAGE = getPrefs.getBoolean(Message, true);
            if (activity instanceof ConversationsActivity && (SHOW_MESSAGE && updateInstalled(activity) && Config.SHOW_MIGRATION_INFO)) {
                Log.d(Config.LOGTAG, "UpdateHelper: installed update from Pix-Art Messenger to blabber.im");
                activity.runOnUiThread(() -> {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setTitle(activity.getString(R.string.title_activity_updater));
                    builder.setMessage(activity.getString(R.string.updated_to_blabber));
                    builder.setCancelable(false);
                    builder.setPositiveButton(R.string.ok, (dialog, which) -> SaveMessageShown(activity, blabber_message)
                    );
                    builder.create().show();
                });
            } else if (activity instanceof WelcomeActivity && (SHOW_MESSAGE && newInstalled(activity) && !Config.SHOW_MIGRATION_INFO && PAMInstalled(activity))) {
                Log.d(Config.LOGTAG, "UpdateHelper: new installed blabber.im");
                showNewInstalledDialog(activity);
            }
        });
        t.start();
    }

    private static void showNewInstalledDialog(Activity activity) {
        checkOldData();
        activity.runOnUiThread(() -> {
            if (dataMoved) {
                ToastCompat.makeText(activity, R.string.data_successfully_moved, Toast.LENGTH_LONG).show();
            }
            final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(activity.getString(R.string.title_activity_updater));
            builder.setMessage(activity.getString(R.string.updated_to_blabber_google));
            builder.setCancelable(false);
            builder.setPositiveButton(R.string.link, (dialog, which) -> {
                        SaveMessageShown(activity, blabber_message);
                        try {
                            final Uri uri = Uri.parse(Config.migrationURL);
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                            activity.startActivity(browserIntent);
                            showNewInstalledDialog(activity);
                        } catch (Exception e) {
                            ToastCompat.makeText(activity, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG).show();
                            showNewInstalledDialog(activity);
                        }
                    }
            );
            builder.setNegativeButton(R.string.move_data, (dialog, which) -> {
                        SaveMessageShown(activity, blabber_message);
                        try {
                            if (!moveData) {
                                ToastCompat.makeText(activity, R.string.error_moving_data, Toast.LENGTH_LONG).show();
                            } else {
                                moveData();
                            }
                            showNewInstalledDialog(activity);
                        } catch (Exception e) {
                            ToastCompat.makeText(activity, R.string.error_moving_data, Toast.LENGTH_LONG).show();
                            showNewInstalledDialog(activity);
                        }
                    }
            );
            builder.setNeutralButton(R.string.done, (dialog, which) -> SaveMessageShown(activity, blabber_message)
            );
            AlertDialog dialog = builder.create();
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!dataMoved);
        });
    }

    private static void checkOldData() {
        if (oldMainDirectory.exists() && oldMainDirectory.isDirectory()) {
            if (newMainDirectory.exists() && newMainDirectory.isDirectory()) {
                moveData = false;
            } else {
                moveData = true;
            }
        } else {
            moveData = false;
        }
        Log.d(Config.LOGTAG, "UpdateHelper: old data available: " + moveData);
    }

    public static void moveData() {
        if (oldPicturesDirectory.exists() && oldPicturesDirectory.isDirectory()) {
            final File newPicturesDirectory = new File(Environment.getExternalStorageDirectory() + "/Pix-Art Messenger/Media/blabber.im Images/");
            newPicturesDirectory.getParentFile().mkdirs();
            final File[] files = oldPicturesDirectory.listFiles();
            if (files == null) {
                return;
            }
            if (oldPicturesDirectory.renameTo(newPicturesDirectory)) {
                Log.d(Config.LOGTAG, "moved " + oldPicturesDirectory.getAbsolutePath() + " to " + newPicturesDirectory.getAbsolutePath());
            } else {
                Log.d(Config.LOGTAG, "could not move " + oldPicturesDirectory.getAbsolutePath() + " to " + newPicturesDirectory.getAbsolutePath());
            }
        }
        if (oldFilesDirectory.exists() && oldFilesDirectory.isDirectory()) {
            final File newFilesDirectory = new File(Environment.getExternalStorageDirectory() + "/Pix-Art Messenger/Media/blabber.im Files/");
            newFilesDirectory.mkdirs();
            final File[] files = oldFilesDirectory.listFiles();
            if (files == null) {
                return;
            }
            if (oldFilesDirectory.renameTo(newFilesDirectory)) {
                Log.d(Config.LOGTAG, "moved " + oldFilesDirectory.getAbsolutePath() + " to " + newFilesDirectory.getAbsolutePath());
            } else {
                Log.d(Config.LOGTAG, "could not move " + oldFilesDirectory.getAbsolutePath() + " to " + newFilesDirectory.getAbsolutePath());
            }
        }
        if (oldAudiosDirectory.exists() && oldAudiosDirectory.isDirectory()) {
            final File newAudiosDirectory = new File(Environment.getExternalStorageDirectory() + "/Pix-Art Messenger/Media/blabber.im Audios/");
            newAudiosDirectory.mkdirs();
            final File[] files = oldAudiosDirectory.listFiles();
            if (files == null) {
                return;
            }
            if (oldAudiosDirectory.renameTo(newAudiosDirectory)) {
                Log.d(Config.LOGTAG, "moved " + oldAudiosDirectory.getAbsolutePath() + " to " + newAudiosDirectory.getAbsolutePath());
            } else {
                Log.d(Config.LOGTAG, "could not move " + oldAudiosDirectory.getAbsolutePath() + " to " + newAudiosDirectory.getAbsolutePath());
            }
        }
        if (oldVideosDirectory.exists() && oldVideosDirectory.isDirectory()) {
            final File newVideosDirectory = new File(Environment.getExternalStorageDirectory() + "/Pix-Art Messenger/Media/blabber.im Videos/");
            newVideosDirectory.mkdirs();
            final File[] files = oldVideosDirectory.listFiles();
            if (files == null) {
                return;
            }
            if (oldVideosDirectory.renameTo(newVideosDirectory)) {
                Log.d(Config.LOGTAG, "moved " + oldVideosDirectory.getAbsolutePath() + " to " + newVideosDirectory.getAbsolutePath());
            } else {
                Log.d(Config.LOGTAG, "could not move " + oldVideosDirectory.getAbsolutePath() + " to " + newVideosDirectory.getAbsolutePath());
            }
        }
        if (oldMainDirectory.exists() && oldMainDirectory.isDirectory()) {
            newMainDirectory.mkdirs();
            final File[] files = oldMainDirectory.listFiles();
            if (files == null) {
                return;
            }
            if (oldMainDirectory.renameTo(newMainDirectory)) {
                dataMoved = true;
                Log.d(Config.LOGTAG, "moved " + oldMainDirectory.getAbsolutePath() + " to " + newMainDirectory.getAbsolutePath());
            } else {
                Log.d(Config.LOGTAG, "could not move " + oldMainDirectory.getAbsolutePath() + " to " + newMainDirectory.getAbsolutePath());
            }
        }
    }

    private static boolean updateInstalled(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        PackageInfo packageInfo;
        String firstInstalled = null;
        String lastUpdate = null;
        Date updateDate = null;
        Date lastUpdateDate = null;
        try {
            packageInfo = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
            firstInstalled = DATE_FORMAT.format(new Date(packageInfo.firstInstallTime));
            lastUpdate = DATE_FORMAT.format(new Date(packageInfo.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        try {
            updateDate = DATE_FORMAT.parse(INSTALL_DATE);
            if (lastUpdate != null) {
                lastUpdateDate = DATE_FORMAT.parse(lastUpdate);
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return updateDate != null && lastUpdateDate != null && !firstInstalled.equals(lastUpdate) && lastUpdateDate.getTime() >= updateDate.getTime();
    }

    private static boolean newInstalled(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        PackageInfo packageInfo;
        String firstInstalled = null;
        Date installDate = null;
        Date firstInstalledDate = null;
        try {
            packageInfo = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
            firstInstalled = DATE_FORMAT.format(new Date(packageInfo.firstInstallTime));
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        try {
            installDate = DATE_FORMAT.parse(INSTALL_DATE);
            if (firstInstalled != null) {
                firstInstalledDate = DATE_FORMAT.parse(firstInstalled);
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return installDate != null && firstInstalledDate != null && firstInstalledDate.getTime() >= installDate.getTime();
    }

    private static boolean PAMInstalled(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        try {
            pm.getPackageInfo("de.pixart.messenger", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void SaveMessageShown(Context context, String message) {
        SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        String Message = "message_shown_" + message;
        SharedPreferences.Editor e = getPrefs.edit();
        e.putBoolean(Message, false);
        //e.apply();
    }
}