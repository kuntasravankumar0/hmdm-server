const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const path = require('path');
const jwt = require('jsonwebtoken');
const { initDatabase, checkMemory, startMemoryMonitor } = require('./db');

// Import routes
const contactsRouter = require('./routes/contacts');
const calllogsRouter = require('./routes/calllogs');
const notificationsRouter = require('./routes/notifications');

const app = express();
const PORT = process.env.PORT || 3001;
const API_KEY = process.env.API_KEY || '';

// JWT config (override via env var for production)
const JWT_SECRET = process.env.JWT_SECRET || 'hmdm-data-api-secret-key-2024';
const JWT_EXPIRES_IN = '24h';

// Local admin credentials (override via env vars)
const ADMIN_LOGIN = process.env.ADMIN_LOGIN || 'Sravan@admin.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Sravan@123';

// DB ready flag — set to true after successful init
let dbReady = false;

// === SECURITY & PARSING ===
app.use(helmet({ contentSecurityPolicy: false }));
app.use(cors());
app.use(express.json({ limit: '2mb' }));
app.use(express.urlencoded({ extended: true, limit: '1mb' }));

// === REQUEST TIMEOUT ===
app.use((req, res, next) => {
  if (req.method === 'POST') {
    req.setTimeout(30000, () => {
      console.warn(`[TIMEOUT] ${req.method} ${req.path}`);
      res.status(408).json({ status: 'error', message: 'Request timed out' });
    });
  }
  next();
});

// === MEMORY CHECK ===
app.use((req, res, next) => {
  if (req.method === 'POST' && checkMemory()) {
    return res.status(503).json({ status: 'error', message: 'Server busy, try again shortly' });
  }
  next();
});

// === JWT AUTH MIDDLEWARE ===
function extractToken(req) {
  const authHeader = req.headers['authorization'];
  if (authHeader && authHeader.startsWith('Bearer ')) {
    return authHeader.substring(7);
  }
  if (req.query.token) {
    return req.query.token;
  }
  return null;
}

function authMiddleware(req, res, next) {
  // Public paths (no auth required)
  if (req.path === '/health' || req.path.startsWith('/api/auth') || req.path === '/api/seed' || req.path === '/wakeup') {
    return next();
  }

  // Only protect /api/* routes
  if (req.path.startsWith('/api')) {
    const apiKey = req.headers['x-api-key'] || req.query.api_key;
    if (apiKey && apiKey === API_KEY) {
      return next();
    }

    const token = extractToken(req);
    if (token) {
      try {
        jwt.verify(token, JWT_SECRET);
        return next();
      } catch (err) {
        return res.status(401).json({ status: 'error', message: 'Session expired. Please login again.' });
      }
    }

    return res.status(401).json({ status: 'error', message: 'Authentication required' });
  }

  next();
}

app.use(authMiddleware);

// === DB-READY MIDDLEWARE ===
// Protects API routes that need the database from being called before DB is ready
function requireDb(req, res, next) {
  if (!dbReady) {
    return res.status(503).json({ status: 'error', message: 'Database not connected. Server starting up…' });
  }
  next();
}

// === AUTH ROUTES ===

// POST /api/auth/login - Local auth, issue JWT on success (no DB needed)
app.post('/api/auth/login', (req, res) => {
  const { login, password } = req.body;

  if (!login || !password) {
    return res.status(400).json({ status: 'error', message: 'Missing login or password' });
  }

  if (login === ADMIN_LOGIN && password === ADMIN_PASSWORD) {
    const token = jwt.sign(
      { username: login, auth: 'dashboard', iat: Math.floor(Date.now() / 1000) },
      JWT_SECRET,
      { expiresIn: JWT_EXPIRES_IN }
    );
    return res.json({ status: 'success', token, message: 'Login successful' });
  }

  return res.status(401).json({ status: 'error', message: 'Invalid username or password' });
});

// GET /api/auth/verify - Check if current token is still valid
app.get('/api/auth/verify', (req, res) => {
  const token = extractToken(req);
  if (!token) {
    return res.status(401).json({ status: 'error', message: 'No token provided' });
  }
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    return res.json({ status: 'success', username: decoded.username });
  } catch (err) {
    return res.status(401).json({ status: 'error', message: 'Invalid or expired token' });
  }
});

// === STATIC FILES & PAGES ===

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'login.html'));
});

app.get('/login', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'login.html'));
});

app.get('/dashboard', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'dashboard.html'));
});

app.use(express.static(path.join(__dirname, 'public')));

