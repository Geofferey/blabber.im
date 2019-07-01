package de.pixart.messenger.services;

import de.pixart.messenger.entities.Account;

public class PushManagementService {

    protected final XmppConnectionService mXmppConnectionService;

    public PushManagementService(XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    void registerPushTokenOnServer(Account account) {
        //stub implementation. only affects playstore flavor
    }

    void registerPushTokenOnServer(Conversation conversation) {
        //stub implementation. only affects playstore flavor
    }

    void disablePushOnServer(Conversation conversation) {
        //stub implementation. only affects playstore flavor
    }

    public boolean available(Account account) {
        return false;
    }

    public boolean isStub() {
        return true;
    }
}
