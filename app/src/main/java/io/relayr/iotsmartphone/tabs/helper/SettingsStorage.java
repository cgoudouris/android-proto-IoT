package io.relayr.iotsmartphone.tabs.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.relayr.iotsmartphone.IotApplication;
import io.relayr.java.model.Device;
import io.relayr.java.model.models.transport.DeviceReading;

import static io.relayr.iotsmartphone.tabs.helper.Constants.DeviceType.PHONE;
import static io.relayr.iotsmartphone.tabs.helper.Constants.DeviceType.WATCH;

public class SettingsStorage {

    public static final String MODEL_PHONE = "86e0a7d7-5e18-449c-b7aa-f3b089c33b67";
    public static final String MODEL_WATCH = "b42cc83d-5766-406c-872d-9294d96bdf69";

    private static final String PREFS_NAME = "io.relayr.iotsp.settings";

    private static final String PREFS_WARNING = "io.relayr.warning";
    private static final String PREFS_SETTINGS_LOCATION = "io.relayr.iotsp.permission.location";

    private static final String PREFS_ACTIVE_PHONE = "active_p";
    private static final String PREFS_ACTIVE_WATCH = "active_w";
    private static final String PREFS_FREQUENCY_PHONE = "freq_p";
    private static final String PREFS_FREQUENCY_WATCH = "freq_w";

    private static final String PREFS_PHONE_ID = "phone_id";
    private static final String PREFS_PHONE_NAME = "phone_name";
    private static final String PREFS_PHONE_SDK = "phone_sdk";

    private static final String PREFS_WATCH_ID = "watch_id";
    private static final String PREFS_WATCH_NAME = "watch_name";
    private static final String PREFS_WATCH_SDK = "watch_sdk";

    private static List<DeviceReading> sReadingsPhone = new ArrayList<>();
    private static List<DeviceReading> sReadingsWatch = new ArrayList<>();

    private static String sPhoneId;
    private static String sWatchId;

    private final static SettingsStorage singleton = new SettingsStorage();
    private final SharedPreferences PREFS;

