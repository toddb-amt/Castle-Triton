package castech.emvtxn.atm.host;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Append-only audit journal for reversal events (tasks #6 + #7).
 *
 * <p>Mirrors BlueVerse's {@code CJnlDB} / {@code CJnlMgr} pattern:</p>
 * <ul>
 *     <li>Append-only — entries are written sequentially; no in-place updates</li>
 *     <li>Date-partitioned — one file per UTC day:
 *         {@code reversal_journal_YYYYMMDD.jsonl}</li>
 *     <li>JSON-Lines format — one JSON object per line for easy streaming
 *         and replay</li>
 *     <li>Retention policy — files older than the retention window are
 *         deleted on app startup or explicit {@link #cleanupOldFiles}</li>
 * </ul>
 *
 * <p>This journal is <strong>additive</strong> to the existing
 * {@link ReversalPersistenceManager} SharedPreferences store, which remains
 * the canonical state for active recovery. The journal serves as an
 * audit trail / PCI-friendly immutable record.</p>
 *
 * <p>All operations are best-effort — failures to write the journal must NOT
 * fail the calling operation. We log and continue.</p>
 */
public class ReversalJournal {

    private static final String TAG = "ReversalJournal";

    /** Subdirectory under app files dir where journal files live. */
    private static final String JOURNAL_DIR = "reversal_journal";

    /** File name pattern: reversal_journal_YYYYMMDD.jsonl (UTC date). */
    private static final String FILE_PATTERN = "reversal_journal_%s.jsonl";

    /** Date format for file partitioning (UTC). */
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyyMMdd", Locale.US);

    /** Default retention window: 90 days. */
    public static final int DEFAULT_RETENTION_DAYS = 90;

    private final Context context;
    private final int retentionDays;

    public ReversalJournal(Context context) {
        this(context, DEFAULT_RETENTION_DAYS);
    }

    public ReversalJournal(Context context, int retentionDays) {
        this.context = context.getApplicationContext();
        this.retentionDays = retentionDays;
    }

    /**
     * Returns the journal directory ({@code <files>/reversal_journal/}),
     * creating it if needed.
     */
    private File journalDir() {
        File dir = new File(context.getFilesDir(), JOURNAL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create journal dir: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Returns the journal file for the given date, creating its parent directory
     * if needed. Does not create the file itself.
     */
    private File journalFileFor(Date when) {
        String name = String.format(Locale.US, FILE_PATTERN, DATE_FMT.format(when));
        return new File(journalDir(), name);
    }

    /**
     * Returns today's journal file (UTC).
     */
    public File todaysJournalFile() {
        return journalFileFor(new Date());
    }

    /**
     * Appends a reversal lifecycle event to today's journal.
     *
     * @param event one of "create_presend", "promote", "drain_start",
     *              "drain_success", "drain_failed", "drain_exhausted",
     *              "operator_clear"
     * @param reversal the reversal record being acted on (may be null for
     *                 system-wide events)
     * @param notes   optional free-form note (may be null)
     */
    public void appendEvent(String event,
                            ReversalPersistenceManager.PendingReversal reversal,
                            String notes) {
        File f = todaysJournalFile();
        try {
            JSONObject entry = new JSONObject();
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("event", event);
            if (reversal != null) {
                entry.put("reversal", reversal.toJson());
            }
            if (notes != null) {
                entry.put("notes", notes);
            }
            try (FileOutputStream fos = new FileOutputStream(f, /*append*/true)) {
                fos.write((entry.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();   // Durable write — important for power-loss safety
            }
        } catch (JSONException | IOException e) {
            Log.w(TAG, "appendEvent failed (event=" + event + "): " + e.getMessage());
        }
    }

    /**
     * Reads all entries from today's journal. Used for diagnostics and
     * recovery verification.
     *
     * @return list of journal entries (JSON objects), oldest first
     */
    public List<JSONObject> readTodaysEntries() {
        return readEntries(todaysJournalFile());
    }

    private List<JSONObject> readEntries(File file) {
        List<JSONObject> out = new ArrayList<>();
        if (file == null || !file.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (JSONException je) {
                    Log.w(TAG, "Skipping malformed journal line: " + je.getMessage());
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "readEntries failed for " + file.getName() + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * Deletes journal files older than the retention window. Returns the
     * number of files removed.
     */
    public int cleanupOldFiles() {
        File[] files = journalDir().listFiles((dir, name) ->
                name.startsWith("reversal_journal_") && name.endsWith(".jsonl"));
        if (files == null) return 0;

        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24L * 60L * 60L * 1000L;
        int removed = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    removed++;
                    Log.d(TAG, "Cleanup: removed expired journal " + f.getName());
                } else {
                    Log.w(TAG, "Cleanup: failed to remove " + f.getName());
                }
            }
        }
        if (removed > 0) {
            Log.d(TAG, "Cleanup: removed " + removed + " expired journal files");
        }
        return removed;
    }

    /**
     * Returns the list of all journal file names currently on disk, sorted
     * lexicographically (which is also chronological for our naming scheme).
     */
    public List<String> listAllFiles() {
        File[] files = journalDir().listFiles((dir, name) ->
                name.startsWith("reversal_journal_") && name.endsWith(".jsonl"));
        if (files == null) return Collections.emptyList();
        List<String> names = new ArrayList<>(files.length);
        for (File f : files) names.add(f.getName());
        Collections.sort(names);
        return names;
    }
}
