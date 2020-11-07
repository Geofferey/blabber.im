package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import me.drakeet.support.toast.ToastCompat;

public class UpdateHelper {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
    private static final String INSTALL_DATE = "2020-11-01";

    public static void showPopup(Activity activity) {
        Thread t = new Thread(() -> {
            String blabber_message = "BLABBER.IM_UPDATE_MESSAGE";
            SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
            String Message = "message_shown_" + blabber_message;
            boolean SHOW_MESSAGE = getPrefs.getBoolean(Message, true);

            if (SHOW_MESSAGE && updateInstalled(activity) && Config.SHOW_MIGRATION_INFO) {
                activity.runOnUiThread(() -> {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setTitle(activity.getString(R.string.title_activity_updater));
                    builder.setMessage(activity.getString(R.string.updated_to_blabber));
                    builder.setCancelable(false);
                    builder.setPositiveButton(R.string.ok, (dialog, which) -> SaveMessageShown(activity, blabber_message)
                    );
                    builder.create().show();
                });
            } else if (SHOW_MESSAGE && newInstalled(activity) && !Config.SHOW_MIGRATION_INFO && PAMInstalled(activity)) {
                activity.runOnUiThread(() -> {
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
                                } catch (Exception e) {
                                    ToastCompat.makeText(activity, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT).show();
                                }
                            }
                    );
                    builder.setNegativeButton(R.string.cancel, (dialog, which) -> SaveMessageShown(activity, blabber_message)
                    );
                    builder.create().show();
                });
            }
        });
        t.start();
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
        e.apply();
    }
}