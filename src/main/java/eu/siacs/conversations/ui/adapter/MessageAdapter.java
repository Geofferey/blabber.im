package eu.siacs.conversations.ui.adapter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.common.base.Strings;
import com.squareup.picasso.Picasso;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Message.FileParams;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AudioPlayer;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.text.DividerSpan;
import eu.siacs.conversations.ui.text.QuoteSpan;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.ui.widget.ClickableMovementMethod;
import eu.siacs.conversations.ui.widget.RichLinkView;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.EmojiWrapper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.RichPreview;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import me.drakeet.support.toast.ToastCompat;
import pl.droidsonroids.gif.GifImageView;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static eu.siacs.conversations.entities.Message.DELETED_MESSAGE_BODY;
import static eu.siacs.conversations.entities.Message.DELETED_MESSAGE_BODY_OLD;
import static eu.siacs.conversations.persistance.FileBackend.formatTime;
import static eu.siacs.conversations.persistance.FileBackend.safeLongToInt;
import static eu.siacs.conversations.ui.SettingsActivity.PLAY_GIF_INSIDE;
import static eu.siacs.conversations.ui.SettingsActivity.SHOW_LINKS_INSIDE;
import static eu.siacs.conversations.ui.SettingsActivity.SHOW_MAPS_INSIDE;
import static eu.siacs.conversations.ui.util.MyLinkify.removeTrackingParameter;
import static eu.siacs.conversations.ui.util.MyLinkify.removeTrailingBracket;
import static eu.siacs.conversations.ui.util.MyLinkify.replaceYoutube;

public class MessageAdapter extends ArrayAdapter<Message> {

    public static final String DATE_SEPARATOR_BODY = "DATE_SEPARATOR";
    private static final int SENT = 0;
    private static final int RECEIVED = 1;
    private static final int STATUS = 2;
    private static final int DATE_SEPARATOR = 3;
    private static final int RTP_SESSION = 4;
    boolean isResendable = false;

    private final XmppActivity activity;
    private final AudioPlayer audioPlayer;
    private List<String> highlightedTerm = null;
    private final DisplayMetrics metrics;
    private OnContactPictureClicked mOnContactPictureClickedListener;
    private OnContactPictureLongClicked mOnContactPictureLongClickedListener;
    private boolean mIndicateReceived = false;
    private boolean mPlayGifInside = false;
    private boolean mShowLinksInside = false;
    private boolean mShowMapsInside = false;

    public MessageAdapter(XmppActivity activity, List<Message> messages) {
        super(activity, 0, messages);
        this.activity = activity;
        this.audioPlayer = new AudioPlayer(this);
        metrics = getContext().getResources().getDisplayMetrics();
        updatePreferences();
    }

    private static void resetClickListener(View... views) {
        for (View view : views) {
            view.setOnClickListener(null);
        }
    }