// === HEALTH ===
app.get('/health', (req, res) => {
  const mem = process.memoryUsage();
  res.json({
    status: dbReady ? 'ok' : 'starting',
    service: 'hmdm-data-api',
    version: '1.0.0',
    dbReady,
    uptime: process.uptime(),
    memory: `${Math.round(mem.heapUsed/1024/1024)}MB/${Math.round(mem.rss/1024/1024)}MB`
  });
});

// === WAKEUP / KEEP-ALIVE ===
// Prevents Render.com free tier from sleeping
// Call this periodically via cron-job.org or similar
const KEEPALIVE_INTERVAL_MS = parseInt(process.env.KEEPALIVE_INTERVAL || '300000'); // 5 min default
const ALIVE_SERVERS = (process.env.ALIVE_SERVERS || '').split(',').filter(Boolean);

app.get('/wakeup', async (req, res) => {
  const results = { me: 'awake', servers: [] };
  
  // Ping other configured servers to keep them awake too
  for (const serverUrl of ALIVE_SERVERS) {
    try {
      const resp = await fetch(serverUrl.replace(/\/$/, '') + '/health', {
        signal: AbortSignal.timeout(10000)
      });
      const data = await resp.json();
      results.servers.push({ url: serverUrl, status: data.status || 'ok' });
    } catch (err) {
      results.servers.push({ url: serverUrl, status: 'unreachable', error: err.message });
    }
  }
  
  res.json({
    status: 'awake',
    service: 'hmdm-data-api',
    uptime: Math.floor(process.uptime()),
    time: new Date().toISOString(),
    servers: results.servers
  });
});

// Auto-keepalive: ping ourselves every 5 minutes to prevent Render spin-down
function startKeepAlive() {
  const selfUrl = `http://localhost:${PORT}`;
  const externalUrl = process.env.RENDER_EXTERNAL_URL || '';
  
  setInterval(async () => {
    try {
      // Ping localhost (keeps Node event loop active)
      await fetch(`${selfUrl}/health`, { signal: AbortSignal.timeout(5000) });
      
      // Ping external URL if available (keeps Render router active)
      if (externalUrl) {
        await fetch(`${externalUrl}/health`, { signal: AbortSignal.timeout(10000) });
      }
      
      // Ping configured peer servers
      for (const serverUrl of ALIVE_SERVERS) {
        try {
          await fetch(serverUrl.replace(/\/$/, '') + '/health', {
            signal: AbortSignal.timeout(10000)
          });
        } catch (e) {
          // Silently ignore - server might be starting up
        }
      }
    } catch (e) {
      // Silently ignore keepalive errors
    }
  }, KEEPALIVE_INTERVAL_MS);
  
  console.log(`[KeepAlive] Started (every ${KEEPALIVE_INTERVAL_MS/1000}s)`);
  if (ALIVE_SERVERS.length > 0) {
    console.log(`[KeepAlive] Watching servers: ${ALIVE_SERVERS.join(', ')}`);
  }
}

// === API ROUTES (require DB) ===
app.use('/api/contacts', requireDb, contactsRouter);
app.use('/api/calllogs', requireDb, calllogsRouter);
app.use('/api/notifications', requireDb, notificationsRouter);

// === LOCATIONS ===
app.post('/api/locations/sync', requireDb, async (req, res) => {
  const { deviceId, locations } = req.body;
  if (!deviceId) return res.status(400).json({ status: 'error', message: 'Missing deviceId' });
  if (!locations || !Array.isArray(locations) || locations.length === 0) {
    return res.json({ status: 'success', message: 'No locations to sync', saved: 0 });
  }
  try {
    const { getPool } = require('./db');
    const pool = getPool();
    const now = Date.now();
    let saved = 0;
    for (const loc of locations) {
      const lat = parseFloat(loc.lat || loc.latitude || 0);
      const lon = parseFloat(loc.lon || loc.lng || loc.longitude || 0);
      if (lat === 0 && lon === 0) continue;
      const ts = parseInt(loc.ts || loc.timestamp || loc.receivedAt || now);
      await pool.query(
        `INSERT INTO device_locations (device_id, lat, lon, ts, synced_at)
         VALUES ($1, $2, $3, $4, $5)`,
        [deviceId, lat, lon, ts, now]
      );
      saved++;
    }
    // Register device
    await pool.query(
      `INSERT INTO device_info (device_id, info_data, synced_at)
       VALUES ($1, $2::jsonb, $3)
       ON CONFLICT (device_id)
       DO UPDATE SET synced_at = EXCLUDED.synced_at, updated_at = CURRENT_TIMESTAMP`,
      [deviceId, JSON.stringify({ name: deviceId }), now]
    );
    res.json({ status: 'success', saved, total: locations.length, deviceId });
  } catch (err) {
    console.error('[Locations] Sync error:', err.message);
    res.status(500).json({ status: 'error', message: 'Internal error' });
  }
});

