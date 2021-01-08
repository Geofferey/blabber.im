package eu.siacs.conversations.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.URLUtil;

import org.osmdroid.util.GeoPoint;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.SettingsActivity;
import eu.siacs.conversations.ui.ShowLocationActivity;

public class GeoHelper {

    public static Pattern GEO_URI = Pattern.compile("geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,-?\\d+(?:\\.\\d+)?)?(?:;crs=[\\w-]+)?(?:;u=\\d+(?:\\.\\d+)?)?(?:;[\\w-]+=(?:[\\w-_.!~*'()]|%[\\da-f][\\da-f])+)*(\\?z=\\d+)?", Pattern.CASE_INSENSITIVE);

    public static String MapPreviewUri(Message message, Activity activity) {
        Matcher matcher = GEO_URI.matcher(message.getBody());
        if (!matcher.matches()) {
            return null;
        }
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(matcher.group(1));
            if (latitude > 90.0 || latitude < -90.0) {
                return null;
            }
            longitude = Double.parseDouble(matcher.group(2));
            if (longitude > 180.0 || longitude < -180.0) {
                return null;
            }
        } catch (NumberFormatException nfe) {
            return null;
        }
        return getMappreviewHost(activity) + "?center=" + latitude + "," + longitude + "&size=500x500&markers=" + latitude + "," + longitude + "&zoom=" + Config.DEFAULT_ZOOM;
    }

    private static String getMappreviewHost(Activity activity) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
        String mapprevieHost = sharedPreferences.getString(SettingsActivity.MAPPREVIEW_HOST, activity.getResources().getString(R.string.mappreview_url));
        if (mapprevieHost.length() == 0) {
            return activity.getResources().getString(R.string.mappreview_url);
        } else if ((mapprevieHost.length() > 0) && isValid(mapprevieHost)) {
            return mapprevieHost;
        } else {
            return activity.getResources().getString(R.string.mappreview_url);
        }
    }

    private static boolean isValid(String url) {
        String urlstring = url;
        if (!urlstring.toLowerCase(Locale.US).startsWith("http://") && !urlstring.toLowerCase(Locale.US).startsWith("https://")) {
            urlstring = "https://" + url;
        }
        try {
            return URLUtil.isValidUrl(urlstring) && Patterns.WEB_URL.matcher(urlstring).matches();
        } catch (Exception e) {
            Log.d(Config.LOGTAG, "Could not use custom mappreview host and using blabber.im for mappreview " + e);
        }
        return false;
    }

    private static GeoPoint parseGeoPoint(String body) throws IllegalArgumentException {
        Matcher matcher = GEO_URI.matcher(body);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid geo uri");
        }
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(matcher.group(1));
            if (latitude > 90.0 || latitude < -90.0) {
                throw new IllegalArgumentException("Invalid geo uri");
            }
            longitude = Double.parseDouble(matcher.group(2));
            if (longitude > 180.0 || longitude < -180.0) {
                throw new IllegalArgumentException("Invalid geo uri");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid geo uri", e);
        }
        return new GeoPoint(latitude, longitude);
    }

    public static ArrayList<Intent> createGeoIntentsFromMessage(Context context, Message message) {
        final ArrayList<Intent> intents = new ArrayList<>();
        final GeoPoint geoPoint;
        try {
            geoPoint = parseGeoPoint(message.getBody());
        } catch (IllegalArgumentException e) {
            return intents;
        }
        final Conversational conversation = message.getConversation();
        final String label = getLabel(context, message);

        Intent locationPluginIntent = new Intent(context, ShowLocationActivity.class);
        locationPluginIntent.putExtra("latitude", geoPoint.getLatitude());
        locationPluginIntent.putExtra("longitude", geoPoint.getLongitude());
        if (message.getStatus() != Message.STATUS_RECEIVED) {
            locationPluginIntent.putExtra("jid", conversation.getAccount().getJid().toString());
            locationPluginIntent.putExtra("name", context.getString(R.string.me));
        } else {
            Contact contact = message.getContact();
            if (contact != null) {
                locationPluginIntent.putExtra("name", contact.getDisplayName());
                locationPluginIntent.putExtra("jid", contact.getJid().toString());
            } else {
                locationPluginIntent.putExtra("name", UIHelper.getDisplayedMucCounterpart(message.getCounterpart()));
            }
        }
        intents.add(locationPluginIntent);

        Intent geoIntent = new Intent(Intent.ACTION_VIEW);
        geoIntent.setData(Uri.parse("geo:" + String.valueOf(geoPoint.getLatitude()) + "," + String.valueOf(geoPoint.getLongitude()) + "?q=" + String.valueOf(geoPoint.getLatitude()) + "," + String.valueOf(geoPoint.getLongitude()) + label));
        intents.add(geoIntent);
        return intents;
    }

    public static void view(Context context, Message message) {
        final GeoPoint geoPoint = parseGeoPoint(message.getBody());
        final String label = getLabel(context, message);
        context.startActivity(geoIntent(geoPoint, label));
    }

    private static Intent geoIntent(GeoPoint geoPoint, String label) {
        Intent geoIntent = new Intent(Intent.ACTION_VIEW);
        geoIntent.setData(Uri.parse("geo:" + String.valueOf(geoPoint.getLatitude()) + "," + String.valueOf(geoPoint.getLongitude()) + "?q=" + String.valueOf(geoPoint.getLatitude()) + "," + String.valueOf(geoPoint.getLongitude()) + "(" + label + ")"));
        return geoIntent;
    }

    public static boolean openInOsmAnd(Context context, Message message) {
        try {
            final GeoPoint geoPoint = parseGeoPoint(message.getBody());
            final String label = getLabel(context, message);
            return geoIntent(geoPoint, label).resolveActivity(context.getPackageManager()) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String getLabel(Context context, Message message) {
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            try {
                return URLEncoder.encode(UIHelper.getMessageDisplayName(message), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        } else {
            return context.getString(R.string.me);
        }
    }
}