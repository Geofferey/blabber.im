package eu.siacs.conversations.utils;

import android.app.Activity;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import me.drakeet.support.toast.ToastCompat;

public class AccountUtils {

    public static final Class<?> MANAGE_ACCOUNT_ACTIVITY;

    static {
        MANAGE_ACCOUNT_ACTIVITY = getManageAccountActivityClass();
    }

    public static List<String> getEnabledAccounts(final XmppConnectionService service) {
        ArrayList<String> accounts = new ArrayList<>();
        for (Account account : service.getAccounts()) {
            if (account.getStatus() != Account.State.DISABLED) {
                if (Config.DOMAIN_LOCK != null) {
                    accounts.add(account.getJid().getEscapedLocal());
                } else {
                    accounts.add(account.getJid().asBareJid().toEscapedString());
                }
            }
        }
        return accounts;
    }

    public static Account getFirstEnabled(XmppConnectionService service) {
        final List<Account> accounts = service.getAccounts();
        for (Account account : accounts) {
            if (!account.isOptionSet(Account.OPTION_DISABLED)) {
                return account;
            }
        }
        return null;
    }

    public static Account getFirst(XmppConnectionService service) {
        final List<Account> accounts = service.getAccounts();
        for (Account account : accounts) {
            return account;
        }
        return null;
    }

    public static Account getPendingAccount(XmppConnectionService service) {
        Account pending = null;
        for (Account account : service.getAccounts()) {
            if (!account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)) {
                pending = account;
            } else {
                return null;
            }
        }
        return pending;
    }

    public static void launchManageAccounts(final Activity activity) {
        if (MANAGE_ACCOUNT_ACTIVITY != null) {
            activity.startActivity(new Intent(activity, MANAGE_ACCOUNT_ACTIVITY));
        } else {
            ToastCompat.makeText(activity, R.string.feature_not_implemented, ToastCompat.LENGTH_SHORT).show();
        }
    }

    public static void launchManageAccount(final XmppActivity xmppActivity) {
        final Account account = getFirst(xmppActivity.xmppConnectionService);
        xmppActivity.switchToAccount(account);
    }

    private static Class<?> getManageAccountActivityClass() {
        try {
            return Class.forName("eu.siacs.conversations.ui.ManageAccountActivity");
        } catch (final ClassNotFoundException e) {
            return null;
        }
    }
}