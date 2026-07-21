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

package com.hmdm.rest.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.List;

/**
 * <p>A request model for device data sync (contacts, call logs, notifications).</p>
 *
 * @author h-mdm
 */
@ApiModel(description = "Device data sync request containing contacts, call logs, and notifications")
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncDataRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("A list of contacts synced from the device")
    private List<SyncDataContact> contacts;

    @ApiModelProperty("A list of call logs synced from the device")
    private List<SyncDataCallLog> callLogs;

    @ApiModelProperty("A list of notifications synced from the device")
    private List<SyncDataNotification> notifications;

    public List<SyncDataContact> getContacts() {
        return contacts;
    }

    public void setContacts(List<SyncDataContact> contacts) {
        this.contacts = contacts;
    }

    public List<SyncDataCallLog> getCallLogs() {
        return callLogs;
    }

    public void setCallLogs(List<SyncDataCallLog> callLogs) {
        this.callLogs = callLogs;
    }

    public List<SyncDataNotification> getNotifications() {
        return notifications;
    }

    public void setNotifications(List<SyncDataNotification> notifications) {
        this.notifications = notifications;
    }

    @ApiModel(description = "A single contact record from device")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SyncDataContact implements Serializable {
        private static final long serialVersionUID = 1L;

        private String rawContactId;
        private String name;
        private String phone;
        private String phoneType;
        private String email;

        public String getRawContactId() { return rawContactId; }
        public void setRawContactId(String rawContactId) { this.rawContactId = rawContactId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getPhoneType() { return phoneType; }
        public void setPhoneType(String phoneType) { this.phoneType = phoneType; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    @ApiModel(description = "A single call log record from device")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SyncDataCallLog implements Serializable {
        private static final long serialVersionUID = 1L;

        private String phoneNumber;
        private String callType;
        private Integer durationSec;
        private Long callDate;
        private String contactName;

        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getCallType() { return callType; }
        public void setCallType(String callType) { this.callType = callType; }
        public Integer getDurationSec() { return durationSec; }
        public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
        public Long getCallDate() { return callDate; }
        public void setCallDate(Long callDate) { this.callDate = callDate; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
    }

    @ApiModel(description = "A single notification record from device")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SyncDataNotification implements Serializable {
        private static final long serialVersionUID = 1L;

        private String packageName;
        private String appName;
        private String title;
        private String text;
        private Long receivedAt;

        public String getPackageName() { return packageName; }
        public void setPackageName(String packageName) { this.packageName = packageName; }
        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Long getReceivedAt() { return receivedAt; }
        public void setReceivedAt(Long receivedAt) { this.receivedAt = receivedAt; }
    }
}