app.get('/api/locations', requireDb, async (req, res) => {
  const { deviceId, limit = 50, offset = 0 } = req.query;
  try {
    const { getPool } = require('./db');
    const pool = getPool();
    let query = 'SELECT * FROM device_locations WHERE 1=1';
    const params = [];
    let idx = 1;
    if (deviceId) { query += ` AND device_id = $${idx++}`; params.push(deviceId); }
    query += ` ORDER BY ts DESC LIMIT $${idx++} OFFSET $${idx++}`;
    params.push(parseInt(limit), parseInt(offset));
    const result = await pool.query(query, params);
    const countResult = await pool.query(
      deviceId
        ? 'SELECT COUNT(*) FROM device_locations WHERE device_id = $1'
        : 'SELECT COUNT(*) FROM device_locations',
      deviceId ? [deviceId] : []
    );
    res.json({
      status: 'success',
      total: parseInt(countResult.rows[0].count),
      limit: parseInt(limit),
      offset: parseInt(offset),
      data: result.rows
    });
  } catch (err) {
    console.error('[Locations] Get error:', err.message);
    res.status(500).json({ status: 'error', message: 'Internal error' });
  }
});

// === DEVICES LIST ===
app.get('/api/devices', requireDb, async (req, res) => {
  try {
    const { getPool } = require('./db');
    const pool = getPool();
    const result = await pool.query(
      `SELECT device_id, info_data, synced_at, updated_at
       FROM device_info
       ORDER BY COALESCE(info_data->>'name', device_id) ASC`
    );

    const devices = result.rows.map(row => ({
      device_id: row.device_id,
      name: row.info_data?.name || row.info_data?.model || row.device_id,
      model: row.info_data?.model || '',
      android_version: row.info_data?.androidVersion || '',
      manufacturer: row.info_data?.manufacturer || '',
      info: row.info_data || {},
      synced_at: row.synced_at,
      updated_at: row.updated_at
    }));

    res.json({ status: 'success', data: devices });
  } catch (err) {
    console.error('[Devices] Error:', err.message);
    res.status(500).json({ status: 'error', message: 'Internal error' });
  }
});

// === DEVICE INFO ===
app.post('/api/device/info', requireDb, async (req, res) => {
  const { deviceId, info } = req.body;
  if (!deviceId) return res.status(400).json({ status: 'error', message: 'Missing deviceId' });
  try {
    const { getPool } = require('./db');
    const pool = getPool();
    const now = Date.now();
    await pool.query(
      `INSERT INTO device_info (device_id, info_data, synced_at)
       VALUES ($1, $2::jsonb, $3)
       ON CONFLICT (device_id)
       DO UPDATE SET info_data = EXCLUDED.info_data, synced_at = EXCLUDED.synced_at, updated_at = CURRENT_TIMESTAMP`,
      [deviceId, JSON.stringify(info || {}), now]
    );
    res.json({ status: 'success', deviceId });
  } catch (err) {
    console.error('[Device] Info error:', err.message);
    res.status(500).json({ status: 'error', message: 'Internal error' });
  }
});

app.get('/api/device/info', requireDb, async (req, res) => {
  const { deviceId } = req.query;
  try {
    const { getPool } = require('./db');
    const pool = getPool();
    let result;
    if (deviceId) {
      result = await pool.query('SELECT device_id, synced_at, updated_at FROM device_info WHERE device_id = $1', [deviceId]);
    } else {
      result = await pool.query('SELECT device_id, synced_at, updated_at FROM device_info ORDER BY updated_at DESC');
    }
    res.json({ status: 'success', data: result.rows });
  } catch (err) {
    console.error('[Device] Get error:', err.message);
    res.status(500).json({ status: 'error', message: 'Internal error' });
  }
});

