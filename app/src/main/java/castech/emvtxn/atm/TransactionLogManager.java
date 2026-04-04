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
    private static final int DATABASE_VERSION = 1;

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
                    COL_ERROR_MESSAGE + " TEXT" +
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
        Log.d(TAG, "Database created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        // For now, just recreate - in production, migrate data
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_TRANSACTIONS);
        onCreate(db);
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

        return log;
    }
}
