package de.pixart.messenger.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import de.pixart.messenger.R;
import de.pixart.messenger.ui.util.IntroHelper;

import static de.pixart.messenger.Config.DISALLOW_REGISTRATION_IN_UI;
import static de.pixart.messenger.utils.PermissionUtils.allGranted;
import static de.pixart.messenger.utils.PermissionUtils.readGranted;

public class WelcomeActivity extends XmppActivity {

    private static final int REQUEST_IMPORT_BACKUP = 0x63fb;

    @Override
    protected void refreshUiReal() {
    }

    @Override
    void onBackendConnected() {
    }

    private static final int REQUEST_READ_EXTERNAL_STORAGE = 0XD737;

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (intent != null) {
            setIntent(intent);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.welcome);
        setSupportActionBar(findViewById(R.id.toolbar));
        final ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayShowHomeEnabled(false);
            ab.setDisplayHomeAsUpEnabled(false);
        }
        IntroHelper.showIntro(this, false);
        final Button ImportDatabase = findViewById(R.id.import_database);
        final TextView ImportText = findViewById(R.id.import_text);
        if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
            ImportDatabase.setVisibility(View.VISIBLE);
            ImportText.setVisibility(View.VISIBLE);
        }
        ImportDatabase.setOnClickListener(v -> startActivity(new Intent(this, ImportBackupActivity.class)));


        final Button createAccount = findViewById(R.id.create_account);
        createAccount.setOnClickListener(v -> {
            final Intent intent = new Intent(WelcomeActivity.this, MagicCreateActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            addInviteUri(intent);
            startActivity(intent);
        });
        if (DISALLOW_REGISTRATION_IN_UI) {
            createAccount.setVisibility(View.GONE);
        }
        final Button useExistingAccount = findViewById(R.id.use_existing_account);
        useExistingAccount.setOnClickListener(v -> {
            Intent intent = new Intent(WelcomeActivity.this, EditAccountActivity.class);
            intent.putExtra("init", true);
            intent.putExtra("existing", true);
            addInviteUri(intent);
            startActivity(intent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
            finish();
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        });

    }

    public void addInviteUri(Intent intent) {
        StartConversationActivity.addInviteUri(intent, getIntent());
    }

    public static void launch(AppCompatActivity activity) {
        Intent intent = new Intent(activity, WelcomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0) {
            if (allGranted(grantResults)) {
                switch (requestCode) {
                    case REQUEST_IMPORT_BACKUP:
                        startActivity(new Intent(this, ImportBackupActivity.class));
                        break;
                }
            } else {
                Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
            }
        }
        if (readGranted(grantResults, permissions)) {
            if (xmppConnectionService != null) {
                xmppConnectionService.restartFileObserver();
            }
        }
    }
}