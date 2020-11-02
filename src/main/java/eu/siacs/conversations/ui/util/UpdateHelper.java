package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.IntroActivity;
import me.drakeet.support.toast.ToastCompat;

import static eu.siacs.conversations.ui.IntroActivity.ACTIVITY;
import static eu.siacs.conversations.ui.IntroActivity.MULTICHAT;

public class UpdateHelper {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
    private static final String UPDATE_DATE = "2020-11-01";

    public static void showPopup(Activity activity) {
        Thread t = new Thread(() -> {
            String blabber_message = "BLABBER.IM_UPDATE_MESSAGE";
            SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
            String Message = "message_shown_" + blabber_message;
            boolean SHOW_MESSAGE = getPrefs.getBoolean(Message, true);

            if (SHOW_MESSAGE && updateInstalled(activity)) {
                activity.runOnUiThread(() -> {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setTitle(activity.getString(R.string.title_activity_updater));
                    builder.setMessage(activity.getString(R.string.updated_to_blabber));
                    builder.setCancelable(false);
                    builder.setPositiveButton(R.string.ok, (dialog, which) -> SaveMessageShown(activity, blabber_message)
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
            updateDate = DATE_FORMAT.parse(UPDATE_DATE);
            if (lastUpdate != null) {
                lastUpdateDate = DATE_FORMAT.parse(lastUpdate);
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return updateDate != null && lastUpdateDate != null && !firstInstalled.equals(lastUpdate) && lastUpdateDate.getTime() >= updateDate.getTime();
    }

    public static void SaveMessageShown(Context context, String message) {
        SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        String Message = "message_shown_" + message;
        SharedPreferences.Editor e = getPrefs.edit();
        e.putBoolean(Message, false);
        e.apply();
    }
}