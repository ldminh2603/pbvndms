package com.webcore.api.dms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** PBBDMS-compatible synchronous clamd INSTREAM scanner. It fails closed. */
@Service
public class DmsClamdScanService {
    private static final int CHUNK_SIZE = 8192;
    private final DmsClamavProperties properties;
    private volatile String engineVersion;

    public DmsClamdScanService(DmsClamavProperties properties) { this.properties = properties; }

    public ScanResult scan(byte[] content) {
        final String reply;
        try { reply = instream(content); }
        catch (IOException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Malware scanner is unavailable; upload was not stored.", error); }
        if (reply.endsWith("OK")) return new ScanResult("CLEAN", version(), null);
        if (reply.endsWith("FOUND")) return new ScanResult("INFECTED", version(), reply.replace("stream:", "").replace("FOUND", "").trim());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Malware scanner returned an unexpected response; upload was not stored.");
    }

    private String instream(byte[] content) throws IOException {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; ) {
                int length = Math.min(CHUNK_SIZE, content.length - offset);
                out.write(ByteBuffer.allocate(4).putInt(length).array()); out.write(content, offset, length); offset += length;
            }
            out.write(new byte[4]); out.flush(); return reply(socket.getInputStream());
        }
    }

    private String version() {
        if (engineVersion != null) return engineVersion;
        try (Socket socket = connect()) {
            socket.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            return engineVersion = reply(socket.getInputStream());
        } catch (IOException ignored) { return "clamd-version-unknown"; }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket(); socket.connect(new InetSocketAddress(properties.host(), properties.port()), properties.connectTimeoutMs()); socket.setSoTimeout(properties.readTimeoutMs()); return socket;
    }
    private static String reply(InputStream input) throws IOException { ByteArrayOutputStream result = new ByteArrayOutputStream(); for (int next; (next = input.read()) > 0;) result.write(next); return result.toString(StandardCharsets.US_ASCII).trim(); }
    public record ScanResult(String status, String engineVersion, String signature) { public boolean clean() { return "CLEAN".equals(status); } }
}
