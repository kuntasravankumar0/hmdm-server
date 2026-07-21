/*
 *
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
 *
 */

package com.hmdm.rest.resource;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import javax.inject.Named;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.hmdm.notification.rest.NotificationResource;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.rest.filter.PublicIPFilter;
import com.hmdm.rest.json.Response;
import com.hmdm.util.CryptoUtil;
import org.apache.poi.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DownloadFilesServlet extends HttpServlet {
    private final ApplicationDAO applicationDAO;
    private final String filesDirectory;
    private final File baseDirectory;
    private final PublicIPFilter publicIPFilter;

    private boolean secureEnrollment;
    private String hashSecret;

    // Fallback URLs for files that can be proxied from the official Headwind MDM repository
    private static final String HMDM_FALLBACK_BASE = "https://h-mdm.com/files";

    private static final String HEADER_ENROLLMENT_SIGNATURE = "X-Request-Signature";
    private static final String CONTENT_TYPE_APK = "application/vnd.android.package-archive";

    private static final Logger log = LoggerFactory.getLogger(DownloadFilesServlet.class);

    @Inject
    public DownloadFilesServlet(ApplicationDAO applicationDAO,
                                PublicIPFilter publicIPFilter,
                                @Named("files.directory") String filesDirectory,
                                @Named("secure.enrollment") boolean secureEnrollment,
                                @Named("hash.secret") String hashSecret) {
        this.applicationDAO = applicationDAO;
        this.filesDirectory = filesDirectory;
        this.baseDirectory = new File(filesDirectory);
        this.publicIPFilter = publicIPFilter;
        this.secureEnrollment = secureEnrollment;
        this.hashSecret = hashSecret;
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }

    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = URLDecoder.decode(req.getRequestURI(), "UTF8");
        int index = path.indexOf("/files/", 0) + "/files/".length();
        path = path.substring(index);

        if (!publicIPFilter.match(req)) {
            log.warn("Request blocked by IP: " + req.getRemoteAddr());
            resp.sendError(403);
            return;
        }

        if (secureEnrollment && !applicationDAO.isMainApp("%" + path)) {
            String signature = req.getHeader(HEADER_ENROLLMENT_SIGNATURE);
            if (signature == null) {
                log.warn("No signature for file request " + req.getRequestURL().toString());
                resp.sendError(403);
                return;
            }
            try {
                String goodSignature = CryptoUtil.getSHA1String(hashSecret + path);
                if (!signature.equalsIgnoreCase(goodSignature)) {
                    log.warn("Wrong signature for file request " + path + ": " + signature + " Should be: " + goodSignature);
                    resp.sendError(403);
                    return;
                }
            } catch (Exception e) {
            }
        }

        // Try to serve the file from the local files directory first
        File file = new File(String.format("%s/%s", this.filesDirectory, path));
        if (file.exists()) {
            serveLocalFile(file, req, resp);
            return;
        }

        // File not found locally; attempt to proxy it from the official Headwind MDM repository
        log.warn("File not found locally: {}. Attempting fallback download from h-mdm.com...", file.getAbsolutePath());
        proxyFromFallback(path, resp);

    }

    /**
     * Serves a file from the local filesystem.
     */
    private void serveLocalFile(File file, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long modifiedSince = req.getDateHeader("If-Modified-Since");
        if (modifiedSince != -1 && modifiedSince > file.lastModified()) {
            // Client can use cached images
            resp.setStatus(304);
            return;
        }

        String range = req.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            sendPartialContent(range.substring(6), file, resp);
            return;
        }

        // Cross XSS vulnerability fix: prevent opening a potentially malicious file having the Headwind MDM domain
        resp.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
        try (InputStream input = new FileInputStream(file);
             ServletOutputStream outputStream = resp.getOutputStream()) {
            long length = file.length();
            if (length <= 2147483647L) {
                resp.setContentLength((int)length);
            } else {
                resp.addHeader("Content-Length", Long.toString(length));
            }
            if (file.getAbsolutePath().endsWith(".apk")) {
                resp.setContentType(CONTENT_TYPE_APK);
            }

            IOUtils.copy(input, outputStream);
            outputStream.flush();
        } catch (Exception e) {
            log.error("Failed to serve local file {}: {}", file.getAbsolutePath(), e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Proxies a file from the official Headwind MDM fallback URL when the file is not available locally.
     * This ensures devices can still download plugin APKs even if the initial Docker download failed.
     */
    private void proxyFromFallback(String path, HttpServletResponse resp) {
        String fallbackUrl = HMDM_FALLBACK_BASE + "/" + path;
        log.info("Proxying {} from fallback URL: {}", path, fallbackUrl);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(fallbackUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.warn("Fallback URL returned {} for {}", responseCode, fallbackUrl);
                resp.sendError(404, "File not found locally or on fallback server");
                return;
            }

            // Set response headers from the fallback response
            String contentType = connection.getContentType();
            if (contentType != null) {
                resp.setContentType(contentType);
            } else if (path.endsWith(".apk")) {
                resp.setContentType(CONTENT_TYPE_APK);
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0) {
                if (contentLength <= 2147483647L) {
                    resp.setContentLength((int)contentLength);
                } else {
                    resp.addHeader("Content-Length", Long.toString(contentLength));
                }
            }

            String fileName = new File(path).getName();
            resp.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            // Cache the fallback response for 1 hour so repeated requests don't hit h-mdm.com each time
            resp.addHeader("Cache-Control", "public, max-age=3600");

            try (InputStream fallbackInput = connection.getInputStream();
                 ServletOutputStream outputStream = resp.getOutputStream()) {

                // Cache the file locally so future requests don't need to hit the fallback
                File localFile = new File(filesDirectory, path);
                File parentDir = localFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                // Read into buffer and write to both local file and response simultaneously
                byte[] buffer = new byte[8192];
                int len;
                long totalBytes = 0;
                java.io.FileOutputStream localOut = null;
                try {
                    localOut = new java.io.FileOutputStream(localFile);
                    while ((len = fallbackInput.read(buffer)) != -1) {
                        localOut.write(buffer, 0, len);
                        outputStream.write(buffer, 0, len);
                        totalBytes += len;
                    }
                    localOut.flush();
                } catch (Exception cacheEx) {
                    log.warn("Failed to cache proxied file locally: {}", cacheEx.getMessage());
                    // If local caching failed, we already wrote to outputStream, so don't throw
                } finally {
                    if (localOut != null) {
                        try { localOut.close(); } catch (Exception ignored) {}
                    }
                }
                outputStream.flush();
            }

            log.info("Successfully proxied {} from fallback and cached locally", path);

        } catch (Exception e) {
            log.error("Failed to proxy {} from fallback: {}", path, e.getMessage());
            try {
                resp.sendError(404, "File not found locally or on fallback server");
            } catch (IOException ioe) {
                log.error("Failed to send error response", ioe);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void sendPartialContent(String rangeStr, File file, HttpServletResponse resp) {
        try {
            String[] range = rangeStr.split("-");
            Long start = Long.parseLong(range[0]);
            Long end = null;
            if (range.length > 1) {
                end = Long.parseLong(range[1]);
            }
            InputStream input = new FileInputStream(file);
            ServletOutputStream outputStream = resp.getOutputStream();
            long length = file.length();
            if (end == null) {
                end = length;
            }

            resp.setStatus(206);
            resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
            long contentLength = end - start;
            if (length <= 2147483647L) {
                resp.setContentLength((int)contentLength);
            } else {
                resp.addHeader("Content-Length", Long.toString(contentLength));
            }
            if (file.getAbsolutePath().endsWith(".apk")) {
                resp.setContentType(CONTENT_TYPE_APK);
            }

            input.skip(start);

            IOUtils.copy(input, outputStream, contentLength);
            outputStream.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
