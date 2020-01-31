package de.pixart.messenger.ui;

import android.os.Bundle;
import android.text.Editable;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import java.util.Collections;

import de.pixart.messenger.R;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Blockable;
import de.pixart.messenger.entities.ListItem;
import de.pixart.messenger.entities.RawBlockable;
import de.pixart.messenger.ui.interfaces.OnBackendConnected;
import de.pixart.messenger.xmpp.OnUpdateBlocklist;
import me.drakeet.support.toast.ToastCompat;
import rocks.xmpp.addr.Jid;

public class BlocklistActivity extends AbstractSearchableListItemActivity implements OnUpdateBlocklist {
    private Account account = null;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            BlockContactDialog.show(BlocklistActivity.this, (Blockable) getListItems().get(position));
            return true;
        });
        this.binding.fab.show();
        this.binding.fab.setOnClickListener((v) -> showEnterJidDialog());
    }

    @Override
    public void onBackendConnected() {
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.getJid().toString().equals(getIntent().getStringExtra(EXTRA_ACCOUNT))) {
                this.account = account;
                break;
            }
        }
        filterContacts();
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (fragment instanceof OnBackendConnected) {
            ((OnBackendConnected) fragment).onBackendConnected();
        }
    }

    @Override
    protected void filterContacts(final String needle) {
        getListItems().clear();
        if (account != null) {
            for (final Jid jid : account.getBlocklist()) {
                ListItem item;
                if (jid.isFullJid()) {
                    item = new RawBlockable(account, jid);
                } else {
                    item = account.getRoster().getContact(jid);
                }
                if (item.match(this, needle)) {
                    getListItems().add(item);
                }
            }
            Collections.sort(getListItems());
        }
        getListItemAdapter().notifyDataSetChanged();
    }

    protected void showEnterJidDialog() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        EnterJidDialog dialog = EnterJidDialog.newInstance(
                null,
                getString(R.string.block_jabber_id),
                getString(R.string.block),
                null,
                account.getJid().asBareJid().toString(),
                true,
                xmppConnectionService.multipleAccounts(),
                false
        );

        dialog.setOnEnterJidDialogPositiveListener((accountJid, contactJid) -> {
            Blockable blockable = new RawBlockable(account, contactJid);
            if (xmppConnectionService.sendBlockRequest(blockable, false)) {
                ToastCompat.makeText(BlocklistActivity.this, R.string.corresponding_conversations_closed, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        dialog.show(ft, "dialog");
    }

    protected void refreshUiReal() {
        final Editable editable = getSearchEditText().getText();
        if (editable != null) {
            filterContacts(editable.toString());
        } else {
            filterContacts();
        }
    }

    @Override
    public void OnUpdateBlocklist(final OnUpdateBlocklist.Status status) {
        refreshUi();
    }
}
