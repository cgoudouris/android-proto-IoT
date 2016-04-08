package io.relayr.iotsmartphone.tabs;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.concurrent.TimeUnit;

import butterknife.ButterKnife;
import butterknife.InjectView;
import de.greenrobot.event.EventBus;
import io.relayr.android.RelayrSdk;
import io.relayr.iotsmartphone.R;
import io.relayr.iotsmartphone.helper.FlashHelper;
import io.relayr.iotsmartphone.helper.SoundHelper;
import io.relayr.iotsmartphone.tabs.cloud.FragmentCloud;
import io.relayr.iotsmartphone.tabs.helper.Constants;
import io.relayr.iotsmartphone.tabs.helper.ReadingUtils;
import io.relayr.iotsmartphone.tabs.helper.SettingsStorage;
import io.relayr.iotsmartphone.tabs.readings.FragmentReadings;
import io.relayr.iotsmartphone.tabs.rules.FragmentRules;
import io.relayr.java.model.action.Command;
import io.relayr.java.model.action.Reading;
import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.Intent.ACTION_BATTERY_CHANGED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.hardware.Sensor.TYPE_GYROSCOPE;
import static android.hardware.Sensor.TYPE_LIGHT;
import static android.hardware.Sensor.TYPE_LINEAR_ACCELERATION;
import static android.hardware.SensorManager.SENSOR_DELAY_NORMAL;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_SCALE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.widget.Toast.LENGTH_SHORT;
import static io.relayr.iotsmartphone.tabs.helper.Constants.DeviceType.PHONE;
import static io.relayr.iotsmartphone.tabs.helper.SettingsStorage.FREQS_PHONE;

