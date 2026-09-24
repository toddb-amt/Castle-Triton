package castech.emvtxn;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.Arrays;

/**
 * Reads app configuration parameters that CasHUB pushes to this terminal, scoped
 * to our package (castech.emvtxn), via the CasHUB agent's ParameterContentProvider
 * (authority "com.castlestech.cashub.agent", exported). This lets host/TID/port be
 * provisioned centrally from CasHUB instead of typed on the terminal.
 *
 * NOTE: The provider returns each caller only ITS OWN package's parameters, so this
 * MUST run inside our app (adb shell sees nothing). The exact cursor schema is being
 * discovered — dumpDiagnostic() logs the columns/values so we can build the parser.
 */
public final class CasHubParams {

    private static final String TAG = "CasHubParams";
    private static final String AUTHORITY = "com.castlestech.cashub.agent";

    private CasHubParams() {}

    /** CasHUB broadcasts this when a pushed parameter arrives/updates. */
    public static final String ACTION_PARAMETER_UPDATED =
            "com.castlestech.cashub.agent.action.PARAMETER_UPDATED";

    /**
     * Registers a receiver for the CasHUB PARAMETER_UPDATED broadcast so a
     * parameter pushed while the app is running is applied LIVE (no reboot needed).
     * On receipt it re-reads the parameter provider, applies the config to
     * GlobalPara, and backs it up to KMS-II. Safe; never throws.
     */
    public static void registerParameterReceiver(final Context ctx) {
        try {
            final Context appCtx = ctx.getApplicationContext();
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent intent) {
                    try {
                        Log.w(TAG, "PARAMETER_UPDATED received — re-applying CasHUB config");
                        Applied a = applyToConfigDetailed(appCtx);
                        if (a.anyRows) {
                            KmsConfigStore.backup();
                            // Re-persist to SharedPreferences so admin screen /
                            // hasHostConfiguration() reflect the live update too.
                            if (GlobalPara.mainActivity != null) {
                                AtmSettingsManager sm = GlobalPara.mainActivity.getAtmSettingsManager();
                                if (sm != null) sm.persistCurrentToPrefs();
                                // POS settings changed: restart the POS stack live so the
                                // lane picks up the new proxy / credential / on-off state
                                // without waiting for the nightly reboot.
                                if (a.posDiff != null && a.posDiff.any()) {
                                    GlobalPara.mainActivity.onPosParamsChanged(a.posDiff);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "PARAMETER_UPDATED handler error: " + t.getMessage());
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_PARAMETER_UPDATED);
            // RECEIVER_EXPORTED (flag value 0x2, API 33+) so the agent — another app —
            // can reach us. Literal used because compileSdk is 31.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, 2 /* RECEIVER_EXPORTED */);
            } else {
                ctx.registerReceiver(r, f);
            }
            Log.w(TAG, "Registered receiver for " + ACTION_PARAMETER_UPDATED);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register PARAMETER_UPDATED receiver: " + t.getMessage());
        }
    }

    /**
     * Logs everything the ParameterContentProvider returns, across a few candidate
     * URIs, so we can see the real column names / value encoding. Read-only; never
     * throws. Remove or gate once the schema is known and the parser is built.
     */
    /** The two real UriMatcher paths (verified by decompiling the agent's
     *  ParameterContentProvider): /terminal -> terminalparadata table,
     *  /merchant -> merchantparadata table. The provider auto-filters rows by
     *  getCallingPackage(), so this MUST run inside our app. The pushed JSON is
     *  in the "content" column. */
    public static final Uri URI_TERMINAL = Uri.parse("content://" + AUTHORITY + "/terminal");
    public static final Uri URI_MERCHANT = Uri.parse("content://" + AUTHORITY + "/merchant");

    public static void dumpDiagnostic(Context ctx) {
        Log.w(TAG, "===== CasHUB ParameterContentProvider diagnostic (pkg=" + ctx.getPackageName() + ") =====");
        queryAndLog(ctx, URI_TERMINAL.toString());
        queryAndLog(ctx, URI_MERCHANT.toString());
        Log.w(TAG, "==============================================================");
    }

    /**
     * Reads the JSON payload CasHUB pushed for our package. Tries the terminal
     * parameter table first, then merchant. Returns the "content" column of the
     * first non-empty row, or null if nothing has been pushed / downloaded yet.
     * Read-only; never throws.
     */
    public static String getParamContent(Context ctx) {
        String c = readContent(ctx, URI_TERMINAL);
        if (c == null || c.isEmpty()) c = readContent(ctx, URI_MERCHANT);
        return (c != null && !c.isEmpty()) ? c : null;
    }

    /**
     * Applies CasHUB-pushed configuration to GlobalPara. Reads every parameter row
     * for our package (terminal table then merchant table), parses each row's JSON
     * {@code content}, and merges the recognized keys into the ATM config via the
     * shared {@link KmsConfigStore#applyPayload} mapping (last row wins per key).
     *
     * This is the CENTRAL-CONFIG path: whatever the operator sets in CasHUB is
     * re-applied on every startup, so a CasHUB-managed terminal never needs local
     * entry. Recognized JSON keys mirror KmsConfigStore: host_address, host_port,
     * terminal_id, processor_type, protocol_type, use_flat_fee, flat_fee,
     * percentage_fee, min_amount, max_amount — plus the POS-mode keys pos_enabled,
     * pos_proxy_url, pos_terminal_access_key (see {@link #applyToConfigDetailed}).
     *
     * @return true if at least one parameter row was found and applied
     */
    public static boolean applyToConfig(Context ctx) {
        return applyToConfigDetailed(ctx).anyRows;
    }

    /** Outcome of one apply: whether any rows existed, and what the POS keys changed. */
    public static final class Applied {
        public final boolean anyRows;
        /** Null when no pos_* key was present. */
        public final castech.emvtxn.pos.PosParams.Diff posDiff;
        Applied(boolean anyRows, castech.emvtxn.pos.PosParams.Diff posDiff) {
            this.anyRows = anyRows;
            this.posDiff = posDiff;
        }
    }

    /**
     * Same as {@link #applyToConfig} but also reports what the POS-mode keys
     * ({@code pos_enabled}, {@code pos_proxy_url}, {@code pos_terminal_access_key})
     * changed. Those keys go to {@link castech.emvtxn.pos.PosConfig} — the single
     * owner of POS settings, which the admin screen also reads — and are kept out
     * of the host-config payload (and therefore out of the KMS-II backup, which
     * must never carry the access key). Absent keys leave local values alone;
     * present-but-invalid values are logged and ignored.
     */
    public static Applied applyToConfigDetailed(Context ctx) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        int rows = mergeRows(ctx, URI_TERMINAL, merged);
        rows += mergeRows(ctx, URI_MERCHANT, merged);
        if (merged.isEmpty()) return new Applied(false, null);

        if (rows > 1) {
            Log.w(TAG, "CasHUB has " + rows + " parameter rows for this package; merged "
                    + "last-wins. Consolidate to a single parameter to avoid ambiguity.");
        }

        // POS-mode keys → PosConfig
        castech.emvtxn.pos.PosParams pos = castech.emvtxn.pos.PosParams.parse(merged);
        castech.emvtxn.pos.PosParams.Diff posDiff = null;
        if (!pos.isEmpty()) {
            for (String problem : pos.problems) {
                Log.w(TAG, "CasHUB POS param ignored — " + problem);
            }
            castech.emvtxn.pos.PosConfig cfg = new castech.emvtxn.pos.PosConfig(ctx);
            posDiff = pos.applyTo(cfg);
            // Never the key itself — it is a bearer credential.
            Log.w(TAG, "Applied CasHUB POS config: enabled=" + cfg.isEnabled()
                    + " proxy=" + cfg.getProxyBaseUrl()
                    + " accessKey=" + (cfg.getTerminalAccessKey().isEmpty() ? "[none]" : "[set]")
                    + (posDiff.any() ? " (changed: enabled=" + posDiff.enabledChanged
                        + " credentials=" + posDiff.credentialsChanged + ")" : " (unchanged)"));
        }

        // Everything else → the host-config mapping
        StringBuilder payload = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : merged.entrySet()) {
            if (castech.emvtxn.pos.PosParams.KEYS.contains(e.getKey())) continue;
            payload.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        KmsConfigStore.applyPayload(payload.toString());
        Log.w(TAG, "Applied CasHUB central config: host=" + GlobalPara.atmHostAddress
                + " port=" + GlobalPara.atmHostPort + " tid=" + GlobalPara.atmTerminalId
                + " processor=" + GlobalPara.atmProcessorType);
        return new Applied(true, posDiff);
    }

