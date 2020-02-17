package de.pixart.messenger.parser;

import android.os.Build;
import android.text.Html;
import android.util.Log;
import android.util.Pair;

import net.java.otr4j.session.Session;
import net.java.otr4j.session.SessionStatus;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.crypto.OtrService;
import de.pixart.messenger.crypto.axolotl.AxolotlService;
import de.pixart.messenger.crypto.axolotl.BrokenSessionException;
import de.pixart.messenger.crypto.axolotl.NotEncryptedForThisDeviceException;
import de.pixart.messenger.crypto.axolotl.OutdatedSenderException;
import de.pixart.messenger.crypto.axolotl.XmppAxolotlMessage;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Bookmark;
import de.pixart.messenger.entities.Contact;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.entities.Conversational;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.entities.MucOptions;
import de.pixart.messenger.entities.Presence;
import de.pixart.messenger.entities.ReadByMarker;
import de.pixart.messenger.entities.ReceiptRequest;
import de.pixart.messenger.entities.ServiceDiscoveryResult;
import de.pixart.messenger.http.HttpConnectionManager;
import de.pixart.messenger.http.P1S3UrlStreamHandler;
import de.pixart.messenger.services.MessageArchiveService;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.Namespace;
import de.pixart.messenger.xml.Element;
import de.pixart.messenger.xml.LocalizedContent;
import de.pixart.messenger.xmpp.InvalidJid;
import de.pixart.messenger.xmpp.OnMessagePacketReceived;
import de.pixart.messenger.xmpp.chatstate.ChatState;
import de.pixart.messenger.xmpp.pep.Avatar;
import de.pixart.messenger.xmpp.stanzas.MessagePacket;
import rocks.xmpp.addr.Jid;

import static de.pixart.messenger.entities.Message.DELETED_MESSAGE_BODY;

public class MessageParser extends AbstractParser implements OnMessagePacketReceived {

