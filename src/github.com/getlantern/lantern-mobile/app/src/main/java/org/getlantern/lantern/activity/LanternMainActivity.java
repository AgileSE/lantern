package org.getlantern.lantern.activity;

import android.app.Application;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.view.MenuItem;
import android.view.KeyEvent;
import android.support.v7.app.AppCompatActivity;
import android.support.v4.app.Fragment;
import android.support.v4.view.ViewPager;

import org.getlantern.lantern.vpn.Service;
import org.getlantern.lantern.fragment.FeedFragment;
import org.getlantern.lantern.model.GetFeed;
import org.getlantern.lantern.model.UI;
import org.getlantern.lantern.model.Utils;
import org.getlantern.lantern.R;

import java.util.ArrayList; 

import com.thefinestartist.finestwebview.FinestWebView;
import com.ogaclejapan.smarttablayout.utils.v4.FragmentPagerItemAdapter;
import com.ogaclejapan.smarttablayout.utils.v4.FragmentPagerItems;
import com.ogaclejapan.smarttablayout.SmartTabLayout;


import org.lantern.mobilesdk.Lantern;
import org.lantern.mobilesdk.StartResult;
import org.lantern.mobilesdk.LanternNotRunningException;

public class LanternMainActivity extends AppCompatActivity implements 
Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    private static final String TAG = "LanternMainActivity";
    private static final String PREFS_NAME = "LanternPrefs";
    private final static int REQUEST_VPN = 7777;
    private SharedPreferences mPrefs = null;
    private BroadcastReceiver mReceiver;
    private boolean isInBackground = false;
    private FragmentPagerItemAdapter feedAdapter;
    private SmartTabLayout viewPagerTab;
    private String lastFeedSelected;

    private Context context;
    private UI LanternUI;
    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getApplication().registerActivityLifecycleCallbacks(this);

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        setContentView(R.layout.activity_lantern_main);

        lastFeedSelected = getResources().getString(R.string.all_feeds);

        // we want to use the ActionBar from the AppCompat
        // support library, but with our custom design
        // we hide the default action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        context = getApplicationContext();
        mPrefs = Utils.getSharedPrefs(context);

        LanternUI = new UI(this, mPrefs);
        // since onCreate is only called when the main activity
        // is first created, we clear shared preferences in case
        // Lantern was forcibly stopped during a previous run
        if (!Service.isRunning(context)) {
            Utils.clearPreferences(this);
        }

        // the ACTION_SHUTDOWN intent is broadcast when the phone is
        // about to be shutdown. We register a receiver to make sure we
        // clear the preferences and switch the VpnService to the off
        // state when this happens
        IntentFilter filter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);

        mReceiver = new LanternReceiver();
        registerReceiver(mReceiver, filter);

        if (getIntent().getBooleanExtra("EXIT", false)) {
            finish();
            return;
        }

        try {
            // configure actions to be taken whenever slider changes state
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String appVersion = pInfo.versionName;
            Log.d(TAG, "Currently running Lantern version: " + appVersion);

            LanternUI.setVersionNum(appVersion);
            LanternUI.setupLanternSwitch();

            new GetFeed(this, startLocalProxy()).execute("");

        } catch (Exception e) {
            Log.d(TAG, "Got an exception " + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        //  we check if mPrefs has been initialized before
        // since onCreate and onResume are always both called
        if (mPrefs != null) {
            LanternUI.setBtnStatus();
        }
    }

    // startLocalProxy starts a separate instance of Lantern
    // used for proxying requests we need to make even before
    // the user enables full-device VPN mode
    private String startLocalProxy() {
        // if the Lantern VPN is already running
        // then we just fetch the feed without
        // starting another local proxy
        if (Service.isRunning(context)) {
            return "";
        }

        try {
            int startTimeoutMillis = 60000;
            String analyticsTrackingID = ""; // don't track analytics since those are already being tracked elsewhere
            StartResult result = Lantern.enable(getApplicationContext(), startTimeoutMillis, analyticsTrackingID);
            return result.getHTTPAddr();
        }  catch (LanternNotRunningException lnre) {
            throw new RuntimeException("Lantern failed to start: " + lnre.getMessage(), lnre);
        }  
    }

    // override onKeyDown and onBackPressed default 
    // behavior to prevent back button from interfering 
    // with on/off switch
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        if (Integer.parseInt(android.os.Build.VERSION.SDK) > 5
                && keyCode == KeyEvent.KEYCODE_BACK
                && event.getRepeatCount() == 0) {
            Log.d(TAG, "onKeyDown Called");
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "onBackPressed Called");
        Intent setIntent = new Intent(Intent.ACTION_MAIN);
        setIntent.addCategory(Intent.CATEGORY_HOME);
        setIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(setIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getApplication().unregisterActivityLifecycleCallbacks(this);
        try {
            if (mReceiver != null) {
                unregisterReceiver(mReceiver);
            }
        } catch (Exception e) {

        }
    }

    // quitLantern is the side menu option and cleanyl exits the app
    public void quitLantern() {
        try {
            Log.d(TAG, "About to exit Lantern...");

            stopLantern();

            // sleep for a few ms before exiting
            Thread.sleep(200);

            finish();
            moveTaskToBack(true);

        } catch (Exception e) {
            Log.e(TAG, "Got an exception when quitting Lantern " + e.getMessage());
        }
    }

    public void refreshFeed(View view) {
        Log.d(TAG, "Refresh feed clicked");
        findViewById(R.id.feed_error).setVisibility(View.INVISIBLE);
        new GetFeed(this, startLocalProxy()).execute("");
    }

    public void showFeedError() {
        findViewById(R.id.feed_error).setVisibility(View.VISIBLE);
    }

    public void openFeedItem(View view) {
        TextView url = (TextView)view.findViewById(R.id.link);
        Log.d(TAG, "Feed item clicked: " + url.getText());

        if (lastFeedSelected != null) {
            // whenever a user clicks on an article, send a custom event to GA 
            // that includes the source/feed category
            Utils.sendFeedEvent(getApplicationContext(),
                    String.format("feed-%s", lastFeedSelected));
        }

        new FinestWebView.Builder(this)
            .webViewSupportMultipleWindows(true)
            .webViewJavaScriptEnabled(true)
            .swipeRefreshColorRes(R.color.black)
            .webViewAllowFileAccessFromFileURLs(true)
            .webViewJavaScriptCanOpenWindowsAutomatically(true)
            .webViewLoadWithProxy(startLocalProxy())
            // if we aren't in full-device VPN mode, configure the 
            // WebView to use our local proxy
            .show(url.getText().toString());
    }

    public void changeFeedHeaderColor(boolean useVpn) {
        if (feedAdapter != null && viewPagerTab != null) {
            int c;
            if (useVpn) {
                c = getResources().getColor(R.color.accent_white); 
            } else {
                c = getResources().getColor(R.color.black); 
            }
            int count = feedAdapter.getCount();
            for (int i = 0; i < count; i++) {
                TextView view = (TextView) viewPagerTab.getTabAt(i);
                view.setTextColor(c);
            }
        }
    }

    public void setupFeed(final ArrayList<String> sources) {

        final FragmentPagerItems.Creator c = FragmentPagerItems.with(this);

        if (sources != null && !sources.isEmpty()) {
            String all = getResources().getString(R.string.all_feeds);
            sources.add(0, all);

            for (String source : sources) {
                Bundle bundle = new Bundle();
                bundle.putString("name", source);
                c.add(source, FeedFragment.class, bundle);
            }
        } else {
            // if we get back zero sources, some issue occurred
            // downloading and/or parsing the feed
            showFeedError();
        }

        feedAdapter = new FragmentPagerItemAdapter(
                this.getSupportFragmentManager(), c.create());

        ViewPager viewPager = (ViewPager)this.findViewById(R.id.viewpager);
        viewPager.setAdapter(feedAdapter);

        viewPagerTab = (SmartTabLayout)this.findViewById(R.id.viewpagertab);
        viewPagerTab.setViewPager(viewPager);

        viewPagerTab.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                Fragment f = feedAdapter.getPage(position);
                if (f instanceof FeedFragment) {
                    lastFeedSelected = ((FeedFragment)f).getFeedName();
                }
            }
        });

        View tab = viewPagerTab.getTabAt(0);
        if (tab != null) {
            tab.setSelected(true);
        }

        changeFeedHeaderColor(Service.IsRunning);
    }



    public void sendDesktopVersion(View view) {
        if (LanternUI != null) {
            LanternUI.sendDesktopVersion(view);
        }
    }

    // Prompt the user to enable full-device VPN mode
    // Make a VPN connection from the client
    // We should only have one active VPN connection per client
    public void enableVPN() {
        Log.d(TAG, "Load VPN configuration");
        try {
            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                Log.w(TAG,"Requesting VPN connection");
                startActivityForResult(intent, REQUEST_VPN);
            } else {
                Log.d(TAG, "VPN enabled, starting Lantern...");
                Lantern.disable(getApplicationContext());
                LanternUI.toggleSwitch(true);
                changeFeedHeaderColor(true);
                sendIntentToService();
            }    
        } catch (Exception e) {
            Log.d(TAG, "Could not establish VPN connection: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);

        if (request == REQUEST_VPN) {
            if (response != RESULT_OK) {
                // no permission given to open
                // VPN connection; return to off state
                LanternUI.toggleSwitch(false);
            } else {
                Lantern.disable(getApplicationContext());
                LanternUI.toggleSwitch(true);
                sendIntentToService();
            }
        }
    }

    private void sendIntentToService() {
        startService(new Intent(this, Service.class));
    }

    public void stopLantern() {
        Service.IsRunning = false;
        Utils.clearPreferences(this);
        changeFeedHeaderColor(false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Pass the event to ActionBarDrawerToggle
        // If it returns true, then it has handled
        // the nav drawer indicator touch event
        if (LanternUI != null && LanternUI.optionSelected(item)) {
            return true;
        }

        // Handle your other action bar items...
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (LanternUI != null) {
            LanternUI.syncState();
        }
    }

    // LanternReceiver is used to capture broadcasts 
    // such as network connectivity and when the app
    // is powered off
    public class LanternReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SHUTDOWN)) {
                // whenever the device is powered off or the app
                // abruptly closed, we want to clear user preferences
                Utils.clearPreferences(context);
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                // whenever a user disconnects from Wifi and Lantern is running
                NetworkInfo networkInfo =
                    intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                if (LanternUI.useVpn() && 
                        networkInfo.getType() == ConnectivityManager.TYPE_WIFI &&
                        !networkInfo.isConnected()) {
                    stopLantern();
                }
            }
        }
    }

    public void onActivityResumed(Activity activity) { 
        // we only want to refresh the public feed whenever the
        // app returns to the foreground instead of every
        // time the main activity is resumed
        if (isInBackground) {
            Log.d(TAG, "App in foreground");
            isInBackground = false;
            refreshFeed(null);
        }
    }

    // Below unused
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    public void onActivityDestroyed(Activity activity) {}

    public void onActivityPaused(Activity activity) {}

    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    public void onActivityStarted(Activity activity) {}

    public void onActivityStopped(Activity activity) {}

    @Override
    public void onTrimMemory(int i) {
        // this lets us know when the app process is no longer showing a user
        // interface, i.e. when the app went into the background
        if (i == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Log.d(TAG, "App went to background");
            isInBackground = true;
        }
    }

}