    /**
     * Reads all parameter rows at the given URI, parses each row's JSON content,
     * and copies its keys into {@code out} (later rows override earlier ones).
     * Returns the number of rows that contributed at least one key.
     */
    private static int mergeRows(Context ctx, Uri uri, java.util.Map<String, String> out) {
        Cursor c = null;
        int used = 0;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c == null || !c.moveToFirst()) return 0;
            int idx = c.getColumnIndex("content");
            if (idx < 0) return 0;
            do {
                String json = c.getString(idx);
                if (json == null || json.isEmpty()) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    boolean any = false;
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String k = it.next();
                        out.put(k, String.valueOf(o.get(k)));
                        any = true;
                    }
                    if (any) used++;
                } catch (Throwable badJson) {
                    Log.w(TAG, "Skipping non-JSON parameter content: " + badJson.getMessage());
                }
            } while (c.moveToNext());
        } catch (Throwable t) {
            Log.w(TAG, "mergeRows(" + uri + ") failed: " + t.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return used;
    }

    private static String readContent(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            // NOTE: the provider's helper walks the cursor to the end and closes
            // the DB before returning it, so we must moveToFirst() to re-read.
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            int idx = c.getColumnIndex("content");
            if (idx < 0) return null;
            String content = c.getString(idx);
            if (content != null && !content.isEmpty()) {
                Log.w(TAG, "getParamContent(" + uri.getLastPathSegment() + ") -> " + content.length() + " chars");
            }
            return content;
        } catch (Throwable t) {
            Log.w(TAG, "readContent(" + uri + ") failed: " + t.getMessage());
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
    }

    private static void queryAndLog(Context ctx, String uriStr) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse(uriStr), null, null, null, null);
            if (c == null) {
                Log.w(TAG, uriStr + " -> null cursor");
                return;
            }
            Log.w(TAG, uriStr + " -> rows=" + c.getCount()
                    + " cols=" + Arrays.toString(c.getColumnNames()));
            // moveToFirst(): the provider's helper already advanced the cursor
            // to the end (and closed the DB) before returning it to us.
            if (c.moveToFirst()) {
                int row = 0;
                do {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        String v;
                        try {
                            v = c.getString(i);
                        } catch (Throwable t) {
                            v = "<non-string type>";
                        }
                        if (v != null) v = castech.emvtxn.pos.PosParams.maskForLog(v);   // never log the access key
                        if (v != null && v.length() > 300) v = v.substring(0, 300) + "...(" + v.length() + ")";
                        sb.append(c.getColumnName(i)).append('=').append(v).append(" | ");
                    }
                    Log.w(TAG, "  row" + row + ": " + sb);
                    row++;
                } while (row < 8 && c.moveToNext());
            }
        } catch (Throwable t) {
            Log.w(TAG, uriStr + " -> query failed: " + t.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignore) {}
            }
        }
    }
}
