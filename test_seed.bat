@echo off
REM ============================================================
REM  Seed Dashboard with Sample Data
REM  Run this to populate your dashboard with test contacts,
REM  call logs, and notifications for testing.
REM ============================================================

echo Seeding dashboard with sample data...
echo.

curl -X POST https://hmdm-data-apicallandcontact.onrender.com/api/seed ^
  -H "Content-Type: application/json" ^
  -H "x-api-key: hmdm-sync-key-2024" ^
  -d "{\"deviceId\":\"test-device-001\"}"

echo.
echo.
echo Done! Now refresh your dashboard at:
echo https://hmdm-data-apicallandcontact.onrender.com/dashboard
echo.
pause
