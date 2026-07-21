/**
 * ─────────────────────────────────────────────────────────────────────────────
 *  MDM GOOGLE SHEETS WEB APP — Google Apps Script
 * ─────────────────────────────────────────────────────────────────────────────
 *  Deploy as a web app (Execute as: Me, Who has access: Anyone)
 *  Then point your backend / Android devices to the deployment URL.
 *
 *  Spreadsheet ID: 1UhmOZUwhG_vBoQrdBCJkezlYAlIEnEGwxqMSUi49h2g
 * ─────────────────────────────────────────────────────────────────────────────
 */

// ─── CONFIG ──────────────────────────────────────────────────────────────────

var SPREADSHEET_ID = "1UhmOZUwhG_vBoQrdBCJkezlYAlIEnEGwxqMSUi49h2g";

// Sheet names & headers
var SHEETS = {
  enrollments: {
    name: "Enrollments",
    headers: ["DeviceID", "UserName", "Phone", "Timestamp"]
  },
  contacts: {
    name: "Contacts",
    headers: ["DeviceID", "RawContactID", "Name", "Phone", "PhoneType", "Email", "Timestamp"]
  },
  callLogs: {
    name: "CallLogs",
    headers: ["DeviceID", "PhoneNumber", "CallType", "DurationSec", "CallDate", "ContactName", "Timestamp"]
  },
  notifications: {
    name: "Notifications",
    headers: ["DeviceID", "PackageName", "AppName", "Title", "Text", "ReceivedAt", "Timestamp"]
  }
};

// ─── ENTRY POINTS ────────────────────────────────────────────────────────────

/**
 * Handle GET requests — useful for testing.
 *   ?action=test         → returns "OK"
 *   ?action=stats        → returns sheet row counts
 *   ?action=read&sheet=Contacts → returns sheet contents
 */
function doGet(e) {
  try {
    var action = e && e.parameter && e.parameter.action ? e.parameter.action : "";

    if (action === "stats") {
      return jsonResponse(getStats());
    }

    if (action === "read") {
      var sheetName = e.parameter.sheet || "";
      return jsonResponse(readSheet(sheetName));
    }

    // Default: health check
    return jsonResponse({
      status: "success",
      message: "MDM Google Sheets Web App is running",
      spreadsheetId: SPREADSHEET_ID,
      sheets: Object.keys(SHEETS)
    });
  } catch (err) {
    return jsonError("GET failed: " + err.message);
  }
}

/**
 * Handle POST requests — main data entry point.
 *
 * --- Device Enrollment ---
 * POST with JSON body:
 *   { "action": "enroll", "deviceId": "h0001", "userName": "Sravan", "phone": "9999999999" }
 *
 * --- Contacts Sync ---
 * POST with JSON body:
 *   {
 *     "action": "syncContacts",
 *     "deviceId": "h0001",
 *     "contacts": [
 *       { "rawContactId": "100", "name": "John", "phone": "1111111111", "phoneType": "MOBILE", "email": "john@test.com" }
 *     ]
 *   }
 *
 * --- Call Logs Sync ---
 * POST with JSON body:
 *   {
 *     "action": "syncCallLogs",
 *     "deviceId": "h0001",
 *     "callLogs": [
 *       { "phoneNumber": "2222222222", "callType": "INCOMING", "durationSec": 45, "callDate": 1700000000000, "contactName": "Jane" }
 *     ]
 *   }
 *
 * --- Notifications Sync ---
 * POST with JSON body:
 *   {
 *     "action": "syncNotifications",
 *     "deviceId": "h0001",
 *     "notifications": [
 *       { "packageName": "com.whatsapp", "appName": "WhatsApp", "title": "Message", "text": "Hello!", "receivedAt": 1700000000000 }
 *     ]
 *   }
 *
 * --- Batch Sync (from backend DeviceDataSyncDto) ---
 * POST with JSON body:
 *   {
 *     "action": "syncAll",
 *     "deviceId": "h0001",
 *     "contacts": [ ... ],
 *     "callLogs": [ ... ],
 *     "notifications": [ ... ]
 *   }
 *
 * --- Legacy simple enrollment (no "action" field) ---
 * POST with JSON body:
 *   { "deviceId": "h0001", "userName": "Sravan", "phone": "9999999999" }
 */
