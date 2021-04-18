package eu.siacs.conversations.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.BigPictureStyle;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.EditAccountActivity;
import eu.siacs.conversations.ui.RtpSessionActivity;
import eu.siacs.conversations.ui.TimePreference;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.EmojiWrapper;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.TorServiceUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.Media;

import static eu.siacs.conversations.ui.util.MyLinkify.replaceYoutube;

public class NotificationService {

    public static final Object CATCHUP_LOCK = new Object();
    public static final String MESSAGES_CHANNEL_ID = "messages";
    public static final String SILENT_MESSAGES_CHANNEL_ID = "silent_messages";
    public static final String INCOMING_CALLS_CHANNEL_ID = "incoming_calls";
    public static final String ONGOING_CALLS_CHANNEL_ID = "ongoing_calls";
    public static final String MISSED_CALLS_CHANNEL_ID = "missed_calls";
    public static final String FOREGROUND_CHANNEL_ID = "foreground";
    public static final String QUIET_HOURS_CHANNEL_ID = "quiet_hours";
    public static final String DELIVERY_FAILED_CHANNEL_ID = "delivery_failed";
    public static final String BACKUP_CHANNEL_ID = "backup";
    public static final String UPDATE_CHANNEL_ID = "appupdate";
    public static final String VIDEOCOMPRESSION_CHANNEL_ID = "compression";
    public static final String ERROR_CHANNEL_ID = "error";
    public static final String INDIVIDUAL_NOTIFICATION_PREFIX = "XxXx_";
    public static final String OLD_INDIVIDUAL_NOTIFICATION_PREFIX = "xxXx_"; // x, Xx, xXx,
    private static final String DEFAULT = "default_ID";
    private static final int CALL_DAT = 120;
    private static final long[] CALL_PATTERN = {0, 3 * CALL_DAT, CALL_DAT, CALL_DAT, 3 * CALL_DAT, CALL_DAT, CALL_DAT};

    private static final String MESSAGES_GROUP = "eu.siacs.conversations.messages";
    private static final String MISSED_CALLS_GROUP = "eu.siacs.conversations.missed_calls";
    private static final int NOTIFICATION_ID_MULTIPLIER = 1024 * 1024;
    public static final int NOTIFICATION_ID = 2 * NOTIFICATION_ID_MULTIPLIER;
    public static final int FOREGROUND_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 4;
    public static final int ERROR_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 6;
    private static final int LED_COLOR = 0xff0080FF;
    private static final int INCOMING_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 8;
    public static final int ONGOING_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 10;
    private static final int DELIVERY_FAILED_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 12;
    public static final int MISSED_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 14;
    private final XmppConnectionService mXmppConnectionService;
    private final LinkedHashMap<String, ArrayList<Message>> notifications = new LinkedHashMap<>();
    private final LinkedHashMap<Conversational, MissedCallsInfo> mMissedCalls = new LinkedHashMap<>();
    private final HashMap<Conversation, AtomicInteger> mBacklogMessageCounter = new HashMap<>();
    private Conversation mOpenConversation;
    private boolean mIsInForeground;
    private long mLastNotification;

    NotificationService(final XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    private static boolean displaySnoozeAction(List<Message> messages) {
        int numberOfMessagesWithoutReply = 0;
        for (Message message : messages) {
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                ++numberOfMessagesWithoutReply;
            } else {
                return false;
            }
        }
        return numberOfMessagesWithoutReply >= 3;
    }

