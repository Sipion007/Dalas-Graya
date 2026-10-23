package ci.dallasbouff.pro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/** Appels au serveur Dallas Bouff' (fonctions RPC Supabase). */
final class Api {
    private Api() {}

    static final String BASE = "https://gdkcrazrejzpwzxbhpxp.supabase.co";
    static final String KEY = "sb_publishable_1cijZWLbC-Em1Q9o3UwpvA_VLmNtmwi";

    static String rpc(String fn, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/rest/v1/rpc/" + fn).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("apikey", KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = read(in);
            if (code >= 400) throw new IOException("HTTP " + code + " " + text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    static JSONObject rpcObject(String fn, JSONObject body) throws Exception {
        return new JSONObject(rpc(fn, body));
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