public class MainTabActivity extends AppCompatActivity implements
        SensorEventListener, LocationListener,
        DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    @InjectView(R.id.toolbar) Toolbar mToolbar;
    @InjectView(R.id.viewpager) ViewPager mViewPager;
    @InjectView(R.id.tabs) TabLayout mTabView;

    private FlashHelper mFlash;
    private SoundHelper mSound;
    private WifiManager mWifiManager;
    private SensorManager mSensorManager;
    private LocationManager mLocationManager;
    private ConnectivityManager mConnectivityManager;

    private Subscription mCommandsSubscription = null;
    private long mAccelerationChange = 0;
    private long mGyroscopeChange = 0;
    private long mLightChange = 0;

    private Subscription mRefreshSubs;

    private GoogleApiClient mGoogleApiClient;

    private final Fragment[] mFragments = new Fragment[3];
    private boolean mResolvingError;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tab_main);
        ButterKnife.inject(this);

        setSupportActionBar(mToolbar);
        setupViewPager(state);
        setUpTabs();

        ReadingUtils.getReadings();

        IntentFilter messageFilter = new IntentFilter(Intent.ACTION_SEND);
        MessageReceiver messageReceiver = new MessageReceiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, messageFilter);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        if (!mResolvingError) mGoogleApiClient.connect();
    }

    @Override protected void onResume() {
        super.onResume();

        EventBus.getDefault().register(this);
        initReadings();
        if (mRefreshSubs == null)
            mRefreshSubs = Observable.interval(1, TimeUnit.SECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Long>() {
                        @Override public void onCompleted() {}

                        @Override public void onError(Throwable e) {
                            Log.e("MTA", "Failed while refreshing");
                            e.printStackTrace();
                        }

                        @Override public void onNext(Long num) {
                            if (num % FREQS_PHONE.get("rssi") == 0) monitorWiFi();
                            if (num % FREQS_PHONE.get("location") == 0) monitorLocation();
                            if (num % FREQS_PHONE.get("batteryLevel") == 0) monitorBattery();
                            ReadingUtils.publish(new Reading(0, System.currentTimeMillis(), "touch", "/", false));
                        }
                    });
    }

    @Override protected void onPause() {
        super.onPause();
        EventBus.getDefault().unregister(this);

        turnSensorOff();
        if (mRefreshSubs != null) mRefreshSubs.unsubscribe();
        mRefreshSubs = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case 100: {
                final boolean granted = grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED;
                Crashlytics.log(Log.INFO, "MA", "User granted permission: " + granted);
                SettingsStorage.instance().locationPermission(granted);
                initLocationManager();
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN)
            ReadingUtils.publish(new Reading(0, System.currentTimeMillis(), "touch", "/", true));
        else if (ev.getAction() == MotionEvent.ACTION_UP)
            ReadingUtils.publish(new Reading(0, System.currentTimeMillis(), "touch", "/", false));
        return super.dispatchTouchEvent(ev);
    }

    @SuppressWarnings("unused") public void onEvent(Constants.WatchSelected event) {
        sendToWearable();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        mResolvingError = false;
        Wearable.DataApi.addListener(mGoogleApiClient, this);
        sendToWearable();
    }

    @Override
    public void onConnectionSuspended(int cause) {}

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult result) {
        if (mResolvingError) return;
        if (result.hasResolution()) {
            try {
                mResolvingError = true;
                result.startResolutionForResult(this, Constants.REQUEST_RESOLVE_ERROR);
            } catch (IntentSender.SendIntentException e) {
                mGoogleApiClient.connect();
            }
        } else {
            mResolvingError = false;
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : FreezableUtils.freezeIterable(dataEvents))
            if (event.getType() == DataEvent.TYPE_CHANGED)
                ReadingUtils.publishWatch(event.getDataItem());
    }

    public class MessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            toggleFlash(false);
        }
    }

    @Override public void onProviderEnabled(String provider) {
        initLocationManager();
    }

    @Override public void onProviderDisabled(String provider) {
        //        if (mLocSwitch != null) mLocSwitch.setChecked(false);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onLocationChanged(Location location) {}

    @Override
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public void onSensorChanged(SensorEvent e) {
        final long millis = System.currentTimeMillis();
        if (e.sensor.getType() == TYPE_LINEAR_ACCELERATION &&
                millis - FREQS_PHONE.get("acceleration") > mAccelerationChange) {
            mAccelerationChange = millis;
            ReadingUtils.publish(ReadingUtils.createAccelReading(e.values[0], e.values[1], e.values[2]));
        } else if (e.sensor.getType() == TYPE_GYROSCOPE &&
                millis - FREQS_PHONE.get("angularSpeed") > mGyroscopeChange) {
            ReadingUtils.publish(ReadingUtils.createGyroReading(e.values[0], e.values[1], e.values[2]));
            mGyroscopeChange = millis;
        } else if (e.sensor.getType() == TYPE_LIGHT &&
                millis - FREQS_PHONE.get("luminosity") > mLightChange) {
            ReadingUtils.publish(new Reading(0, System.currentTimeMillis(), "luminosity", "/", e.values[0]));
            mLightChange = millis;
        }
    }

    private void setupViewPager(Bundle savedInstanceState) {
        if (savedInstanceState != null &&
                getSupportFragmentManager() != null &&
                getSupportFragmentManager().getFragments() != null) {
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof FragmentReadings)
                    mFragments[0] = fragment;
                else if (fragment instanceof FragmentCloud)
                    mFragments[1] = fragment;
                else if (fragment instanceof FragmentRules)
                    mFragments[2] = fragment;
            }
        } else {
            mFragments[0] = new FragmentReadings();
            mFragments[1] = new FragmentCloud();
            mFragments[2] = new FragmentRules();
        }

        if (getSupportFragmentManager() == null) return;
        ViewPagerAdapter mViewPagerAdapter = new ViewPagerAdapter(getSupportFragmentManager());
        mViewPagerAdapter.addFrag(mFragments[0]);
        mViewPagerAdapter.addFrag(mFragments[1]);
        mViewPagerAdapter.addFrag(mFragments[2]);

        mViewPager.setAdapter(mViewPagerAdapter);
    }

    private void setUpTabs() {
        mTabView.setupWithViewPager(mViewPager);
        mTabView.setSelectedTabIndicatorHeight(getResources().getDimensionPixelSize(R.dimen.default_padding));
        mTabView.setOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                mViewPager.setCurrentItem(tab.getPosition());
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}

            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        setupTabIcons();
    }

    private void setupTabIcons() {
        if (mTabView == null) return;
        mTabView.getTabAt(0).setIcon(R.drawable.ic_tab_hardware);
        mTabView.getTabAt(1).setIcon(R.drawable.ic_tab_cloud);
        mTabView.getTabAt(2).setIcon(R.drawable.ic_tab_rule);
    }

    private void initReadings() {
        initSensorManager();
        initWifiManager();
        monitorBattery();

        //        if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PERMISSION_GRANTED &&
        //                ActivityCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED) {
        //            ActivityCompat.requestPermissions(this, new String[]{ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION}, 100);
        //        } else {
        //            initLocationManager();
        //        }
    }

    private void initSensorManager() {
        if (mSensorManager == null)
            mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        final Sensor acceleration = mSensorManager.getDefaultSensor(TYPE_LINEAR_ACCELERATION);
        if (acceleration != null)
            mSensorManager.registerListener(this, acceleration, SENSOR_DELAY_NORMAL);

        final Sensor gyroscope = mSensorManager.getDefaultSensor(TYPE_GYROSCOPE);
        if (gyroscope != null)
            mSensorManager.registerListener(this, gyroscope, SENSOR_DELAY_NORMAL);

        final Sensor light = mSensorManager.getDefaultSensor(TYPE_LIGHT);
        if (light != null)
            mSensorManager.registerListener(this, light, SENSOR_DELAY_NORMAL);
    }

    private void turnSensorOff() {
        if (mSensorManager != null)
            mSensorManager.unregisterListener(this);
    }

    private void initWifiManager() {
        if (mWifiManager != null && mConnectivityManager != null) return;
        mWifiManager = (WifiManager) MainTabActivity.this.getSystemService(WIFI_SERVICE);
        mConnectivityManager = (ConnectivityManager) MainTabActivity.this.getSystemService(CONNECTIVITY_SERVICE);
        monitorWiFi();
    }

    private void monitorWiFi() {
        if (mConnectivityManager == null || mWifiManager == null) return;

        if (!checkWifi(mConnectivityManager))
            Toast.makeText(MainTabActivity.this, R.string.sv_no_wifi, LENGTH_SHORT).show();

        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        if (wifiInfo != null)
            ReadingUtils.publish(new Reading(0, System.currentTimeMillis(), "rssi", "wifi", wifiInfo.getRssi()));
    }

    private boolean checkWifi(ConnectivityManager cm) {
        if (SDK_INT >= LOLLIPOP) {
            for (Network net : cm.getAllNetworks()) {
                NetworkInfo networkInfo = cm.getNetworkInfo(net);
                if (networkInfo != null && networkInfo.getType() == TYPE_WIFI)
                    return networkInfo.isConnected();
            }
        } else {
            //noinspection deprecation
            NetworkInfo networkInfo = cm.getNetworkInfo(TYPE_WIFI);
            return networkInfo != null && networkInfo.isConnected();
        }
        return false;
    }

    private void monitorBattery() {
        Intent batteryIntent = MainTabActivity.this.registerReceiver(null, new IntentFilter(ACTION_BATTERY_CHANGED));
        int level = batteryIntent != null ? batteryIntent.getIntExtra(EXTRA_LEVEL, -1) : 0;
        int scale = batteryIntent != null ? batteryIntent.getIntExtra(EXTRA_SCALE, -1) : 0;

        float bat;
        if (level == -1 || scale == -1) bat = 50.0f;
        else bat = ((float) level / (float) scale) * 100.0f;

        ReadingUtils.publish(new Reading(0, System.currentTimeMillis(), "batteryLevel", "/", bat));
    }

    private void initLocationManager() {
        if (SettingsStorage.instance().locationGranted()) {
            new Handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (mLocationManager == null)
                        mLocationManager = (LocationManager) MainTabActivity.this.getSystemService(LOCATION_SERVICE);
                    if (ContextCompat.checkSelfPermission(MainTabActivity.this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) {
                        try {
                            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, MainTabActivity.this);
                            monitorLocation();
                        } catch (Exception e) {
                            Crashlytics.log(Log.ERROR, "SRV", "GPS_PROVIDER doesn't exist.");
                            try {
                                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, MainTabActivity.this);
                                monitorLocation();
                            } catch (Exception e1) {
                                Crashlytics.log(Log.ERROR, "SRV", "NETWORK_PROVIDER doesn't exist.");
                            }
                        }
                    }
                }
            }, 500);
        }
    }

    private void monitorLocation() {
        if (mLocationManager == null) return;
        new Handler().post(new Runnable() {
            @Override public void run() {
                if (ContextCompat.checkSelfPermission(MainTabActivity.this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(MainTabActivity.this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED) {
                    Location location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (location == null)
                        location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (location != null)
                        ReadingUtils.publishLocation(MainTabActivity.this, location);
                    else showLocationDialog();
                }
            }
        });
    }

    //    private void turnOffLocation() {
    //        if (mLocationManager != null)
    //            if (ContextCompat.checkSelfPermission(MainTabActivity.this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED &&
    //                    ContextCompat.checkSelfPermission(MainTabActivity.this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED)
    //                mLocationManager.removeUpdates(this);
    //    }

    private void showLocationDialog() {
        new AlertDialog.Builder(this, R.style.AppTheme_DialogOverlay)
                .setTitle(this.getString(R.string.sv_location_off_title))
                .setIcon(R.drawable.ic_warning)
                .setMessage(MainTabActivity.this.getString(R.string.sv_location_off_message))
                .setPositiveButton(MainTabActivity.this.getString(R.string.ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int i) {
                        dialog.dismiss();
                        Intent myIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        MainTabActivity.this.startActivity(myIntent);
                    }
                })
                .setNegativeButton(MainTabActivity.this.getString(R.string.cancel), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void createFlashHelper() {
        if (mFlash != null) return;

        mFlash = new FlashHelper();
        try {
            mFlash.open(MainTabActivity.this.getApplicationContext());
        } catch (Exception e) {
            Crashlytics.log(Log.ERROR, "SRV", "Failed to create FlashHelper.");
            e.printStackTrace();

            Toast.makeText(MainTabActivity.this, R.string.sv_err_using_flash, Toast.LENGTH_SHORT).show();
            mFlash.close();
            mFlash = null;
        }
    }

    private void createSoundHelper() {
        if (mSound != null) return;
        mSound = new SoundHelper();
    }

    private void subscribeToCommands() {
        if (mCommandsSubscription == null)
            mCommandsSubscription = RelayrSdk.getWebSocketClient()
                    .subscribeToCommands(SettingsStorage.instance().getDeviceId(PHONE))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Subscriber<Command>() {
                        @Override public void onCompleted() {}

                        @Override public void onError(Throwable e) {
                            Crashlytics.log(Log.ERROR, "SettingsView", "subscribeToCommands - error");
                            Crashlytics.logException(e);
                        }

                        @Override public void onNext(Command action) {
                            final String cmd = action.getName();
                            Crashlytics.log(Log.DEBUG, "SettingsView", "CMD - " + cmd);
                            if (cmd.equals("flashlight"))
                                toggleFlash(Boolean.parseBoolean(String.valueOf(action.getValue())));
                            if (cmd.equals("playSound")) playMusic((String) action.getValue());
                        }
                    });
    }

    private void toggleFlash(boolean on) {
        if (mFlash != null && !mFlash.hasFlash(MainTabActivity.this)) {
            Toast.makeText(MainTabActivity.this, R.string.sv_flashlight_not_available, LENGTH_SHORT).show();
        } else {
            //            showNotification(on, mSettings[6]);
            if (mFlash == null) return;
            if (on) mFlash.on();
            else mFlash.off();
        }
    }

    private void playMusic(String value) {
        if (value == null) return;
        if (mSound == null) createSoundHelper();

        mSound.playMusic(MainTabActivity.this, value);
    }

    private void sendToWearable() {
        DataMap dataMap = new DataMap();
        dataMap.putLong(Constants.ACTIVATE, System.currentTimeMillis());
        new SendToDataLayerThread(Constants.ACTIVATE_PATH, dataMap).start();
    }

    class SendToDataLayerThread extends Thread {
        String path;
        DataMap dataMap;

        SendToDataLayerThread(String p, DataMap data) {
            path = p;
            dataMap = data;
        }

        public void run() {
            PutDataMapRequest putDMR = PutDataMapRequest.create(path);
            putDMR.getDataMap().putAll(dataMap);
            PutDataRequest request = putDMR.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, request).await();
        }
    }
}
