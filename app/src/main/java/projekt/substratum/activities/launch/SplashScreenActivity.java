/*
 * Copyright (c) 2016-2017 Projekt Substratum
 * This file is part of Substratum.
 *
 * Substratum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Substratum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Substratum.  If not, see <http://www.gnu.org/licenses/>.
 */

package projekt.substratum.activities.launch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.jaredrummler.android.widget.AnimatedSvgView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import projekt.substratum.MainActivity;
import projekt.substratum.R;
import projekt.substratum.Substratum;
import projekt.substratum.common.Packages;
import projekt.substratum.common.References;
import projekt.substratum.common.Systems;
import projekt.substratum.common.analytics.FirebaseAnalytics;
import projekt.substratum.databinding.SplashscreenActivityBinding;
import projekt.substratum.util.helpers.MD5;

import static projekt.substratum.common.Internal.AUTHENTICATED_RECEIVER;
import static projekt.substratum.common.Internal.AUTHENTICATE_RECEIVER;
import static projekt.substratum.common.References.ANDROMEDA_PACKAGE;
import static projekt.substratum.common.References.PLAY_STORE_PACKAGE_NAME;
import static projekt.substratum.common.References.SST_ADDON_PACKAGE;
import static projekt.substratum.common.References.SUBSTRATUM_LOG;
import static projekt.substratum.common.Systems.checkPackageSupport;
import static projekt.substratum.common.Systems.checkThemeSystemModule;
import static projekt.substratum.common.Systems.isAndromedaDevice;
import static projekt.substratum.common.analytics.FirebaseAnalytics.PACKAGES_PREFS;
import static projekt.substratum.common.analytics.PackageAnalytics.isLowEnd;

public class SplashScreenActivity extends Activity {

    private static final long DELAY_LAUNCH_MAIN_ACTIVITY = 300;
    private static final long DELAY_LAUNCH_APP_INTRO = 2300;
    private static final long DELAY_SHOW_PROGRESS_BAR = 2500;
    private ProgressBar progressBar;
    private Intent intent;
    private boolean firstRun = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SplashscreenActivityBinding binding =
                DataBindingUtil.setContentView(this, R.layout.splashscreen_activity);

        AnimatedSvgView svgView = binding.animatedSvgView;
        ImageView splashScreenImage = binding.splashscreenImage;
        progressBar = binding.progressBarLoader;

        Intent currentIntent = getIntent();
        firstRun = currentIntent.getBooleanExtra("first_run", false);
        checkThemeSystemModule(this, firstRun);
        intent = new Intent(SplashScreenActivity.this, MainActivity.class);
        long intentLaunchDelay = DELAY_LAUNCH_MAIN_ACTIVITY;

        if (firstRun && !isLowEnd()) {
            // Load the ImageView that will host the animation and
            // set its background to our AnimationDrawable XML resource.
            try {
                splashScreenImage.setVisibility(View.GONE);
                svgView.start();
                // Finally set the proper launch activity and delay
                intent = new Intent(SplashScreenActivity.this, AppIntroActivity.class);
                intentLaunchDelay = DELAY_LAUNCH_APP_INTRO;
            } catch (OutOfMemoryError ignored) {
                Log.e(References.SUBSTRATUM_LOG, "The VM has blown up and the rendering of " +
                        "the splash screen animated icon has been cancelled.");
            }
        } else {
            svgView.setVisibility(View.GONE);
        }