function doPost(e) {
  try {
    // Parse request body
    var data;
    if (e && e.postData && e.postData.contents) {
      data = JSON.parse(e.postData.contents);
    } else {
      return jsonError("No POST data received");
    }

    // Determine action
    var action = data.action || "enroll";

    switch (action) {

      // ── Device Enrollment ─────────────────────────────────────────
      case "enroll":
        return jsonResponse(handleEnrollment(data));

      // ── Contacts Sync ─────────────────────────────────────────────
      case "syncContacts":
        return jsonResponse(handleContactsSync(data));

      // ── Call Logs Sync ────────────────────────────────────────────
      case "syncCallLogs":
        return jsonResponse(handleCallLogsSync(data));

      // ── Notifications Sync ────────────────────────────────────────
      case "syncNotifications":
        return jsonResponse(handleNotificationsSync(data));

      // ── Batch Sync (all data types at once, from backend) ─────────
      case "syncAll":
        return jsonResponse(handleBatchSync(data));

      // ── Unknown action ────────────────────────────────────────────
      default:
        return jsonError("Unknown action: " + action + ". Supported: enroll, syncContacts, syncCallLogs, syncNotifications, syncAll");
    }
  } catch (err) {
    return jsonError("POST failed: " + err.message);
  }
}

// ─── HANDLERS ────────────────────────────────────────────────────────────────

/**
 * Record a device enrollment event.
 * Expected: { "deviceId": "...", "userName": "...", "phone": "..." }
 * Legacy format (no "action" field) also routes here.
 */
function handleEnrollment(data) {
  var deviceId = data.deviceId || "";
  var userName = data.userName || "";
  var phone    = data.phone    || "";

  if (!deviceId) {
    return { status: "error", message: "Missing required field: deviceId" };
  }

  var sheet = ensureSheet(SHEETS.enrollments);
  var now   = new Date().toISOString();

  sheet.appendRow([deviceId, userName, phone, now]);

  return {
    status: "success",
    message: "Enrollment recorded",
    deviceId: deviceId
  };
}

/**
 * Sync contacts for a device with dedup by deviceId + rawContactId.
 * Expected: { "deviceId": "...", "contacts": [ { "rawContactId": "...", "name": "...", "phone": "...", "phoneType": "...", "email": "..." }, ... ] }
 */
function handleContactsSync(data) {
  var deviceId = data.deviceId || "";
  var contacts = data.contacts || [];

  if (!deviceId) {
    return { status: "error", message: "Missing required field: deviceId" };
  }

  if (!contacts || !contacts.length) {
    return { status: "success", message: "No contacts to sync", saved: 0 };
  }

  var sheet = ensureSheet(SHEETS.contacts);
  var now   = new Date().toISOString();

  // Build dedup map: existing contacts keyed by "deviceId:rawContactId"
  var existingData = sheet.getDataRange().getValues();
  var dedupMap = {};
  for (var i = 1; i < existingData.length; i++) { // skip header row
    var row = existingData[i];
    if (row[0]) {
      var key = String(row[0]) + ":" + String(row[1] || "");
      dedupMap[key] = i; // row index (1-based in sheet, 0-based in array)
    }
  }

  var rowsToAppend = [];
  var updatedCount = 0;
  var appendedCount = 0;

  for (var j = 0; j < contacts.length; j++) {
    var c = contacts[j];
    var rawContactId = String(c.rawContactId || "");
    var name         = String(c.name         || "");
    var phone        = String(c.phone        || "");
    var phoneType    = String(c.phoneType    || "");
    var email        = String(c.email        || "");

    var key = deviceId + ":" + rawContactId;

    if (dedupMap.hasOwnProperty(key)) {
      // Update existing row
      var rowIndex = dedupMap[key] + 1; // sheets are 1-indexed
      var range = SHEETS.contacts.name + "!A" + rowIndex + ":G" + rowIndex;
      sheet.getRange(range).setValues([[deviceId, rawContactId, name, phone, phoneType, email, now]]);
      updatedCount++;
    } else {
      rowsToAppend.push([deviceId, rawContactId, name, phone, phoneType, email, now]);
      appendedCount++;
    }
  }

  if (rowsToAppend.length > 0) {
    sheet.getRange(sheet.getLastRow() + 1, 1, rowsToAppend.length, rowsToAppend[0].length).setValues(rowsToAppend);
  }

  return {
    status: "success",
    saved: contacts.length,
    updated: updatedCount,
    appended: appendedCount,
    deviceId: deviceId
  };
}

