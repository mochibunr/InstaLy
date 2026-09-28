package com.mochibunr.instaly;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mochibunr.instaly.auth.InstagramAuthProviderImpl;
import com.mochibunr.instaly.data.DemoReelRepository;
import com.mochibunr.instaly.data.ReelRepository;
import com.mochibunr.instaly.ui.ReelPagerAdapter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private ViewPager2 pager;
    private ReelPagerAdapter adapter;
    private InstagramAuthProviderImpl auth;
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        auth = new InstagramAuthProviderImpl(this);
        if (auth.isConnected()) showReels(new DemoReelRepository());
        else showLogin();
    }

    private void showLogin() {
        setContentView(R.layout.screen_login);
        EditText username = findViewById(R.id.username);
        EditText password = findViewById(R.id.password);
        findViewById(R.id.connectInstagram).setOnClickListener(v -> {
            String u = username.getText().toString().trim();
            String p = password.getText().toString();
            if (u.isEmpty() || p.isEmpty()) {
                toast("Enter your Instagram username and password.");
                return;
            }
            v.setEnabled(false);
            network.execute(() -> {
                try {
                    InstagramAuthProviderImpl.AuthResult result = auth.login(u, p);
                    runOnUiThread(() -> {
                        v.setEnabled(true);
                        password.setText("");
                        if ("authenticated".equals(result.status)) {
                            showReels(new DemoReelRepository());
                        } else if ("two_factor_required".equals(result.status)) {
                            showVerification(result.loginId);
                        } else if ("challenge_required".equals(result.status)) {
                            showChallenge();
                        } else {
                            toast("Instagram login failed.");
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        v.setEnabled(true);
                        password.setText("");
                        toast(e.getMessage() == null ? "Instagram login failed." : e.getMessage());
                    });
                }
            });
        });
        findViewById(R.id.demoFeed).setOnClickListener(v -> showReels(new DemoReelRepository()));
    }

    private void showVerification(String loginId) {
        EditText code = new EditText(this);
        code.setHint("Verification code");
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Instagram verification")
            .setMessage("Enter the code Instagram sent to you.")
            .setView(code)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Verify", null)
            .create()
            .setOnShowListener(d -> {
                androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) d;
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String value = code.getText().toString().trim();
                    if (value.isEmpty()) return;
                    v.setEnabled(false);
                    network.execute(() -> {
                        try {
                            InstagramAuthProviderImpl.AuthResult result = auth.verify(loginId, value);
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                if ("authenticated".equals(result.status)) showReels(new DemoReelRepository());
                                else toast("Verification failed.");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                v.setEnabled(true);
                                toast(e.getMessage() == null ? "Verification failed." : e.getMessage());
                            });
                        }
                    });
                });
            }).show();
    }

    private void showChallenge() {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Instagram needs verification")
            .setMessage("Instagram requested a challenge that requires confirmation outside this automated login flow. Complete it in the official Instagram app, then try InstaLy again.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void showReels(ReelRepository repo) {
        setContentView(R.layout.screen_reels);
        pager = findViewById(R.id.reelPager);
        adapter = new ReelPagerAdapter(repo.loadInitial());
        pager.setAdapter(adapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int p) {
                for (int i = 0; i < adapter.getItemCount(); i++) setPlaying(i, i == p);
            }
        });
        pager.post(() -> setPlaying(0, true));
        findViewById(R.id.navHome).setOnClickListener(v -> toast("Home is next."));
        findViewById(R.id.navSearch).setOnClickListener(v -> toast("Search is next."));
        findViewById(R.id.navCreate).setOnClickListener(v -> toast("Create is next."));
        findViewById(R.id.navProfile).setOnClickListener(v -> toast("Profile is next."));
    }

    private void setPlaying(int pos, boolean play) {
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return;
        RecyclerView.ViewHolder h = ((RecyclerView) child).findViewHolderForAdapterPosition(pos);
        if (h instanceof ReelPagerAdapter.Holder) ((ReelPagerAdapter.Holder) h).setPlaying(play);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }
}
