package de.pixart.messenger.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import java.util.List;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.databinding.ActivityImportBackupBinding;
import de.pixart.messenger.databinding.DialogEnterPasswordBinding;
import de.pixart.messenger.services.ImportBackupService;
import de.pixart.messenger.ui.adapter.BackupFileAdapter;

public class ImportBackupActivity extends XmppActivity implements ServiceConnection, ImportBackupService.OnBackupFilesLoaded, BackupFileAdapter.OnItemClickedListener, ImportBackupService.OnBackupProcessed {

    private ActivityImportBackupBinding binding;

    private BackupFileAdapter backupFileAdapter;
    private ImportBackupService service;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_import_backup);
        setSupportActionBar((Toolbar) binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.backupFileAdapter = new BackupFileAdapter();
        this.binding.list.setAdapter(this.backupFileAdapter);
        this.backupFileAdapter.setOnItemClickedListener(this);
    }

    @Override
    protected void refreshUiReal() {
    }

    @Override
    public void onStart() {
        super.onStart();
        bindService(new Intent(this, ImportBackupService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (this.service != null) {
            this.service.removeOnBackupProcessedListener(this);
        }
        unbindService(this);
    }

    @Override
    void onBackendConnected() {
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ImportBackupService.ImportBackupServiceBinder binder = (ImportBackupService.ImportBackupServiceBinder) service;
        this.service = binder.getService();
        this.service.addOnBackupProcessedListener(this);
        setLoadingState(this.service.getLoadingState());
        this.service.loadBackupFiles(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        this.service = null;
    }

    @Override
    public void onBackupFilesLoaded(final List<ImportBackupService.BackupFile> files) {
        runOnUiThread(() -> {
            if (files.size() >= 1) {
                this.binding.hint.setVisibility(View.GONE);
                this.binding.list.setVisibility(View.VISIBLE);
                backupFileAdapter.setFiles(files);
            } else {
                this.binding.list.setVisibility(View.GONE);
                this.binding.hint.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onClick(ImportBackupService.BackupFile backupFile) {
        final DialogEnterPasswordBinding enterPasswordBinding = DataBindingUtil.inflate(LayoutInflater.from(this), R.layout.dialog_enter_password, null, false);
        Log.d(Config.LOGTAG, "attempting to import " + backupFile.getFile().getAbsolutePath());
        enterPasswordBinding.explain.setText(getString(R.string.enter_password_to_restore, backupFile.getHeader().getJid().toString()));
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(enterPasswordBinding.getRoot());
        builder.setTitle(R.string.enter_password);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.restore, (dialog, which) -> {
            final String password = enterPasswordBinding.accountPassword.getEditableText().toString();
            Intent intent = new Intent(this, ImportBackupService.class);
            intent.putExtra("password", password);
            intent.putExtra("file", backupFile.getFile().getAbsolutePath());
            setLoadingState(true);
            ContextCompat.startForegroundService(this, intent);
        });
        builder.setCancelable(false);
        builder.create().show();
    }

    private void setLoadingState(final boolean loadingState) {
        binding.coordinator.setVisibility(loadingState ? View.GONE : View.VISIBLE);
        binding.inProgress.setVisibility(loadingState ? View.VISIBLE : View.GONE);
        setTitle(loadingState ? R.string.restoring_backup : R.string.restore_backup);
        configureActionBar(getSupportActionBar(), !loadingState);
    }

    @Override
    public void onBackupRestored() {
        runOnUiThread(this::restart);
    }

    private void restart() {
        Log.d(Config.LOGTAG, "Restarting " + getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName()));
        Intent intent = getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        System.exit(0);
    }

    @Override
    public void onBackupDecryptionFailed() {
        runOnUiThread(() -> {
            setLoadingState(false);
            Snackbar.make(binding.coordinator, R.string.unable_to_decrypt_backup, Snackbar.LENGTH_LONG).show();
        });
    }

    @Override
    public void onBackupRestoreFailed() {
        runOnUiThread(() -> {
            setLoadingState(false);
            Snackbar.make(binding.coordinator, R.string.unable_to_restore_backup, Snackbar.LENGTH_LONG).show();
        });
    }
}