    public static Pattern generateNickHighlightPattern(final String nick) {
        return Pattern.compile("(?<=(^|\\s))" + Pattern.quote(nick) + "(?=\\s|$|\\p{Punct})");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    void updateChannels() {
        mXmppConnectionService.mNotificationChannelExecutor.execute(this::initializeChannels);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    void initializeChannels() {
        final Context c = mXmppConnectionService;
        final NotificationManager notificationManager = c.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        notificationManager.createNotificationChannelGroup(new NotificationChannelGroup("status", c.getString(R.string.notification_group_status_information)));
        notificationManager.createNotificationChannelGroup(new NotificationChannelGroup("chats", c.getString(R.string.notification_group_messages)));
        notificationManager.createNotificationChannelGroup(new NotificationChannelGroup("calls", c.getString(R.string.notification_group_calls)));

        NotificationChannel foregroundServiceChannel = notificationManager.getNotificationChannel(FOREGROUND_CHANNEL_ID);
        if (foregroundServiceChannel == null) {
            foregroundServiceChannel = new NotificationChannel(FOREGROUND_CHANNEL_ID,
                    c.getString(R.string.foreground_service_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            foregroundServiceChannel.setDescription(c.getString(R.string.foreground_service_channel_description));
            foregroundServiceChannel.setShowBadge(false);
            foregroundServiceChannel.setGroup("status");
            notificationManager.createNotificationChannel(foregroundServiceChannel);
        }

        NotificationChannel backupChannel = notificationManager.getNotificationChannel(BACKUP_CHANNEL_ID);
        if (backupChannel == null) {
            backupChannel = new NotificationChannel(BACKUP_CHANNEL_ID,
                    c.getString(R.string.backup_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            backupChannel.setShowBadge(false);
            backupChannel.setGroup("status");
            notificationManager.createNotificationChannel(backupChannel);
        }

        NotificationChannel videoCompressionChannel = notificationManager.getNotificationChannel(VIDEOCOMPRESSION_CHANNEL_ID);
        if (videoCompressionChannel == null) {
            videoCompressionChannel = new NotificationChannel(VIDEOCOMPRESSION_CHANNEL_ID,
                    c.getString(R.string.video_compression_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            videoCompressionChannel.setShowBadge(false);
            videoCompressionChannel.setGroup("status");
            notificationManager.createNotificationChannel(videoCompressionChannel);
        }

        NotificationChannel AppUpdateChannel = notificationManager.getNotificationChannel(UPDATE_CHANNEL_ID);
        if (AppUpdateChannel == null) {
            AppUpdateChannel = new NotificationChannel(UPDATE_CHANNEL_ID,
                    c.getString(R.string.app_update_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            AppUpdateChannel.setShowBadge(false);
            AppUpdateChannel.setGroup("status");
            notificationManager.createNotificationChannel(AppUpdateChannel);
        }

        NotificationChannel ongoingCallsChannel = notificationManager.getNotificationChannel(ONGOING_CALLS_CHANNEL_ID);
        if (ongoingCallsChannel == null) {
            ongoingCallsChannel = new NotificationChannel(ONGOING_CALLS_CHANNEL_ID,
                    c.getString(R.string.ongoing_calls_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ongoingCallsChannel.setShowBadge(false);
            ongoingCallsChannel.setGroup("calls");
            notificationManager.createNotificationChannel(ongoingCallsChannel);
        }

        NotificationChannel missedCallsChannel = notificationManager.getNotificationChannel(MISSED_CALLS_CHANNEL_ID);
        if (missedCallsChannel == null) {
            missedCallsChannel = new NotificationChannel(MISSED_CALLS_CHANNEL_ID,
                    c.getString(R.string.missed_calls_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            missedCallsChannel.setShowBadge(true);
            missedCallsChannel.setSound(null, null);
            missedCallsChannel.setLightColor(LED_COLOR);
            missedCallsChannel.enableLights(true);
            missedCallsChannel.setGroup("calls");
            notificationManager.createNotificationChannel(missedCallsChannel);
        }

        NotificationChannel silentMessagesChannel = notificationManager.getNotificationChannel(SILENT_MESSAGES_CHANNEL_ID);
        if (silentMessagesChannel == null) {
            silentMessagesChannel = new NotificationChannel(SILENT_MESSAGES_CHANNEL_ID,
                    c.getString(R.string.silent_messages_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            silentMessagesChannel.setDescription(c.getString(R.string.silent_messages_channel_description));
            silentMessagesChannel.setShowBadge(true);
            silentMessagesChannel.setLightColor(LED_COLOR);
            silentMessagesChannel.enableLights(true);
            silentMessagesChannel.setGroup("chats");
            notificationManager.createNotificationChannel(silentMessagesChannel);
        }

        NotificationChannel quietHoursChannel = notificationManager.getNotificationChannel(QUIET_HOURS_CHANNEL_ID);
        if (quietHoursChannel == null) {
            quietHoursChannel = new NotificationChannel(QUIET_HOURS_CHANNEL_ID,
                    c.getString(R.string.title_pref_quiet_hours),
                    NotificationManager.IMPORTANCE_LOW);
            quietHoursChannel.setShowBadge(true);
            quietHoursChannel.setLightColor(LED_COLOR);
            quietHoursChannel.enableLights(true);
            quietHoursChannel.setGroup("chats");
            quietHoursChannel.enableVibration(false);
            quietHoursChannel.setSound(null, null);
            notificationManager.createNotificationChannel(quietHoursChannel);
        }

        NotificationChannel deliveryFailedChannel = notificationManager.getNotificationChannel(DELIVERY_FAILED_CHANNEL_ID);
        if (deliveryFailedChannel == null) {
            deliveryFailedChannel = new NotificationChannel(DELIVERY_FAILED_CHANNEL_ID,
                    c.getString(R.string.delivery_failed_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            deliveryFailedChannel.setShowBadge(false);
            deliveryFailedChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build());
            deliveryFailedChannel.setGroup("chats");
            notificationManager.createNotificationChannel(deliveryFailedChannel);
        }

        createDefaultMessageNotificationChannel(notificationManager);
        createDefaultCallNotificationChannel(notificationManager);

        createIndividualNotificationChannels(notificationManager);
    }

    // create individual notification channels for selected chats
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createIndividualNotificationChannels(final NotificationManager notificationManager) {
        final int chats = mXmppConnectionService.getConversations().size();
        for (int i = 0; i < chats; i++) {
            if (mXmppConnectionService.hasIndividualNotification(mXmppConnectionService.getConversations().get(i))) {
                if (mXmppConnectionService.getConversations().get(i).getMode() == Conversation.MODE_SINGLE) {
                    createCallNotificationChannels(notificationManager, i);
                }
                createMessageNotificationChannels(notificationManager, i);
            }
        }
    }

    // create individual call notification channel for selected chats
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createCallNotificationChannels(final NotificationManager notificationManager, final int i) {
        final String uuid = mXmppConnectionService.getConversations().get(i).getUuid();
        final String jid = mXmppConnectionService.getConversations().get(i).getAccount().getJid().asBareJid().toString();
        final String name = mXmppConnectionService.getConversations().get(i).getName().toString().toLowerCase();
        final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(mXmppConnectionService.getConversations().get(i)));
        final String channelID = INDIVIDUAL_NOTIFICATION_PREFIX + INCOMING_CALLS_CHANNEL_ID + "_" + uuid + "_" + time;
        try {
            final NotificationChannel incomingCallsChannel = new NotificationChannel(channelID,
                    mXmppConnectionService.getString(R.string.notification_group_calls),
                    NotificationManager.IMPORTANCE_HIGH);
            incomingCallsChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build());
            incomingCallsChannel.setShowBadge(false);
            incomingCallsChannel.setLightColor(LED_COLOR);
            incomingCallsChannel.enableLights(true);
            notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(INDIVIDUAL_NOTIFICATION_PREFIX + name + uuid, name + " (" + jid + ")"));
            incomingCallsChannel.setGroup(INDIVIDUAL_NOTIFICATION_PREFIX + name + uuid);
            incomingCallsChannel.setBypassDnd(true);
            incomingCallsChannel.enableVibration(true);
            incomingCallsChannel.setVibrationPattern(CALL_PATTERN);
            notificationManager.createNotificationChannel(incomingCallsChannel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // create individual message notification channels for selected chat
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createMessageNotificationChannels(final NotificationManager notificationManager, final int i) {
        final String uuid = mXmppConnectionService.getConversations().get(i).getUuid();
        final String jid = mXmppConnectionService.getConversations().get(i).getAccount().getJid().asBareJid().toString();
        final String name = mXmppConnectionService.getConversations().get(i).getName().toString().toLowerCase();
        final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(mXmppConnectionService.getConversations().get(i)));
        final String channelID = INDIVIDUAL_NOTIFICATION_PREFIX + MESSAGES_CHANNEL_ID + "_" + uuid + "_" + time;
        try {
            final NotificationChannel messagesChannel = new NotificationChannel(channelID,
                    mXmppConnectionService.getString(R.string.notification_group_messages),
                    NotificationManager.IMPORTANCE_HIGH);
            messagesChannel.setShowBadge(true);
            messagesChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build());
            messagesChannel.setLightColor(LED_COLOR);
            final int dat = 70;
            final long[] pattern = {0, 3 * dat, dat, dat};
            messagesChannel.setVibrationPattern(pattern);
            messagesChannel.enableVibration(true);
            messagesChannel.enableLights(true);
            notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(INDIVIDUAL_NOTIFICATION_PREFIX + name + uuid, name + " (" + jid + ")"));
            messagesChannel.setGroup(INDIVIDUAL_NOTIFICATION_PREFIX + name + uuid);
            notificationManager.createNotificationChannel(messagesChannel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // default message notification channel
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createDefaultMessageNotificationChannel(final NotificationManager notificationManager) {
        final String channelID = MESSAGES_CHANNEL_ID + "_" + DEFAULT;
        try {
            final NotificationChannel messagesChannel = new NotificationChannel(channelID,
                    mXmppConnectionService.getString(R.string.notification_group_messages),
                    NotificationManager.IMPORTANCE_HIGH);
            messagesChannel.setShowBadge(true);
            messagesChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build());
            messagesChannel.setLightColor(LED_COLOR);
            final int dat = 70;
            final long[] pattern = {0, 3 * dat, dat, dat};
            messagesChannel.setVibrationPattern(pattern);
            messagesChannel.enableVibration(true);
            messagesChannel.enableLights(true);
            messagesChannel.setGroup("chats");
            notificationManager.createNotificationChannel(messagesChannel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // default call notification channel
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createDefaultCallNotificationChannel(final NotificationManager notificationManager) {
        final String channelID = INCOMING_CALLS_CHANNEL_ID + "_" + DEFAULT;
        try {
            final NotificationChannel incomingCallsChannel = new NotificationChannel(channelID,
                    mXmppConnectionService.getString(R.string.notification_group_calls),
                    NotificationManager.IMPORTANCE_HIGH);
            incomingCallsChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build());
            incomingCallsChannel.setShowBadge(false);
            incomingCallsChannel.setLightColor(LED_COLOR);
            incomingCallsChannel.enableLights(true);
            incomingCallsChannel.setGroup("calls");
            incomingCallsChannel.setBypassDnd(true);
            incomingCallsChannel.enableVibration(true);
            incomingCallsChannel.setVibrationPattern(CALL_PATTERN);
            notificationManager.createNotificationChannel(incomingCallsChannel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // clean individual notification channels and groups for selected user
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void cleanNotificationChannels(final Context context, final String uuid) {
        final NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        cleanCallNotificationChannels(notificationManager, uuid);
        cleanMessageNotificationChannels(notificationManager, uuid);
        cleanNotificationGroup(notificationManager, uuid);
    }

    // clean individual notification group for user
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void cleanNotificationGroup(final NotificationManager notificationManager, final String uuid) {
        try {
            final String name = mXmppConnectionService.findConversationByUuid(uuid).getName().toString().toLowerCase();
            final String groupID = INDIVIDUAL_NOTIFICATION_PREFIX + name + uuid;
            final List<NotificationChannelGroup> list = notificationManager.getNotificationChannelGroups();
            final int count = list.size();
            for (int a = 0; a < count; a++) {
                final NotificationChannelGroup group = list.get(a);
                final String id = group.getId();
                if (id.startsWith(groupID) || id.startsWith(uuid)) {
                    notificationManager.deleteNotificationChannelGroup(id);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // clean individual call notification channels for user
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void cleanCallNotificationChannels(final NotificationManager notificationManager, final String uuid) {
        final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(mXmppConnectionService.findConversationByUuid(uuid)));
        final String channelID = INDIVIDUAL_NOTIFICATION_PREFIX + INCOMING_CALLS_CHANNEL_ID + "_" + uuid + "_" + time;
        try {
            final List<NotificationChannel> list = notificationManager.getNotificationChannels();
            final int count = list.size();
            for (int a = 0; a < count; a++) {
                final NotificationChannel channel = list.get(a);
                final String id = channel.getId();
                if (id.startsWith(channelID) || id.startsWith(INCOMING_CALLS_CHANNEL_ID + "_" + uuid)) {
                    notificationManager.deleteNotificationChannel(id);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // clean individual message notification channels and group for user
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void cleanMessageNotificationChannels(final NotificationManager notificationManager, final String uuid) {
        final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(mXmppConnectionService.findConversationByUuid(uuid)));
        final String channelID = INDIVIDUAL_NOTIFICATION_PREFIX + MESSAGES_CHANNEL_ID + "_" + uuid + "_" + time;
        try {
            final List<NotificationChannel> list = notificationManager.getNotificationChannels();
            final int count = list.size();
            for (int a = 0; a < count; a++) {
                final NotificationChannel channel = list.get(a);
                final String id = channel.getId();
                if (id.startsWith(channelID) || id.startsWith(MESSAGES_CHANNEL_ID + "_" + uuid)) {
                    notificationManager.deleteNotificationChannel(id);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // clean all individual notification settings
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void cleanAllNotificationChannels(final Context context) {
        final NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        final int chats = mXmppConnectionService.getConversations().size();
        for (int i = 0; i < chats; i++) {
            if (mXmppConnectionService.hasIndividualNotification(mXmppConnectionService.getConversations().get(i))) {
                final String uuid = mXmppConnectionService.getConversations().get(i).getUuid();
                mXmppConnectionService.setIndividualNotificationPreference(mXmppConnectionService.getConversations().get(i), true);
                cleanCallNotificationChannels(notificationManager, uuid);
                cleanMessageNotificationChannels(notificationManager, uuid);
                cleanNotificationGroup(notificationManager, uuid);
            }
        }
    }

    // clean all old individual notifications
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void cleanAllOldNotificationChannels(final Context context) {
        final NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        try {
            final int channels = notificationManager.getNotificationChannels().size();
            for (int i1 = 0; i1 < channels; i1++) {
                final String channelID = notificationManager.getNotificationChannels().get(i1).getId();
                if (channelID.startsWith(OLD_INDIVIDUAL_NOTIFICATION_PREFIX)) {
                    notificationManager.deleteNotificationChannel(channelID);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            final int groups = notificationManager.getNotificationChannelGroups().size();
            for (int i2 = 0; i2 < groups; i2++) {
                final String groupID = notificationManager.getNotificationChannelGroups().get(i2).getId();
                if (groupID.startsWith(OLD_INDIVIDUAL_NOTIFICATION_PREFIX)) {
                    notificationManager.deleteNotificationChannel(groupID);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean notifyMessage(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        return message.getStatus() == Message.STATUS_RECEIVED
                && !conversation.isMuted()
                && (conversation.alwaysNotify() || wasHighlightedOrPrivate(message))
                && (!conversation.isWithStranger() || notificationsFromStrangers())
                && message.getType() != Message.TYPE_RTP_SESSION;
    }

    private boolean notifyMissedCall(final Message message) {
        return message.getType() == Message.TYPE_RTP_SESSION
                && message.getStatus() == Message.STATUS_RECEIVED;
    }

    public boolean notificationsFromStrangers() {
        return mXmppConnectionService.getBooleanPreference("notifications_from_strangers", R.bool.notifications_from_strangers);
    }

    private boolean isQuietHours() {
        if (!mXmppConnectionService.getBooleanPreference("enable_quiet_hours", R.bool.enable_quiet_hours)) {
            return false;
        }
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
        final long startTime = TimePreference.minutesToTimestamp(preferences.getLong("quiet_hours_start", TimePreference.DEFAULT_VALUE));
        final long endTime = TimePreference.minutesToTimestamp(preferences.getLong("quiet_hours_end", TimePreference.DEFAULT_VALUE));
        final long nowTime = Calendar.getInstance().getTimeInMillis();

        if (endTime < startTime) {
            return nowTime > startTime || nowTime < endTime;
        } else {
            return nowTime > startTime && nowTime < endTime;
        }
    }

    public void pushFromBacklog(final Message message) {
        if (notifyMessage(message)) {
            synchronized (notifications) {
                getBacklogMessageCounter((Conversation) message.getConversation()).incrementAndGet();
                pushToStack(message);
            }
        } else if (notifyMissedCall(message)) {
            synchronized (mMissedCalls) {
                pushMissedCall(message);
            }
        }
    }

    private AtomicInteger getBacklogMessageCounter(Conversation conversation) {
        synchronized (mBacklogMessageCounter) {
            if (!mBacklogMessageCounter.containsKey(conversation)) {
                mBacklogMessageCounter.put(conversation, new AtomicInteger(0));
            }
            return mBacklogMessageCounter.get(conversation);
        }
    }

    void pushFromDirectReply(final Message message) {
        synchronized (notifications) {
            pushToStack(message);
            updateNotification(false);
        }
    }

    public void finishBacklog(boolean notify, Account account) {
        synchronized (notifications) {
            mXmppConnectionService.updateUnreadCountBadge();
            if (account == null || !notify) {
                updateNotification(notify);
            } else {
                final int count;
                final List<String> conversations;
                synchronized (this.mBacklogMessageCounter) {
                    conversations = getBacklogConversations(account);
                    count = getBacklogMessageCount(account);
                }
                updateNotification(count > 0, conversations);
            }
        }
        synchronized (mMissedCalls) {
            updateMissedCallNotifications(mMissedCalls.keySet());
        }
    }

    private List<String> getBacklogConversations(Account account) {
        final List<String> conversations = new ArrayList<>();
        for (Map.Entry<Conversation, AtomicInteger> entry : mBacklogMessageCounter.entrySet()) {
            if (entry.getKey().getAccount() == account) {
                conversations.add(entry.getKey().getUuid());
            }
        }
        return conversations;
    }

    private int getBacklogMessageCount(Account account) {
        int count = 0;
        for (Iterator<Map.Entry<Conversation, AtomicInteger>> it = mBacklogMessageCounter.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Conversation, AtomicInteger> entry = it.next();
            if (entry.getKey().getAccount() == account) {
                count += entry.getValue().get();
                it.remove();
            }
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": backlog message count=" + count);
        return count;
    }

    void finishBacklog(boolean notify) {
        finishBacklog(notify, null);
    }

    private void pushToStack(final Message message) {
        final String conversationUuid = message.getConversationUuid();
        if (notifications.containsKey(conversationUuid)) {
            notifications.get(conversationUuid).add(message);
        } else {
            final ArrayList<Message> mList = new ArrayList<>();
            mList.add(message);
            notifications.put(conversationUuid, mList);
        }
    }

    public void push(final Message message) {
        synchronized (CATCHUP_LOCK) {
            final XmppConnection connection = message.getConversation().getAccount().getXmppConnection();
            if (connection != null && connection.isWaitingForSmCatchup()) {
                connection.incrementSmCatchupMessageCounter();
                pushFromBacklog(message);
            } else {
                pushNow(message);
            }
        }
    }

    public void pushFailedDelivery(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        final boolean isScreenLocked = !mXmppConnectionService.isScreenLocked();
        if (this.mIsInForeground && isScreenLocked && this.mOpenConversation == message.getConversation()) {
            Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": suppressing failed delivery notification because conversation is open");
            return;
        }
        final PendingIntent pendingIntent = createContentIntent(conversation);
        final int notificationId = generateRequestCode(conversation, 0) + DELIVERY_FAILED_NOTIFICATION_ID;
        final int failedDeliveries = conversation.countFailedDeliveries();
        final Notification notification =
                new Builder(mXmppConnectionService, DELIVERY_FAILED_CHANNEL_ID)
                        .setContentTitle(conversation.getName())
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_error_white_24dp)
                        .setContentText(mXmppConnectionService.getResources().getQuantityText(R.plurals.some_messages_could_not_be_delivered, failedDeliveries))
                        .setGroup("delivery_failed")
                        .setContentIntent(pendingIntent).build();
        final Notification summaryNotification =
                new Builder(mXmppConnectionService, DELIVERY_FAILED_CHANNEL_ID)
                        .setContentTitle(mXmppConnectionService.getString(R.string.failed_deliveries))
                        .setContentText(mXmppConnectionService.getResources().getQuantityText(R.plurals.some_messages_could_not_be_delivered, 1024))
                        .setSmallIcon(R.drawable.ic_error_white_24dp)
                        .setGroup("delivery_failed")
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .build();
        notify(notificationId, notification);
        notify(DELIVERY_FAILED_NOTIFICATION_ID, summaryNotification);
    }

    public void showIncomingCallNotification(final AbstractJingleConnection.Id id, final Set<Media> media, final String uuid) {
        final Intent fullScreenIntent = new Intent(mXmppConnectionService, RtpSessionActivity.class);
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_ACCOUNT, id.account.getJid().asBareJid().toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Builder builder = null;
        if (mXmppConnectionService.hasIndividualNotification(mXmppConnectionService.findConversationByUuid(uuid))) {
            final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(mXmppConnectionService.findConversationByUuid(uuid)));
            builder =  new Builder(mXmppConnectionService, INDIVIDUAL_NOTIFICATION_PREFIX + INCOMING_CALLS_CHANNEL_ID + "_" + uuid + "_" + time);
        } else {
            builder =  new Builder(mXmppConnectionService, INCOMING_CALLS_CHANNEL_ID + "_" + DEFAULT);
        }
        if (media.contains(Media.VIDEO)) {
            builder.setSmallIcon(R.drawable.ic_videocam_white_24dp);
            builder.setContentTitle(mXmppConnectionService.getString(R.string.rtp_state_incoming_video_call));
        } else {
            builder.setSmallIcon(R.drawable.ic_call_white_24dp);
            builder.setContentTitle(mXmppConnectionService.getString(R.string.rtp_state_incoming_call));
        }
        final Contact contact = id.getContact();
        builder.setLargeIcon(mXmppConnectionService.getAvatarService().get(
                contact,
                AvatarService.getSystemUiAvatarSize(mXmppConnectionService))
        );
        final Uri systemAccount = contact.getSystemAccount();
        if (systemAccount != null) {
            builder.addPerson(systemAccount.toString());
        }
        String string = id.account.getRoster().getContact(id.with).getDisplayName();
        if (mXmppConnectionService.multipleAccounts()) {
            string += " (";
            string += id.account.getJid().asBareJid().toString();
            string += ")";
        }
        builder.setContentText(string);
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        PendingIntent pendingIntent = createPendingRtpSession(id, Intent.ACTION_VIEW, 101);
        builder.setFullScreenIntent(pendingIntent, true);
        builder.setContentIntent(pendingIntent); //old androids need this?
        builder.setOngoing(true);
        final String dismissString = mXmppConnectionService.getString(R.string.dismiss_call);
        final SpannableString dismiss = new SpannableString(dismissString);
        dismiss.setSpan(new ForegroundColorSpan(mXmppConnectionService.getResources().getColor(R.color.red700)), 0, dismissString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.addAction(new NotificationCompat.Action.Builder(
                R.drawable.ic_call_end_white_48dp,
                dismiss,
                createCallAction(id.sessionId, XmppConnectionService.ACTION_DISMISS_CALL, 102))
                .build());
        final String acceptString = mXmppConnectionService.getString(R.string.answer_call);
        final SpannableString accept = new SpannableString(acceptString);
        accept.setSpan(new ForegroundColorSpan(mXmppConnectionService.getResources().getColor(R.color.green500)), 0, acceptString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.addAction(new NotificationCompat.Action.Builder(
                R.drawable.ic_call_white_24dp,
                accept,
                createPendingRtpSession(id, RtpSessionActivity.ACTION_ACCEPT_CALL, 103))
                .build());
        modifyIncomingCall(builder);
        final Notification notification = builder.build();
        notification.flags = notification.flags | Notification.FLAG_INSISTENT;
        notify(INCOMING_CALL_NOTIFICATION_ID, notification);
    }

    public Notification getOngoingCallNotification(final AbstractJingleConnection.Id id, final Set<Media> media) {
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(mXmppConnectionService, ONGOING_CALLS_CHANNEL_ID);
        if (media.contains(Media.VIDEO)) {
            builder.setSmallIcon(R.drawable.ic_videocam_white_24dp);
            builder.setContentTitle(mXmppConnectionService.getString(R.string.ongoing_video_call));
        } else {
            builder.setSmallIcon(R.drawable.ic_call_white_24dp);
            builder.setContentTitle(mXmppConnectionService.getString(R.string.ongoing_call));
        }
        builder.setContentText(id.account.getRoster().getContact(id.with).getDisplayName());
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setContentIntent(createPendingRtpSession(id, Intent.ACTION_VIEW, 101));
        builder.setOngoing(true);
        builder.addAction(new NotificationCompat.Action.Builder(
                R.drawable.ic_call_end_white_48dp,
                mXmppConnectionService.getString(R.string.hang_up),
                createCallAction(id.sessionId, XmppConnectionService.ACTION_END_CALL, 104))
                .build());
        return builder.build();
    }

    private PendingIntent createPendingRtpSession(final AbstractJingleConnection.Id id, final String action, final int requestCode) {
        final Intent fullScreenIntent = new Intent(mXmppConnectionService, RtpSessionActivity.class);
        fullScreenIntent.setAction(action);
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_ACCOUNT, id.account.getJid().asBareJid().toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId);
        return PendingIntent.getActivity(mXmppConnectionService, requestCode, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void cancelIncomingCallNotification() {
        cancel(INCOMING_CALL_NOTIFICATION_ID);
    }

    public static void cancelIncomingCallNotification(final Context context) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        try {
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to cancel incoming call notification after crash", e);
        }
    }

    private void pushNow(final Message message) {
        mXmppConnectionService.updateUnreadCountBadge();
        if (!notifyMessage(message)) {
            if (this.mIsInForeground && this.mOpenConversation == message.getConversation()) {
                mXmppConnectionService.vibrate();
            }
            Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": suppressing notification because turned off");
            return;
        }
        final boolean isScreenLocked = mXmppConnectionService.isScreenLocked();
        if (this.mIsInForeground && !isScreenLocked && this.mOpenConversation == message.getConversation()) {
            mXmppConnectionService.vibrate();
            Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": suppressing notification because conversation is open");
            return;
        }
        if (this.mIsInForeground) {
            mXmppConnectionService.vibrate();
        }
        synchronized (notifications) {
            pushToStack(message);
            final Conversational conversation = message.getConversation();
            final Account account = conversation.getAccount();
            final boolean doNotify = (!(this.mIsInForeground && this.mOpenConversation == null) || isScreenLocked)
                    && !account.inGracePeriod()
                    && !this.inMiniGracePeriod(account);
            updateNotification(doNotify, Collections.singletonList(conversation.getUuid()));
        }
    }

    private void pushMissedCall(final Message message) {
        final Conversational conversation = message.getConversation();
        final MissedCallsInfo info = mMissedCalls.get(conversation);
        if (info == null) {
            mMissedCalls.put(conversation, new MissedCallsInfo(message.getTimeSent()));
        } else {
            info.newMissedCall(message.getTimeSent());
        }
    }

    public void pushMissedCallNow(final Message message) {
        synchronized (mMissedCalls) {
            pushMissedCall(message);
            updateMissedCallNotifications(Collections.singleton(message.getConversation()));
        }
    }

    public void clear(final Conversation conversation) {
        clearMessages(conversation);
        clearMissedCalls(conversation);
    }

    public void clearMessages() {
        synchronized (notifications) {
            for (ArrayList<Message> messages : notifications.values()) {
                markAsReadIfHasDirectReply(messages);
            }
            notifications.clear();
            updateNotification(false);
        }
    }

    public void clearMessages(final Conversation conversation) {
        synchronized (this.mBacklogMessageCounter) {
            this.mBacklogMessageCounter.remove(conversation);
        }
        synchronized (notifications) {
            markAsReadIfHasDirectReply(conversation);
            if (notifications.remove(conversation.getUuid()) != null) {
                cancel(conversation.getUuid(), NOTIFICATION_ID);
                updateNotification(false, null, true);
            }
        }
    }

    public void clearMissedCalls() {
        synchronized (mMissedCalls) {
            for (final Conversational conversation : mMissedCalls.keySet()) {
                cancel(conversation.getUuid(), MISSED_CALL_NOTIFICATION_ID);
            }
            mMissedCalls.clear();
            updateMissedCallNotifications(null);
        }
    }

    public void clearMissedCalls(final Conversation conversation) {
        synchronized (mMissedCalls) {
            if (mMissedCalls.remove(conversation) != null) {
                cancel(conversation.getUuid(), MISSED_CALL_NOTIFICATION_ID);
                updateMissedCallNotifications(null);
            }
        }
    }

    private void markAsReadIfHasDirectReply(final Conversation conversation) {
        markAsReadIfHasDirectReply(notifications.get(conversation.getUuid()));
    }

    private void markAsReadIfHasDirectReply(final ArrayList<Message> messages) {
        if (messages != null && messages.size() > 0) {
            Message last = messages.get(messages.size() - 1);
            if (last.getStatus() != Message.STATUS_RECEIVED) {
                if (mXmppConnectionService.markRead((Conversation) last.getConversation(), false)) {
                    mXmppConnectionService.updateConversationUi();
                }
            }
        }
    }

    private void setNotificationColor(final Builder mBuilder) {
        mBuilder.setColor(ContextCompat.getColor(mXmppConnectionService, ThemeHelper.notificationColor(mXmppConnectionService)));
    }

    public void updateNotification() {
        synchronized (notifications) {
            updateNotification(false);
        }
    }

    private void updateNotification(final boolean notify) {
        updateNotification(notify, null, false);
    }

    private void updateNotification(final boolean notify, final List<String> conversations) {
        updateNotification(notify, conversations, false);
    }

    private void updateNotification(final boolean notify, final List<String> conversations, final boolean summaryOnly) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
        final boolean quiteHours = isQuietHours();
        final boolean notifyOnlyOneChild = notify && conversations != null && conversations.size() == 1; //if this check is changed to > 0 catchup messages will create one notification per conversation
        if (notifications.size() == 0) {
            cancel(NOTIFICATION_ID);
        } else {
            if (notify) {
                this.markLastNotification();
            }
            final Builder mBuilder;
            if (notifications.size() == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                mBuilder = buildSingleConversations(notifications.values().iterator().next(), notify, quiteHours);
                modifyForSoundVibrationAndLight(mBuilder, notify, quiteHours, preferences);
                notify(NOTIFICATION_ID, mBuilder.build());
            } else {
                mBuilder = buildMultipleConversation(notify, quiteHours);
                if (notifyOnlyOneChild) {
                    mBuilder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
                }
                modifyForSoundVibrationAndLight(mBuilder, notify, quiteHours, preferences);
                if (!summaryOnly) {
                    for (Map.Entry<String, ArrayList<Message>> entry : notifications.entrySet()) {
                        String uuid = entry.getKey();
                        final boolean notifyThis = notifyOnlyOneChild ? conversations.contains(uuid) : notify;
                        Builder singleBuilder = buildSingleConversations(entry.getValue(), notifyThis, quiteHours);
                        if (!notifyOnlyOneChild) {
                            singleBuilder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY);
                        }
                        modifyForSoundVibrationAndLight(singleBuilder, notifyThis, quiteHours, preferences);
                        singleBuilder.setGroup(MESSAGES_GROUP);
                        setNotificationColor(singleBuilder);
                        notify(entry.getKey(), NOTIFICATION_ID, singleBuilder.build());
                    }
                }
                notify(NOTIFICATION_ID, mBuilder.build());
            }
        }
    }

    private void updateMissedCallNotifications(final Set<Conversational> update) {
        if (mMissedCalls.isEmpty()) {
            cancel(MISSED_CALL_NOTIFICATION_ID);
            return;
        }
        if (mMissedCalls.size() == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            final Conversational conversation = mMissedCalls.keySet().iterator().next();
            final MissedCallsInfo info = mMissedCalls.values().iterator().next();
            final Notification notification = missedCall(conversation, info);
            notify(MISSED_CALL_NOTIFICATION_ID, notification);
        } else {
            final Notification summary = missedCallsSummary();
            notify(MISSED_CALL_NOTIFICATION_ID, summary);
            if (update != null) {
                for (final Conversational conversation : update) {
                    final MissedCallsInfo info = mMissedCalls.get(conversation);
                    if (info != null) {
                        final Notification notification = missedCall(conversation, info);
                        notify(conversation.getUuid(), MISSED_CALL_NOTIFICATION_ID, notification);
                    }
                }
            }
        }
    }

    private void modifyForSoundVibrationAndLight(Builder mBuilder, boolean notify, boolean quietHours, SharedPreferences preferences) {
        final Resources resources = mXmppConnectionService.getResources();
        final String ringtone = preferences.getString("notification_ringtone", resources.getString(R.string.notification_ringtone));
        final boolean vibrate = preferences.getBoolean("vibrate_on_notification", resources.getBoolean(R.bool.vibrate_on_notification));
        final boolean led = preferences.getBoolean("led", resources.getBoolean(R.bool.led));
        final boolean headsup = preferences.getBoolean("notification_headsup", resources.getBoolean(R.bool.headsup_notifications));
        if (notify && !quietHours) {
            if (vibrate) {
                final int dat = 70;
                final long[] pattern = {0, 3 * dat, dat, dat};
                mBuilder.setVibrate(pattern);
            } else {
                mBuilder.setVibrate(new long[]{0});
            }
            Uri uri = Uri.parse(ringtone);
            try {
                mBuilder.setSound(fixRingtoneUri(uri));
            } catch (SecurityException e) {
                Log.d(Config.LOGTAG, "unable to use custom notification sound " + uri.toString());
            }
        } else {
            mBuilder.setLocalOnly(true);
        }
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBuilder.setCategory(Notification.CATEGORY_MESSAGE);
        }
        mBuilder.setPriority(notify ? (headsup ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT) : NotificationCompat.PRIORITY_LOW);
        setNotificationColor(mBuilder);
        mBuilder.setDefaults(0);
        if (led) {
            mBuilder.setLights(LED_COLOR, 2000, 3000);
        }
    }

    private void modifyIncomingCall(Builder mBuilder) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
        final Resources resources = mXmppConnectionService.getResources();
        final String ringtone = preferences.getString("call_ringtone", resources.getString(R.string.incoming_call_ringtone));
        mBuilder.setVibrate(CALL_PATTERN);
        final Uri uri = Uri.parse(ringtone);
        try {
            mBuilder.setSound(fixRingtoneUri(uri));
        } catch (SecurityException e) {
            Log.d(Config.LOGTAG, "unable to use custom notification sound " + uri.toString());
        }
        mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);
        setNotificationColor(mBuilder);
        mBuilder.setLights(LED_COLOR, 2000, 3000);
    }

    private Uri fixRingtoneUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "file".equals(uri.getScheme())) {
            return FileBackend.getUriForFile(mXmppConnectionService, new File(uri.getPath()));
        } else {
            return uri;
        }
    }

    private Notification missedCallsSummary() {
        final Builder publicBuilder = buildMissedCallsSummary(true);
        final Builder builder = buildMissedCallsSummary(false);
        builder.setPublicVersion(publicBuilder.build());
        return builder.build();
    }

    private Builder buildMissedCallsSummary(boolean publicVersion) {
        final Builder builder = new NotificationCompat.Builder(mXmppConnectionService, MISSED_CALLS_CHANNEL_ID);
        int totalCalls = 0;
        final StringBuilder names = new StringBuilder();
        long lastTime = 0;
        for (Map.Entry<Conversational, MissedCallsInfo> entry : mMissedCalls.entrySet()) {
            final Conversational conversation = entry.getKey();
            final MissedCallsInfo missedCallsInfo = entry.getValue();
            names.append(conversation.getContact().getDisplayName());
            names.append(", ");
            totalCalls += missedCallsInfo.getNumberOfCalls();
            lastTime = Math.max(lastTime, missedCallsInfo.getLastTime());
        }
        if (names.length() >= 2) {
            names.delete(names.length() - 2, names.length());
        }
        final String title = (totalCalls == 1) ? mXmppConnectionService.getString(R.string.missed_call) :
                (mMissedCalls.size() == 1) ? mXmppConnectionService.getString(R.string.n_missed_calls, totalCalls) :
                        mXmppConnectionService.getString(R.string.n_missed_calls_from_m_contacts, totalCalls, mMissedCalls.size());
        builder.setContentTitle(title);
        builder.setTicker(title);
        if (!publicVersion) {
            builder.setContentText(names.toString());
        }
        builder.setSmallIcon(R.drawable.ic_missed_call_notification);
        builder.setGroupSummary(true);
        builder.setGroup(MISSED_CALLS_GROUP);
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setWhen(lastTime);
        if (!mMissedCalls.isEmpty()) {
            final Conversational firstConversation = mMissedCalls.keySet().iterator().next();
            builder.setContentIntent(createContentIntent(firstConversation));
        }
        builder.setDeleteIntent(createMissedCallsDeleteIntent(null));
        modifyMissedCall(builder);
        return builder;
    }

    private Notification missedCall(final Conversational conversation, final MissedCallsInfo info) {
        final Builder publicBuilder = buildMissedCall(conversation, info, true);
        final Builder builder = buildMissedCall(conversation, info, false);
        builder.setPublicVersion(publicBuilder.build());
        return builder.build();
    }

    private Builder buildMissedCall(final Conversational conversation, final MissedCallsInfo info, boolean publicVersion) {
        final Builder builder = new NotificationCompat.Builder(mXmppConnectionService, MISSED_CALLS_CHANNEL_ID);
        final String title = (info.getNumberOfCalls() == 1) ? mXmppConnectionService.getString(R.string.missed_call) :
                mXmppConnectionService.getString(R.string.n_missed_calls, info.getNumberOfCalls());
        builder.setContentTitle(title);
        final String name = conversation.getContact().getDisplayName();
        if (publicVersion) {
            builder.setTicker(title);
        } else {
            if (info.getNumberOfCalls() == 1) {
                builder.setTicker(mXmppConnectionService.getString(R.string.missed_call_from_x, name));
            } else {
                builder.setTicker(mXmppConnectionService.getString(R.string.n_missed_calls_from_x, info.getNumberOfCalls(), name));
            }
            builder.setContentText(name);
        }
        builder.setSmallIcon(R.drawable.ic_missed_call_notification);
        builder.setGroup(MISSED_CALLS_GROUP);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setWhen(info.getLastTime());
        builder.setContentIntent(createContentIntent(conversation));
        builder.setDeleteIntent(createMissedCallsDeleteIntent(conversation));
        if (!publicVersion && conversation instanceof Conversation) {
            builder.setLargeIcon(mXmppConnectionService.getAvatarService()
                    .get((Conversation) conversation, AvatarService.getSystemUiAvatarSize(mXmppConnectionService)));
        }
        modifyMissedCall(builder);
        return builder;
    }

    private void modifyMissedCall(final Builder builder) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
        final Resources resources = mXmppConnectionService.getResources();
        final boolean led = preferences.getBoolean("led", resources.getBoolean(R.bool.led));
        if (led) {
            builder.setLights(LED_COLOR, 2000, 3000);
        }
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setSound(null);
        setNotificationColor(builder);
    }

    private Builder buildMultipleConversation(final boolean notify, final boolean quietHours) {
        Builder mBuilder = null;
        final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        style.setBigContentTitle(mXmppConnectionService.getResources().getQuantityString(R.plurals.x_unread_conversations, notifications.size(), notifications.size()));
        final StringBuilder names = new StringBuilder();
        Conversation conversation = null;
        for (final ArrayList<Message> messages : notifications.values()) {
            if (messages.size() > 0) {
                conversation = (Conversation) messages.get(0).getConversation();
                if (quietHours) {
                    mBuilder = new Builder(mXmppConnectionService, QUIET_HOURS_CHANNEL_ID);
                } else if (notify) {
                    if (mXmppConnectionService.hasIndividualNotification(conversation)) {
                        final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(conversation));
                        mBuilder = new Builder(mXmppConnectionService, INDIVIDUAL_NOTIFICATION_PREFIX + MESSAGES_CHANNEL_ID + "_" + conversation.getUuid() + "_" + time);
                    } else {
                        mBuilder = new Builder(mXmppConnectionService, MESSAGES_CHANNEL_ID + "_" + DEFAULT);
                    }
                } else {
                    mBuilder = new Builder(mXmppConnectionService, SILENT_MESSAGES_CHANNEL_ID);
                }
                final String name = conversation.getName().toString();
                SpannableString styledString;
                if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
                    int count = messages.size();
                    styledString = new SpannableString(name + ": " + mXmppConnectionService.getResources().getQuantityString(R.plurals.x_messages, count, count));
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    style.addLine(styledString);
                } else {
                    styledString = new SpannableString(name + ": " + UIHelper.getMessagePreview(mXmppConnectionService, messages.get(0)).first);
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    style.addLine(styledString);
                }
                names.append(name);
                names.append(", ");
            }
        }
        if (names.length() >= 2) {
            names.delete(names.length() - 2, names.length());
        }
        final String contentTitle = mXmppConnectionService.getResources().getQuantityString(R.plurals.x_unread_conversations, notifications.size(), notifications.size());
        mBuilder.setContentTitle(contentTitle);
        mBuilder.setTicker(contentTitle);
        mBuilder.setContentText(names.toString());
        mBuilder.setStyle(style);
        if (conversation != null) {
            mBuilder.setContentIntent(createOpenConversationsIntent());
        }
        mBuilder.setGroupSummary(true);
        mBuilder.setGroup(MESSAGES_GROUP);
        mBuilder.setDeleteIntent(createDeleteIntent(null));
        mBuilder.setSmallIcon(R.drawable.ic_notification);
        return mBuilder;
    }

    private Builder buildSingleConversations(final ArrayList<Message> messages, final boolean notify, final boolean quietHours) {
        Builder mBuilder = null;
        if (messages.size() >= 1) {
            final Conversation conversation = (Conversation) messages.get(0).getConversation();
            if (quietHours) {
                mBuilder = new Builder(mXmppConnectionService, QUIET_HOURS_CHANNEL_ID);
            } else {
                if (notify) {
                    if (mXmppConnectionService.hasIndividualNotification(conversation)) {
                        final String time = String.valueOf(mXmppConnectionService.getIndividualNotificationPreference(conversation));
                        mBuilder = new Builder(mXmppConnectionService, INDIVIDUAL_NOTIFICATION_PREFIX + MESSAGES_CHANNEL_ID + "_" + conversation.getUuid() + "_" + time);
                    } else {
                        mBuilder = new Builder(mXmppConnectionService, MESSAGES_CHANNEL_ID + "_" + DEFAULT);
                    }
                } else {
                    mBuilder = new Builder(mXmppConnectionService, SILENT_MESSAGES_CHANNEL_ID);
                }
            }
            mBuilder.setLargeIcon(mXmppConnectionService.getAvatarService().get(conversation, AvatarService.getSystemUiAvatarSize(mXmppConnectionService)));
            mBuilder.setContentTitle(conversation.getName());
            if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
                int count = messages.size();
                mBuilder.setContentText(mXmppConnectionService.getResources().getQuantityString(R.plurals.x_messages, count, count));
            } else {
                Message message;
                //TODO starting with Android 9 we might want to put images in MessageStyle
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && (message = getImage(messages)) != null) {
                    modifyForImage(mBuilder, message, messages);
                } else {
                    modifyForTextOnly(mBuilder, messages);
                }
                RemoteInput remoteInput = new RemoteInput.Builder("text_reply").setLabel(UIHelper.getMessageHint(mXmppConnectionService, conversation)).build();
                PendingIntent markAsReadPendingIntent = createReadPendingIntent(conversation);
                NotificationCompat.Action markReadAction = new NotificationCompat.Action.Builder(
                        R.drawable.ic_email_open_outline_white_24dp,
                        mXmppConnectionService.getString(R.string.mark_as_read),
                        markAsReadPendingIntent)
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                        .setShowsUserInterface(false)
                        .build();
                String replyLabel = mXmppConnectionService.getString(R.string.reply);
                NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                        R.drawable.ic_reply_white_24dp,
                        replyLabel,
                        createReplyIntent(conversation, false))
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                        .setShowsUserInterface(false)
                        .addRemoteInput(remoteInput).build();
                NotificationCompat.Action wearReplyAction = new NotificationCompat.Action.Builder(R.drawable.ic_wear_reply,
                        replyLabel,
                        createReplyIntent(conversation, true)).addRemoteInput(remoteInput).build();
                mBuilder.extend(new NotificationCompat.WearableExtender().addAction(wearReplyAction));
                int addedActionsCount = 1;
                mBuilder.addAction(markReadAction);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mBuilder.addAction(replyAction);
                    ++addedActionsCount;
                }
                if (displaySnoozeAction(messages)) {
                    String label = mXmppConnectionService.getString(R.string.snooze);
                    PendingIntent pendingSnoozeIntent = createSnoozeIntent(conversation);
                    NotificationCompat.Action snoozeAction = new NotificationCompat.Action.Builder(
                            R.drawable.ic_notifications_paused_white_24dp,
                            label,
                            pendingSnoozeIntent).build();
                    mBuilder.addAction(snoozeAction);
                    ++addedActionsCount;
                }
                if (addedActionsCount < 3 && mXmppConnectionService.webViewAvailable()) {
                    final Message firstLocationMessage = getFirstLocationMessage(messages);
                    if (firstLocationMessage != null) {
                        final PendingIntent pendingShowLocationIntent = createShowLocationIntent(firstLocationMessage);
                        if (pendingShowLocationIntent != null) {
                            final String label = mXmppConnectionService.getResources().getString(R.string.show_location);
                            NotificationCompat.Action locationAction = new NotificationCompat.Action.Builder(
                                    R.drawable.ic_room_white_24dp,
                                    label,
                                    pendingShowLocationIntent).build();
                            mBuilder.addAction(locationAction);
                            ++addedActionsCount;
                        }
                    }
                }
                if (addedActionsCount < 3) {
                    Message firstDownloadableMessage = getFirstDownloadableMessage(messages);
                    if (firstDownloadableMessage != null) {
                        String label = mXmppConnectionService.getResources().getString(R.string.download_x_file, UIHelper.getFileDescriptionString(mXmppConnectionService, firstDownloadableMessage));
                        PendingIntent pendingDownloadIntent = createDownloadIntent(firstDownloadableMessage);
                        NotificationCompat.Action downloadAction = new NotificationCompat.Action.Builder(
                                R.drawable.ic_file_download_white_24dp,
                                label,
                                pendingDownloadIntent).build();
                        mBuilder.addAction(downloadAction);
                        ++addedActionsCount;
                    }
                }
            }
            if (conversation.getMode() == Conversation.MODE_SINGLE) {
                Contact contact = conversation.getContact();
                Uri systemAccount = contact.getSystemAccount();
                if (systemAccount != null) {
                    mBuilder.addPerson(systemAccount.toString());
                }
            }
            mBuilder.setWhen(conversation.getLatestMessage().getTimeSent());
            mBuilder.setSmallIcon(R.drawable.ic_notification);
            mBuilder.setDeleteIntent(createDeleteIntent(conversation));
            mBuilder.setContentIntent(createContentIntent(conversation));
        }
        return mBuilder;
    }

    private void modifyForImage(final Builder builder, final Message message, final ArrayList<Message> messages) {
        try {
            final Bitmap bitmap = mXmppConnectionService.getFileBackend().getThumbnail(message, getPixel(288), false);
            final ArrayList<Message> tmp = new ArrayList<>();
            for (final Message msg : messages) {
                if (msg.getType() == Message.TYPE_TEXT
                        && msg.getTransferable() == null) {
                    tmp.add(msg);
                }
            }
            final BigPictureStyle bigPictureStyle = new NotificationCompat.BigPictureStyle();
            bigPictureStyle.bigPicture(bitmap);
            if (tmp.size() > 0) {
                CharSequence text = getMergedBodies(tmp);
                bigPictureStyle.setSummaryText(text);
                builder.setContentText(text);
                builder.setTicker(text);
            } else {
                final String description = UIHelper.getFileDescriptionString(mXmppConnectionService, message);
                builder.setContentText(description);
                builder.setTicker(description);
            }
            builder.setStyle(bigPictureStyle);
        } catch (final IOException e) {
            modifyForTextOnly(builder, messages);
        }
    }

    private Person getPerson(Message message) {
        final Contact contact = message.getContact();
        final Person.Builder builder = new Person.Builder();
        if (contact != null) {
            builder.setName(contact.getDisplayName());
            final Uri uri = contact.getSystemAccount();
            if (uri != null) {
                builder.setUri(uri.toString());
            }
        } else {
            builder.setName(UIHelper.getColoredUsername(mXmppConnectionService, message));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIcon(IconCompat.createWithBitmap(mXmppConnectionService.getAvatarService().get(message, AvatarService.getSystemUiAvatarSize(mXmppConnectionService), false)));
        }
        return builder.build();
    }

    private void modifyForTextOnly(final Builder builder, final ArrayList<Message> messages) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final Conversation conversation = (Conversation) messages.get(0).getConversation();
            final Person.Builder meBuilder = new Person.Builder().setName(mXmppConnectionService.getString(R.string.me));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                meBuilder.setIcon(IconCompat.createWithBitmap(mXmppConnectionService.getAvatarService().get(conversation.getAccount(), AvatarService.getSystemUiAvatarSize(mXmppConnectionService))));
            }
            final Person me = meBuilder.build();
            NotificationCompat.MessagingStyle messagingStyle = new NotificationCompat.MessagingStyle(me);
            final boolean multiple = conversation.getMode() == Conversation.MODE_MULTI;
            if (multiple) {
                messagingStyle.setConversationTitle(conversation.getName());
            }
            for (Message message : messages) {
                final Person sender = message.getStatus() == Message.STATUS_RECEIVED ? getPerson(message) : null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isImageMessage(message)) {
                    final Uri dataUri = FileBackend.getMediaUri(mXmppConnectionService, mXmppConnectionService.getFileBackend().getFile(message));
                    NotificationCompat.MessagingStyle.Message imageMessage = new NotificationCompat.MessagingStyle.Message(UIHelper.getMessagePreview(mXmppConnectionService, message).first, message.getTimeSent(), sender);
                    if (dataUri != null) {
                        imageMessage.setData(message.getMimeType(), dataUri);
                    }
                    messagingStyle.addMessage(imageMessage);
                } else {
                    messagingStyle.addMessage(UIHelper.getMessagePreview(mXmppConnectionService, message).first, message.getTimeSent(), sender);
                }
            }
            messagingStyle.setGroupConversation(multiple);
            builder.setStyle(messagingStyle);
        } else {
            if (messages.get(0).getConversation().getMode() == Conversation.MODE_SINGLE) {
                builder.setStyle(new NotificationCompat.BigTextStyle().bigText(getMergedBodies(messages)));
                final CharSequence preview = UIHelper.getMessagePreview(mXmppConnectionService, messages.get(messages.size() - 1)).first;
                builder.setContentText(preview);
                builder.setTicker(preview);
                builder.setNumber(messages.size());
            } else {
                final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                SpannableString styledString;
                for (Message message : messages) {
                    final SpannableString name = UIHelper.getColoredUsername(mXmppConnectionService, message);
                    styledString = new SpannableString(name + ": " + EmojiWrapper.transform(replaceYoutube(mXmppConnectionService, message.getBody())));
                    style.addLine(styledString);
                }
                builder.setStyle(style);
                int count = messages.size();
                if (count == 1) {
                    final SpannableString name = UIHelper.getColoredUsername(mXmppConnectionService, messages.get(0));
                    styledString = new SpannableString(name + ": " + EmojiWrapper.transform(replaceYoutube(mXmppConnectionService, messages.get(0).getBody())));
                    builder.setContentText(styledString);
                    builder.setTicker(styledString);
                } else {
                    final String text = mXmppConnectionService.getResources().getQuantityString(R.plurals.x_messages, count, count);
                    builder.setContentText(text);
                    builder.setTicker(text);
                }
            }
        }
    }

    private Message getImage(final Iterable<Message> messages) {
        Message image = null;
        for (final Message message : messages) {
            if (message.getStatus() != Message.STATUS_RECEIVED) {
                return null;
            }
            if (isImageMessage(message)) {
                image = message;
            }
        }
        return image;
    }

    private static boolean isImageMessage(Message message) {
        return message.getType() != Message.TYPE_TEXT
                && message.getTransferable() == null
                && !message.isFileDeleted()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getFileParams().height > 0;
    }

    private Message getFirstDownloadableMessage(final Iterable<Message> messages) {
        for (final Message message : messages) {
            if (message.getTransferable() != null || (message.getType() == Message.TYPE_TEXT && message.treatAsDownloadable())) {
                return message;
            }
        }
        return null;
    }

    private Message getFirstLocationMessage(final Iterable<Message> messages) {
        for (final Message message : messages) {
            if (message.isGeoUri()) {
                return message;
            }
        }
        return null;
    }

    private CharSequence getMergedBodies(final ArrayList<Message> messages) {
        final StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (text.length() != 0) {
                text.append("\n");
            }
            text.append(UIHelper.getMessagePreview(mXmppConnectionService, message).first);
        }
        return text.toString();
    }

    private PendingIntent createShowLocationIntent(final Message message) {
        Iterable<Intent> intents = GeoHelper.createGeoIntentsFromMessage(mXmppConnectionService, message);
        for (Intent intent : intents) {
            if (intent.resolveActivity(mXmppConnectionService.getPackageManager()) != null) {
                return PendingIntent.getActivity(mXmppConnectionService, generateRequestCode(message.getConversation(), 18), intent, PendingIntent.FLAG_UPDATE_CURRENT);
            }
        }
        return null;
    }

    private PendingIntent createContentIntent(final String conversationUuid, final String downloadMessageUuid) {
        final Intent viewConversationIntent = new Intent(mXmppConnectionService, ConversationsActivity.class);
        viewConversationIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        viewConversationIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversationUuid);
        if (downloadMessageUuid != null) {
            viewConversationIntent.putExtra(ConversationsActivity.EXTRA_DOWNLOAD_UUID, downloadMessageUuid);
            return PendingIntent.getActivity(mXmppConnectionService,
                    generateRequestCode(conversationUuid, 8),
                    viewConversationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            return PendingIntent.getActivity(mXmppConnectionService,
                    generateRequestCode(conversationUuid, 10),
                    viewConversationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private int generateRequestCode(String uuid, int actionId) {
        return (actionId * NOTIFICATION_ID_MULTIPLIER) + (uuid.hashCode() % NOTIFICATION_ID_MULTIPLIER);
    }

    private int generateRequestCode(Conversational conversation, int actionId) {
        return generateRequestCode(conversation.getUuid(), actionId);
    }

    private PendingIntent createDownloadIntent(final Message message) {
        return createContentIntent(message.getConversationUuid(), message.getUuid());
    }

    private PendingIntent createContentIntent(final Conversational conversation) {
        return createContentIntent(conversation.getUuid(), null);
    }

    private PendingIntent createDeleteIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_CLEAR_MESSAGE_NOTIFICATION);
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid());
            return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 20), intent, 0);
        }
        return PendingIntent.getService(mXmppConnectionService, 0, intent, 0);
    }

    private PendingIntent createMissedCallsDeleteIntent(final Conversational conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_CLEAR_MISSED_CALL_NOTIFICATION);
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid());
            return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 21), intent, 0);
        }
        return PendingIntent.getService(mXmppConnectionService, 1, intent, 0);
    }

    private PendingIntent createReplyIntent(Conversation conversation, boolean dismissAfterReply) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_REPLY_TO_CONVERSATION);
        intent.putExtra("uuid", conversation.getUuid());
        intent.putExtra("dismiss_notification", dismissAfterReply);
        final int id = generateRequestCode(conversation, dismissAfterReply ? 12 : 14);
        return PendingIntent.getService(mXmppConnectionService, id, intent, 0);
    }

    private PendingIntent createReadPendingIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_MARK_AS_READ);
        intent.putExtra("uuid", conversation.getUuid());
        intent.setPackage(mXmppConnectionService.getPackageName());
        return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 16), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createCallAction(String sessionId, final String action, int requestCode) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(action);
        intent.setPackage(mXmppConnectionService.getPackageName());
        intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, sessionId);
        return PendingIntent.getService(mXmppConnectionService, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createSnoozeIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_SNOOZE);
        intent.putExtra("uuid", conversation.getUuid());
        intent.setPackage(mXmppConnectionService.getPackageName());
        return PendingIntent.getService(mXmppConnectionService, generateRequestCode(conversation, 22), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createTryAgainIntent() {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_TRY_AGAIN);
        return PendingIntent.getService(mXmppConnectionService, 45, intent, 0);
    }

    private PendingIntent createDismissErrorIntent() {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_DISMISS_ERROR_NOTIFICATIONS);
        return PendingIntent.getService(mXmppConnectionService, 69, intent, 0);
    }

    private boolean wasHighlightedOrPrivate(final Message message) {
        if (message.getConversation() instanceof Conversation) {
            Conversation conversation = (Conversation) message.getConversation();
            final String nick = conversation.getMucOptions().getActualNick();
            final Pattern highlight = generateNickHighlightPattern(nick);
            if (message.getBody() == null || nick == null) {
                return false;
            }
            final Matcher m = highlight.matcher(message.getBody());
            return (m.find() || message.isPrivateMessage());
        } else {
            return false;
        }
    }

    public void setOpenConversation(final Conversation conversation) {
        this.mOpenConversation = conversation;
    }

    public void setIsInForeground(final boolean foreground) {
        this.mIsInForeground = foreground;
    }

    private int getPixel(final int dp) {
        final DisplayMetrics metrics = mXmppConnectionService.getResources()
                .getDisplayMetrics();
        return ((int) (dp * metrics.density));
    }

    private void markLastNotification() {
        this.mLastNotification = SystemClock.elapsedRealtime();
    }

    private boolean inMiniGracePeriod(final Account account) {
        final int miniGrace = account.getStatus() == Account.State.ONLINE ? Config.MINI_GRACE_PERIOD
                : Config.MINI_GRACE_PERIOD * 2;
        return SystemClock.elapsedRealtime() < (this.mLastNotification + miniGrace);
    }

    Notification createForegroundNotification() {
        final Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service));
        String status;
        final List<Account> accounts = mXmppConnectionService.getAccounts();
        int enabled = 0;
        int connected = 0;
        if (accounts != null) {
            for (Account account : accounts) {
                if (account.isOnlineAndConnected()) {
                    connected++;
                    enabled++;
                } else if (account.isEnabled()) {
                    enabled++;
                }
            }
            if (accounts.size() == 1) {
                Account mAccount = accounts.get(0);
                if (mAccount.getStatus() == Account.State.ONLINE) {
                    status = "(" + mXmppConnectionService.getString(R.string.account_status_online) + ")";
                    status = " " + status;
                    Log.d(Config.LOGTAG, "Status: " + status);
                    mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service) + status);
                } else if (mAccount.getStatus() == Account.State.CONNECTING) {
                    status = "(" + mXmppConnectionService.getString(R.string.account_status_connecting) + ")";
                    status = " " + status;
                    Log.d(Config.LOGTAG, "Status: " + status);
                    mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service) + status);
                } else {
                    status = "(" + mXmppConnectionService.getString(R.string.account_status_offline) + ")";
                    status = " " + status;
                    Log.d(Config.LOGTAG, "Status: " + status);
                    mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service) + status);
                }
            } else if (accounts.size() > 1) {
                mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service));
            } else {
                status = "(" + mXmppConnectionService.getString(R.string.account_status_offline) + ")";
                status = " " + status;
                Log.d(Config.LOGTAG, "Status: " + status);
                mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.conversations_foreground_service) + status);
            }
        }
        mBuilder.setContentText(mXmppConnectionService.getString(R.string.connected_accounts, connected, enabled));
        mBuilder.setContentIntent(createOpenConversationsIntent());
        final PendingIntent openIntent = createOpenConversationsIntent();
        if (openIntent != null) {
            mBuilder.setContentIntent(openIntent);
        }
        mBuilder.setWhen(0);
        mBuilder.setPriority(Notification.PRIORITY_MIN);
        mBuilder.setSmallIcon(connected > 0 ? R.drawable.ic_link_white_24dp : R.drawable.ic_link_off_white_24dp);
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId(FOREGROUND_CHANNEL_ID);
        }
        return mBuilder.build();
    }

    private PendingIntent createOpenConversationsIntent() {
        try {
            return PendingIntent.getActivity(mXmppConnectionService, 0, new Intent(mXmppConnectionService, ConversationsActivity.class), 0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    void updateErrorNotification() {
        if (Config.SUPPRESS_ERROR_NOTIFICATION) {
            cancel(ERROR_NOTIFICATION_ID);
            return;
        }
        final boolean showAllErrors = QuickConversationsService.isConversations();
        final List<Account> errors = new ArrayList<>();
        boolean torNotAvailable = false;
        for (final Account account : mXmppConnectionService.getAccounts()) {
            if (account.hasErrorStatus() && account.showErrorNotification() && (showAllErrors || account.getLastErrorStatus() == Account.State.UNAUTHORIZED)) {
                errors.add(account);
                torNotAvailable |= account.getStatus() == Account.State.TOR_NOT_AVAILABLE;
            }
        }
        if (mXmppConnectionService.foregroundNotificationNeedsUpdatingWhenErrorStateChanges()) {
            notify(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
        }
        final Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        if (errors.size() == 0) {
            cancel(ERROR_NOTIFICATION_ID);
            return;
        } else if (errors.size() == 1) {
            mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.problem_connecting_to_account));
            mBuilder.setContentText(errors.get(0).getJid().asBareJid().toEscapedString());
        } else {
            mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.problem_connecting_to_accounts));
            mBuilder.setContentText(mXmppConnectionService.getString(R.string.touch_to_fix));
        }
        mBuilder.addAction(R.drawable.ic_autorenew_white_24dp,
                mXmppConnectionService.getString(R.string.try_again),
                createTryAgainIntent()
        );
        if (torNotAvailable) {
            if (TorServiceUtils.isOrbotInstalled(mXmppConnectionService)) {
                mBuilder.addAction(
                        R.drawable.ic_play_circle_filled_white_48dp,
                        mXmppConnectionService.getString(R.string.start_orbot),
                        PendingIntent.getActivity(mXmppConnectionService, 147, TorServiceUtils.LAUNCH_INTENT, 0)
                );
            } else {
                mBuilder.addAction(
                        R.drawable.ic_file_download_white_24dp,
                        mXmppConnectionService.getString(R.string.install_orbot),
                        PendingIntent.getActivity(mXmppConnectionService, 146, TorServiceUtils.INSTALL_INTENT, 0)
                );
            }
        }
        mBuilder.setDeleteIntent(createDismissErrorIntent());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBuilder.setVisibility(Notification.VISIBILITY_PRIVATE);
            mBuilder.setSmallIcon(R.drawable.ic_warning_white_24dp);
        } else {
            mBuilder.setSmallIcon(R.drawable.ic_warning_white_24dp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            mBuilder.setLocalOnly(true);
        }
        mBuilder.setPriority(Notification.PRIORITY_LOW);
        final Intent intent;
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            intent = new Intent(mXmppConnectionService, AccountUtils.MANAGE_ACCOUNT_ACTIVITY);
        } else {
            intent = new Intent(mXmppConnectionService, EditAccountActivity.class);
            intent.putExtra("jid", errors.get(0).getJid().asBareJid().toEscapedString());
            intent.putExtra(EditAccountActivity.EXTRA_OPENED_FROM_NOTIFICATION, true);
        }
        mBuilder.setContentIntent(PendingIntent.getActivity(mXmppConnectionService, 145, intent, PendingIntent.FLAG_UPDATE_CURRENT));
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId(ERROR_CHANNEL_ID);
        }
        notify(ERROR_NOTIFICATION_ID, mBuilder.build());
    }

    void updateFileAddingNotification(int current, Message message) {
        Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.transcoding_video));
        mBuilder.setProgress(100, current, false);
        mBuilder.setSmallIcon(R.drawable.ic_hourglass_empty_white_24dp);
        mBuilder.setContentIntent(createContentIntent(message.getConversation()));
        mBuilder.setOngoing(true);
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId(VIDEOCOMPRESSION_CHANNEL_ID);
        }
        Notification notification = mBuilder.build();
        notify(FOREGROUND_NOTIFICATION_ID, notification);
    }

    Notification AppUpdateNotification(PendingIntent intent, String version, String filesize) {
        Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.app_name));
        mBuilder.setContentText(String.format(mXmppConnectionService.getString(R.string.update_available), version, filesize));
        mBuilder.setSmallIcon(R.drawable.ic_update_notification);
        mBuilder.setContentIntent(intent);
        mBuilder.setOngoing(true);
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId(UPDATE_CHANNEL_ID);
        }
        return mBuilder.build();
    }

    public void AppUpdateServiceNotification(Notification notification) {
        notify(FOREGROUND_NOTIFICATION_ID, notification);
    }

    private void notify(String tag, int id, Notification notification) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.notify(tag, id, notification);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to make notification", e);
        }
    }

    public void notify(int id, Notification notification) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.notify(id, notification);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to make notification", e);
        }
    }

    public void cancel(int id) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.cancel(id);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to cancel notification", e);
        }
    }

    private void cancel(String tag, int id) {
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.cancel(tag, id);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to cancel notification", e);
        }
    }

    private static class MissedCallsInfo {
        private int numberOfCalls;
        private long lastTime;

        MissedCallsInfo(final long time) {
            numberOfCalls = 1;
            lastTime = time;
        }

        public void newMissedCall(final long time) {
            ++numberOfCalls;
            lastTime = time;
        }

        public int getNumberOfCalls() {
            return numberOfCalls;
        }

        public long getLastTime() {
            return lastTime;
        }
    }
}
