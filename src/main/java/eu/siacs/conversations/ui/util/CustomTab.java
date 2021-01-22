package eu.siacs.conversations.ui.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Browser;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;

import eu.siacs.conversations.R;

import static eu.siacs.conversations.utils.Compatibility.runsTwentyOne;

public class CustomTab {
    public static void openTab(Context context, Uri uri, boolean dark) throws ActivityNotFoundException {
        CustomTabsIntent.Builder tabBuilder = new CustomTabsIntent.Builder();
        tabBuilder.setShowTitle(true);
        tabBuilder.setUrlBarHidingEnabled(false);
        tabBuilder.setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(StyledAttributes.getColor(context, R.attr.colorPrimary))
                .setSecondaryToolbarColor(StyledAttributes.getColor(context, R.attr.colorPrimaryDark))
                .setNavigationBarColor(Color.BLUE)
                .build());
        tabBuilder.setColorScheme(dark ? CustomTabsIntent.COLOR_SCHEME_DARK : CustomTabsIntent.COLOR_SCHEME_LIGHT);
        tabBuilder.setShareState(CustomTabsIntent.SHARE_STATE_ON);
        CustomTabsIntent customTabsIntent = tabBuilder.build();
        if (runsTwentyOne()) {
            customTabsIntent.intent.setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        }
        customTabsIntent.intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
        customTabsIntent.launchUrl(context, uri);
    }
}