        Handler handler = new Handler();
        handler.postDelayed(() -> new CheckSamsung(this).execute(), intentLaunchDelay);
        handler.postDelayed(() -> runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE)),
                DELAY_SHOW_PROGRESS_BAR + intentLaunchDelay);
    }

    private void launch() {
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    static class CheckSamsung extends AsyncTask<Void, Void, Void> {
        private WeakReference<SplashScreenActivity> ref;
        private Handler handler = new Handler();
        private SharedPreferences prefs;
        private SharedPreferences.Editor editor;
        private KeyRetrieval keyRetrieval;
        private Intent securityIntent;
        private Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (securityIntent != null) {
                    handler.removeCallbacks(runnable);
                } else {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    handler.postDelayed(this, 100L);
                }
            }
        };

        CheckSamsung(SplashScreenActivity activity) {
            super();
            ref = new WeakReference<>(activity);
        }

        @Override
        protected Void doInBackground(Void... voids) {
            SplashScreenActivity activity = ref.get();
            if (activity != null) {
                Context context = Substratum.getInstance();
                prefs = context.getSharedPreferences("substratum_state", Context.MODE_PRIVATE);
                editor = prefs.edit();
                editor.clear().apply();
                FirebaseAnalytics.withdrawBlacklistedPackages(
                        activity.getApplicationContext(),
                        activity.firstRun
                );
                checkPackageSupport(activity.getApplicationContext(), false);
                prefs = context.getSharedPreferences(PACKAGES_PREFS, Context.MODE_PRIVATE);
                SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyyyy", Locale.US);
                int timeoutCount = 0;
                while (!prefs.contains(dateFormat.format(new Date())) && (timeoutCount < 100)) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    timeoutCount++;
                }
                if (!prefs.contains(dateFormat.format(new Date()))) {
                    Log.d(SUBSTRATUM_LOG, "Failed to withdraw blacklisted packages.");
                }

                if (isAndromedaDevice(context)) {
                    int andromedaVer = Packages.getAppVersionCode(context, ANDROMEDA_PACKAGE);
                    FirebaseAnalytics.withdrawAndromedaFingerprint(context, andromedaVer);
                    SharedPreferences prefs2 =
                            context.getSharedPreferences("substratum_state", Context.MODE_PRIVATE);
                    timeoutCount = 0;
                    while (!prefs2.contains("andromeda_exp_fp_" + andromedaVer) &&
                            (timeoutCount < 100)) {
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        timeoutCount++;
                    }
                    if (!prefs2.contains("andromeda_exp_fp_" + andromedaVer)) {
                        Log.d(SUBSTRATUM_LOG, "Failed to withdraw andromeda fingerprint.");
                    } else {
                        String installed_directory =
                                Packages.getInstalledDirectory(context, ANDROMEDA_PACKAGE);
                        if (installed_directory != null) {
                            prefs2.edit()
                                    .putString("andromeda_fp",
                                            MD5.calculateMD5(new File(installed_directory)))
                                    .putString("andromeda_installer", context.getPackageManager()
                                            .getInstallerPackageName(ANDROMEDA_PACKAGE))
                                    .apply();
                        }
                    }
                }

                if (Systems.isSamsungDevice(context) &&
                        Packages.isPackageInstalled(context, SST_ADDON_PACKAGE)) {
                    int sstVersion = Packages.getAppVersionCode(context, SST_ADDON_PACKAGE);
                    FirebaseAnalytics.withdrawSungstratumFingerprint(context, sstVersion);
                    SharedPreferences prefs2 =
                            context.getSharedPreferences("substratum_state", Context.MODE_PRIVATE);
                    timeoutCount = 0;
                    while (!prefs2.contains("sungstratum_exp_fp_" + sstVersion) && (timeoutCount <
                            100)) {
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        timeoutCount++;
                    }
                    if (!prefs2.contains("sungstratum_exp_fp_" + sstVersion)) {
                        Log.d(SUBSTRATUM_LOG, "Failed to withdraw sungstratum fingerprint.");
                    }

                    keyRetrieval = new KeyRetrieval();
                    IntentFilter filter = new IntentFilter(AUTHENTICATED_RECEIVER);
                    context.getApplicationContext().registerReceiver(keyRetrieval, filter);

                    Intent intent = new Intent(AUTHENTICATE_RECEIVER);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        context.startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    handler.postDelayed(runnable, 100L);
                    int counter = 0;
                    while ((securityIntent == null) && (counter < 5)) {
                        try {
                            Thread.sleep(500L);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        counter++;
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void voids) {
            SplashScreenActivity activity = ref.get();
            if (activity != null) {
                activity.launch();
            }
        }

        @SuppressWarnings("ConstantConditions")
        class KeyRetrieval extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                securityIntent = intent;
                context.getApplicationContext().unregisterReceiver(keyRetrieval);
                if (intent != null) {
                    boolean debug = securityIntent.getBooleanExtra("app_debug", true);
                    String installer = securityIntent.getStringExtra("app_installer");
                    editor.putBoolean("sungstratum_debug", debug).apply();
                    editor.putBoolean("sungstratum_installer",
                            installer.equals(PLAY_STORE_PACKAGE_NAME)).apply();
                    editor.putString("sungstratum_fp", MD5.calculateMD5(new File(
                            Packages.getInstalledDirectory(context, SST_ADDON_PACKAGE))));
                    editor.apply();
                }
            }
        }
    }
}