    public void flagDisableInputs() {
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    public void flagEnableInputs() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    public void flagScreenOn() {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void flagScreenOff() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public boolean autoPauseVoice() {
        return activity.xmppConnectionService.pauseVoiceOnMoveFromEar();
    }

    public void setVolumeControl(final int stream) {
        activity.setVolumeControlStream(stream);
    }

    public void setOnContactPictureClicked(OnContactPictureClicked listener) {
        this.mOnContactPictureClickedListener = listener;
    }

    public void setOnContactPictureLongClicked(
            OnContactPictureLongClicked listener) {
        this.mOnContactPictureLongClickedListener = listener;
    }

    public Activity getActivity() {
        return activity;
    }

    @Override
    public int getViewTypeCount() {
        return 5;
    }

    private int getItemViewType(Message message) {
        if (message.getType() == Message.TYPE_STATUS) {
            if (DATE_SEPARATOR_BODY.equals(message.getBody())) {
                return DATE_SEPARATOR;
            } else {
                return STATUS;
            }
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            return RTP_SESSION;
        } else if (message.getStatus() <= Message.STATUS_RECEIVED) {
            return RECEIVED;
        } else {
            return SENT;
        }
    }

    @Override
    public int getItemViewType(int position) {
        return this.getItemViewType(getItem(position));
    }

    private void displayStatus(ViewHolder viewHolder, final Message message, int type, boolean darkBackground) {
        String filesize = null;
        String info = null;
        boolean error = false;
        viewHolder.user.setText(UIHelper.getDisplayedMucCounterpart(message.getCounterpart()));
        if (viewHolder.indicatorReceived != null) {
            viewHolder.indicatorReceived.setVisibility(View.GONE);
        }
        if (viewHolder.edit_indicator != null) {
            if (message.edited()) {
                viewHolder.edit_indicator.setVisibility(View.VISIBLE);
                viewHolder.edit_indicator.setImageResource(darkBackground ? R.drawable.ic_mode_edit_white_18dp : R.drawable.ic_mode_edit_black_18dp);
                viewHolder.edit_indicator.setAlpha(darkBackground ? 0.7f : 0.57f);
            } else {
                viewHolder.edit_indicator.setVisibility(View.GONE);
            }
        }
        final Transferable transferable = message.getTransferable();
        boolean multiReceived = message.getConversation().getMode() == Conversation.MODE_MULTI
                && message.getMergedStatus() <= Message.STATUS_RECEIVED;
        boolean singleReceived = message.getConversation().getMode() == Conversation.MODE_SINGLE
                && message.getMergedStatus() <= Message.STATUS_RECEIVED;
        if (message.isFileOrImage() || transferable != null || MessageUtils.unInitiatedButKnownSize(message)) {
            FileParams params = message.getFileParams();
            filesize = params.size > 0 ? UIHelper.filesizeToString(params.size) : null;
            if (transferable != null && (transferable.getStatus() == Transferable.STATUS_FAILED || transferable.getStatus() == Transferable.STATUS_CANCELLED)) {
                error = true;
            }
        }
        switch (message.getMergedStatus()) {
            case Message.STATUS_WAITING:
                info = getContext().getString(R.string.waiting);
                break;
            case Message.STATUS_UNSEND:
                if (transferable != null) {
                    info = getContext().getString(R.string.sending);
                    showProgress(viewHolder, transferable);
                } else {
                    info = getContext().getString(R.string.sending);
                }
                break;
            case Message.STATUS_OFFERED:
                info = getContext().getString(R.string.offering);
                break;
            case Message.STATUS_SEND_RECEIVED:
                if (mIndicateReceived) {
                    if (viewHolder.indicatorReceived != null) {
                        viewHolder.indicatorReceived.setVisibility(View.VISIBLE);
                        viewHolder.indicatorReceived.setImageResource(darkBackground ? R.drawable.ic_check_white_18dp : R.drawable.ic_check_black_18dp);
                        viewHolder.indicatorReceived.setAlpha(darkBackground ? 0.7f : 0.57f);
                    }
                } else {
                    viewHolder.indicatorReceived.setVisibility(View.GONE);
                }
                break;
            case Message.STATUS_SEND_DISPLAYED:
                if (mIndicateReceived) {
                    if (viewHolder.indicatorReceived != null) {
                        viewHolder.indicatorReceived.setVisibility(View.VISIBLE);
                        viewHolder.indicatorReceived.setImageResource(darkBackground ? R.drawable.ic_check_all_white_18dp : R.drawable.ic_check_all_black_18dp);
                        viewHolder.indicatorReceived.setAlpha(darkBackground ? 0.7f : 0.57f);
                    }
                } else {
                    viewHolder.indicatorReceived.setVisibility(View.GONE);
                }
                break;
            case Message.STATUS_SEND_FAILED:
                DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
                if (isResendable && file.exists()) {
                    info = getContext().getString(R.string.send_failed_resend);
                } else {
                    final String errorMessage = message.getErrorMessage();
                    if (Message.ERROR_MESSAGE_CANCELLED.equals(errorMessage)) {
                        info = getContext().getString(R.string.cancelled);
                    } else {
                        if (errorMessage != null) {
                            final String[] errorParts = errorMessage.split("\\u001f", 2);
                            if (errorParts.length == 2) {
                                switch (errorParts[0]) {
                                    case "file-too-large":
                                        info = getContext().getString(R.string.file_too_large);
                                        break;
                                    default:
                                        info = getContext().getString(R.string.send_failed);
                                        break;
                                }
                            } else {
                                info = getContext().getString(R.string.send_failed);
                            }
                        } else {
                            info = getContext().getString(R.string.send_failed);
                        }
                    }
                }
                error = true;
                break;
            default:
                if (multiReceived) {
                    final int shadowSize = 10;
                    viewHolder.username.setVisibility(View.VISIBLE);
                    viewHolder.username.setText(UIHelper.getColoredUsername(activity.xmppConnectionService, message));
                    if (activity.xmppConnectionService.colored_muc_names() && ThemeHelper.showColoredUsernameBackGround(activity, darkBackground)) {
                        viewHolder.username.setPadding(4, 2, 4, 2);
                        viewHolder.username.setBackground(ContextCompat.getDrawable(activity, R.drawable.duration_background));
                    }
                }
                if (singleReceived) {
                    viewHolder.username.setVisibility(View.GONE);
                }
                break;
        }
        if (error && type == SENT) {
            if (darkBackground) {
                viewHolder.time.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_Warning_OnDark);
            } else {
                viewHolder.time.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_Warning);
            }
            DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
            if (file.exists()) {
                if (activity.xmppConnectionService.mHttpConnectionManager.getAutoAcceptFileSize() >= message.getFileParams().size && (transferable != null && transferable.getStatus() == Transferable.STATUS_FAILED)) {
                    isResendable = true;
                    viewHolder.resend_button.setVisibility(View.GONE);
                } else {
                    isResendable = false;
                    viewHolder.resend_button.setVisibility(View.GONE);
                    /*
                    viewHolder.resend_button.setVisibility(View.VISIBLE);
                    viewHolder.resend_button.setText(R.string.send_again);
                    viewHolder.resend_button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_resend_grey600_48dp, 0, 0, 0);
                    viewHolder.resend_button.setOnClickListener(v -> mConversationFragment.resendMessage(message));
                    */
                }
            } else {
                isResendable = false;
                viewHolder.resend_button.setVisibility(View.GONE);
            }
        } else {
            if (darkBackground) {
                viewHolder.time.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_OnDark);
            } else {
                viewHolder.time.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption);
            }
            viewHolder.time.setTextColor(ThemeHelper.getMessageTextColor(getContext(), darkBackground, false));
        }
        if (!error && type == SENT) {
            viewHolder.resend_button.setVisibility(View.GONE);
        }
        if (message.getEncryption() == Message.ENCRYPTION_NONE) {
            viewHolder.indicator.setVisibility(View.GONE);
        } else {
            boolean verified = false;
            if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                final FingerprintStatus status = message.getConversation().getAccount().getAxolotlService().getFingerprintTrust(message.getFingerprint());
                if (status != null && status.isVerified()) {
                    verified = true;
                }
            }
            if (verified) {
                viewHolder.indicator.setImageResource(darkBackground ? R.drawable.ic_verified_user_white_18dp : R.drawable.ic_verified_user_black_18dp);
            } else {
                viewHolder.indicator.setImageResource(darkBackground ? R.drawable.ic_lock_white_18dp : R.drawable.ic_lock_black_18dp);
            }
            if (darkBackground) {
                viewHolder.indicator.setAlpha(0.7f);
            } else {
                viewHolder.indicator.setAlpha(0.57f);
            }
            viewHolder.indicator.setVisibility(View.VISIBLE);
        }

        final String formattedTime = UIHelper.readableTimeDifferenceFull(getContext(), message.getMergedTimeSent());
        final String bodyLanguage = message.getBodyLanguage();
        final String bodyLanguageInfo = bodyLanguage == null ? "" : String.format(" \u00B7 %s", bodyLanguage.toUpperCase(Locale.US));
        if (message.getStatus() <= Message.STATUS_RECEIVED) {
            if ((filesize != null) && (info != null)) {
                viewHolder.time.setText(formattedTime + " \u00B7 " + filesize + " \u00B7 " + info + bodyLanguageInfo);
            } else if ((filesize == null) && (info != null)) {
                viewHolder.time.setText(formattedTime + " \u00B7 " + info + bodyLanguageInfo);
            } else if ((filesize != null) && (info == null)) {
                viewHolder.time.setText(formattedTime + " \u00B7 " + filesize + bodyLanguageInfo);
            } else {
                viewHolder.time.setText(formattedTime + bodyLanguageInfo);
            }
        } else {
            if ((filesize != null) && (info != null)) {
                viewHolder.time.setText(filesize + " \u00B7 " + info + bodyLanguageInfo);
            } else if ((filesize == null) && (info != null)) {
                if (error) {
                    viewHolder.time.setText(info + " \u00B7 " + formattedTime + bodyLanguageInfo);
                } else {
                    viewHolder.time.setText(info);
                }
            } else if ((filesize != null) && (info == null)) {
                viewHolder.time.setText(filesize + " \u00B7 " + formattedTime + bodyLanguageInfo);
            } else {
                viewHolder.time.setText(formattedTime + bodyLanguageInfo);
            }
        }
    }

    private void displayInfoMessage(ViewHolder viewHolder, CharSequence text, boolean darkBackground, Message message) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        viewHolder.messageBody.setText(text);
        showProgress(viewHolder, message.getTransferable());
        if (darkBackground) {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1_Secondary_OnDark);
        } else {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1_Secondary);
        }
        viewHolder.messageBody.setTextIsSelectable(false);
    }

    private void showProgress(final ViewHolder viewHolder, final Transferable transferable) {
        if (transferable != null) {
            if (transferable.getStatus() == Transferable.STATUS_DOWNLOADING || transferable.getStatus() == Transferable.STATUS_UPLOADING || transferable.getStatus() == Transferable.STATUS_WAITING) {
                viewHolder.progressBar.setVisibility(View.VISIBLE);
                viewHolder.progressBar.setProgress(transferable.getProgress());
            } else {
                viewHolder.progressBar.setVisibility(View.GONE);
            }
        } else {
            viewHolder.progressBar.setVisibility(View.GONE);
        }
    }

    private void displayEmojiMessage(final ViewHolder viewHolder, final String body, final boolean darkBackground) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        if (darkBackground) {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1_Emoji_OnDark);
        } else {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1_Emoji);
        }
        final Spannable span = new SpannableString(body);
        final float size = Emoticons.isEmoji(body) ? 3.0f : 2.0f;
        span.setSpan(new RelativeSizeSpan(size), 0, body.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.messageBody.setText(EmojiWrapper.transform(span));
    }

    private void displayXmppMessage(final ViewHolder viewHolder, final String body) {
        String contact = body.toLowerCase();
        contact = contact.split(":")[1];
        boolean group;
        try {
            group = ((contact.split("\\?")[1]) != null && (contact.split("\\?")[1]).length() > 0 && (contact.split("\\?")[1]).equalsIgnoreCase("join"));
        } catch (Exception e) {
            group = false;
        }
        contact = contact.split("\\?")[0];
        final String add_contact = activity.getString(R.string.add_to_contact_list) + " (" + contact + ")";
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(add_contact);
        if (group) {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_account_multiple_plus_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        } else {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_account_plus_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        }
        viewHolder.download_button.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(body));
                activity.startActivity(intent);
                activity.overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
            } catch (Exception e) {
                ToastCompat.makeText(activity, R.string.no_application_found_to_view_contact, ToastCompat.LENGTH_LONG).show();
            }

        });
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.GONE);
    }

    private void applyQuoteSpan(SpannableStringBuilder body, int start, int end, boolean darkBackground) {
        if (start > 1 && !"\n\n".equals(body.subSequence(start - 2, start).toString())) {
            body.insert(start++, "\n");
            body.setSpan(new DividerSpan(false), start - 2, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            end++;
        }
        if (end < body.length() - 1 && !"\n\n".equals(body.subSequence(end, end + 2).toString())) {
            body.insert(end, "\n");
            body.setSpan(new DividerSpan(false), end, end + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int color = ThemeHelper.messageTextColor(activity);
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        body.setSpan(new QuoteSpan(color, metrics), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Applies QuoteSpan to group of lines which starts with > or » characters.
     * Appends likebreaks and applies DividerSpan to them to show a padding between quote and text.
     */
    private boolean handleTextQuotes(SpannableStringBuilder body, boolean darkBackground) {
        boolean startsWithQuote = false;
        char previous = '\n';
        int lineStart = -1;
        int lineTextStart = -1;
        int quoteStart = -1;
        for (int i = 0; i <= body.length(); i++) {
            char current = body.length() > i ? body.charAt(i) : '\n';
            if (lineStart == -1) {
                if (previous == '\n') {
                    if ((current == '>' && UIHelper.isPositionFollowedByQuoteableCharacter(body, i))
                            || current == '\u00bb' && !UIHelper.isPositionFollowedByQuote(body, i)) {
                        // Line start with quote
                        lineStart = i;
                        if (quoteStart == -1) quoteStart = i;
                        if (i == 0) startsWithQuote = true;
                    } else if (quoteStart >= 0) {
                        // Line start without quote, apply spans there
                        applyQuoteSpan(body, quoteStart, i - 1, darkBackground);
                        quoteStart = -1;
                    }
                }
            } else {
                // Remove extra spaces between > and first character in the line
                // > character will be removed too
                if (current != ' ' && lineTextStart == -1) {
                    lineTextStart = i;
                }
                if (current == '\n') {
                    body.delete(lineStart, lineTextStart);
                    i -= lineTextStart - lineStart;
                    if (i == lineStart) {
                        // Avoid empty lines because span over empty line can be hidden
                        body.insert(i++, " ");
                    }
                    lineStart = -1;
                    lineTextStart = -1;
                }
            }
            previous = current;
        }
        if (quoteStart >= 0) {
            // Apply spans to finishing open quote
            applyQuoteSpan(body, quoteStart, body.length(), darkBackground);
        }
        return startsWithQuote;
    }

    private void displayTextMessage(final ViewHolder viewHolder, final Message message, boolean darkBackground, int type) {
        viewHolder.download_button.setVisibility(View.GONE);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        if (darkBackground) {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1_OnDark);
        } else {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1);
        }
        viewHolder.messageBody.setHighlightColor(darkBackground ? type == SENT ? StyledAttributes.getColor(activity, R.attr.colorAccent) : StyledAttributes.getColor(activity, R.attr.colorAccent) : StyledAttributes.getColor(activity, R.attr.colorAccent));
        viewHolder.messageBody.setTypeface(null, Typeface.NORMAL);
        if (message.getBody() != null) {
            final SpannableString nick = UIHelper.getColoredUsername(activity.xmppConnectionService, message);
            SpannableStringBuilder body = new SpannableStringBuilder(replaceYoutube(activity.getApplicationContext(), message.getMergedBody()));
            if (message.getBody().equals(DELETED_MESSAGE_BODY)) {
                body = body.replace(0, DELETED_MESSAGE_BODY.length(), activity.getString(R.string.message_deleted));
            } else if (message.getBody().equals(DELETED_MESSAGE_BODY_OLD)) {
                body = body.replace(0, DELETED_MESSAGE_BODY_OLD.length(), activity.getString(R.string.message_deleted));
            } else {
                boolean hasMeCommand = message.hasMeCommand();
                if (hasMeCommand) {
                    body = body.replace(0, Message.ME_COMMAND.length(), nick);
                }
                if (body.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
                    body = new SpannableStringBuilder(body, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
                    body.append("\u2026");
                }
            final Message.MergeSeparator[] mergeSeparators = body.getSpans(0, body.length(), Message.MergeSeparator.class);
                for (Message.MergeSeparator mergeSeparator : mergeSeparators) {
                    int start = body.getSpanStart(mergeSeparator);
                    int end = body.getSpanEnd(mergeSeparator);
                    body.setSpan(new DividerSpan(true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                final boolean startsWithQuote = handleTextQuotes(body, darkBackground);
                if (!message.isPrivateMessage()) {
                    if (hasMeCommand) {
                        body.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), 0, nick.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else {
                    String privateMarker;
                    if (message.getStatus() <= Message.STATUS_RECEIVED) {
                        privateMarker = activity.getString(R.string.private_message);
                    } else {
                        Jid cp = message.getCounterpart();
                        privateMarker = activity.getString(R.string.private_message_to, Strings.nullToEmpty(cp == null ? null : cp.getResource()));
                    }
                    body.insert(0, privateMarker);
                    final int privateMarkerIndex = privateMarker.length();
                    if (startsWithQuote) {
                        body.insert(privateMarkerIndex, "\n\n");
                        body.setSpan(new DividerSpan(false), privateMarkerIndex, privateMarkerIndex + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        body.insert(privateMarkerIndex, " ");
                    }
                    body.setSpan(new ForegroundColorSpan(ThemeHelper.getMessageTextColorPrivate(activity)), 0, privateMarkerIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    body.setSpan(new StyleSpan(Typeface.BOLD), 0, privateMarkerIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (hasMeCommand) {
                        body.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), privateMarkerIndex + 1, privateMarkerIndex + 1 + nick.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                if (message.getConversation().getMode() == Conversation.MODE_MULTI && message.getStatus() == Message.STATUS_RECEIVED) {
                    if (message.getConversation() instanceof Conversation) {
                        final Conversation conversation = (Conversation) message.getConversation();
                        final Pattern pattern = NotificationService.generateNickHighlightPattern(conversation.getMucOptions().getActualNick());
                        final Matcher matcher = pattern.matcher(body);
                        while (matcher.find()) {
                            body.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                }
                final Matcher matcher = Emoticons.getEmojiPattern(body).matcher(body);
                while (matcher.find()) {
                    if (matcher.start() < matcher.end()) {
                        body.setSpan(new RelativeSizeSpan(1.5f), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                StylingHelper.format(body, viewHolder.messageBody.getCurrentTextColor());
                if (highlightedTerm != null) {
                    StylingHelper.highlight(activity, body, highlightedTerm, StylingHelper.isDarkText(viewHolder.messageBody));
                }
            }
            if (message.isWebUri() || message.getWebUri() != null) {
                displayRichLinkMessage(viewHolder, message, darkBackground);
            }
            MyLinkify.addLinks(body, true);
            viewHolder.messageBody.setAutoLinkMask(0);
            viewHolder.messageBody.setText(EmojiWrapper.transform(body));
            viewHolder.messageBody.setMovementMethod(ClickableMovementMethod.getInstance());
        } else {
            viewHolder.messageBody.setText("");
            viewHolder.messageBody.setTextIsSelectable(false);
        }
    }

    private void displayDownloadableMessage(ViewHolder viewHolder, final Message message, String text, final boolean darkBackground) {
        toggleWhisperInfo(viewHolder, message, false, darkBackground);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(text);
        final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_download_grey600_48dp);
        final Drawable drawable = DrawableCompat.wrap(icon);
        DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
        viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        viewHolder.download_button.setOnClickListener(v -> ConversationFragment.downloadFile(activity, message));
    }

    private void displayOpenableMessage(ViewHolder viewHolder, final Message message, final boolean darkBackground) {
        toggleWhisperInfo(viewHolder, message, false, darkBackground);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        final String mimeType = message.getMimeType();
        if (mimeType != null && message.getMimeType().contains("vcard")) {
            try {
                showVCard(message, viewHolder);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (mimeType != null && message.getMimeType().contains("calendar")) {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_calendar_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message)));
        } else if (mimeType != null && message.getMimeType().equals("application/vnd.android.package-archive")) {
            try {
                showAPK(message, viewHolder);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (mimeType != null && message.getMimeType().contains("video")) {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_video_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message)));
        } else if (mimeType != null && message.getMimeType().contains("image")) {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_image_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message)));
        } else if (mimeType != null && message.getMimeType().contains("audio")) {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_audio_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message)));
        } else {
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_file_grey600_48dp);
            final  Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message)));
        }
        viewHolder.download_button.setOnClickListener(v -> openDownloadable(message));
    }

    private void showAPK(final Message message, final ViewHolder viewHolder) {
        String APKName = "";
        if (message.getFileParams().subject.length() != 0) {
            try {
                byte[] data = Base64.decode(message.getFileParams().subject, Base64.DEFAULT);
                APKName = new String(data, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                APKName = "";
                e.printStackTrace();
            }
        }
        final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_android_grey600_48dp);
        final Drawable drawable = DrawableCompat.wrap(icon);
        DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
        viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message) + APKName));
    }

    private void showVCard(final Message message, ViewHolder viewHolder) {
        String VCardName = "";
        if (message.getFileParams().subject.length() != 0) {
            try {
                byte[] data = Base64.decode(message.getFileParams().subject, Base64.DEFAULT);
                VCardName = new String(data, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                VCardName = "";
                e.printStackTrace();
            }
        }
        final  Drawable icon = activity.getResources().getDrawable(R.drawable.ic_account_card_details_grey600_48dp);
        final  Drawable drawable = DrawableCompat.wrap(icon);
        DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
        viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        viewHolder.download_button.setText(activity.getString(R.string.open_x_file, UIHelper.getFileDescriptionString(activity, message) + VCardName));
    }

    private void displayRichLinkMessage(final ViewHolder viewHolder, final Message message, boolean darkBackground) {
        toggleWhisperInfo(viewHolder, message, true, darkBackground);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        showImages(false, viewHolder);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        String url;
        if (message.isWebUri()) {
            url = removeTrackingParameter(Uri.parse(message.getBody().trim())).toString();
        } else {
            url = removeTrackingParameter(Uri.parse(message.getWebUri())).toString();
        }
        final String link = replaceYoutube(activity.getApplicationContext(), url);
        Log.d(Config.LOGTAG, "Weburi body for preview: " + link);
        final boolean dataSaverDisabled = activity.xmppConnectionService.isDataSaverDisabled();
        viewHolder.richlinkview.setVisibility(mShowLinksInside ? View.VISIBLE : View.GONE);
        if (mShowLinksInside) {
            final int color = ThemeHelper.messageTextColor(activity);
            final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
            final int scaledH;
            if (Math.max(100, 100) * metrics.density <= target) {
                scaledH = (int) (100 * metrics.density);
            } else if (Math.max(100, 100) <= target) {
                scaledH = 100;
            } else {
                scaledH = (int) (100 / ((double) 100 / target));
            }
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(WRAP_CONTENT, scaledH);
            layoutParams.setMargins(0, (int) (metrics.density * 4), 0, (int) (metrics.density * 4));
            viewHolder.richlinkview.setLayoutParams(layoutParams);
            final String weburl;
            if (link.startsWith("http://") || link.startsWith("https://")) {
                weburl = removeTrailingBracket(link);
            } else {
                weburl = "http://" + removeTrailingBracket(link);
            }
            Log.d(Config.LOGTAG, "Weburi for preview: " + weburl);
            viewHolder.richlinkview.setLink(weburl, message.getUuid(), dataSaverDisabled, activity.xmppConnectionService, color, new RichPreview.ViewListener() {

                @Override
                public void onSuccess(boolean status) {
                }

                @Override
                public void onError(Exception e) {
                    e.printStackTrace();
                    viewHolder.richlinkview.setVisibility(View.GONE);
                }
            });
        }
    }

    private void displayLocationMessage(final ViewHolder viewHolder, final Message message, final boolean darkBackground, Activity activity) {
        toggleWhisperInfo(viewHolder, message, false, darkBackground);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        final String url = GeoHelper.MapPreviewUri(message, activity);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        if (mShowMapsInside) {
            showImages(mShowMapsInside, 0, false, viewHolder);
            final double target = activity.getResources().getDimension(R.dimen.image_preview_width);
            final int scaledW;
            final int scaledH;
            if (Math.max(500, 500) * metrics.density <= target) {
                scaledW = (int) (500 * metrics.density);
                scaledH = (int) (500 * metrics.density);
            } else if (Math.max(500, 500) <= target) {
                scaledW = 500;
                scaledH = 500;
            } else {
                scaledW = (int) target;
                scaledH = (int) (500 / ((double) 500 / target));
            }
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(scaledW, scaledH);
            layoutParams.setMargins(0, (int) (metrics.density * 4), 0, (int) (metrics.density * 4));
            viewHolder.images.setLayoutParams(layoutParams);
            viewHolder.image.setOnClickListener(v -> showLocation(message));
            Picasso .get()
                    .load(Uri.parse(url))
                    .placeholder(R.drawable.ic_map_marker_grey600_48dp)
                    .error(R.drawable.ic_map_marker_grey600_48dp)
                    .into(viewHolder.image);
            viewHolder.image.setMaxWidth(500);
            viewHolder.image.setAdjustViewBounds(true);
            viewHolder.download_button.setVisibility(View.GONE);
        } else {
            showImages(false, viewHolder);
            viewHolder.download_button.setVisibility(View.VISIBLE);
            viewHolder.download_button.setText(R.string.show_location);
            final Drawable icon = activity.getResources().getDrawable(R.drawable.ic_map_marker_grey600_48dp);
            final Drawable drawable = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
            viewHolder.download_button.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            viewHolder.download_button.setOnClickListener(v -> showLocation(message));
        }
    }

    private void displayAudioMessage(ViewHolder viewHolder, Message message, boolean darkBackground) {
        toggleWhisperInfo(viewHolder, message, showTitle(message), darkBackground);
        showImages(false, viewHolder);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        final RelativeLayout audioPlayer = viewHolder.audioPlayer;
        audioPlayer.setVisibility(View.VISIBLE);
        AudioPlayer.ViewHolder.get(audioPlayer).setTheme(darkBackground);
        this.audioPlayer.init(audioPlayer, message);
    }

    private boolean showTitle(Message message) {
        boolean show = false;
        if (message.getFileParams().subject.length() != 0) {
            try {
                byte[] data = Base64.decode(message.getFileParams().subject, Base64.DEFAULT);
                show = (new String(data, "UTF-8").length() != 0);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return show;
    }

    private String getTitle(Message message) {
        if (message.getFileParams().subject.length() != 0) {
            try {
                byte[] data = Base64.decode(message.getFileParams().subject, Base64.DEFAULT);
                return new String(data, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return "";
    }

    private void displayMediaPreviewMessage(ViewHolder viewHolder, final Message message, final boolean darkBackground) {
        toggleWhisperInfo(viewHolder, message, false, darkBackground);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.richlinkview.setVisibility(View.GONE);
        viewHolder.progressBar.setVisibility(View.GONE);
        final DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
        if (!file.exists()) {
            ToastCompat.makeText(activity, R.string.file_deleted, ToastCompat.LENGTH_SHORT).show();
            return;
        }
        final String mime = file.getMimeType();
        final boolean isGif = mime != null && mime.equals("image/gif");
        final int mediaRuntime = message.getFileParams().runtime;
        if (isGif && mPlayGifInside) {
            showImages(true, mediaRuntime, true, viewHolder);
            Log.d(Config.LOGTAG, "Gif Image file");
            final FileParams params = message.getFileParams();
            final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
            final int scaledW;
            final int scaledH;
            if (Math.max(params.height, params.width) * metrics.density <= target) {
                scaledW = (int) (params.width * metrics.density);
                scaledH = (int) (params.height * metrics.density);
            } else if (Math.max(params.height, params.width) <= target) {
                scaledW = params.width;
                scaledH = params.height;
            } else if (params.width <= params.height) {
                scaledW = (int) (params.width / ((double) params.height / target));
                scaledH = (int) target;
            } else {
                scaledW = (int) target;
                scaledH = (int) (params.height / ((double) params.width / target));
            }
            final LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(scaledW, scaledH);
            layoutParams.setMargins(0, (int) (metrics.density * 4), 0, (int) (metrics.density * 4));
            viewHolder.images.setLayoutParams(layoutParams);
            activity.loadGif(file, viewHolder.gifImage);
            viewHolder.gifImage.setOnClickListener(v -> openDownloadable(message));
        } else {
            showImages(true, mediaRuntime, false, viewHolder);
            FileParams params = message.getFileParams();
            final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
            final int scaledW;
            final int scaledH;
            if (Math.max(params.height, params.width) * metrics.density <= target) {
                scaledW = (int) (params.width * metrics.density);
                scaledH = (int) (params.height * metrics.density);
            } else if (Math.max(params.height, params.width) <= target) {
                scaledW = params.width;
                scaledH = params.height;
            } else if (params.width <= params.height) {
                scaledW = (int) (params.width / ((double) params.height / target));
                scaledH = (int) target;
            } else {
                scaledW = (int) target;
                scaledH = (int) (params.height / ((double) params.width / target));
            }
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(scaledW, scaledH);
            layoutParams.setMargins(0, (int) (metrics.density * 4), 0, (int) (metrics.density * 4));
            viewHolder.images.setLayoutParams(layoutParams);
            activity.loadBitmap(message, viewHolder.image);
            viewHolder.image.setOnClickListener(v -> openDownloadable(message));
        }
    }

    private void showImages(final boolean show, final ViewHolder viewHolder) {
        showImages(show, 0, false, viewHolder);
    }

    private void showImages(final boolean show, final int duration, final boolean isGif, final ViewHolder viewHolder) {
        boolean hasDuration = duration > 0;
        if (show) {
            viewHolder.images.setVisibility(View.VISIBLE);
            if (hasDuration) {
                viewHolder.mediaduration.setVisibility(View.VISIBLE);
                viewHolder.mediaduration.setText(formatTime(safeLongToInt(duration)));
            } else {
                viewHolder.mediaduration.setVisibility(View.GONE);
            }
            if (isGif) {
                viewHolder.image.setVisibility(View.GONE);
                viewHolder.gifImage.setVisibility(View.VISIBLE);
            } else {
                viewHolder.image.setVisibility(View.VISIBLE);
                viewHolder.gifImage.setVisibility(View.GONE);
            }
        } else {
            viewHolder.images.setVisibility(View.GONE);
            viewHolder.image.setVisibility(View.GONE);
            viewHolder.gifImage.setVisibility(View.GONE);
            viewHolder.mediaduration.setVisibility(View.GONE);
        }
    }

    private void toggleWhisperInfo(ViewHolder viewHolder, final Message message,
                                   final boolean includeBody, final boolean darkBackground) {
        SpannableStringBuilder messageBody = new SpannableStringBuilder(replaceYoutube(activity.getApplicationContext(), message.getBody()));

        final String mimeType = message.getMimeType();
        if (mimeType != null && message.getMimeType().contains("audio")) {
            messageBody.clear();
            messageBody.append(getTitle(message));
        }
        Editable body;
        if (darkBackground) {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1_OnDark);
        } else {
            viewHolder.messageBody.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Body1);
        }
        if (message.isPrivateMessage()) {
            final String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker = activity.getString(R.string.private_message_to, Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            body = new SpannableStringBuilder(privateMarker);
            viewHolder.messageBody.setVisibility(View.VISIBLE);
            if (includeBody) {
                body.append("\n");
                body.append(messageBody);
            }
            body.setSpan(new ForegroundColorSpan(ThemeHelper.getMessageTextColorPrivate(activity)), 0, privateMarker.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(new StyleSpan(Typeface.BOLD), 0, privateMarker.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            MyLinkify.addLinks(body, false);
            viewHolder.messageBody.setAutoLinkMask(0);
            viewHolder.messageBody.setText(body);
            viewHolder.messageBody.setTextIsSelectable(true);
            viewHolder.messageBody.setMovementMethod(ClickableMovementMethod.getInstance());
        } else {
            if (includeBody) {
                viewHolder.messageBody.setVisibility(View.VISIBLE);
                body = new SpannableStringBuilder(messageBody);
                MyLinkify.addLinks(body, false);
                viewHolder.messageBody.setAutoLinkMask(0);
                viewHolder.messageBody.setText(body);
                viewHolder.messageBody.setTextIsSelectable(true);
                viewHolder.messageBody.setMovementMethod(ClickableMovementMethod.getInstance());
            } else {
                viewHolder.messageBody.setVisibility(View.GONE);
            }
        }
    }

    private void loadMoreMessages(Conversation conversation) {
        conversation.setLastClearHistory(0, null);
        activity.xmppConnectionService.updateConversation(conversation);
        conversation.setHasMessagesLeftOnServer(true);
        conversation.setFirstMamReference(null);
        long timestamp = conversation.getLastMessageTransmitted().getTimestamp();
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        conversation.messagesLoaded.set(true);
        MessageArchiveService.Query query = activity.xmppConnectionService.getMessageArchiveService().query(conversation, new MamReference(0), timestamp, false);
        if (query != null) {
            ToastCompat.makeText(activity, R.string.fetching_history_from_server, ToastCompat.LENGTH_LONG).show();
        } else {
            ToastCompat.makeText(activity, R.string.not_fetching_history_retention_period, ToastCompat.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public View getView(int position, View view, ViewGroup parent) {
        final Message message = getItem(position);
        final boolean omemoEncryption = message.getEncryption() == Message.ENCRYPTION_AXOLOTL;
        final boolean isInValidSession = message.isValidInSession() && (!omemoEncryption || message.isTrusted());
        final Conversational conversation = message.getConversation();
        final Account account = conversation.getAccount();
        final int type = getItemViewType(position);
        ViewHolder viewHolder;
        if (view == null) {
            viewHolder = new ViewHolder();
            switch (type) {
                case DATE_SEPARATOR:
                    view = activity.getLayoutInflater().inflate(R.layout.message_date_bubble, parent, false);
                    viewHolder.status_message = view.findViewById(R.id.status_message);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    break;
                case RTP_SESSION:
                    view = activity.getLayoutInflater().inflate(R.layout.message_rtp_session, parent, false);
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    break;
                case SENT:
                    view = activity.getLayoutInflater().inflate(R.layout.message_sent, parent, false);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.resend_button = view.findViewById(R.id.resend_button);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.images = view.findViewById(R.id.images);
                    viewHolder.mediaduration = view.findViewById(R.id.media_duration);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.gifImage = view.findViewById(R.id.message_image_gif);
                    viewHolder.richlinkview = view.findViewById(R.id.richLinkView);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.user = view.findViewById(R.id.message_user);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.progressBar = view.findViewById(R.id.progressBar);
                    break;
                case RECEIVED:
                    view = activity.getLayoutInflater().inflate(R.layout.message_received, parent, false);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.username = view.findViewById(R.id.username);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.answer_button = view.findViewById(R.id.answer);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.images = view.findViewById(R.id.images);
                    viewHolder.mediaduration = view.findViewById(R.id.media_duration);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.gifImage = view.findViewById(R.id.message_image_gif);
                    viewHolder.richlinkview = view.findViewById(R.id.richLinkView);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.user = view.findViewById(R.id.message_user);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.encryption = view.findViewById(R.id.message_encryption);
                    viewHolder.progressBar = view.findViewById(R.id.progressBar);
                    break;
                case STATUS:
                    view = activity.getLayoutInflater().inflate(R.layout.message_status, parent, false);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.status_message = view.findViewById(R.id.status_message);
                    viewHolder.load_more_messages = view.findViewById(R.id.load_more_messages);
                    break;
                default:
                    throw new AssertionError("Unknown view type");
            }
            view.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) view.getTag();
            if (viewHolder == null) {
                return view;
            }
        }

        boolean darkBackground = activity.isDarkTheme();

        if (type == DATE_SEPARATOR) {
            if (UIHelper.today(message.getTimeSent())) {
                viewHolder.status_message.setText(R.string.today);
            } else if (UIHelper.yesterday(message.getTimeSent())) {
                viewHolder.status_message.setText(R.string.yesterday);
            } else {
                viewHolder.status_message.setText(DateUtils.formatDateTime(activity, message.getTimeSent(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
            }
            viewHolder.message_box.setBackgroundResource(darkBackground ? R.drawable.date_bubble_dark : R.drawable.date_bubble);
            return view;
        } else if (type == RTP_SESSION) {
            final boolean isDarkTheme = activity.isDarkTheme();
            final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
            final String formattedTime = UIHelper.readableTimeDifferenceFull(activity, message.getMergedTimeSent());
            final RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
            final long duration = rtpSessionStatus.duration;
            if (received) {
                if (duration > 0) {
                    viewHolder.status_message.setText(activity.getString(R.string.incoming_call_duration, formattedTime, TimeFrameUtils.resolve(activity, duration)));
                } else {
                    viewHolder.status_message.setText(activity.getString(R.string.missed_call_timestamp, formattedTime));
                }
            } else {
                if (duration > 0) {
                    viewHolder.status_message.setText(activity.getString(R.string.outgoing_call_duration, formattedTime, TimeFrameUtils.resolve(activity, duration)));
                } else {
                    viewHolder.status_message.setText(activity.getString(R.string.outgoing_call_time, formattedTime));
                }
            }
            viewHolder.indicatorReceived.setImageResource(RtpSessionStatus.getDrawable(received, rtpSessionStatus.successful, isDarkTheme));
            viewHolder.indicatorReceived.setAlpha(isDarkTheme ? 0.7f : 0.57f);
            viewHolder.message_box.setBackgroundResource(darkBackground ? R.drawable.date_bubble_dark : R.drawable.date_bubble);
            return view;
        } else if (type == STATUS) {
            if ("LOAD_MORE".equals(message.getBody())) {
                viewHolder.status_message.setVisibility(View.GONE);
                viewHolder.contact_picture.setVisibility(View.GONE);
                viewHolder.load_more_messages.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setOnClickListener(v -> loadMoreMessages((Conversation) message.getConversation()));
            } else {
                viewHolder.status_message.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setVisibility(View.GONE);
                viewHolder.status_message.setText(EmojiWrapper.transform(message.getBody()));
                boolean showAvatar;
                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                    showAvatar = true;
                    AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar_on_status_message);
                } else if (message.getCounterpart() != null || message.getTrueCounterpart() != null || (message.getCounterparts() != null && message.getCounterparts().size() > 0)) {
                    showAvatar = true;
                    AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar_on_status_message);
                } else {
                    showAvatar = false;
                }
                if (showAvatar) {
                    viewHolder.contact_picture.setAlpha(0.5f);
                    viewHolder.contact_picture.setVisibility(View.VISIBLE);
                } else {
                    viewHolder.contact_picture.setVisibility(View.GONE);
                }
            }
            return view;
        } else {
            AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar);
        }

        resetClickListener(viewHolder.message_box, viewHolder.messageBody);

        viewHolder.contact_picture.setOnClickListener(v -> {
            if (MessageAdapter.this.mOnContactPictureClickedListener != null) {
                MessageAdapter.this.mOnContactPictureClickedListener.onContactPictureClicked(message);
            }

        });
        viewHolder.contact_picture.setOnLongClickListener(v -> {
            if (MessageAdapter.this.mOnContactPictureLongClickedListener != null) {
                MessageAdapter.this.mOnContactPictureLongClickedListener.onContactPictureLongClicked(v, message);
                return true;
            } else {
                return false;
            }
        });

        final Transferable transferable = message.getTransferable();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);
        if (unInitiatedButKnownSize || message.isFileDeleted() || (transferable != null && transferable.getStatus() != Transferable.STATUS_UPLOADING)) {
            if (unInitiatedButKnownSize || transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER) {
                displayDownloadableMessage(viewHolder, message, activity.getString(R.string.download_x_file, UIHelper.getFileDescriptionString(activity, message)), darkBackground);
            } else if (transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                displayDownloadableMessage(viewHolder, message, activity.getString(R.string.check_x_filesize, UIHelper.getFileDescriptionString(activity, message)), darkBackground);
            } else {
                if (checkFileExistence(message, view, viewHolder)) {
                    markFileExisting(message);
                } else {
                    displayInfoMessage(viewHolder, UIHelper.getMessagePreview(activity, message).first, darkBackground, message);
                }
            }
        } else if (message.isFileOrImage() && message.getEncryption() != Message.ENCRYPTION_PGP && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (message.getFileParams().width > 0 && message.getFileParams().height > 0) {
                displayMediaPreviewMessage(viewHolder, message, darkBackground);
            } else if (message.getFileParams().runtime > 0 && (message.getFileParams().width == 0 && message.getFileParams().height == 0)) {
                displayAudioMessage(viewHolder, message, darkBackground);
            } else {
                displayOpenableMessage(viewHolder, message, darkBackground);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            if (account.isPgpDecryptionServiceConnected()) {
                if (conversation instanceof Conversation && !account.hasPendingPgpIntent((Conversation) conversation)) {
                    displayInfoMessage(viewHolder, activity.getString(R.string.message_decrypting), darkBackground, message);
                } else {
                    displayInfoMessage(viewHolder, activity.getString(R.string.pgp_message), darkBackground, message);
                }
            } else {
                displayInfoMessage(viewHolder, activity.getString(R.string.install_openkeychain), darkBackground, message);
                viewHolder.message_box.setOnClickListener(this::promptOpenKeychainInstall);
                viewHolder.messageBody.setOnClickListener(this::promptOpenKeychainInstall);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            displayInfoMessage(viewHolder, activity.getString(R.string.decryption_failed), darkBackground, message);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            displayInfoMessage(viewHolder, activity.getString(R.string.not_encrypted_for_this_device), darkBackground, message);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            displayInfoMessage(viewHolder, activity.getString(R.string.omemo_decryption_failed), darkBackground, message);
        } else {
            if (message.isGeoUri()) {
                displayLocationMessage(viewHolder, message, darkBackground, activity);
            } else if (message.bodyIsOnlyEmojis() && message.getType() != Message.TYPE_PRIVATE) {
                displayEmojiMessage(viewHolder, message.getBody().trim(), darkBackground);
            } else if (message.isXmppUri()) {
                displayXmppMessage(viewHolder, message.getBody().trim());
            } else if (message.treatAsDownloadable()) {
                try {
                    final URI uri = new URI(message.getBody());
                        displayDownloadableMessage(viewHolder,
                                message,
                                activity.getString(R.string.check_x_filesize_on_host,
                                        UIHelper.getFileDescriptionString(activity, message),
                                        uri.getHost()),
                                darkBackground);
                } catch (Exception e) {
                    displayDownloadableMessage(viewHolder,
                            message,
                            activity.getString(R.string.check_x_filesize,
                                    UIHelper.getFileDescriptionString(activity, message)),
                            darkBackground);
                }
            } else {
                displayTextMessage(viewHolder, message, darkBackground, type);
            }
        }

        if (type == RECEIVED) {
            if (message.isPrivateMessage()) {
                viewHolder.answer_button.setVisibility(View.VISIBLE);
                Drawable icon = activity.getResources().getDrawable(R.drawable.ic_reply_circle_black_24dp);
                Drawable drawable = DrawableCompat.wrap(icon);
                DrawableCompat.setTint(drawable, StyledAttributes.getColor(getContext(), R.attr.colorAccent));
                viewHolder.answer_button.setImageDrawable(drawable);
                viewHolder.answer_button.setOnClickListener(v -> {
                    try {
                        if (activity instanceof ConversationsActivity) {
                            ConversationFragment conversationFragment = ConversationFragment.get(activity);
                            if (conversationFragment != null) {
                                activity.invalidateOptionsMenu();
                                conversationFragment.privateMessageWith(message.getCounterpart());
                            }
                        }
                    } catch (Exception e) {
                        viewHolder.answer_button.setVisibility(View.GONE);
                        e.printStackTrace();
                    }
                });
            } else {
                viewHolder.answer_button.setVisibility(View.GONE);
            }
            if (isInValidSession) {
                setBubbleBackgroundColor(viewHolder.message_box, type, message.isPrivateMessage(), isInValidSession);
                viewHolder.encryption.setVisibility(View.GONE);
                viewHolder.encryption.setTextColor(ThemeHelper.getMessageTextColor(activity, darkBackground, false));
            } else {
                setBubbleBackgroundColor(viewHolder.message_box, type, message.isPrivateMessage(), isInValidSession);
                viewHolder.encryption.setVisibility(View.VISIBLE);
                viewHolder.encryption.setTextColor(ThemeHelper.getWarningTextColor(activity, darkBackground));
                if (omemoEncryption && !message.isTrusted()) {
                    viewHolder.encryption.setText(R.string.not_trusted);
                } else {
                    viewHolder.encryption.setText(CryptoHelper.encryptionTypeToText(message.getEncryption()));
                }
            }
        }

        if (type == SENT) {
            setBubbleBackgroundColor(viewHolder.message_box, type, message.isPrivateMessage(), isInValidSession);
        }
        displayStatus(viewHolder, message, type, darkBackground);
        return view;
    }

    private void markFileExisting(Message message) {
        Log.d(Config.LOGTAG, "Found and restored orphaned file " + message.getRelativeFilePath());
        message.setFileDeleted(false);
        activity.xmppConnectionService.updateMessage(message, false);
        activity.xmppConnectionService.updateConversation((Conversation) message.getConversation());
    }

    private boolean checkFileExistence(Message message, View view, ViewHolder viewHolder) {
        final Rect scrollBounds = new Rect();
        view.getHitRect(scrollBounds);
        if (message.isFileDeleted() && viewHolder.messageBody.getLocalVisibleRect(scrollBounds)) {
            return activity.xmppConnectionService.getFileBackend().getFile(message).exists();
        } else {
            return false;
        }
    }

    private void promptOpenKeychainInstall(View view) {
        activity.showInstallPgpDialog();
    }

    public FileBackend getFileBackend() {
        return activity.xmppConnectionService.getFileBackend();
    }

    public void stopAudioPlayer() {
        audioPlayer.stop();
    }

    public void unregisterListenerInAudioPlayer() {
        audioPlayer.unregisterListener();
    }

    public void startStopPending() {
        audioPlayer.startStopPending();
    }

    public void openDownloadable(Message message) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ConversationFragment.registerPendingMessage(activity, message);
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, ConversationsActivity.REQUEST_OPEN_MESSAGE);
            return;
        }
        final DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
        ViewUtil.view(activity, file);
    }

    private void showLocation(Message message) {
        if (activity.xmppConnectionService.webViewAvailable()) {
            for (Intent intent : GeoHelper.createGeoIntentsFromMessage(this.getContext(), message)) {
                if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                    getContext().startActivity(intent);
                    activity.overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return;
                }
            }
        } else {
            ToastCompat.makeText(activity, R.string.webview_not_available, ToastCompat.LENGTH_LONG).show();
        }
    }

    public void updatePreferences() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(activity);
        this.mIndicateReceived = p.getBoolean("indicate_received", activity.getResources().getBoolean(R.bool.indicate_received));
        this.mPlayGifInside = p.getBoolean(PLAY_GIF_INSIDE, activity.getResources().getBoolean(R.bool.play_gif_inside));
        this.mShowLinksInside = p.getBoolean(SHOW_LINKS_INSIDE, activity.getResources().getBoolean(R.bool.show_links_inside));
        this.mShowMapsInside = p.getBoolean(SHOW_MAPS_INSIDE, activity.getResources().getBoolean(R.bool.show_maps_inside));
    }

    public void setHighlightedTerm(List<String> terms) {
        this.highlightedTerm = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    public interface OnQuoteListener {
        void onQuote(String text, String user);
    }

    public interface OnContactPictureClicked {
        void onContactPictureClicked(Message message);
    }

    public interface OnContactPictureLongClicked {
        void onContactPictureLongClicked(View v, Message message);
    }

    private static class ViewHolder {

        public Button load_more_messages;
        public ImageView edit_indicator;
        public RelativeLayout audioPlayer;
        public RelativeLayout images;
        protected LinearLayout message_box;
        protected Button download_button;
        protected Button resend_button;
        protected ImageButton answer_button;
        protected ImageView image;
        protected GifImageView gifImage;
        protected TextView mediaduration;
        protected RichLinkView richlinkview;
        protected ImageView indicator;
        protected ImageView indicatorReceived;
        protected TextView time;
        protected TextView messageBody;
        protected TextView user;
        protected TextView username;
        protected ImageView contact_picture;
        protected TextView status_message;
        protected TextView encryption;
        protected ProgressBar progressBar;
    }

    public void setBubbleBackgroundColor(final View viewHolder, final int type,
                                         final boolean isPrivateMessage, final boolean isInValidSession) {
        if (type == RECEIVED) {
            if (isInValidSession) {
                if (isPrivateMessage) {
                    viewHolder.setBackgroundResource(R.drawable.message_bubble_received_light_private);
                    activity.setBubbleColor(viewHolder, StyledAttributes.getColor(activity, R.attr.color_bubble_light), StyledAttributes.getColor(activity, R.attr.colorAccent));
                } else {
                    viewHolder.setBackgroundResource(R.drawable.message_bubble_received_light);
                    activity.setBubbleColor(viewHolder, StyledAttributes.getColor(activity, R.attr.color_bubble_light), -1);
                }
            } else {
                if (isPrivateMessage) {
                    viewHolder.setBackgroundResource(R.drawable.message_bubble_received_warning_private);
                    activity.setBubbleColor(viewHolder, StyledAttributes.getColor(activity, R.attr.color_bubble_warning), StyledAttributes.getColor(activity, R.attr.colorAccent));
                } else {
                    viewHolder.setBackgroundResource(R.drawable.message_bubble_received_warning);
                    activity.setBubbleColor(viewHolder, StyledAttributes.getColor(activity, R.attr.color_bubble_warning), -1);
                }
            }
        }

        if (type == SENT) {
            if (isPrivateMessage) {
                viewHolder.setBackgroundResource(R.drawable.message_bubble_sent_private);
                activity.setBubbleColor(viewHolder, StyledAttributes.getColor(activity, R.attr.color_bubble_dark), StyledAttributes.getColor(activity, R.attr.colorAccent));
            } else {
                viewHolder.setBackgroundResource(R.drawable.message_bubble_sent);
                activity.setBubbleColor(viewHolder, StyledAttributes.getColor(activity, R.attr.color_bubble_dark), -1);
            }
        }
    }
}