/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.SQLite;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ApplicationLoader;

public class SQLiteDatabase {

	private final long sqliteHandle;

	private boolean isOpen;
    private boolean inTransaction;

	public long getSQLiteHandle() {
		return sqliteHandle;
	}

	public SQLiteDatabase(String fileName) throws SQLiteException {
		sqliteHandle = opendb(fileName, ApplicationLoader.getFilesDirFixed().getPath());
		isOpen = true;
	}

	public boolean tableExists(String tableName) throws SQLiteException {
		checkOpened();
		String s = "SELECT rowid FROM sqlite_master WHERE type='table' AND name=?;";
		return executeInt(s, tableName) != null;
	}

    public SQLitePreparedStatement executeFast(String sql) throws SQLiteException {
        return new SQLitePreparedStatement(this, sql);
    }

	public Integer executeInt(String sql, Object... args) throws SQLiteException {
		checkOpened();
		SQLiteCursor cursor = queryFinalized(sql, args);
		try {
			if (!cursor.next()) {
				return null;
			}
			return cursor.intValue(0);
		} finally {
			cursor.dispose();
		}
	}

	public void explainQuery(String sql, Object... args) throws SQLiteException {
		checkOpened();
		SQLiteCursor cursor = new SQLitePreparedStatement(this, "EXPLAIN QUERY PLAN " + sql).query(args);
		while (cursor.next()) {
			int count = cursor.getColumnCount();
			StringBuilder builder = new StringBuilder();
			for (int a = 0; a < count; a++) {
			    builder.append(cursor.stringValue(a)).append(", ");
            }
            FileLog.d("EXPLAIN QUERY PLAN " + builder.toString());
		}
		cursor.dispose();
	}

	public SQLiteCursor queryFinalized(String sql, Object... args) throws SQLiteException {
		checkOpened();
		return new SQLitePreparedStatement(this, sql).query(args);
	}

	public void close() {
		if (isOpen) {
			try {
                commitTransaction();
				closedb(sqliteHandle);
			} catch (SQLiteException e) {
				if (BuildVars.LOGS_ENABLED) {
					FileLog.e(e.getMessage(), e);
				}
			}
			isOpen = false;
		}
	}

	void checkOpened() throws SQLiteException {
		if (!isOpen) {
			throw new SQLiteException("Database closed");
		}
	}

	public void finalize() throws Throwable {
        super.finalize();
		close();
	}

    public void beginTransaction() throws SQLiteException {
        if (inTransaction) {
			if (BuildVars.DEBUG_PRIVATE_VERSION) {
				throw new SQLiteException("database already in transaction");
			} else {
				commitTransaction();
			}
        }
        inTransaction = true;
        beginTransaction(sqliteHandle);
    }

    public void commitTransaction() {
        if (!inTransaction) {
            return;
        }
        inTransaction = false;
        commitTransaction(sqliteHandle);
    }

    /**
     * Strict BEGIN used by the push placeholder path. Unlike {@link #beginTransaction()} it never
     * silently commits a previous transaction: a nested begin is an error and the flag is set only
     * after SQLite reported success, so a failed BEGIN leaves the connection outside a transaction.
     */
    public void beginTransactionChecked() throws SQLiteException {
        checkOpened();
        if (inTransaction) {
            throw new SQLiteException("database already in transaction");
        }
        int errorCode = beginTransactionChecked(sqliteHandle);
        if (errorCode != 0) {
            throw new SQLiteException(errorCode, "BEGIN failed with error code " + errorCode);
        }
        inTransaction = true;
    }

    /**
     * Strict COMMIT. On failure the flag stays set and the exception is rethrown without retrying:
     * the caller owns the rollback decision and must not publish anything before a confirmed commit.
     */
    public void commitTransactionChecked() throws SQLiteException {
        checkOpened();
        if (!inTransaction) {
            return;
        }
        int errorCode = commitTransactionChecked(sqliteHandle);
        if (errorCode != 0) {
            throw new SQLiteException(errorCode, "COMMIT failed with error code " + errorCode);
        }
        inTransaction = false;
    }

    /**
     * Strict ROLLBACK. Never falls back to COMMIT; on failure the flag stays set and the exception
     * is rethrown so the caller can report the still-open transaction.
     */
    public void rollbackTransactionChecked() throws SQLiteException {
        checkOpened();
        if (!inTransaction) {
            return;
        }
        int errorCode = rollbackTransactionChecked(sqliteHandle);
        if (errorCode != 0) {
            throw new SQLiteException(errorCode, "ROLLBACK failed with error code " + errorCode);
        }
        inTransaction = false;
    }

    /** True while this connection owns an open transaction (checked or legacy). */
    public boolean isInTransaction() {
        return inTransaction;
    }

	native long opendb(String fileName, String tempDir) throws SQLiteException;
	native void closedb(long sqliteHandle) throws SQLiteException;
    native void beginTransaction(long sqliteHandle);
    native void commitTransaction(long sqliteHandle);
    native int beginTransactionChecked(long sqliteHandle);
    native int commitTransactionChecked(long sqliteHandle);
    native int rollbackTransactionChecked(long sqliteHandle);
}
