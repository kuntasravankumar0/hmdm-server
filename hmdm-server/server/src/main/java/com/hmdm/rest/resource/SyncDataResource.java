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
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.SyncDataRequest;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * <p>A resource for syncing device data (contacts, call logs, notifications) from Android devices
 * to the data API endpoint. This bridges the gap between Android devices and the hmdm-data-api storage.</p>
 *
 * @author h-mdm
 */
@Api(tags = {"Device Data Sync"})
@Singleton
@Path("/public/data")
public class SyncDataResource {

    private static final Logger log = LoggerFactory.getLogger(SyncDataResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_DATA_API_URL =
        System.getProperty("data.api.url", "https://hmdm-data-apicallandcontact.onrender.com");
    private static final String DEFAULT_DATA_API_KEY =
        System.getProperty("data.api.key", "hmdm-sync-key-2024");

    private final String dataApiUrl;
    private final String dataApiKey;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public SyncDataResource() {
        this.dataApiUrl = DEFAULT_DATA_API_URL;
        this.dataApiKey = DEFAULT_DATA_API_KEY;
    }

    @ApiOperation(
            value = "Sync device data",
            notes = "Receives contacts, call logs, and notifications from Android devices and forwards them to the data API for storage."
    )
    @POST
    @Path("/sync/{deviceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncDeviceData(SyncDataRequest request,
                                   @PathParam("deviceId") @ApiParam("Device identifier") String deviceId) {

        log.info("Received data sync request for device: {} (contacts: {}, callLogs: {}, notifications: {})",
                deviceId,
                request.getContacts() != null ? request.getContacts().size() : 0,
                request.getCallLogs() != null ? request.getCallLogs().size() : 0,
                request.getNotifications() != null ? request.getNotifications().size() : 0);

        int saved = 0;
        int failed = 0;

        // Forward contacts to data API
        if (request.getContacts() != null && !request.getContacts().isEmpty()) {
            try {
                if (forwardToDataApi("/api/contacts/sync", deviceId, "contacts", request.getContacts())) {
                    saved += request.getContacts().size();
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Failed to forward contacts for device {}: {}", deviceId, e.getMessage());
                failed++;
            }
        }

        // Forward call logs to data API
        if (request.getCallLogs() != null && !request.getCallLogs().isEmpty()) {
            try {
                if (forwardToDataApi("/api/calllogs/sync", deviceId, "callLogs", request.getCallLogs())) {
                    saved += request.getCallLogs().size();
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Failed to forward call logs for device {}: {}", deviceId, e.getMessage());
                failed++;
            }
        }

        // Forward notifications to data API
        if (request.getNotifications() != null && !request.getNotifications().isEmpty()) {
            try {
                if (forwardToDataApi("/api/notifications/sync", deviceId, "notifications", request.getNotifications())) {
                    saved += request.getNotifications().size();
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Failed to forward notifications for device {}: {}", deviceId, e.getMessage());
                failed++;
            }
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("saved", saved);
        result.put("failed", failed);
        result.put("deviceId", deviceId);
        return Response.OK(result);
    }

    /**
     * Forwards data to the hmdm-data-api endpoint.
     * @return true if the data was forwarded successfully (HTTP 200), false otherwise
     */
    private boolean forwardToDataApi(String endpoint, String deviceId, String dataKey, Object data) {
        String apiUrl = dataApiUrl + endpoint;
        log.debug("Forwarding {} data to: {}", dataKey, apiUrl);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-api-key", dataApiKey);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);

            // Build the request body
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("deviceId", deviceId);
            body.put(dataKey, data);

            byte[] jsonBytes = MAPPER.writeValueAsBytes(body);

            connection.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));

            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.warn("Data API returned {} for {} (device: {})", responseCode, endpoint, deviceId);
                return false;
            }

            log.debug("Successfully forwarded {} records for device {}", dataKey, deviceId);
            return true;
        } catch (Exception e) {
            log.error("Error forwarding {} for device {}: {}", dataKey, deviceId, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
