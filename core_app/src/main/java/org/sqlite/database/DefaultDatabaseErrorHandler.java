/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
** Modified to support SQLite extensions by the SQLite developers: 
** sqlite-dev@sqlite.org.
*/

package org.sqlite.database;

import org.opendatakit.common.android.database.OperationLog;
import org.sqlite.database.sqlite.SQLiteDatabase;
import org.sqlite.database.sqlite.SQLiteDatabaseConfiguration;

/**
 * Default class used to define the actions to take when the database corruption is reported
 * by sqlite.  This had destroyed the database. Changed to simply report the error.
 * <p>
 * An application can specify an implementation of {@link DatabaseErrorHandler} on the
 * following:
 * <ul>
 *   <li>{@link SQLiteDatabase#SQLiteDatabase(SQLiteDatabaseConfiguration,
 *         OperationLog, DatabaseErrorHandler, String)}</li>
 * </ul>
 * The specified {@link DatabaseErrorHandler} is used to handle database corruption errors, if they
 * occur.
 * <p>
 * If null is specified for DatabaeErrorHandler param in the above calls, then this class is used
 * as the default {@link DatabaseErrorHandler}.
 */
public final class DefaultDatabaseErrorHandler implements DatabaseErrorHandler {

    private static final String TAG = "DefaultDbErrorHandler";

    /**
     * defines the default method to be invoked when database corruption is detected.
     * @param dbObj the {@link SQLiteDatabase} object representing the database on which corruption
     * is detected.
     */
    public void onCorruption(SQLiteDatabase dbObj) {
       dbObj.getLogger().e(TAG, "Corruption reported by sqlite on database: " + dbObj.getAppName());
       throw new IllegalStateException("Corrupted database -- what do we do now?");
    }
}
