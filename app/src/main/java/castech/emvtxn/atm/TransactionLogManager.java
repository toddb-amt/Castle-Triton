package castech.emvtxn.atm;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistent storage of transaction logs using SQLite.
 * Provides methods to save, query, and export transaction audit trail.
 */
public class TransactionLogManager extends SQLiteOpenHelper {

    private static final String TAG = "TransactionLogManager";

    // Database configuration
    private static final String DATABASE_NAME = "atm_transactions.db";
    private static final int DATABASE_VERSION = 2;   // 2 (6.2.11): report columns + batches table

    // Table name
    private static final String TABLE_TRANSACTIONS = "transactions";

    // Column names
    private static final String COL_ID = "id";
    private static final String COL_TRANSACTION_ID = "transaction_id";
    private static final String COL_TRANSACTION_TYPE = "transaction_type";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_CARD_LAST_FOUR = "card_last_four";
    private static final String COL_ENTRY_MODE = "entry_mode";
    private static final String COL_AMOUNT_CENTS = "amount_cents";
    private static final String COL_FEE_CENTS = "fee_cents";
    private static final String COL_TOTAL_CENTS = "total_cents";
    private static final String COL_RESULT = "result";
    private static final String COL_RESPONSE_CODE = "response_code";
    private static final String COL_AUTH_CODE = "auth_code";
    private static final String COL_REFERENCE_NUMBER = "reference_number";
    private static final String COL_TERMINAL_ID = "terminal_id";
    private static final String COL_PROCESSOR_TYPE = "processor_type";
    private static final String COL_BALANCE_ACCOUNT = "balance_account_cents";
    private static final String COL_BALANCE_AVAILABLE = "balance_available_cents";
    private static final String COL_ERROR_MESSAGE = "error_message";
    // 6.2.11 report columns
    private static final String COL_SEQUENCE = "sequence_number";
    private static final String COL_ACCOUNT_TYPE = "account_type";
    private static final String COL_CLERK_ID = "clerk_id";
    private static final String COL_INVOICE_NO = "invoice_no";
    private static final String COL_TIP_CENTS = "tip_cents";
    private static final String COL_BATCH_ID = "batch_id";
    private static final String COL_REVERSED = "reversed";

    // Terminal-owned batches (6.2.11): one row per batch; closed_at = 0 while open
    private static final String TABLE_BATCHES = "batches";
    private static final String SQL_CREATE_BATCHES =
            "CREATE TABLE IF NOT EXISTS " + TABLE_BATCHES + " (" +
            "id INTEGER PRIMARY KEY, opened_at INTEGER NOT NULL, closed_at INTEGER DEFAULT 0, " +
            "withdrawals INTEGER DEFAULT 0, amount_cents INTEGER DEFAULT 0, fee_cents INTEGER DEFAULT 0, tip_cents INTEGER DEFAULT 0)";

    // Create table SQL
    private static final String SQL_CREATE_TABLE =
            "CREATE TABLE " + TABLE_TRANSACTIONS + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_TRANSACTION_ID + " TEXT UNIQUE NOT NULL, " +
                    COL_TRANSACTION_TYPE + " TEXT NOT NULL, " +
                    COL_TIMESTAMP + " INTEGER NOT NULL, " +
                    COL_CARD_LAST_FOUR + " TEXT, " +
                    COL_ENTRY_MODE + " TEXT, " +
                    COL_AMOUNT_CENTS + " INTEGER DEFAULT 0, " +
                    COL_FEE_CENTS + " INTEGER DEFAULT 0, " +
                    COL_TOTAL_CENTS + " INTEGER DEFAULT 0, " +
                    COL_RESULT + " TEXT, " +
                    COL_RESPONSE_CODE + " TEXT, " +
                    COL_AUTH_CODE + " TEXT, " +
                    COL_REFERENCE_NUMBER + " TEXT, " +
                    COL_TERMINAL_ID + " TEXT, " +
                    COL_PROCESSOR_TYPE + " TEXT, " +
                    COL_BALANCE_ACCOUNT + " INTEGER DEFAULT 0, " +
                    COL_BALANCE_AVAILABLE + " INTEGER DEFAULT 0, " +
                    COL_ERROR_MESSAGE + " TEXT, " +
                    COL_SEQUENCE + " INTEGER DEFAULT 0, " +
                    COL_ACCOUNT_TYPE + " INTEGER DEFAULT 20, " +
                    COL_CLERK_ID + " TEXT, " +
                    COL_INVOICE_NO + " TEXT, " +
                    COL_TIP_CENTS + " INTEGER DEFAULT 0, " +
                    COL_BATCH_ID + " INTEGER DEFAULT 1, " +
                    COL_REVERSED + " INTEGER DEFAULT 0" +
                    ")";