    private SettingsStorage() {
        PREFS = IotApplication.context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static Map<String, Integer> FREQS_PHONE = new HashMap<String, Integer>() {
        {
            put("acceleration", SettingsStorage.instance().loadFrequency("acceleration", PHONE));
            put("angularSpeed", SettingsStorage.instance().loadFrequency("angularSpeed", PHONE));
            put("batteryLevel", SettingsStorage.instance().loadFrequency("batteryLevel", PHONE));
            put("luminosity", SettingsStorage.instance().loadFrequency("luminosity", PHONE));
            put("location", SettingsStorage.instance().loadFrequency("location", PHONE));
            put("rssi", SettingsStorage.instance().loadFrequency("rssi", PHONE));
        }
    };

    public static Map<String, Integer> FREQS_WATCH = new HashMap<String, Integer>() {
        {
            put("acceleration", SettingsStorage.instance().loadFrequency("acceleration", WATCH));
            put("batteryLevel", SettingsStorage.instance().loadFrequency("batteryLevel", WATCH));
            put("luminosity", SettingsStorage.instance().loadFrequency("luminosity", WATCH));
        }
    };

    public static Map<String, Boolean> ACTIVITY_PHONE = new HashMap<String, Boolean>() {
        {
            put("acceleration", SettingsStorage.instance().loadActivity("acceleration", PHONE));
            put("angularSpeed", SettingsStorage.instance().loadActivity("angularSpeed", PHONE));
            put("batteryLevel", SettingsStorage.instance().loadActivity("batteryLevel", PHONE));
            put("luminosity", SettingsStorage.instance().loadActivity("luminosity", PHONE));
            put("location", SettingsStorage.instance().loadActivity("location", PHONE));
            put("rssi", SettingsStorage.instance().loadActivity("rssi", PHONE));
            put("touch", SettingsStorage.instance().loadActivity("touch", PHONE));
        }
    };

    public static Map<String, Boolean> ACTIVITY_WATCH = new HashMap<String, Boolean>() {
        {
            put("acceleration", SettingsStorage.instance().loadActivity("acceleration", WATCH));
            put("batteryLevel", SettingsStorage.instance().loadActivity("batteryLevel", WATCH));
            put("luminosity", SettingsStorage.instance().loadActivity("luminosity", WATCH));
            put("touch", SettingsStorage.instance().loadActivity("touch", WATCH));
        }
    };

    public static SettingsStorage instance() {return singleton;}

    public void saveDevice(Device device, Constants.DeviceType type) {
        Log.e("Storage", device.toString());
        if (type == PHONE) {
            PREFS.edit().putString(PREFS_PHONE_ID, device.getId()).apply();
            PREFS.edit().putString(PREFS_PHONE_NAME, device.getName()).apply();
        } else {
            PREFS.edit().putString(PREFS_WATCH_ID, device.getId()).apply();
            PREFS.edit().putString(PREFS_WATCH_NAME, device.getName()).apply();
        }
    }

    public void saveActivity(String meaning, Constants.DeviceType type, boolean active) {
        if (type == PHONE) ACTIVITY_PHONE.put(meaning, active);
        else ACTIVITY_WATCH.put(meaning, active);
        PREFS.edit().putBoolean((type == PHONE ? PREFS_ACTIVE_PHONE : PREFS_ACTIVE_WATCH) + meaning, active).apply();
    }

    private boolean loadActivity(String meaning, Constants.DeviceType type) {
        return PREFS.getBoolean((type == PHONE ? PREFS_ACTIVE_PHONE : PREFS_ACTIVE_WATCH) + meaning, false);
    }

    public void saveFrequency(String meaning, Constants.DeviceType type, int freq) {
        if (type == PHONE) FREQS_PHONE.put(meaning, freq);
        else FREQS_WATCH.put(meaning, freq);

        PREFS.edit().putInt((type == PHONE ? PREFS_FREQUENCY_PHONE : PREFS_FREQUENCY_WATCH) + meaning, freq).apply();
    }

    public int loadFrequency(String meaning, Constants.DeviceType type) {
        return PREFS.getInt((type == PHONE ? PREFS_FREQUENCY_PHONE : PREFS_FREQUENCY_WATCH) + meaning, 3);
    }

    public void locationPermission(boolean granted) {
        PREFS.edit().putBoolean(PREFS_SETTINGS_LOCATION, granted).apply();
    }

    public boolean locationGranted() {
        return PREFS.getBoolean(PREFS_SETTINGS_LOCATION, true);
    }

    public void warningShown() {
        PREFS.edit().putBoolean(PREFS_WARNING, true).apply();
    }

    public boolean isWarningShown() {
        return PREFS.getBoolean(PREFS_WARNING, false);
    }

    public void savePhoneData(String name, int sdk) {
        PREFS.edit().putString(PREFS_PHONE_NAME, name).apply();
        PREFS.edit().putInt(PREFS_PHONE_SDK, sdk).apply();
    }

    public String getDeviceName(Constants.DeviceType type) {
        if (type == PHONE) return PREFS.getString(PREFS_PHONE_NAME, null);
        else return PREFS.getString(PREFS_WATCH_NAME, null);
    }

    public String getDeviceId(Constants.DeviceType type) {
        if (type == PHONE) {
            sPhoneId = PREFS.getString(PREFS_PHONE_ID, null);
            return sPhoneId;
        } else {
            sWatchId = PREFS.getString(PREFS_WATCH_ID, null);
            return sWatchId;
        }
    }

    public int getDeviceSdk(Constants.DeviceType type) {
        if (type == PHONE) return PREFS.getInt(PREFS_PHONE_SDK, 0);
        else return PREFS.getInt(PREFS_WATCH_SDK, 0);
    }

    //DEVICE MODEL DATA
    public void savePhoneReadings(List<DeviceReading> readings) {
        sReadingsPhone.clear();
        sReadingsPhone.addAll(readings);
        Collections.sort(sReadingsPhone, new Comparator<DeviceReading>() {
            @Override public int compare(DeviceReading lhs, DeviceReading rhs) {
                return lhs.getMeaning().compareTo(rhs.getMeaning());
            }
        });
    }

    public List<DeviceReading> loadReadings(Constants.DeviceType type) {
        if (type == PHONE) return sReadingsPhone;
        else return sReadingsWatch;
    }

    public void saveWatchReadings(List<DeviceReading> readings) {
        sReadingsWatch.clear();
        sReadingsWatch.addAll(readings);
        Collections.sort(sReadingsWatch, new Comparator<DeviceReading>() {
            @Override public int compare(DeviceReading lhs, DeviceReading rhs) {
                return lhs.getMeaning().compareTo(rhs.getMeaning());
            }
        });
    }

    public void logOut() {
        sPhoneId = null;
        sWatchId = null;

        PREFS.edit().remove(PREFS_PHONE_ID).apply();
        PREFS.edit().remove(PREFS_WATCH_ID).apply();
        PREFS.edit().remove(PREFS_WARNING).apply();
    }
}