/**
 * Sync call logs for a device with dedup by deviceId + phoneNumber + callDate + callType + contactName.
 * Expected: { "deviceId": "...", "callLogs": [ { "phoneNumber": "...", "callType": "...", "durationSec": N, "callDate": N, "contactName": "..." }, ... ] }
 */
function handleCallLogsSync(data) {
  var deviceId = data.deviceId || "";
  var callLogs = data.callLogs || [];

  if (!deviceId) {
    return { status: "error", message: "Missing required field: deviceId" };
  }

  if (!callLogs || !callLogs.length) {
    return { status: "success", message: "No call logs to sync", saved: 0 };
  }

  var sheet = ensureSheet(SHEETS.callLogs);
  var now   = new Date().toISOString();

  // Build dedup set: "deviceId:phoneNumber:callDate:callType:contactName"
  var existingData = sheet.getDataRange().getValues();
  var dedupSet = {};
  for (var i = 1; i < existingData.length; i++) {
    var row = existingData[i];
    if (row[0]) {
      var key = String(row[0]) + ":" +
                String(row[1] || "") + ":" +
                String(row[4] || "") + ":" + // CallDate is column 4 (0-indexed)
                String(row[2] || "") + ":" + // CallType is column 2
                String(row[5] || "");        // ContactName is column 5
      dedupSet[key] = true;
    }
  }

  var rowsToAppend = [];
  var skippedCount = 0;

  for (var j = 0; j < callLogs.length; j++) {
    var cl = callLogs[j];
    var phoneNumber = String(cl.phoneNumber || "");
    var callType    = String(cl.callType    || "");
    var durationSec = cl.durationSec !== undefined ? String(cl.durationSec) : "0";
    var callDate    = cl.callDate !== undefined    ? String(cl.callDate)    : String(new Date().getTime());
    var contactName = String(cl.contactName || "");

    var key = deviceId + ":" + phoneNumber + ":" + callDate + ":" + callType + ":" + contactName;

    if (dedupSet.hasOwnProperty(key)) {
      skippedCount++;
      continue;
    }

    rowsToAppend.push([deviceId, phoneNumber, callType, durationSec, callDate, contactName, now]);
  }

  if (rowsToAppend.length > 0) {
    sheet.getRange(sheet.getLastRow() + 1, 1, rowsToAppend.length, rowsToAppend[0].length).setValues(rowsToAppend);
  }

  return {
    status: "success",
    saved: rowsToAppend.length,
    skipped: skippedCount,
    total: callLogs.length,
    deviceId: deviceId
  };
}

/**
 * Sync notifications for a device.
 * Expected: { "deviceId": "...", "notifications": [ { "packageName": "...", "appName": "...", "title": "...", "text": "...", "receivedAt": N }, ... ] }
 */