    // Index for faster queries
    private static final String SQL_CREATE_INDEX_TIMESTAMP =
            "CREATE INDEX idx_timestamp ON " + TABLE_TRANSACTIONS + " (" + COL_TIMESTAMP + " DESC)";

    private static final String SQL_CREATE_INDEX_RESULT =
            "CREATE INDEX idx_result ON " + TABLE_TRANSACTIONS + " (" + COL_RESULT + ")";

    private static TransactionLogManager instance;
    private final Context context;

    /**
     * Gets singleton instance of TransactionLogManager.
     *
     * @param context Application context
     * @return TransactionLogManager instance
     */
    public static synchronized TransactionLogManager getInstance(Context context) {
        if (instance == null) {
            instance = new TransactionLogManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Private constructor - use getInstance() instead.
     */
    private TransactionLogManager(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context;
        Log.d(TAG, "TransactionLogManager initialized");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "Creating transaction log database");
        db.execSQL(SQL_CREATE_TABLE);
        db.execSQL(SQL_CREATE_INDEX_TIMESTAMP);
        db.execSQL(SQL_CREATE_INDEX_RESULT);
        db.execSQL(SQL_CREATE_BATCHES);
        Log.d(TAG, "Database created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading transaction log " + oldVersion + " → " + newVersion + " (data preserved)");
        if (oldVersion < 2) {
            for (String ddl : new String[] {
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_SEQUENCE + " INTEGER DEFAULT 0",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_ACCOUNT_TYPE + " INTEGER DEFAULT 20",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_CLERK_ID + " TEXT",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_INVOICE_NO + " TEXT",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_TIP_CENTS + " INTEGER DEFAULT 0",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_BATCH_ID + " INTEGER DEFAULT 1",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_REVERSED + " INTEGER DEFAULT 0" }) {
                try { db.execSQL(ddl); } catch (Exception e) { Log.w(TAG, "migration step skipped: " + e.getMessage()); }
            }
            db.execSQL(SQL_CREATE_BATCHES);
        }
    }

    /**
     * Saves a transaction log entry to the database.
     *
     * @param log Transaction log to save
     * @return Database row ID, or -1 if error
     */
    public long saveTransaction(TransactionLog log) {
        if (log == null) {
            Log.e(TAG, "Cannot save null transaction log");
            return -1;
        }

        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            ContentValues values = new ContentValues();

            values.put(COL_TRANSACTION_ID, log.getTransactionId());
            values.put(COL_TRANSACTION_TYPE, log.getTransactionType());
            values.put(COL_TIMESTAMP, log.getTimestamp());
            values.put(COL_CARD_LAST_FOUR, log.getCardLastFour());
            values.put(COL_ENTRY_MODE, log.getEntryMode());
            values.put(COL_AMOUNT_CENTS, log.getAmountCents());
            values.put(COL_FEE_CENTS, log.getFeeCents());
            values.put(COL_TOTAL_CENTS, log.getTotalCents());
            values.put(COL_RESULT, log.getResult());
            values.put(COL_RESPONSE_CODE, log.getResponseCode());
            values.put(COL_AUTH_CODE, log.getAuthCode());
            values.put(COL_REFERENCE_NUMBER, log.getReferenceNumber());
            values.put(COL_TERMINAL_ID, log.getTerminalId());
            values.put(COL_PROCESSOR_TYPE, log.getProcessorType());
            values.put(COL_BALANCE_ACCOUNT, log.getBalanceAccountCents());
            values.put(COL_BALANCE_AVAILABLE, log.getBalanceAvailableCents());
            values.put(COL_ERROR_MESSAGE, log.getErrorMessage());
            values.put(COL_SEQUENCE, log.getSequenceNumber());
            values.put(COL_ACCOUNT_TYPE, log.getAccountType());
            values.put(COL_CLERK_ID, log.getClerkId());
            values.put(COL_INVOICE_NO, log.getInvoiceNo());
            values.put(COL_TIP_CENTS, log.getTipCents());
            values.put(COL_BATCH_ID, log.getBatchId());
            values.put(COL_REVERSED, log.isReversed() ? 1 : 0);

            long id = db.insert(TABLE_TRANSACTIONS, null, values);
            if (id != -1) {
                log.setId(id);
                Log.d(TAG, "Transaction saved: " + log.getTransactionId() + " (id=" + id + ")");
            } else {
                Log.e(TAG, "Failed to save transaction: " + log.getTransactionId());
            }
            return id;

        } catch (Exception e) {
            Log.e(TAG, "Error saving transaction: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Updates an existing transaction log entry.
     *
     * @param log Transaction log to update (must have valid ID)
     * @return true if updated successfully
     */
    public boolean updateTransaction(TransactionLog log) {
        if (log == null || log.getId() <= 0) {
            Log.e(TAG, "Cannot update transaction with invalid ID");
            return false;
        }

        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            ContentValues values = new ContentValues();

            values.put(COL_RESULT, log.getResult());
            values.put(COL_RESPONSE_CODE, log.getResponseCode());
            values.put(COL_AUTH_CODE, log.getAuthCode());
            values.put(COL_REFERENCE_NUMBER, log.getReferenceNumber());
            values.put(COL_BALANCE_ACCOUNT, log.getBalanceAccountCents());
            values.put(COL_BALANCE_AVAILABLE, log.getBalanceAvailableCents());
            values.put(COL_ERROR_MESSAGE, log.getErrorMessage());
            values.put(COL_SEQUENCE, log.getSequenceNumber());
            values.put(COL_ACCOUNT_TYPE, log.getAccountType());
            values.put(COL_CLERK_ID, log.getClerkId());
            values.put(COL_INVOICE_NO, log.getInvoiceNo());
            values.put(COL_TIP_CENTS, log.getTipCents());
            values.put(COL_BATCH_ID, log.getBatchId());
            values.put(COL_REVERSED, log.isReversed() ? 1 : 0);

            int rows = db.update(TABLE_TRANSACTIONS, values,
                    COL_ID + " = ?", new String[]{String.valueOf(log.getId())});

            Log.d(TAG, "Updated transaction " + log.getTransactionId() + ": " + rows + " rows affected");
            return rows > 0;

        } catch (Exception e) {
            Log.e(TAG, "Error updating transaction: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets a transaction by its transaction ID.
     *
     * @param transactionId Unique transaction identifier
     * @return TransactionLog or null if not found
     */
    public TransactionLog getTransactionById(String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return null;
        }

        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getReadableDatabase();
            cursor = db.query(TABLE_TRANSACTIONS, null,
                    COL_TRANSACTION_ID + " = ?", new String[]{transactionId},
                    null, null, null);

            if (cursor.moveToFirst()) {
                return cursorToTransactionLog(cursor);
            }
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error getting transaction: " + e.getMessage());
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets recent transactions, ordered by timestamp descending.
     *
     * @param limit Maximum number of transactions to return
     * @return List of transaction logs
     */
    public List<TransactionLog> getRecentTransactions(int limit) {
        List<TransactionLog> transactions = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cursor = null;

        try {
            db = getReadableDatabase();
            cursor = db.query(TABLE_TRANSACTIONS, null,
                    null, null, null, null,
                    COL_TIMESTAMP + " DESC",
                    String.valueOf(limit));

            while (cursor.moveToNext()) {
                transactions.add(cursorToTransactionLog(cursor));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting recent transactions: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return transactions;
    }

    /**
     * Gets transactions within a date range.
     *
     * @param startTimestamp Start time in milliseconds
     * @param endTimestamp   End time in milliseconds
     * @return List of transaction logs
     */
    public List<TransactionLog> getTransactionsByDateRange(long startTimestamp, long endTimestamp) {
        List<TransactionLog> transactions = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cursor = null;

        try {
            db = getReadableDatabase();
            cursor = db.query(TABLE_TRANSACTIONS, null,
                    COL_TIMESTAMP + " >= ? AND " + COL_TIMESTAMP + " <= ?",
                    new String[]{String.valueOf(startTimestamp), String.valueOf(endTimestamp)},
                    null, null,
                    COL_TIMESTAMP + " DESC");

            while (cursor.moveToNext()) {
                transactions.add(cursorToTransactionLog(cursor));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting transactions by date range: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return transactions;
    }

    /**
     * Gets transactions by result type.
     *
     * @param result Result type (APPROVED, DECLINED, etc.)
     * @param limit  Maximum number to return
     * @return List of transaction logs
     */
    public List<TransactionLog> getTransactionsByResult(String result, int limit) {
        List<TransactionLog> transactions = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cursor = null;

        try {
            db = getReadableDatabase();
            cursor = db.query(TABLE_TRANSACTIONS, null,
                    COL_RESULT + " = ?", new String[]{result},
                    null, null,
                    COL_TIMESTAMP + " DESC",
                    String.valueOf(limit));

            while (cursor.moveToNext()) {
                transactions.add(cursorToTransactionLog(cursor));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting transactions by result: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return transactions;
    }

    /**
     * Gets total transaction count.
     *
     * @return Number of transactions in database
     */
    public int getTransactionCount() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getReadableDatabase();
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_TRANSACTIONS, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error getting transaction count: " + e.getMessage());
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets count of transactions by result type.
     *
     * @param result Result type
     * @return Count of matching transactions
     */
    public int getCountByResult(String result) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_TRANSACTIONS + " WHERE " + COL_RESULT + " = ?",
                    new String[]{result});
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error getting count by result: " + e.getMessage());
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets total amount of approved withdrawals (in cents).
     *
     * @return Total amount in cents
     */
    public long getTotalApprovedAmount() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT SUM(" + COL_AMOUNT_CENTS + ") FROM " + TABLE_TRANSACTIONS +
                            " WHERE " + COL_RESULT + " = ? AND " + COL_TRANSACTION_TYPE + " = ?",
                    new String[]{TransactionLog.RESULT_APPROVED, TransactionLog.TYPE_WITHDRAWAL});
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error getting total approved amount: " + e.getMessage());
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets total fees collected (in cents).
     *
     * @return Total fees in cents
     */
    public long getTotalFeesCollected() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT SUM(" + COL_FEE_CENTS + ") FROM " + TABLE_TRANSACTIONS +
                            " WHERE " + COL_RESULT + " = ?",
                    new String[]{TransactionLog.RESULT_APPROVED});
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error getting total fees: " + e.getMessage());
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Deletes transactions older than specified timestamp.
     * Use for maintenance to prevent database from growing too large.
     *
     * @param olderThanTimestamp Delete transactions before this time
     * @return Number of deleted rows
     */
    public int deleteOldTransactions(long olderThanTimestamp) {
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            int deleted = db.delete(TABLE_TRANSACTIONS,
                    COL_TIMESTAMP + " < ?",
                    new String[]{String.valueOf(olderThanTimestamp)});
            Log.d(TAG, "Deleted " + deleted + " old transactions");
            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "Error deleting old transactions: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Clears all transaction logs.
     * Use with caution - for testing only.
     *
     * @return Number of deleted rows
     */
    public int clearAllTransactions() {
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            int deleted = db.delete(TABLE_TRANSACTIONS, null, null);
            Log.d(TAG, "Cleared all transactions: " + deleted + " rows");
            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing transactions: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets a summary of transaction statistics.
     *
     * @return Summary string for display
     */
    public String getTransactionSummary() {
        int total = getTransactionCount();
        int approved = getCountByResult(TransactionLog.RESULT_APPROVED);
        int declined = getCountByResult(TransactionLog.RESULT_DECLINED);
        int errors = getCountByResult(TransactionLog.RESULT_ERROR);
        long totalAmount = getTotalApprovedAmount();
        long totalFees = getTotalFeesCollected();

        StringBuilder sb = new StringBuilder();
        sb.append("Transaction Summary\n");
        sb.append("==================\n");
        sb.append("Total Transactions: ").append(total).append("\n");
        sb.append("Approved: ").append(approved).append("\n");
        sb.append("Declined: ").append(declined).append("\n");
        sb.append("Errors: ").append(errors).append("\n");
        sb.append("Total Amount: $").append(String.format("%.2f", totalAmount / 100.0)).append("\n");
        sb.append("Total Fees: $").append(String.format("%.2f", totalFees / 100.0)).append("\n");

        return sb.toString();
    }

    /**
     * Converts a cursor row to a TransactionLog object.
     */
    private TransactionLog cursorToTransactionLog(Cursor cursor) {
        TransactionLog log = new TransactionLog();

        log.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)));
        log.setTransactionId(cursor.getString(cursor.getColumnIndexOrThrow(COL_TRANSACTION_ID)));
        log.setTransactionType(cursor.getString(cursor.getColumnIndexOrThrow(COL_TRANSACTION_TYPE)));
        log.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
        log.setCardLastFour(cursor.getString(cursor.getColumnIndexOrThrow(COL_CARD_LAST_FOUR)));
        log.setEntryMode(cursor.getString(cursor.getColumnIndexOrThrow(COL_ENTRY_MODE)));
        log.setAmountCents(cursor.getLong(cursor.getColumnIndexOrThrow(COL_AMOUNT_CENTS)));
        log.setFeeCents(cursor.getLong(cursor.getColumnIndexOrThrow(COL_FEE_CENTS)));
        log.setTotalCents(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TOTAL_CENTS)));
        log.setResult(cursor.getString(cursor.getColumnIndexOrThrow(COL_RESULT)));
        log.setResponseCode(cursor.getString(cursor.getColumnIndexOrThrow(COL_RESPONSE_CODE)));
        log.setAuthCode(cursor.getString(cursor.getColumnIndexOrThrow(COL_AUTH_CODE)));
        log.setReferenceNumber(cursor.getString(cursor.getColumnIndexOrThrow(COL_REFERENCE_NUMBER)));
        log.setTerminalId(cursor.getString(cursor.getColumnIndexOrThrow(COL_TERMINAL_ID)));
        log.setProcessorType(cursor.getString(cursor.getColumnIndexOrThrow(COL_PROCESSOR_TYPE)));
        log.setBalanceAccountCents(cursor.getLong(cursor.getColumnIndexOrThrow(COL_BALANCE_ACCOUNT)));
        log.setBalanceAvailableCents(cursor.getLong(cursor.getColumnIndexOrThrow(COL_BALANCE_AVAILABLE)));
        log.setErrorMessage(cursor.getString(cursor.getColumnIndexOrThrow(COL_ERROR_MESSAGE)));
        // 6.2.11 columns (guarded: a cursor from an un-migrated row set may lack them)
        int i;
        if ((i = cursor.getColumnIndex(COL_SEQUENCE)) >= 0) log.setSequenceNumber(cursor.getInt(i));
        if ((i = cursor.getColumnIndex(COL_ACCOUNT_TYPE)) >= 0) log.setAccountType(cursor.getInt(i));
        if ((i = cursor.getColumnIndex(COL_CLERK_ID)) >= 0) log.setClerkId(cursor.getString(i));
        if ((i = cursor.getColumnIndex(COL_INVOICE_NO)) >= 0) log.setInvoiceNo(cursor.getString(i));
        if ((i = cursor.getColumnIndex(COL_TIP_CENTS)) >= 0) log.setTipCents(cursor.getLong(i));
        if ((i = cursor.getColumnIndex(COL_BATCH_ID)) >= 0) log.setBatchId(cursor.getInt(i));
        if ((i = cursor.getColumnIndex(COL_REVERSED)) >= 0) log.setReversed(cursor.getInt(i) != 0);
        return log;
    }

    // ======================================================================
    // Batches (6.2.11) — terminal-owned boundary + counter, see BatchMath
    // ======================================================================

    /** The open batch's id; opens batch 001 on first use. */
    public synchronized int currentBatchId() {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM " + TABLE_BATCHES + " WHERE closed_at = 0 ORDER BY id DESC LIMIT 1", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        ContentValues v = new ContentValues();
        v.put("id", BatchMath.firstBatchId());
        v.put("opened_at", System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_BATCHES, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return BatchMath.firstBatchId();
    }

    public synchronized long currentBatchOpenedAt() {
        int id = currentBatchId();
        Cursor c = getReadableDatabase().rawQuery("SELECT opened_at FROM " + TABLE_BATCHES + " WHERE id = ?",
                new String[] { String.valueOf(id) });
        try {
            return c.moveToFirst() ? c.getLong(0) : System.currentTimeMillis();
        } finally {
            c.close();
        }
    }

    /** Close the open batch with its summary snapshot; open the next; prune. Returns the NEW batch id. */
    public synchronized int closeCurrentBatch(castech.emvtxn.atm.report.DetailReport.Summary s) {
        int current = currentBatchId();
        int next = BatchMath.nextBatchId(current);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues close = new ContentValues();
            close.put("closed_at", System.currentTimeMillis());
            close.put("withdrawals", s.withdrawals);
            close.put("amount_cents", s.amountCents);
            close.put("fee_cents", s.feeCents);
            close.put("tip_cents", s.tipCents);
            db.update(TABLE_BATCHES, close, "id = ?", new String[] { String.valueOf(current) });
            ContentValues open = new ContentValues();
            open.put("id", next);
            open.put("opened_at", System.currentTimeMillis());
            db.insert(TABLE_BATCHES, null, open);
            long oldest = BatchMath.oldestBatchToKeep(next, BatchMath.KEEP_BATCHES);
            db.delete(TABLE_TRANSACTIONS, COL_BATCH_ID + " < ?", new String[] { String.valueOf(oldest) });
            db.delete(TABLE_BATCHES, "id < ?", new String[] { String.valueOf(oldest) });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.w(TAG, "Batch " + current + " closed; batch " + next + " opened");
        return next;
    }

    public List<TransactionLog> getTransactionsForBatch(int batchId) {
        List<TransactionLog> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_TRANSACTIONS, null, COL_BATCH_ID + " = ?",
                    new String[] { String.valueOf(batchId) }, null, null, COL_TIMESTAMP + " ASC, " + COL_ID + " ASC");
            while (c.moveToNext()) out.add(cursorToTransactionLog(c));
        } catch (Exception e) {
            Log.e(TAG, "getTransactionsForBatch: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * Marks the withdrawal row with this transaction id as reversed; false when there is none.
     * Keyed on the pre-send reversal record id (shared by the journal row and the reversal),
     * never on the per-session sequence number — that restarts at 1 on every app start and
     * would match an unrelated earlier withdrawal in the same batch (review #1).
     */
    public boolean markReversed(String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) return false;
        ContentValues v = new ContentValues();
        v.put(COL_REVERSED, 1);
        int n = getWritableDatabase().update(TABLE_TRANSACTIONS, v,
                COL_TRANSACTION_ID + " = ? AND " + COL_TRANSACTION_TYPE + " = 'WITHDRAWAL'",
                new String[] { transactionId });
        return n > 0;
    }

    /** Deletes rows (and batch records) of CLOSED batches; the open batch is never touched. */
    public synchronized int clearClosedBatches() {
        int current = currentBatchId();
        SQLiteDatabase db = getWritableDatabase();
        int n = db.delete(TABLE_TRANSACTIONS, COL_BATCH_ID + " < ?", new String[] { String.valueOf(current) });
        db.delete(TABLE_BATCHES, "id < ?", new String[] { String.valueOf(current) });
        return n;
    }
}
