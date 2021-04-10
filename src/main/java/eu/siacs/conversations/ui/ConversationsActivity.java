/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.DataBindingUtil;

import org.openintents.openpgp.util.OpenPgpApi;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.databinding.ActivityConversationsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationRead;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.interfaces.OnConversationsListItemUpdated;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator;
import eu.siacs.conversations.ui.util.IntroHelper;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.util.UpdateHelper;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.EmojiWrapper;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.MenuDoubleTabUtil;
import eu.siacs.conversations.utils.Namespace;
import eu.siacs.conversations.utils.SignupUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import me.drakeet.support.toast.ToastCompat;

import static eu.siacs.conversations.ui.ConversationFragment.REQUEST_DECRYPT_PGP;
import static eu.siacs.conversations.ui.SettingsActivity.HIDE_MEMORY_WARNING;
import static eu.siacs.conversations.ui.SettingsActivity.MIN_ANDROID_SDK21_SHOWN;

public class ConversationsActivity extends XmppActivity implements OnConversationSelected, OnConversationArchived, OnConversationsListItemUpdated, OnConversationRead, XmppConnectionService.OnAccountUpdate, XmppConnectionService.OnConversationUpdate, XmppConnectionService.OnRosterUpdate, OnUpdateBlocklist, XmppConnectionService.OnShowErrorToast, XmppConnectionService.OnAffiliationChanged, XmppConnectionService.OnRoomDestroy {

    public static final String ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.VIEW";
    public static final String EXTRA_CONVERSATION = "conversationUuid";
    public static final String EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid";
    public static final String EXTRA_AS_QUOTE = "eu.siacs.conversations.as_quote";
    public static final String EXTRA_NICK = "nick";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";
    public static final String EXTRA_DO_NOT_APPEND = "do_not_append";
    public static final String EXTRA_POST_INIT_ACTION = "post_init_action";
    public static final String POST_ACTION_RECORD_VOICE = "record_voice";
    public static final String ACTION_DESTROY_MUC = "eu.siacs.conversations.DESTROY_MUC";
    public static final int REQUEST_OPEN_MESSAGE = 0x9876;
    public static final int REQUEST_PLAY_PAUSE = 0x5432;
    private static List<String> VIEW_AND_SHARE_ACTIONS = Arrays.asList(
            ACTION_VIEW_CONVERSATION,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE
    );

    private boolean showLastSeen;

    AlertDialog memoryWarningDialog;

    long FirstStartTime = -1;
    String PREF_FIRST_START = "FirstStart";

    //secondary fragment (when holding the conversation, must be initialized before refreshing the overview fragment
    private static final @IdRes
    int[] FRAGMENT_ID_NOTIFICATION_ORDER = {R.id.secondary_fragment, R.id.main_fragment};
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    private ActivityConversationsBinding binding;
    private boolean mActivityPaused = true;
    private AtomicBoolean mRedirectInProcess = new AtomicBoolean(false);