// === SEED ENDPOINT (for testing the dashboard) ===
// Supports both GET (browser-friendly) and POST
app.all('/api/seed', requireDb, async (req, res) => {
  try {
    const { getPool, batchUpsert } = require('./db');
    const pool = getPool();
    const now = Date.now();
    const deviceId = (req.body && req.body.deviceId) || req.query.deviceId || 'test-device-001';

    // Insert sample contacts
    const contacts = [
      { name: 'Alice Johnson', phone: '+1-555-0101', phoneType: 'Mobile', email: 'alice@example.com' },
      { name: 'Bob Smith', phone: '+1-555-0102', phoneType: 'Mobile', email: 'bob@example.com' },
      { name: 'Carol Williams', phone: '+1-555-0103', phoneType: 'Work', email: 'carol@company.com' },
      { name: 'David Brown', phone: '+1-555-0104', phoneType: 'Home' },
      { name: 'Eve Davis', phone: '+1-555-0105', email: 'eve@example.com' },
      { name: 'Frank Miller', phone: '+1-555-0106', phoneType: 'Mobile' },
      { name: 'Grace Wilson', phone: '+1-555-0107', phoneType: 'Work', email: 'grace@company.com' },
      { name: 'Henry Taylor', phone: '+1-555-0108' },
      { name: 'Ivy Anderson', phone: '+1-555-0109', phoneType: 'Mobile', email: 'ivy@example.com' },
      { name: 'Jack Thomas', phone: '+1-555-0110', phoneType: 'Home' },
    ];

    const contactItems = contacts.map((c, i) => ({
      device_id: deviceId,
      contact_id: `contact_${i}`,
      name: c.name,
      phone: c.phone || '',
      phone_type: c.phoneType || '',
      email: c.email || '',
      raw_data: JSON.stringify(c),
      synced_at: now - (i * 3600000) // spread across the last 10 hours
    }));

    await batchUpsert(
      'device_contacts',
      ['device_id', 'contact_id', 'name', 'phone', 'phone_type', 'email', 'raw_data', 'synced_at'],
      contactItems,
      ['device_id', 'contact_id'],
      ['name', 'phone', 'phone_type', 'email', 'raw_data', 'synced_at'],
      'UPDATE'
    );

    // Insert sample call logs
    const callLogs = [
      { phoneNumber: '+1-555-0101', callType: 'INCOMING', durationSec: 145, contactName: 'Alice Johnson' },
      { phoneNumber: '+1-555-0102', callType: 'OUTGOING', durationSec: 32, contactName: 'Bob Smith' },
      { phoneNumber: '+1-555-0199', callType: 'MISSED', durationSec: 0, contactName: '' },
      { phoneNumber: '+1-555-0103', callType: 'INCOMING', durationSec: 612, contactName: 'Carol Williams' },
      { phoneNumber: '+1-555-0101', callType: 'OUTGOING', durationSec: 89, contactName: 'Alice Johnson' },
      { phoneNumber: '+1-555-0105', callType: 'INCOMING', durationSec: 234, contactName: 'Eve Davis' },
      { phoneNumber: '+1-555-0110', callType: 'MISSED', durationSec: 0, contactName: 'Jack Thomas' },
      { phoneNumber: '+1-555-0108', callType: 'OUTGOING', durationSec: 15, contactName: 'Henry Taylor' },
      { phoneNumber: '+1-555-0107', callType: 'INCOMING', durationSec: 421, contactName: 'Grace Wilson' },
      { phoneNumber: '+1-555-0102', callType: 'OUTGOING', durationSec: 77, contactName: 'Bob Smith' },
    ];

    const callLogItems = callLogs.map((l, i) => ({
      device_id: deviceId,
      call_id: `call_${i}`,
      phone_number: l.phoneNumber,
      call_type: l.callType,
      duration_sec: l.durationSec,
      call_date: now - (i * 1800000), // spread across the last 5 hours
      contact_name: l.contactName || '',
      synced_at: now
    }));

    await batchUpsert(
      'device_call_logs',
      ['device_id', 'call_id', 'phone_number', 'call_type', 'duration_sec', 'call_date', 'contact_name', 'synced_at'],
      callLogItems,
      ['device_id', 'call_date', 'phone_number', 'call_type', 'duration_sec'],
      null,
      'NOTHING'
    );

    // Insert sample notifications
    const notifications = [
      { packageName: 'com.whatsapp', appName: 'WhatsApp', title: 'Alice Johnson', text: 'Hey! Are you coming to the meeting?' },
      { packageName: 'com.google.gmail', appName: 'Gmail', title: 'Invoice #12345', text: 'Your invoice for September is ready' },
      { packageName: 'com.slack', appName: 'Slack', title: '#general', text: 'Alice: The deployment is complete' },
      { packageName: 'com.android.systemui', appName: 'System UI', title: 'Battery Low', text: 'Battery level is below 15%' },
      { packageName: 'com.google.android.apps.maps', appName: 'Google Maps', title: 'Traffic Alert', text: 'Accident reported on Main Street' },
    ];

    const notifItems = notifications.map((n, i) => ({
      device_id: deviceId,
      package_name: n.packageName,
      app_name: n.appName,
      title: n.title,
      text_body: n.text,
      received_at: now - (i * 600000), // spread across the last 50 minutes
      synced_at: now
    }));

    await batchUpsert(
      'device_notifications',
      ['device_id', 'package_name', 'app_name', 'title', 'text_body', 'received_at', 'synced_at'],
      notifItems,
      ['device_id', 'received_at', 'package_name', 'title'],
      null,
      'NOTHING'
    );

    // Register the device
    await pool.query(
      `INSERT INTO device_info (device_id, info_data, synced_at)
       VALUES ($1, $2::jsonb, $3)
       ON CONFLICT (device_id)
       DO UPDATE SET info_data = EXCLUDED.info_data, synced_at = EXCLUDED.synced_at, updated_at = CURRENT_TIMESTAMP`,
      [deviceId, JSON.stringify({ model: 'SM-G998B', androidVersion: '14', manufacturer: 'Samsung', name: 'Test Samsung Galaxy' }), now]
    );

    // Insert sample location data
    const locations = [
      { lat: 41.6938, lon: 44.8015, ts: now - 600000 },    // Tbilisi
      { lat: 41.6945, lon: 44.8025, ts: now - 1200000 },
      { lat: 41.6952, lon: 44.8030, ts: now - 1800000 },
      { lat: 41.6930, lon: 44.8005, ts: now - 2400000 },
      { lat: 41.6948, lon: 44.8010, ts: now - 3000000 },
    ];
    for (const loc of locations) {
      await pool.query(
        `INSERT INTO device_locations (device_id, lat, lon, ts, synced_at)
         VALUES ($1, $2, $3, $4, $5)`,
        [deviceId, loc.lat, loc.lon, loc.ts, now]
      );
    }

    res.json({
      status: 'success',
      message: 'Seed data inserted successfully',
      deviceId,
      counts: {
        contacts: contacts.length,
        callLogs: callLogs.length,
        notifications: notifications.length
      }
    });
  } catch (err) {
    console.error('[Seed] Error:', err.message);
    res.status(500).json({ status: 'error', message: err.message });
  }
});