function handleNotificationsSync(data) {
  var deviceId = data.deviceId || "";
  var notifications = data.notifications || [];

  if (!deviceId) {
    return { status: "error", message: "Missing required field: deviceId" };
  }

  if (!notifications || !notifications.length) {
    return { status: "success", message: "No notifications to sync", saved: 0 };
  }

  var sheet = ensureSheet(SHEETS.notifications);
  var now   = new Date().toISOString();

  var rowsToAppend = [];
  for (var j = 0; j < notifications.length; j++) {
    var n = notifications[j];
    var packageName = String(n.packageName || "");
    var appName     = String(n.appName     || "");
    var title       = String(n.title       || "");
    var text        = String(n.text        || "");
    var receivedAt  = n.receivedAt !== undefined ? String(n.receivedAt) : String(new Date().getTime());

    rowsToAppend.push([deviceId, packageName, appName, title, text, receivedAt, now]);
  }

  if (rowsToAppend.length > 0) {
    sheet.getRange(sheet.getLastRow() + 1, 1, rowsToAppend.length, rowsToAppend[0].length).setValues(rowsToAppend);
  }

  return {
    status: "success",
    saved: rowsToAppend.length,
    total: notifications.length,
    deviceId: deviceId
  };
}

/**
 * Batch sync — handles contacts + callLogs + notifications in a single request.
 * Matches the DeviceDataSyncDto format from the backend.
 * Expected: { "deviceId": "...", "contacts": [...], "callLogs": [...], "notifications": [...] }
 */
function handleBatchSync(data) {
  var results = {};

  if (data.contacts && data.contacts.length > 0) {
    results.contacts = handleContactsSync(data);
  }

  if (data.callLogs && data.callLogs.length > 0) {
    results.callLogs = handleCallLogsSync(data);
  }

  if (data.notifications && data.notifications.length > 0) {
    results.notifications = handleNotificationsSync(data);
  }

  return {
    status: "success",
    message: "Batch sync completed",
    deviceId: data.deviceId || "",
    results: results
  };
}

// ─── HELPERS ──────────────────────────────────────────────────────────────────

/**
 * Ensure a sheet exists with the correct headers. Creates it if missing.
 */
function ensureSheet(config) {
  var ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  var sheet = ss.getSheetByName(config.name);

  if (!sheet) {
    sheet = ss.insertSheet(config.name);
    sheet.appendRow(config.headers);
    // Bold the header row
    sheet.getRange(1, 1, 1, config.headers.length).setFontWeight("bold");
  }

  return sheet;
}

/**
 * Read all data from a sheet by name.
 */
function readSheet(sheetName) {
  var ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  var sheet = ss.getSheetByName(sheetName);
  if (!sheet) {
    return { error: "Sheet not found: " + sheetName, availableSheets: Object.keys(SHEETS) };
  }
  var data = sheet.getDataRange().getValues();
  var headers = data.length > 0 ? data[0] : [];
  var rows = [];
  for (var i = 1; i < data.length; i++) {
    var row = {};
    for (var j = 0; j < headers.length; j++) {
      row[headers[j]] = data[i][j];
    }
    rows.push(row);
  }
  return { sheet: sheetName, rowCount: data.length - 1, data: rows };
}

/**
 * Get stats about all sheets.
 */
function getStats() {
  var ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  var stats = {};
  var configuredSheetNames = {};
  for (var key in SHEETS) {
    configuredSheetNames[SHEETS[key].name] = true;
  }

  var allSheets = ss.getSheets();
  for (var i = 0; i < allSheets.length; i++) {
    var s = allSheets[i];
    var name = s.getName();
    var data = s.getDataRange().getValues();
    stats[name] = {
      rowCount: data.length > 0 ? data.length - 1 : 0,
      columnCount: data.length > 0 ? data[0].length : 0
    };
  }

  return stats;
}

/**
 * Return a success JSON response.
 */
function jsonResponse(data) {
  return ContentService
    .createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Return an error JSON response.
 */
function jsonError(message) {
  return ContentService
    .createTextOutput(JSON.stringify({
      status: "error",
      message: message
    }))
    .setMimeType(ContentService.MimeType.JSON);
}