    private static boolean isViewOrShareIntent(Intent i) {
        Log.d(Config.LOGTAG, "action: " + (i == null ? null : i.getAction()));
        return i != null && VIEW_AND_SHARE_ACTIONS.contains(i.getAction()) && i.hasExtra(EXTRA_CONVERSATION);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, ConversationsActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    @Override
    protected void refreshUiReal() {
        invalidateActionBarTitle();
        invalidateOptionsMenu();
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id);
        }
    }

    @Override
    void onBackendConnected() {
        if (performRedirectIfNecessary(true)) {
            return;
        }
        Log.d(Config.LOGTAG, "ConversationsActivity onBackendConnected(): setIsInForeground = true");
        xmppConnectionService.getNotificationService().setIsInForeground(true);

        final Intent FirstStartIntent = getIntent();
        final Bundle extras = FirstStartIntent.getExtras();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (extras != null && extras.containsKey(PREF_FIRST_START)) {
                FirstStartTime = extras.getLong(PREF_FIRST_START);
                Log.d(Config.LOGTAG, "Get first start time from StartUI: " + FirstStartTime);
            }
        } else {
            FirstStartTime = System.currentTimeMillis();
            Log.d(Config.LOGTAG, "Device is running Android < SDK 23, no restart required: " + FirstStartTime);
        }

        Intent intent = pendingViewIntent.pop();
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment);
                }
                return;
            }
        }

        if (FirstStartTime == 0) {
            Log.d(Config.LOGTAG, "First start time: " + FirstStartTime + ", restarting App");
            //write first start timestamp to file
            FirstStartTime = System.currentTimeMillis();
            SharedPreferences FirstStart = getApplicationContext().getSharedPreferences(PREF_FIRST_START, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = FirstStart.edit();
            editor.putLong(PREF_FIRST_START, FirstStartTime);
            editor.commit();
            // restart
            Intent restartintent = getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName());
            restartintent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            restartintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(restartintent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
            System.exit(0);
        }

        if (useInternalUpdater()) {
            if (xmppConnectionService.getAccounts().size() != 0) {
                if (xmppConnectionService.hasInternetConnection()) {
                    if (xmppConnectionService.isWIFI() || (xmppConnectionService.isMobile() && !xmppConnectionService.isMobileRoaming())) {
                        AppUpdate(xmppConnectionService.installedFrom());
                    }
                }
            }
        }

        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id);
        }

        ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }

        if (binding.secondaryFragment != null && ConversationFragment.getConversation(this) == null) {
            Conversation conversation = ConversationsOverviewFragment.getSuggestion(this);
            if (conversation != null) {
                openConversation(conversation, null);
            }
        }
        invalidateActionBarTitle();
        showDialogsIfMainIsOverview();
    }

    private boolean performRedirectIfNecessary(boolean noAnimation) {
        return performRedirectIfNecessary(null, noAnimation);
    }

    private boolean performRedirectIfNecessary(final Conversation ignore, final boolean noAnimation) {
        if (xmppConnectionService == null) {
            return false;
        }
        boolean isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore);
        if (isConversationsListEmpty && mRedirectInProcess.compareAndSet(false, true)) {
            final Intent intent = SignupUtils.getRedirectionIntent(this);
            if (noAnimation) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            }
            runOnUiThread(() -> {
                startActivity(intent);
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                if (noAnimation) {
                    overridePendingTransition(0, 0);
                }
            });
        }
        return mRedirectInProcess.get();
    }

    private void showDialogsIfMainIsOverview() {
        if (xmppConnectionService == null) {
            return;
        }
        final Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {

            if (ExceptionHelper.checkForCrash(this)) {
                return;
            }
            openBatteryOptimizationDialogIfNeeded();
            new showMemoryWarning().execute();
            showOutdatedVersionWarning();
        }
    }

    private void showOutdatedVersionWarning() {
        if (Compatibility.runsTwentyOne() || getPreferences().getBoolean(MIN_ANDROID_SDK21_SHOWN, false)) {
            Log.d(Config.LOGTAG, "Device is running Android >= SDK 21");
            return;
        }
        Log.d(Config.LOGTAG, "Device is running Android < SDK 21");
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.oldAndroidVersion);
        builder.setMessage(R.string.oldAndroidVersionMessage);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            getPreferences().edit().putBoolean(MIN_ANDROID_SDK21_SHOWN, true).apply();
        });
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds")
        String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    private void openBatteryOptimizationDialogIfNeeded() {
        if (hasAccountWithoutPush()
                && isOptimizingBattery()
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.battery_optimizations_enabled);
            builder.setMessage(R.string.battery_optimizations_enabled_dialog);
            builder.setPositiveButton(R.string.next, (dialog, which) -> {
                final Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                final Uri uri = Uri.parse("package:" + getPackageName());
                intent.setData(uri);
                try {
                    startActivityForResult(intent, REQUEST_BATTERY_OP);
                } catch (ActivityNotFoundException e) {
                    ToastCompat.makeText(this, R.string.device_does_not_support_battery_op, ToastCompat.LENGTH_SHORT).show();
                }
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                builder.setOnDismissListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
            }
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
    }

    public class showMemoryWarning extends AsyncTask<Void, Void, Void> {

        long totalMemory = 0;
        long mediaUsage = 0;
        double relativeUsage = 0;
        String percentUsage = "0%";
        boolean force = false;
        // normal warning: more or equals 20 % or 10 GiB and automatic file deletion is disabled
        double normalWarningRelative = 0.2f; // 20%
        double normalWarningAbsolute = 10f * 1024 * 1024 * 1024; // 10 GiB
        // force warning: usage is more than 50%
        double forceWarningRelative = 0.5f; // 50%

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... params) {
            totalMemory = FileBackend.getDiskSize();
            mediaUsage = FileBackend.getDirectorySize(new File(FileBackend.getAppMediaDirectory()));
            try {
                relativeUsage = ((double) mediaUsage / (double) totalMemory);
                try {
                    percentUsage = String.format("%.2f", relativeUsage * 100) + " %";
                } catch (Exception e) {
                    e.printStackTrace();
                    percentUsage = String.format(Locale.ENGLISH,"%.2f", relativeUsage * 100) + " %";
                }
                force = relativeUsage > forceWarningRelative;
            }   catch (Exception e) {
                e.printStackTrace();
                relativeUsage = 0;
            }
            return null;
        }

        private boolean showWarning(boolean force) {
            if (force) {
                SharedPreferences preferences = getPreferences();
                preferences.edit().putBoolean(HIDE_MEMORY_WARNING, false).apply();
                return true;
            }
            return !xmppConnectionService.hideMemoryWarning() && (relativeUsage > normalWarningRelative || mediaUsage >= normalWarningAbsolute) & xmppConnectionService.getAutomaticAttachmentDeletionDate() == 0;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            Log.d(Config.LOGTAG, "Memory management: using " + UIHelper.filesizeToString(mediaUsage) + " from " + UIHelper.filesizeToString(totalMemory) + " (" + percentUsage + ")");
            if (showWarning(force)) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(ConversationsActivity.this);
                builder.setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    try {
                        final Intent intent = new Intent(ConversationsActivity.this, SettingsActivity.class);
                        intent.setAction(Intent.ACTION_VIEW);
                        intent.addCategory("android.intent.category.PREFERENCE");
                        intent.putExtra("page", "security");
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        e.printStackTrace();
                    }
                });
                if (!force) {
                    builder.setNeutralButton(R.string.hide_warning, (dialog, which) -> {
                        SharedPreferences preferences = getPreferences();
                        preferences.edit().putBoolean(HIDE_MEMORY_WARNING, true).apply();
                    });
                }
                memoryWarningDialog = builder.create();
                memoryWarningDialog.setTitle(R.string.title_memory_management);
                if (force) {
                    memoryWarningDialog.setMessage(getResources().getString(R.string.memory_warning_force, UIHelper.filesizeToString(mediaUsage), percentUsage));
                } else {
                    memoryWarningDialog.setMessage(getResources().getString(R.string.memory_warning, UIHelper.filesizeToString(mediaUsage), percentUsage));
                }
                memoryWarningDialog.setCanceledOnTouchOutside(false);
                memoryWarningDialog.show();
            }
        }
    }

    private boolean hasAccountWithoutPush() {
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE && !xmppConnectionService.getPushManagementService().available(account)) {
                return true;
            }
        }
        return false;
    }


    private void notifyFragmentOfBackendConnected(@IdRes int id) {
        final Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment instanceof OnBackendConnected) {
            ((OnBackendConnected) fragment).onBackendConnected();
        }
    }

    private void refreshFragment(@IdRes int id) {
        final Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment instanceof XmppFragment) {
            ((XmppFragment) fragment).refresh();
        }
    }

    private boolean processViewIntent(Intent intent) {
        Log.d(Config.LOGTAG, "process view intent");
        final String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
        final Conversation conversation = uuid != null ? xmppConnectionService.findConversationByUuid(uuid) : null;
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid);
            return false;
        }
        openConversation(conversation, intent.getExtras());
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switch (requestCode) {
                    case REQUEST_OPEN_MESSAGE:
                        refreshUiReal();
                        ConversationFragment.openPendingMessage(this);
                        break;
                    case REQUEST_PLAY_PAUSE:
                        ConversationFragment.startStopPending(this);
                        break;
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(ActivityResult activityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                if (conversation == null) {
                    break;
                }
                conversation.getAccount().getPgpDecryptionService().giveUpCurrentDecryption();
                break;
            case REQUEST_BATTERY_OP:
                setNeverAskForBatteryOptimizationsAgain();
                break;
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        Log.d(Config.LOGTAG, "positive activity result");
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        if (conversation == null) {
            Log.d(Config.LOGTAG, "conversation not found");
            return;
        }
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                conversation.getAccount().getPgpDecryptionService().continueDecryption(data);
                break;
            case REQUEST_CHOOSE_PGP_ID:
                long id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0);
                if (id != 0) {
                    conversation.getAccount().setPgpSignId(id);
                    announcePgp(conversation.getAccount(), null, null, onOpenPGPKeyPublished);
                } else {
                    choosePgpSignId(conversation.getAccount());
                }
                break;
            case REQUEST_ANNOUNCE_PGP:
                announcePgp(conversation.getAccount(), conversation, data, onOpenPGPKeyPublished);
                break;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConversationMenuConfigurator.reloadFeatures(this);
        OmemoSetting.load(this);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);
        setSupportActionBar((Toolbar) binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.getFragmentManager().addOnBackStackChangedListener(this::invalidateActionBarTitle);
        this.getFragmentManager().addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
        this.initializeFragments();
        this.invalidateActionBarTitle();
        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            intent = savedInstanceState.getParcelable("intent");
        }
        if (isViewOrShareIntent(intent)) {
            pendingViewIntent.push(intent);
            setIntent(createLauncherIntent(this));
        }
        UpdateHelper.showPopup(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_conversations, menu);
        final MenuItem qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code);
        final MenuItem menuEditProfiles = menu.findItem(R.id.action_accounts);
        final MenuItem inviteUser = menu.findItem(R.id.action_invite_user);
        if (qrCodeScanMenuItem != null) {
            if (isCameraFeatureAvailable()) {
                Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
                boolean visible = getResources().getBoolean(R.bool.show_qr_code_scan)
                        && fragment instanceof ConversationsOverviewFragment;
                qrCodeScanMenuItem.setVisible(visible);
            } else {
                qrCodeScanMenuItem.setVisible(false);
            }
        }
        if (xmppConnectionServiceBound && xmppConnectionService.getAccounts().size() == 1 && !xmppConnectionService.multipleAccounts()) {
            menuEditProfiles.setTitle(R.string.action_account);
        } else {
            menuEditProfiles.setTitle(R.string.action_accounts);
        }
        if (xmppConnectionServiceBound && xmppConnectionService.getAccounts().size() > 0) {
            inviteUser.setVisible(true);
        } else {
            inviteUser.setVisible(false);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onConversationSelected(Conversation conversation) {
        clearPendingViewIntent();
        if (ConversationFragment.getConversation(this) == conversation) {
            Log.d(Config.LOGTAG, "ignore onConversationSelected() because conversation is already open");
            return;
        }
        openConversation(conversation, null);
    }

    public void clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent");
        }
    }

    private void displayToast(final String msg) {
        runOnUiThread(() -> ToastCompat.makeText(ConversationsActivity.this, msg, ToastCompat.LENGTH_SHORT).show());
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {
    }

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toEscapedString()));
    }

    private void openConversation(Conversation conversation, Bundle extras) {
        xmppConnectionService.updateNotificationChannels();
        ConversationFragment conversationFragment = (ConversationFragment) getFragmentManager().findFragmentById(R.id.secondary_fragment);
        final boolean mainNeedsRefresh;
        if (conversationFragment == null) {
            mainNeedsRefresh = false;
            Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
            if (mainFragment instanceof ConversationFragment) {
                conversationFragment = (ConversationFragment) mainFragment;
            } else {
                conversationFragment = new ConversationFragment();
                FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                fragmentTransaction.setCustomAnimations(
                        R.animator.fade_right_in, R.animator.fade_right_out,
                        R.animator.fade_right_in, R.animator.fade_right_out
                );
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment);
                fragmentTransaction.addToBackStack(null);
                try {
                    fragmentTransaction.commit();
                } catch (IllegalStateException e) {
                    Log.w(Config.LOGTAG, "sate loss while opening conversation", e);
                    //allowing state loss is probably fine since view intents et all are already stored and a click can probably be 'ignored'
                    return;
                }
            }
        } else {
            mainNeedsRefresh = true;
        }
        conversationFragment.reInit(conversation, extras == null ? new Bundle() : extras);
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment);
        } else {
            invalidateActionBarTitle();
        }
        IntroHelper.showIntro(this, conversation.getMode() == Conversational.MODE_MULTI);
    }

    public boolean onXmppUriClicked(Uri uri) {
        XmppUri xmppUri = new XmppUri(uri);
        if (xmppUri.isValidJid() && !xmppUri.hasFingerprints()) {
            final Conversation conversation = xmppConnectionService.findUniqueConversationByJid(xmppUri);
            if (conversation != null) {
                openConversation(conversation, null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                FragmentManager fm = getFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    try {
                        fm.popBackStack();
                    } catch (IllegalStateException e) {
                        Log.w(Config.LOGTAG, "Unable to pop back stack after pressing home button");
                    }
                    return true;
                }
                break;
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this);
                return true;
            case R.id.action_search_all_conversations:
                startActivity(new Intent(this, SearchActivity.class));
                return true;
            case R.id.action_search_this_conversation:
                final Conversation conversation = ConversationFragment.getConversation(this);
                if (conversation == null) {
                    return true;
                }
                final Intent intent = new Intent(this, SearchActivity.class);
                intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, conversation.getUuid());
                startActivity(intent);
                return true;
            case R.id.action_check_updates:
                if (xmppConnectionService.hasInternetConnection()) {
                    openInstallFromUnknownSourcesDialogIfNeeded(true);
                } else {
                    ToastCompat.makeText(this, R.string.account_status_no_internet, ToastCompat.LENGTH_LONG).show();
                }
                break;
            case R.id.action_invite_user:
                inviteUser();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && keyEvent.isCtrlPressed()) {
            final ConversationFragment conversationFragment = ConversationFragment.get(this);
            if (conversationFragment != null && conversationFragment.onArrowUpCtrlPressed()) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        final Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable("intent", pendingIntent != null ? pendingIntent : getIntent());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onStart() {
        final int theme = findTheme();
        if (this.mTheme != theme) {
            this.mSkipBackgroundBinding = true;
            recreate();
        } else {
            this.mSkipBackgroundBinding = false;
        }
        mRedirectInProcess.set(false);
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.showLastSeen = preferences.getBoolean("last_activity", getResources().getBoolean(R.bool.last_activity));
        super.onStart();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent();
                processViewIntent(intent);
            } else {
                pendingViewIntent.push(intent);
            }
        } else if (intent != null && ACTION_DESTROY_MUC.equals(intent.getAction())) {
            final Bundle extras = intent.getExtras();
            if (extras != null && extras.containsKey("MUC_UUID")) {
                Log.d(Config.LOGTAG, "Get " + intent.getAction() + " intent for " + extras.getString("MUC_UUID"));
                Conversation conversation = xmppConnectionService.findConversationByUuid(extras.getString("MUC_UUID"));
                try {
                    ConversationsActivity.this.xmppConnectionService.clearConversationHistory(conversation);
                    xmppConnectionService.destroyRoom(conversation, ConversationsActivity.this);
                    endConversation(conversation);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        setIntent(createLauncherIntent(this));
    }

    public void endConversation(Conversation conversation) {
        xmppConnectionService.archiveConversation(conversation);
        onConversationArchived(conversation);
    }

    @Override
    public void onPause() {
        this.mActivityPaused = true;
        super.onPause();
        hideMemoryWarningDialog();
    }

    private void hideMemoryWarningDialog() {
        if (memoryWarningDialog != null && memoryWarningDialog.isShowing()) {
            memoryWarningDialog.cancel();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        invalidateActionBarTitle();
        this.mActivityPaused = false;
    }

    private void initializeFragments() {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        Fragment secondaryFragment = getFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (mainFragment != null) {
            Log.d(Config.LOGTAG, "initializeFragment(). main fragment exists");
            if (binding.secondaryFragment != null) {
                if (mainFragment instanceof ConversationFragment) {
                    Log.d(Config.LOGTAG, "gained secondary fragment. moving...");
                    getFragmentManager().popBackStack();
                    transaction.remove(mainFragment);
                    transaction.commit();
                    getFragmentManager().executePendingTransactions();
                    transaction = getFragmentManager().beginTransaction();
                    transaction.replace(R.id.secondary_fragment, mainFragment);
                    transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
                    transaction.commit();
                    return;
                }
            } else {
                if (secondaryFragment instanceof ConversationFragment) {
                    Log.d(Config.LOGTAG, "lost secondary fragment. moving...");
                    transaction.remove(secondaryFragment);
                    transaction.commit();
                    getFragmentManager().executePendingTransactions();
                    transaction = getFragmentManager().beginTransaction();
                    transaction.replace(R.id.main_fragment, secondaryFragment);
                    transaction.addToBackStack(null);
                    transaction.commit();
                    return;
                }
            }
        } else {
            transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
        }

        if (binding.secondaryFragment != null && secondaryFragment == null) {
            transaction.replace(R.id.secondary_fragment, new ConversationFragment());
        }
        transaction.commit();
    }

    private void invalidateActionBarTitle() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
            if (mainFragment instanceof ConversationFragment) {
                final Conversation conversation = ((ConversationFragment) mainFragment).getConversation();
                if (conversation != null) {
                    actionBar.setDisplayHomeAsUpEnabled(true);
                    final View view = getLayoutInflater().inflate(R.layout.ab_title, null);
                    getSupportActionBar().setCustomView(view);
                    actionBar.setIcon(null);
                    actionBar.setBackgroundDrawable(new ColorDrawable(StyledAttributes.getColor(this, R.attr.colorPrimary)));
                    actionBar.setDisplayShowTitleEnabled(false);
                    actionBar.setDisplayShowCustomEnabled(true);
                    TextView abtitle = findViewById(android.R.id.text1);
                    TextView absubtitle = findViewById(android.R.id.text2);
                    abtitle.setText(EmojiWrapper.transform(conversation.getName()));
                    abtitle.setOnClickListener(view1 -> {
                        if (conversation.getMode() == Conversation.MODE_SINGLE) {
                            switchToContactDetails(conversation.getContact());
                        } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                            Intent intent = new Intent(ConversationsActivity.this, ConferenceDetailsActivity.class);
                            intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                            intent.putExtra("uuid", conversation.getUuid());
                            startActivity(intent);
                            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        }
                    });
                    abtitle.setSelected(true);
                    if (conversation.getMode() == Conversation.MODE_SINGLE && !conversation.withSelf()) {
                        ChatState state = conversation.getIncomingChatState();
                            if (state == ChatState.COMPOSING) {
                                absubtitle.setText(getString(R.string.is_typing));
                                absubtitle.setVisibility(View.VISIBLE);
                                absubtitle.setTypeface(null, Typeface.BOLD_ITALIC);
                                absubtitle.setSelected(true);
                                absubtitle.setOnClickListener(view13 -> {
                                    if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                        switchToContactDetails(conversation.getContact());
                                    } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                        Intent intent = new Intent(ConversationsActivity.this, ConferenceDetailsActivity.class);
                                        intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                        intent.putExtra("uuid", conversation.getUuid());
                                        startActivity(intent);
                                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                                    }
                                });
                            } else {
                                if (showLastSeen && conversation.getContact().getLastseen() > 0 && conversation.getContact().getPresences().allOrNonSupport(Namespace.IDLE)) {
                                    absubtitle.setText(UIHelper.lastseen(getApplicationContext(), conversation.getContact().isActive(), conversation.getContact().getLastseen()));
                                    absubtitle.setVisibility(View.VISIBLE);
                                } else {
                                    absubtitle.setText(null);
                                    absubtitle.setVisibility(View.GONE);
                                }
                                absubtitle.setSelected(true);
                                absubtitle.setOnClickListener(view14 -> {
                                    if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                        switchToContactDetails(conversation.getContact());
                                    } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                        Intent intent = new Intent(ConversationsActivity.this, ConferenceDetailsActivity.class);
                                        intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                        intent.putExtra("uuid", conversation.getUuid());
                                        startActivity(intent);
                                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                                    }
                                });
                            }
                    } else {
                        ChatState state = ChatState.COMPOSING;
                        List<MucOptions.User> userWithChatStates = conversation.getMucOptions().getUsersWithChatState(state, 5);
                        if (userWithChatStates.size() == 0) {
                            state = ChatState.PAUSED;
                            userWithChatStates = conversation.getMucOptions().getUsersWithChatState(state, 5);
                        }
                        List<MucOptions.User> users = conversation.getMucOptions().getUsers(true);
                        if (state == ChatState.COMPOSING) {
                            if (userWithChatStates.size() > 0) {
                                if (userWithChatStates.size() == 1) {
                                    MucOptions.User user = userWithChatStates.get(0);
                                    absubtitle.setText(EmojiWrapper.transform(getString(R.string.contact_is_typing, UIHelper.getDisplayName(user))));
                                    absubtitle.setVisibility(View.VISIBLE);
                                } else {
                                    StringBuilder builder = new StringBuilder();
                                    for (MucOptions.User user : userWithChatStates) {
                                        if (builder.length() != 0) {
                                            builder.append(", ");
                                        }
                                        builder.append(UIHelper.getDisplayName(user));
                                    }
                                    absubtitle.setText(EmojiWrapper.transform(getString(R.string.contacts_are_typing, builder.toString())));
                                    absubtitle.setVisibility(View.VISIBLE);
                                }
                            }
                        } else {
                            if (users.size() == 0) {
                                absubtitle.setText(getString(R.string.one_participant));
                                absubtitle.setVisibility(View.VISIBLE);
                            } else {
                                int size = users.size() + 1;
                                absubtitle.setText(getString(R.string.more_participants, size));
                                absubtitle.setVisibility(View.VISIBLE);
                            }
                        }
                        absubtitle.setSelected(true);
                        absubtitle.setOnClickListener(view15 -> {
                            if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                switchToContactDetails(conversation.getContact());
                            } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                Intent intent = new Intent(ConversationsActivity.this, ConferenceDetailsActivity.class);
                                intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                intent.putExtra("uuid", conversation.getUuid());
                                startActivity(intent);
                                overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                            }
                        });
                    }
                    return;
                }
            }
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayShowCustomEnabled(false);
            actionBar.setTitle(null);
            actionBar.setIcon(R.drawable.logo_actionbar);
            actionBar.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.header_background)));
            actionBar.setSubtitle(null);
            actionBar.setDisplayHomeAsUpEnabled(false);
        }
    }

    @Override
    public void onConversationArchived(Conversation conversation) {
        if (performRedirectIfNecessary(conversation, false)) {
            return;
        }
        Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment) {
            try {
                getFragmentManager().popBackStack();
            } catch (IllegalStateException e) {
                Log.w(Config.LOGTAG, "state loss while popping back state after archiving conversation", e);
                //this usually means activity is no longer active; meaning on the next open we will run through this again
            }
            return;
        }
        Fragment secondaryFragment = getFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment) {
            if (((ConversationFragment) secondaryFragment).getConversation() == conversation) {
                Conversation suggestion = ConversationsOverviewFragment.getSuggestion(this, conversation);
                if (suggestion != null) {
                    openConversation(suggestion, null);
                }
            }
        }
    }

    @Override
    public void onConversationsListItemUpdated() {
        Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            ((ConversationsOverviewFragment) fragment).refresh();
        }
    }

    @Override
    public void switchToConversation(Conversation conversation) {
        Log.d(Config.LOGTAG, "override");
        openConversation(conversation, null);
    }

    @Override
    public void onConversationRead(Conversation conversation, String upToUuid) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid);
        } else {
            Log.d(Config.LOGTAG, "ignoring read callback. mActivityPaused=" + Boolean.toString(mActivityPaused));
        }
    }

    @Override
    public void onAccountUpdate() {
        this.refreshUi();
    }

    @Override
    public void onConversationUpdate() {
        if (performRedirectIfNecessary(false)) {
            return;
        }
        this.refreshUi();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(OnUpdateBlocklist.Status status) {
        this.refreshUi();
    }

    @Override
    public void onShowErrorToast(int resId) {
        runOnUiThread(() -> ToastCompat.makeText(this, resId, ToastCompat.LENGTH_SHORT).show());
    }

    protected void AppUpdate(String Store) {
        String PREFS_NAME = "UpdateTimeStamp";
        SharedPreferences UpdateTimeStamp = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUpdateTime = UpdateTimeStamp.getLong("lastUpdateTime", 0);
        Log.d(Config.LOGTAG, "AppUpdater: LastUpdateTime: " + lastUpdateTime);
        if ((lastUpdateTime + (Config.UPDATE_CHECK_TIMER * 1000)) < System.currentTimeMillis()) {
            lastUpdateTime = System.currentTimeMillis();
            SharedPreferences.Editor editor = UpdateTimeStamp.edit();
            editor.putLong("lastUpdateTime", lastUpdateTime);
            editor.apply();
            Log.d(Config.LOGTAG, "AppUpdater: CurrentTime: " + lastUpdateTime);
            if (Store == null) {
                Log.d(Config.LOGTAG, "AppUpdater started");
                openInstallFromUnknownSourcesDialogIfNeeded(false);
            }
        } else {
            Log.d(Config.LOGTAG, "AppUpdater stopped");
        }
    }

    @Override
    public void onRoomDestroySucceeded() {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        final boolean groupChat = conversation != null && conversation.isPrivateAndNonAnonymous();
        displayToast(getString(groupChat ? R.string.destroy_room_succeed : R.string.destroy_channel_succeed));
    }

    @Override
    public void onRoomDestroyFailed() {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        final boolean groupChat = conversation != null && conversation.isPrivateAndNonAnonymous();
        displayToast(getString(groupChat ? R.string.destroy_room_failed : R.string.destroy_channel_failed));
    }
}