// === SUMMARY ===
app.get('/api/summary', requireDb, async (req, res) => {
  try {
    const { getPool } = require('./db');
    const pool = getPool();
    const contacts = await pool.query('SELECT COUNT(*) as count FROM device_contacts');
    const calllogs = await pool.query('SELECT COUNT(*) as count FROM device_call_logs');
    const notifications = await pool.query('SELECT COUNT(*) as count FROM device_notifications');
    const devices = await pool.query('SELECT COUNT(*) as count FROM device_info');
    const latestCall = await pool.query('SELECT device_id, phone_number, call_type, call_date, contact_name FROM device_call_logs ORDER BY call_date DESC LIMIT 5');
    const latestNotif = await pool.query('SELECT device_id, app_name, title, received_at FROM device_notifications ORDER BY received_at DESC LIMIT 5');

    res.json({
      status: 'success',
      counts: {
        contacts: parseInt(contacts.rows[0].count),
        callLogs: parseInt(calllogs.rows[0].count),
        notifications: parseInt(notifications.rows[0].count),
        devices: parseInt(devices.rows[0].count),
      },
      recent: { callLogs: latestCall.rows, notifications: latestNotif.rows }
    });
  } catch (err) {
    console.error('[Summary] Error:', err.message);
    res.status(500).json({ status: 'error', message: 'Internal error' });
  }
});

// === START ===
console.log('[Server] Starting hmdm-data-api…');

// Start HTTP server FIRST (no DB dependency)
app.listen(PORT, '0.0.0.0', () => {
  const mem = process.memoryUsage();
  console.log(`[Server] Listening on port ${PORT}`);
  console.log(`[Server] Memory: ${Math.round(mem.rss/1024/1024)}MB RSS | ${Math.round(mem.heapUsed/1024/1024)}MB heap`);
  console.log(`[Server] Auth: local (login: ${ADMIN_LOGIN})`);
  console.log(`[Server] DB init in background…`);
});

// Start keep-alive and DB init in background
startMemoryMonitor();
startKeepAlive();
initDatabase()
  .then(() => {
    dbReady = true;
    console.log('[Server] DB ready — all API endpoints now available');
  })
  .catch((err) => {
    console.error('[Server] DB init failed — API endpoints will return 503');
    console.error(`[Server] DB error: ${err.message}`);
    console.error('[Server] Set DATABASE_URL env var to connect to PostgreSQL');
    // Keep server running! Login, dashboard UI, and auth still work.
  });
