package castech.emvtxn.reporting;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** One POST to the portal. No retry here — the pusher owns the schedule. */
public final class ReportingClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** What the portal said, flattened for the pusher. */
    public static final class Result {
        public final int httpStatus;          // 0 on a transport failure
        public final String message;          // 200: the portal's "message"
        public final String error;            // non-200: the portal's "error" text, or the transport reason
        public Result(int httpStatus, String message, String error) {
            this.httpStatus = httpStatus;
            this.message = message == null ? "" : message;
            this.error = error == null ? "" : error;
        }
        public boolean accepted()         { return httpStatus == 200; }
        public boolean unauthorized()     { return httpStatus == 401; }
        public boolean transportFailure() { return httpStatus == 0; }
    }

    private final OkHttpClient http;

    public ReportingClient(OkHttpClient http) { this.http = http; }

    /** The production client: 10 s connect, 30 s read/write (the contract says allow 30 s). */
    public static ReportingClient production() {
        return new ReportingClient(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    public Result post(String url, String accessKey, JSONObject body) {
        Request req = new Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("X-API-Key", accessKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() == null ? "" : res.body().string();
            String message = "", error = "";
            try {
                JSONObject o = new JSONObject(text);
                message = o.optString("message", "");
                error = o.optString("error", "");
            } catch (Exception notJson) {
                error = text.length() > 120 ? text.substring(0, 120) : text;
            }
            if (res.code() != 200 && error.isEmpty()) error = "HTTP " + res.code();
            return new Result(res.code(), message, error);
        } catch (IOException e) {
            return new Result(0, "", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }
}