    private static final List<String> CLIENTS_SENDING_HTML_IN_OTR = Arrays.asList("Pidgin", "Adium", "Trillian");
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);

    public MessageParser(XmppConnectionService service) {
        super(service);
    }

    private static String extractStanzaId(Element packet, boolean isTypeGroupChat, Conversation conversation) {
        final Jid by;
        final boolean safeToExtract;
        if (isTypeGroupChat) {
            by = conversation.getJid().asBareJid();
            safeToExtract = conversation.getMucOptions().hasFeature(Namespace.STANZA_IDS);
        } else {
            Account account = conversation.getAccount();
            by = account.getJid().asBareJid();
            safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        }
        return safeToExtract ? extractStanzaId(packet, by) : null;
    }

    private static String extractStanzaId(Element packet, Jid by) {
        for (Element child : packet.getChildren()) {
            if (child.getName().equals("stanza-id")
                    && Namespace.STANZA_IDS.equals(child.getNamespace())
                    && by.equals(InvalidJid.getNullForInvalid(child.getAttributeAsJid("by")))) {
                return child.getAttribute("id");
            }
        }
        return null;
    }

    private static Jid getTrueCounterpart(Element mucUserElement, Jid fallback) {
        final Element item = mucUserElement == null ? null : mucUserElement.findChild("item");
        Jid result = item == null ? null : InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
        return result != null ? result : fallback;
    }

    private static boolean clientMightSendHtml(Account account, Jid from) {
        String resource = from.getResource();
        if (resource == null) {
            return false;
        }
        Presence presence = account.getRoster().getContact(from).getPresences().getPresences().get(resource);
        ServiceDiscoveryResult disco = presence == null ? null : presence.getServiceDiscoveryResult();
        if (disco == null) {
            return false;
        }
        return hasIdentityKnowForSendingHtml(disco.getIdentities());
    }

    private static boolean hasIdentityKnowForSendingHtml(List<ServiceDiscoveryResult.Identity> identities) {
        for (ServiceDiscoveryResult.Identity identity : identities) {
            if (identity.getName() != null) {
                if (CLIENTS_SENDING_HTML_IN_OTR.contains(identity.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean extractChatState(Conversation c, final boolean isTypeGroupChat, final MessagePacket packet) {
        ChatState state = ChatState.parse(packet);
        if (state != null && c != null) {
            final Account account = c.getAccount();
            Jid from = packet.getFrom();
            if (from.asBareJid().equals(account.getJid().asBareJid())) {
                c.setOutgoingChatState(state);
                if (state == ChatState.ACTIVE || state == ChatState.COMPOSING) {
                    mXmppConnectionService.markRead(c);
                    activateGracePeriod(account);
                }
                return false;
            } else {
                if (isTypeGroupChat) {
                    MucOptions.User user = c.getMucOptions().findUserByFullJid(from);
                    if (user != null) {
                        return user.setChatState(state);
                    } else {
                        return false;
                    }
                } else {
                    return c.setIncomingChatState(state);
                }
            }
        }
        return false;
    }

    private Message parseOtrChat(String body, Jid from, String id, Conversation conversation) {
        String presence;
        if (from.isBareJid()) {
            presence = "";
        } else {
            presence = from.getResource();
        }
        if (body.matches("^\\?OTRv\\d{1,2}\\?.*")) {
            conversation.endOtrIfNeeded();
        }
        if (!conversation.hasValidOtrSession()) {
            conversation.startOtrSession(presence, false);
        } else {
            String foreignPresence = conversation.getOtrSession().getSessionID().getUserID();
            if (!foreignPresence.equals(presence)) {
                conversation.endOtrIfNeeded();
                conversation.startOtrSession(presence, false);
            }
        }
        try {
            conversation.setLastReceivedOtrMessageId(id);
            Session otrSession = conversation.getOtrSession();
            body = otrSession.transformReceiving(body);
            SessionStatus status = otrSession.getSessionStatus();
            if (body == null && status == SessionStatus.ENCRYPTED) {
                mXmppConnectionService.onOtrSessionEstablished(conversation);
                return null;
            } else if (body == null && status == SessionStatus.FINISHED) {
                conversation.resetOtrSession();
                mXmppConnectionService.updateConversationUi();
                return null;
            } else if (body == null || (body.isEmpty())) {
                return null;
            }
            if (body.startsWith(CryptoHelper.FILETRANSFER)) {
                String key = body.substring(CryptoHelper.FILETRANSFER.length());
                conversation.setSymmetricKey(CryptoHelper.hexToBytes(key));
                return null;
            }
            if (clientMightSendHtml(conversation.getAccount(), from)) {
                Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": received OTR message from bad behaving client. escaping HTML…");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
                } else {
                    body = Html.fromHtml(body).toString();
                }
            }

            final OtrService otrService = conversation.getAccount().getOtrService();
            Message finishedMessage = new Message(conversation, body, Message.ENCRYPTION_OTR, Message.STATUS_RECEIVED);
            finishedMessage.setFingerprint(otrService.getFingerprint(otrSession.getRemotePublicKey()));
            conversation.setLastReceivedOtrMessageId(null);

            return finishedMessage;
        } catch (Exception e) {
            conversation.resetOtrSession();
            return null;
        }
    }

    private Message parseAxolotlChat(Element axolotlMessage, Jid from, Conversation conversation, int status, final boolean checkedForDuplicates, boolean postpone) {
        final AxolotlService service = conversation.getAccount().getAxolotlService();
        final XmppAxolotlMessage xmppAxolotlMessage;
        try {
            xmppAxolotlMessage = XmppAxolotlMessage.fromElement(axolotlMessage, from.asBareJid());
        } catch (Exception e) {
            Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": invalid omemo message received " + e.getMessage());
            return null;
        }
        if (xmppAxolotlMessage.hasPayload()) {
            final XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintextMessage;
            try {
                plaintextMessage = service.processReceivingPayloadMessage(xmppAxolotlMessage, postpone);
            } catch (BrokenSessionException e) {
                if (checkedForDuplicates) {
                    if (service.trustedOrPreviouslyResponded(from.asBareJid())) {
                        service.reportBrokenSessionException(e, postpone);
                        return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    } else {
                        Log.d(Config.LOGTAG, "ignoring broken session exception because contact was not trusted");
                        return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    }
                } else {
                    Log.d(Config.LOGTAG, "ignoring broken session exception because checkForDuplicates failed");
                    return null;
                }
            } catch (NotEncryptedForThisDeviceException e) {
                return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE, status);
            } catch (OutdatedSenderException e) {
                return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
            }
            if (plaintextMessage != null) {
                Message finishedMessage = new Message(conversation, plaintextMessage.getPlaintext(), Message.ENCRYPTION_AXOLOTL, status);
                finishedMessage.setFingerprint(plaintextMessage.getFingerprint());
                Log.d(Config.LOGTAG, AxolotlService.getLogprefix(finishedMessage.getConversation().getAccount()) + " Received Message with session fingerprint: " + plaintextMessage.getFingerprint());
                return finishedMessage;
            }
        } else {
            Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": received OMEMO key transport message");
            service.processReceivingKeyTransportMessage(xmppAxolotlMessage, postpone);
        }
        return null;
    }

    private Invite extractInvite(Element message) {
        final Element mucUser = message.findChild("x", Namespace.MUC_USER);
        if (mucUser != null) {
            Element invite = mucUser.findChild("invite");
            if (invite != null) {
                String password = mucUser.findChildContent("password");
                Jid from = InvalidJid.getNullForInvalid(invite.getAttributeAsJid("from"));
                Jid room = InvalidJid.getNullForInvalid(message.getAttributeAsJid("from"));
                if (room == null) {
                    return null;
                }
                return new Invite(room, password, false, from);
            }
        }
        final Element conference = message.findChild("x", "jabber:x:conference");
        if (conference != null) {
            Jid from = InvalidJid.getNullForInvalid(message.getAttributeAsJid("from"));
            Jid room = InvalidJid.getNullForInvalid(conference.getAttributeAsJid("jid"));
            if (room == null) {
                return null;
            }
            return new Invite(room, conference.getAttribute("password"), true, from);
        }
        return null;
    }

    private void parseEvent(final Element event, final Jid from, final Account account) {
        Element items = event.findChild("items");
        String node = items == null ? null : items.getAttribute("node");
        if ("urn:xmpp:avatar:metadata".equals(node)) {
            Avatar avatar = Avatar.parseMetadata(items);
            if (avatar != null) {
                avatar.owner = from.asBareJid();
                if (mXmppConnectionService.getFileBackend().isAvatarCached(avatar)) {
                    if (account.getJid().asBareJid().equals(from)) {
                        if (account.setAvatar(avatar.getFilename())) {
                            mXmppConnectionService.databaseBackend.updateAccount(account);
                            mXmppConnectionService.notifyAccountAvatarHasChanged(account);
                        }
                        mXmppConnectionService.getAvatarService().clear(account);
                        mXmppConnectionService.updateConversationUi();
                        mXmppConnectionService.updateAccountUi();
                    } else {
                        Contact contact = account.getRoster().getContact(from);
                        if (contact.setAvatar(avatar)) {
                            mXmppConnectionService.syncRoster(account);
                            mXmppConnectionService.getAvatarService().clear(contact);
                            mXmppConnectionService.updateConversationUi();
                            mXmppConnectionService.updateRosterUi();
                        }
                    }
                } else if (mXmppConnectionService.isDataSaverDisabled()) {
                    mXmppConnectionService.fetchAvatar(account, avatar);
                }
            }
        } else if (Namespace.NICK.equals(node)) {
            final Element i = items.findChild("item");
            final String nick = i == null ? null : i.findChildContent("nick", Namespace.NICK);
            if (nick != null) {
                setNick(account, from, nick);
            }
        } else if (AxolotlService.PEP_DEVICE_LIST.equals(node)) {
            Element item = items.findChild("item");
            Set<Integer> deviceIds = mXmppConnectionService.getIqParser().deviceIds(item);
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received PEP device list " + deviceIds + " update from " + from + ", processing... ");
            AxolotlService axolotlService = account.getAxolotlService();
            axolotlService.registerDevices(from, deviceIds);
        } else if (Namespace.BOOKMARKS.equals(node) && account.getJid().asBareJid().equals(from)) {
            if (account.getXmppConnection().getFeatures().bookmarksConversion()) {
                final Element i = items.findChild("item");
                final Element storage = i == null ? null : i.findChild("storage", Namespace.BOOKMARKS);
                Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                mXmppConnectionService.processBookmarksInitial(account, bookmarks, true);
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": processing bookmark PEP event");
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring bookmark PEP event because bookmark conversion was not detected");
            }
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            final Element retract = items.findChild("retract");
            if (item != null) {
                final Bookmark bookmark = Bookmark.parseFromItem(item, account);
                if (bookmark != null) {
                    account.putBookmark(bookmark);
                    mXmppConnectionService.processModifiedBookmark(bookmark);
                    mXmppConnectionService.updateConversationUi();
                }
            }
            if (retract != null) {
                final Jid id = InvalidJid.getNullForInvalid(retract.getAttributeAsJid("id"));
                if (id != null) {
                    account.removeBookmark(id);
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": deleted bookmark for " + id);
                    mXmppConnectionService.processDeletedBookmark(account, id);
                    mXmppConnectionService.updateConversationUi();
                }
            }
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + " received pubsub notification for node=" + node);
        }
    }

    private void parseDeleteEvent(final Element event, final Jid from, final Account account) {
        final Element delete = event.findChild("delete");
        final String node = delete == null ? null : delete.getAttribute("node");
        if (Namespace.NICK.equals(node)) {
            Log.d(Config.LOGTAG, "parsing nick delete event from " + from);
            setNick(account, from, null);
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            account.setBookmarks(Collections.emptyMap());
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": deleted bookmarks node");
        }
    }

    private void parsePurgeEvent(final Element event, final Jid from, final Account account) {
        final Element purge = event.findChild("purge");
        final String node = purge == null ? null : purge.getAttribute("node");
        if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            account.setBookmarks(Collections.emptyMap());
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": purged bookmarks");
        }
    }

    private void setNick(Account account, Jid user, String nick) {
        if (user.asBareJid().equals(account.getJid().asBareJid())) {
            account.setDisplayName(nick);
        } else {
            Contact contact = account.getRoster().getContact(user);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateAccountUi();
    }

    private boolean handleErrorMessage(Account account, MessagePacket packet) {
        if (packet.getType() == MessagePacket.TYPE_ERROR) {
            Jid from = packet.getFrom();
            if (from != null) {
                Message message = mXmppConnectionService.markMessage(account,
                        from.asBareJid(),
                        packet.getId(),
                        Message.STATUS_SEND_FAILED,
                        extractErrorMessage(packet));
                final Element error = packet.findChild("error");
                final boolean pingWorthyError = error != null && (error.hasChild("not-acceptable") || error.hasChild("remote-server-timeout") || error.hasChild("remote-server-not-found"));
                if (pingWorthyError) {
                    Conversation conversation = mXmppConnectionService.find(account, from);
                    if (conversation != null && conversation.getMode() == Conversational.MODE_MULTI) {
                        if (conversation.getMucOptions().online()) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received ping worthy error for seemingly online muc at " + from);
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                        }
                    }
                }
                if (message != null) {
                    if (message.getEncryption() == Message.ENCRYPTION_OTR) {
                        Conversation conversation = (Conversation) message.getConversation();
                        conversation.endOtrIfNeeded();
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void onMessagePacketReceived(Account account, MessagePacket original) {
        if (handleErrorMessage(account, original)) {
            return;
        }
        final MessagePacket packet;
        Long timestamp = null;
        final boolean isForwarded;
        boolean isCarbon = false;
        String serverMsgId = null;
        final Element fin = original.findChild("fin", MessageArchiveService.Version.MAM_0.namespace);
        if (fin != null) {
            mXmppConnectionService.getMessageArchiveService().processFinLegacy(fin, original.getFrom());
            return;
        }
        final Element result = MessageArchiveService.Version.findResult(original);
        final MessageArchiveService.Query query = result == null ? null : mXmppConnectionService.getMessageArchiveService().findQuery(result.getAttribute("queryid"));
        if (query != null && query.validFrom(original.getFrom())) {
            Pair<MessagePacket, Long> f = original.getForwardedMessagePacket("result", query.version.namespace);
            if (f == null) {
                return;
            }
            timestamp = f.second;
            packet = f.first;
            isForwarded = true;
            serverMsgId = result.getAttribute("id");
            query.incrementMessageCount();
        } else if (query != null) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received mam result from invalid sender");
            return;
        } else if (original.fromServer(account)) {
            Pair<MessagePacket, Long> f;
            f = original.getForwardedMessagePacket("received", "urn:xmpp:carbons:2");
            f = f == null ? original.getForwardedMessagePacket("sent", "urn:xmpp:carbons:2") : f;
            packet = f != null ? f.first : original;
            if (handleErrorMessage(account, packet)) {
                return;
            }
            timestamp = f != null ? f.second : null;
            isCarbon = f != null;
            isForwarded = isCarbon;
        } else {
            packet = original;
            isForwarded = false;
        }

        if (timestamp == null) {
            timestamp = AbstractParser.parseTimestamp(original, AbstractParser.parseTimestamp(packet));
        }
        final LocalizedContent body = packet.getBody();
        final Element mucUserElement = packet.findChild("x", Namespace.MUC_USER);
        final String pgpEncrypted = packet.findChildContent("x", "jabber:x:encrypted");
        final Element replaceElement = packet.findChild("replace", "urn:xmpp:message-correct:0");
        final Element oob = packet.findChild("x", Namespace.OOB);
        final Element xP1S3 = packet.findChild("x", Namespace.P1_S3_FILE_TRANSFER);
        final URL xP1S3url = xP1S3 == null ? null : P1S3UrlStreamHandler.of(xP1S3);
        final String oobUrl = oob != null ? oob.findChildContent("url") : null;
        final String replacementId = replaceElement == null ? null : replaceElement.getAttribute("id");
        final Element axolotlEncrypted = packet.findChildEnsureSingle(XmppAxolotlMessage.CONTAINERTAG, AxolotlService.PEP_PREFIX);
        int status;
        final Jid counterpart;
        final Jid to = packet.getTo();
        final Jid from = packet.getFrom();
        final Element originId = packet.findChild("origin-id", Namespace.STANZA_IDS);
        final String remoteMsgId;
        if (originId != null && originId.getAttribute("id") != null) {
            remoteMsgId = originId.getAttribute("id");
        } else {
            remoteMsgId = packet.getId();
        }
        boolean notify = false;

        if (from == null || !InvalidJid.isValid(from) || !InvalidJid.isValid(to)) {
            Log.e(Config.LOGTAG, "encountered invalid message from='" + from + "' to='" + to + "'");
            return;
        }

        boolean isTypeGroupChat = packet.getType() == MessagePacket.TYPE_GROUPCHAT;
        if (query != null && !query.muc() && isTypeGroupChat) {
            Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": received groupchat (" + from + ") message on regular MAM request. skipping");
            return;
        }
        boolean isProperlyAddressed = (to != null) && (!to.isBareJid() || account.countPresences() == 0);
        boolean isMucStatusMessage = InvalidJid.hasValidFrom(packet) && from.isBareJid() && mucUserElement != null && mucUserElement.hasChild("status");
        boolean selfAddressed;
        if (packet.fromAccount(account)) {
            status = Message.STATUS_SEND;
            selfAddressed = to == null || account.getJid().asBareJid().equals(to.asBareJid());
            if (selfAddressed) {
                counterpart = from;
            } else {
                counterpart = to != null ? to : account.getJid();
            }
        } else {
            status = Message.STATUS_RECEIVED;
            counterpart = from;
            selfAddressed = false;
        }

        final Invite invite = extractInvite(packet);
        if (invite != null) {
            if (isTypeGroupChat) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring invite to " + invite.jid + " because type=groupchat");
            } else if (invite.direct && (mucUserElement != null || invite.inviter == null || mXmppConnectionService.isMuc(account, invite.inviter))) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring direct invite to " + invite.jid + " because it was received in MUC");
            } else {
                invite.execute(account);
                return;
            }
        }

        if ((body != null || pgpEncrypted != null || (axolotlEncrypted != null && axolotlEncrypted.hasChild("payload")) || oobUrl != null || xP1S3 != null) && !isMucStatusMessage) {
            final boolean conversationIsProbablyMuc = isTypeGroupChat || mucUserElement != null || account.getXmppConnection().getMucServersWithholdAccount().contains(counterpart.getDomain());
            final Conversation conversation = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), conversationIsProbablyMuc, false, query, false);
            final boolean conversationMultiMode = conversation.getMode() == Conversation.MODE_MULTI;
            if (serverMsgId == null) {
                serverMsgId = extractStanzaId(packet, isTypeGroupChat, conversation);
            }
            if (selfAddressed) {
                if (mXmppConnectionService.markMessage(conversation, remoteMsgId, Message.STATUS_SEND_RECEIVED, serverMsgId)) {
                    return;
                }
                status = Message.STATUS_RECEIVED;
                if (remoteMsgId != null && conversation.findMessageWithRemoteId(remoteMsgId, counterpart) != null) {
                    return;
                }
            }
            if (isTypeGroupChat) {
                if (conversation.getMucOptions().isSelf(counterpart)) {
                    status = Message.STATUS_SEND_RECEIVED;
                    isCarbon = true; //not really carbon but received from another resource
                    //TODO this would be the place to change the body after something like mod_pastebin
                    if (mXmppConnectionService.markMessage(conversation, remoteMsgId, status, serverMsgId)) {
                        return;
                    } else if (remoteMsgId == null || Config.IGNORE_ID_REWRITE_IN_MUC) {
                        LocalizedContent localizedBody = packet.getBody();
                        if (localizedBody != null) {
                            Message message = conversation.findSentMessageWithBody(localizedBody.content);
                            if (message != null) {
                                mXmppConnectionService.markMessage(message, status);
                                return;
                            }
                        }
                    }
                } else {
                    status = Message.STATUS_RECEIVED;
                }
            }
            final Message message;
            if (body != null && body.content.startsWith("?OTR") && Config.supportOtr()) {
                if (!isForwarded && !isTypeGroupChat && isProperlyAddressed && !conversationMultiMode) {
                    message = parseOtrChat(body.content, from, remoteMsgId, conversation);
                    if (message == null) {
                        return;
                    }
                } else {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring OTR message from " + from + " isForwarded=" + Boolean.toString(isForwarded) + ", isProperlyAddressed=" + Boolean.valueOf(isProperlyAddressed));
                    message = new Message(conversation, body.content, Message.ENCRYPTION_NONE, status);
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language);
                    }
                }
            } else if (xP1S3url != null) {
                message = new Message(conversation, xP1S3url.toString(), Message.ENCRYPTION_NONE, status);
                message.setOob(true);
                if (CryptoHelper.isPgpEncryptedUrl(xP1S3url.toString())) {
                    message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                }
            } else if (pgpEncrypted != null && Config.supportOpenPgp()) {
                message = new Message(conversation, pgpEncrypted, Message.ENCRYPTION_PGP, status);
            } else if (axolotlEncrypted != null && Config.supportOmemo()) {
                Jid origin;
                Set<Jid> fallbacksBySourceId = Collections.emptySet();
                if (conversationMultiMode) {
                    final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin = getTrueCounterpart(query != null ? mucUserElement : null, fallback);
                    if (origin == null) {
                        try {
                            fallbacksBySourceId = account.getAxolotlService().findCounterpartsBySourceId(XmppAxolotlMessage.parseSourceId(axolotlEncrypted));
                        } catch (IllegalArgumentException e) {
                            //ignoring
                        }
                    }
                    if (origin == null && fallbacksBySourceId.size() == 0) {
                        Log.d(Config.LOGTAG, "axolotl message in anonymous conference received and no possible fallbacks");
                        return;
                    }
                } else {
                    fallbacksBySourceId = Collections.emptySet();
                    origin = from;
                }
                final boolean liveMessage = query == null && !isTypeGroupChat && mucUserElement == null;
                final boolean checkedForDuplicates = liveMessage || (serverMsgId != null && remoteMsgId != null && !conversation.possibleDuplicate(serverMsgId, remoteMsgId));

                if (origin != null) {
                    message = parseAxolotlChat(axolotlEncrypted, origin, conversation, status, checkedForDuplicates, query != null);
                } else {
                    Message trial = null;
                    for (Jid fallback : fallbacksBySourceId) {
                        trial = parseAxolotlChat(axolotlEncrypted, fallback, conversation, status, checkedForDuplicates && fallbacksBySourceId.size() == 1, query != null);
                        if (trial != null) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": decoded muc message using fallback");
                            origin = fallback;
                            break;
                        }
                    }
                    message = trial;
                }
                if (message == null) {
                    if (query == null && extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet)) {
                        mXmppConnectionService.updateConversationUi();
                    }
                    if (query != null && status == Message.STATUS_SEND && remoteMsgId != null) {
                        Message previouslySent = conversation.findSentMessageWithUuid(remoteMsgId);
                        if (previouslySent != null && previouslySent.getServerMsgId() == null && serverMsgId != null) {
                            previouslySent.setServerMsgId(serverMsgId);
                            mXmppConnectionService.databaseBackend.updateMessage(previouslySent, false);
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": encountered previously sent OMEMO message without serverId. updating...");
                        }
                    }
                    return;
                }
                if (conversationMultiMode) {
                    message.setTrueCounterpart(origin);
                }
            } else if (body == null && oobUrl != null) {
                message = new Message(conversation, oobUrl, Message.ENCRYPTION_NONE, status);
                message.setOob(true);
                if (CryptoHelper.isPgpEncryptedUrl(oobUrl)) {
                    message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                }
            } else {
                message = new Message(conversation, body.content, Message.ENCRYPTION_NONE, status);
                if (body.count > 1) {
                    message.setBodyLanguage(body.language);
                }
            }

            message.setCounterpart(counterpart);
            message.setRemoteMsgId(remoteMsgId);
            message.setServerMsgId(serverMsgId);
            message.setCarbon(isCarbon);
            message.setTime(timestamp);
            if (body != null && body.content != null && body.content.equals(oobUrl)) {
                message.setOob(true);
                if (CryptoHelper.isPgpEncryptedUrl(oobUrl)) {
                    message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                }
            }
            message.markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
            if (conversationMultiMode) {
                message.setMucUser(conversation.getMucOptions().findUserByFullJid(counterpart));
                final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                Jid trueCounterpart;
                if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                    trueCounterpart = message.getTrueCounterpart();
                } else if (query != null && query.safeToExtractTrueCounterpart()) {
                    trueCounterpart = getTrueCounterpart(mucUserElement, fallback);
                } else {
                    trueCounterpart = fallback;
                }
                if (trueCounterpart != null && isTypeGroupChat) {
                    if (trueCounterpart.asBareJid().equals(account.getJid().asBareJid())) {
                        status = isTypeGroupChat ? Message.STATUS_SEND_RECEIVED : Message.STATUS_SEND;
                    } else {
                        status = Message.STATUS_RECEIVED;
                        message.setCarbon(false);
                    }
                }
                message.setStatus(status);
                message.setTrueCounterpart(trueCounterpart);
                if (!isTypeGroupChat) {
                    message.setType(Message.TYPE_PRIVATE);
                }
            } else {
                updateLastseen(account, from);
            }

            if (replacementId != null && mXmppConnectionService.allowMessageCorrection()) {
                final Message replacedMessage = conversation.findMessageWithRemoteIdAndCounterpart(replacementId,
                        counterpart,
                        message.getStatus() == Message.STATUS_RECEIVED,
                        message.isCarbon());
                if (replacedMessage != null) {
                    final boolean fingerprintsMatch = replacedMessage.getFingerprint() == null
                            || replacedMessage.getFingerprint().equals(message.getFingerprint());
                    final boolean trueCountersMatch = replacedMessage.getTrueCounterpart() != null
                            && message.getTrueCounterpart() != null
                            && replacedMessage.getTrueCounterpart().asBareJid().equals(message.getTrueCounterpart().asBareJid());
                    final boolean mucUserMatches = query == null && replacedMessage.sameMucUser(message); //can not be checked when using mam
                    final boolean duplicate = conversation.hasDuplicateMessage(message);
                    if (fingerprintsMatch && (trueCountersMatch || !conversationMultiMode || mucUserMatches) && !duplicate) {
                        Log.d(Config.LOGTAG, "replaced message '" + replacedMessage.getBody() + "' with '" + message.getBody() + "'");
                        synchronized (replacedMessage) {
                            final String uuid = replacedMessage.getUuid();
                            replacedMessage.setUuid(UUID.randomUUID().toString());
                            replacedMessage.setBody(message.getBody());
                            replacedMessage.putEdited(replacedMessage.getRemoteMsgId(), replacedMessage.getServerMsgId());
                            replacedMessage.setRemoteMsgId(remoteMsgId);
                            if (replacedMessage.getServerMsgId() == null || message.getServerMsgId() != null) {
                                replacedMessage.setServerMsgId(message.getServerMsgId());
                            }
                            replacedMessage.setEncryption(message.getEncryption());
                            if (replacedMessage.getStatus() == Message.STATUS_RECEIVED) {
                                replacedMessage.markUnread();
                            }
                            extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet);
                            mXmppConnectionService.updateMessage(replacedMessage, uuid);
                            if (mXmppConnectionService.confirmMessages()
                                    && replacedMessage.getStatus() == Message.STATUS_RECEIVED
                                    && (replacedMessage.trusted() || replacedMessage.isPrivateMessage()) //TODO do we really want to send receipts for all PMs?
                                    && remoteMsgId != null
                                    && !selfAddressed
                                    && !isTypeGroupChat) {
                                processMessageReceipts(account, packet, query);
                            }
                            if (replacedMessage.getEncryption() == Message.ENCRYPTION_PGP) {
                                conversation.getAccount().getPgpDecryptionService().discard(replacedMessage);
                                conversation.getAccount().getPgpDecryptionService().decrypt(replacedMessage, false);
                            }
                        }
                        mXmppConnectionService.getNotificationService().updateNotification();
                        return;
                    } else {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received message correction but verification didn't check out");
                    }
                }
            } else if (replacementId != null && !mXmppConnectionService.allowMessageCorrection() && message.getBody().equals(DELETED_MESSAGE_BODY)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received deleted message but LMC is deactivated");
                return;
            }

            long deletionDate = mXmppConnectionService.getAutomaticMessageDeletionDate();
            if (deletionDate != 0 && message.getTimeSent() < deletionDate) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": skipping message from " + message.getCounterpart().toString() + " because it was sent prior to our deletion date");
                return;
            }

            boolean checkForDuplicates = (isTypeGroupChat && packet.hasChild("delay", "urn:xmpp:delay"))
                    || message.isPrivateMessage()
                    || message.getServerMsgId() != null
                    || (query == null && mXmppConnectionService.getMessageArchiveService().isCatchupInProgress(conversation));
            if (checkForDuplicates) {
                final Message duplicate = conversation.findDuplicateMessage(message);
                if (duplicate != null) {
                    final boolean serverMsgIdUpdated;
                    if (duplicate.getStatus() != Message.STATUS_RECEIVED
                            && duplicate.getUuid().equals(message.getRemoteMsgId())
                            && duplicate.getServerMsgId() == null
                            && message.getServerMsgId() != null) {
                        duplicate.setServerMsgId(message.getServerMsgId());
                        if (mXmppConnectionService.databaseBackend.updateMessage(duplicate, false)) {
                            serverMsgIdUpdated = true;
                        } else {
                            serverMsgIdUpdated = false;
                            Log.e(Config.LOGTAG, "failed to update message");
                        }
                    } else {
                        serverMsgIdUpdated = false;
                    }
                    Log.d(Config.LOGTAG, "skipping duplicate message with " + message.getCounterpart() + ". serverMsgIdUpdated=" + serverMsgIdUpdated);
                    return;
                }
            }

            if (query != null && query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                conversation.prepend(query.getActualInThisQuery(), message);
            } else {
                conversation.add(message);
            }
            if (query != null) {
                query.incrementActualMessageCount();
            }

            if (query == null || query.isCatchup()) { //either no mam or catchup
                if (status == Message.STATUS_SEND || status == Message.STATUS_SEND_RECEIVED) {
                    mXmppConnectionService.markRead(conversation);
                    if (query == null) {
                        activateGracePeriod(account);
                    }
                } else {
                    message.markUnread();
                    notify = true;
                }
            }

            if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                notify = conversation.getAccount().getPgpDecryptionService().decrypt(message, notify);
            } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
                notify = false;
            }

            if (query == null) {
                extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet);
                mXmppConnectionService.updateConversationUi();
            }

            if (mXmppConnectionService.confirmMessages()
                    && message.getStatus() == Message.STATUS_RECEIVED
                    && (message.trusted() || message.isPrivateMessage())
                    && remoteMsgId != null
                    && !selfAddressed
                    && !isTypeGroupChat) {
                processMessageReceipts(account, packet, query);
            }

            if (message.getStatus() == Message.STATUS_RECEIVED
                    && conversation.getOtrSession() != null
                    && !conversation.getOtrSession().getSessionID().getUserID()
                    .equals(message.getCounterpart().getResource())) {
                conversation.endOtrIfNeeded();
            }

            mXmppConnectionService.databaseBackend.createMessage(message);
            final HttpConnectionManager manager = this.mXmppConnectionService.getHttpConnectionManager();
            if (message.trusted() && message.treatAsDownloadable() && manager.getAutoAcceptFileSize() > 0) {
                manager.createNewDownloadConnection(message);
            } else if (notify) {
                if (query != null && query.isCatchup()) {
                    mXmppConnectionService.getNotificationService().pushFromBacklog(message);
                } else {
                    mXmppConnectionService.getNotificationService().push(message);
                }
            }
        } else if (!packet.hasChild("body")) { //no body
            final Conversation conversation = mXmppConnectionService.find(account, from.asBareJid());
            if (axolotlEncrypted != null) {
                Jid origin;
                if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                    final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin = getTrueCounterpart(query != null ? mucUserElement : null, fallback);
                    if (origin == null) {
                        Log.d(Config.LOGTAG, "omemo key transport message in anonymous conference received");
                        return;
                    }
                } else if (isTypeGroupChat) {
                    return;
                } else {
                    origin = from;
                }
                try {
                    final XmppAxolotlMessage xmppAxolotlMessage = XmppAxolotlMessage.fromElement(axolotlEncrypted, origin.asBareJid());
                    account.getAxolotlService().processReceivingKeyTransportMessage(xmppAxolotlMessage, query != null);
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": omemo key transport message received from " + origin);
                } catch (Exception e) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": invalid omemo key transport message received " + e.getMessage());
                    return;
                }
            }
            if (query == null && extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid()), isTypeGroupChat, packet)) {
                mXmppConnectionService.updateConversationUi();
            }
            if (isTypeGroupChat) {
                if (packet.hasChild("subject")) { //TODO usually we would want to check for lack of body; however some servers do set a body :(
                    if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                        conversation.setHasMessagesLeftOnServer(conversation.countMessages() > 0);
                        final LocalizedContent subject = packet.findInternationalizedChildContentInDefaultNamespace("subject");
                        if (subject != null && conversation.getMucOptions().setSubject(subject.content)) {
                            mXmppConnectionService.updateConversation(conversation);
                        }
                        mXmppConnectionService.updateConversationUi();
                        return;
                    }
                }
            }
            if (conversation != null && mucUserElement != null && InvalidJid.hasValidFrom(packet) && from.isBareJid()) {
                for (Element child : mucUserElement.getChildren()) {
                    if ("status".equals(child.getName())) {
                        try {
                            int code = Integer.parseInt(child.getAttribute("code"));
                            if ((code >= 170 && code <= 174) || (code >= 102 && code <= 104)) {
                                mXmppConnectionService.fetchConferenceConfiguration(conversation);
                                break;
                            }
                        } catch (Exception e) {
                            //ignored
                        }
                    } else if ("item".equals(child.getName())) {
                        MucOptions.User user = AbstractParser.parseItem(conversation, child);
                        Log.d(Config.LOGTAG, account.getJid() + ": changing affiliation for "
                                + user.getRealJid() + " to " + user.getAffiliation() + " in "
                                + conversation.getJid().asBareJid());
                        if (!user.realJidMatchesAccount()) {
                            boolean isNew = conversation.getMucOptions().updateUser(user);
                            mXmppConnectionService.getAvatarService().clear(conversation);
                            mXmppConnectionService.updateMucRosterUi();
                            mXmppConnectionService.updateConversationUi();
                            Contact contact = user.getContact();
                            if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                                Jid jid = user.getRealJid();
                                List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                                if (cryptoTargets.remove(user.getRealJid())) {
                                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": removed " + jid + " from crypto targets of " + conversation.getName());
                                    conversation.setAcceptedCryptoTargets(cryptoTargets);
                                    mXmppConnectionService.updateConversation(conversation);
                                }
                            } else if (isNew
                                    && user.getRealJid() != null
                                    && conversation.getMucOptions().isPrivateAndNonAnonymous()
                                    && (contact == null || !contact.mutualPresenceSubscription())
                                    && account.getAxolotlService().hasEmptyDeviceList(user.getRealJid())) {
                                account.getAxolotlService().fetchDeviceIds(user.getRealJid());
                            }
                        }
                    }
                }
            }
        }


        Element received = packet.findChild("received", "urn:xmpp:chat-markers:0");
        if (received == null) {
            received = packet.findChild("received", "urn:xmpp:receipts");
        }
        if (received != null) {
            String id = received.getAttribute("id");
            if (packet.fromAccount(account)) {
                if (query != null && id != null && packet.getTo() != null) {
                    query.removePendingReceiptRequest(new ReceiptRequest(packet.getTo(), id));
                }
            } else {
                mXmppConnectionService.markMessage(account, from.asBareJid(), received.getAttribute("id"), Message.STATUS_SEND_RECEIVED);
            }
        }
        Element displayed = packet.findChild("displayed", "urn:xmpp:chat-markers:0");
        if (displayed != null) {
            final String id = displayed.getAttribute("id");
            final Jid sender = InvalidJid.getNullForInvalid(displayed.getAttributeAsJid("sender"));
            if (packet.fromAccount(account) && !selfAddressed) {
                dismissNotification(account, counterpart, query);
                if (query == null) {
                    activateGracePeriod(account);
                }
            } else if (isTypeGroupChat) {
                Conversation conversation = mXmppConnectionService.find(account, counterpart.asBareJid());
                if (conversation != null && id != null && sender != null) {
                    Message message = conversation.findMessageWithRemoteId(id, sender);
                    if (message != null) {
                        final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                        final Jid trueJid = getTrueCounterpart((query != null && query.safeToExtractTrueCounterpart()) ? mucUserElement : null, fallback);
                        final boolean trueJidMatchesAccount = account.getJid().asBareJid().equals(trueJid == null ? null : trueJid.asBareJid());
                        if (trueJidMatchesAccount || conversation.getMucOptions().isSelf(counterpart)) {
                            if (!message.isRead() && (query == null || query.isCatchup())) { //checking if message is unread fixes race conditions with reflections
                                mXmppConnectionService.markRead(conversation);
                            }
                        } else if (!counterpart.isBareJid() && trueJid != null) {
                            final ReadByMarker readByMarker = ReadByMarker.from(counterpart, trueJid);
                            if (message.addReadByMarker(readByMarker)) {
                                mXmppConnectionService.updateMessage(message, false);
                                updateReadMarker(account, from, id, selfAddressed, counterpart, query);
                            }
                        }
                    }

                }
            } else {
                final Message displayedMessage = mXmppConnectionService.markMessage(account, from.asBareJid(), id, Message.STATUS_SEND_DISPLAYED);
                Message message = displayedMessage == null ? null : displayedMessage.prev();
                while (message != null
                        && message.getStatus() == Message.STATUS_SEND_RECEIVED
                        && message.getTimeSent() < displayedMessage.getTimeSent()) {
                    mXmppConnectionService.markMessage(message, Message.STATUS_SEND_DISPLAYED);
                    message = message.prev();
                }
                if (displayedMessage != null && selfAddressed) {
                    dismissNotification(account, counterpart, query);
                }
            }
        }

        final Element event = original.findChild("event", "http://jabber.org/protocol/pubsub#event");
        if (event != null && InvalidJid.hasValidFrom(original)) {
            if (event.hasChild("items")) {
                parseEvent(event, original.getFrom(), account);
            } else if (event.hasChild("delete")) {
                parseDeleteEvent(event, original.getFrom(), account);
            } else if (event.hasChild("purge")) {
                parsePurgeEvent(event, original.getFrom(), account);
            }
        }

        final String nick = packet.findChildContent("nick", Namespace.NICK);
        if (nick != null && InvalidJid.hasValidFrom(original)) {
            Contact contact = account.getRoster().getContact(from);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
    }

    private void updateReadMarker(Account account, Jid from, String id, boolean selfAddressed, Jid counterpart, MessageArchiveService.Query query) {
        final Message displayedMessage = mXmppConnectionService.markMessage(account, from.asBareJid(), id, Message.STATUS_SEND_DISPLAYED);
        Message m = displayedMessage == null ? null : displayedMessage.prev();
        while (m != null
                && m.getStatus() == Message.STATUS_SEND_RECEIVED
                && m.getTimeSent() < displayedMessage.getTimeSent()) {
            mXmppConnectionService.markMessage(m, Message.STATUS_SEND_DISPLAYED);
            m = m.prev();
        }
        if (displayedMessage != null && selfAddressed) {
            dismissNotification(account, counterpart, query);
        }
    }

    private void dismissNotification(Account account, Jid counterpart, MessageArchiveService.Query query) {
        Conversation conversation = mXmppConnectionService.find(account, counterpart.asBareJid());
        if (conversation != null && (query == null || query.isCatchup())) {
            mXmppConnectionService.markRead(conversation); //TODO only mark messages read that are older than timestamp
        }
    }

    private void processMessageReceipts(Account account, MessagePacket packet, MessageArchiveService.Query query) {
        final boolean markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
        final boolean request = packet.hasChild("request", "urn:xmpp:receipts");
        if (query == null) {
            final ArrayList<String> receiptsNamespaces = new ArrayList<>();
            if (markable) {
                receiptsNamespaces.add("urn:xmpp:chat-markers:0");
            }
            if (request) {
                receiptsNamespaces.add("urn:xmpp:receipts");
            }
            if (receiptsNamespaces.size() > 0) {
                MessagePacket receipt = mXmppConnectionService.getMessageGenerator().received(account,
                        packet,
                        receiptsNamespaces,
                        packet.getType());
                mXmppConnectionService.sendMessagePacket(account, receipt);
            }
        } else if (query.isCatchup()) {
            if (request) {
                query.addPendingReceiptRequest(new ReceiptRequest(packet.getFrom(), packet.getId()));
            }
        }
    }

    private void activateGracePeriod(Account account) {
        long duration = mXmppConnectionService.getLongPreference("grace_period_length", R.integer.grace_period) * 1000;
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": activating grace period till " + TIME_FORMAT.format(new Date(System.currentTimeMillis() + duration)));
        account.activateGracePeriod(duration);
    }

    private class Invite {
        final Jid jid;
        final String password;
        final boolean direct;
        final Jid inviter;

        Invite(Jid jid, String password, boolean direct, Jid inviter) {
            this.jid = jid;
            this.password = password;
            this.direct = direct;
            this.inviter = inviter;
        }

        public boolean execute(Account account) {
            if (jid != null) {
                Conversation conversation = mXmppConnectionService.findOrCreateConversation(account, jid, true, false);
                if (conversation.getMucOptions().online()) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received invite to " + jid + " but muc is considered to be online");
                    mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                } else {
                    conversation.getMucOptions().setPassword(password);
                    mXmppConnectionService.databaseBackend.updateConversation(conversation);
                    final Contact contact = inviter != null ? account.getRoster().getContactFromContactList(inviter) : null;
                    mXmppConnectionService.joinMuc(conversation, contact != null && contact.mutualPresenceSubscription());
                    mXmppConnectionService.updateConversationUi();
                }
                return true;
            }
            return false;
        }
    }
}