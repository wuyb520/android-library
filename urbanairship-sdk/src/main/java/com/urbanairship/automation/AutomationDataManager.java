/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.automation;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;

import com.urbanairship.Logger;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonMap;
import com.urbanairship.json.JsonPredicate;
import com.urbanairship.json.JsonValue;
import com.urbanairship.util.DataManager;
import com.urbanairship.util.UAStringUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DataManager} class for automation schedules.
 */
class AutomationDataManager extends DataManager {

    /**
     * Action schedules table contract
     */
    private class ActionSchedulesTable implements BaseColumns {

        public static final String TABLE_NAME = "action_schedules";

        static final String COLUMN_NAME_SCHEDULE_ID = "s_id";
        static final String COLUMN_NAME_ACTIONS = "s_actions";
        static final String COLUMN_NAME_COUNT = "s_count";
        static final String COLUMN_NAME_LIMIT = "s_limit";
        static final String COLUMN_NAME_GROUP = "s_group";
        static final String COLUMN_NAME_START = "s_start";
        static final String COLUMN_NAME_END = "s_end";
    }

    /**
     * Triggers table contract
     */
    private class TriggersTable implements BaseColumns {

        public static final String TABLE_NAME = "triggers";

        static final String COLUMN_NAME_TYPE = "t_type";
        static final String COLUMN_NAME_SCHEDULE_ID = "t_s_id";
        static final String COLUMN_NAME_PREDICATE = "t_predicate";
        static final String COLUMN_NAME_PROGRESS = "t_progress";
        static final String COLUMN_NAME_GOAL = "t_goal";
        static final String COLUMN_NAME_START = "t_start";
    }

    /**
     * Maximum SQL argument count.
     */
    private static final int MAX_ARG_COUNT = 999;

    /**
     * The database that the provider uses as its underlying data store
     */
    private static final String DATABASE_NAME = "ua_automation.db";

    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 1;

    /**
     * Appended to the end of schedules GET queries to group rows by schedule ID.
     */
    private static final String ORDER_SCHEDULES_STATEMENT = " ORDER BY " + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + " ASC";

    /**
     * Query for retrieving schedules with a JOIN.
     */
    static final String GET_SCHEDULES_QUERY = "SELECT * FROM " + ActionSchedulesTable.TABLE_NAME + " a INNER JOIN " + TriggersTable.TABLE_NAME + " b ON a." + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + "=b." + TriggersTable.COLUMN_NAME_SCHEDULE_ID;

    /**
     * Partial query for deleting schedules by ID.
     */
    static final String SCHEDULES_TO_DELETE_QUERY = "DELETE FROM " + ActionSchedulesTable.TABLE_NAME + " WHERE " + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID;

    /**
     * Partial query for incrementing schedule counts by ID.
     */
    static final String SCHEDULES_TO_INCREMENT_QUERY = "UPDATE " + ActionSchedulesTable.TABLE_NAME + " SET " + ActionSchedulesTable.COLUMN_NAME_COUNT + " = " + ActionSchedulesTable.COLUMN_NAME_COUNT + " + 1 WHERE " + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID;

    /**
     * Partial query for resetting trigger progress by ID.
     */
    static final String TRIGGERS_TO_RESET_QUERY = "UPDATE " + TriggersTable.TABLE_NAME + " SET " + TriggersTable.COLUMN_NAME_PROGRESS + " = 0 WHERE " + TriggersTable._ID;

    /**
     * Partial query for incrementing trigger progress by ID.
     */
    static final String TRIGGERS_TO_INCREMENT_QUERY = "UPDATE " + TriggersTable.TABLE_NAME + " SET " + TriggersTable.COLUMN_NAME_PROGRESS + " = " + TriggersTable.COLUMN_NAME_PROGRESS + " + %s WHERE " + TriggersTable._ID;

    /**
     * Class constructor.
     *
     * @param context The app context.
     * @param appKey The app key.
     */
    AutomationDataManager(@NonNull Context context, @NonNull String appKey) {
        super(context, appKey, DATABASE_NAME, DATABASE_VERSION);
    }

    @Override
    protected void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        // Logs that the database is being upgraded
        Logger.debug("AutomationDataManager - Upgrading automation database from version " + oldVersion + " to "
                + newVersion + ", which will destroy all old data");

        // Kills the table and existing data
        db.execSQL("DROP TABLE IF EXISTS " + TriggersTable.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + ActionSchedulesTable.TABLE_NAME);

        // Recreates the database with a new version
        onCreate(db);
    }

    @Override
    protected void onCreate(@NonNull SQLiteDatabase db) {
        Logger.debug("AutomationDataManager - Creating automation database");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + ActionSchedulesTable.TABLE_NAME + " ("
                + ActionSchedulesTable._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + " TEXT UNIQUE,"
                + ActionSchedulesTable.COLUMN_NAME_ACTIONS + " TEXT,"
                + ActionSchedulesTable.COLUMN_NAME_START + " INTEGER,"
                + ActionSchedulesTable.COLUMN_NAME_END + " INTEGER,"
                + ActionSchedulesTable.COLUMN_NAME_COUNT + " INTEGER,"
                + ActionSchedulesTable.COLUMN_NAME_LIMIT + " INTEGER,"
                + ActionSchedulesTable.COLUMN_NAME_GROUP + " TEXT"
                + ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TriggersTable.TABLE_NAME + " ("
                + TriggersTable._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + TriggersTable.COLUMN_NAME_TYPE + " INTEGER,"
                + TriggersTable.COLUMN_NAME_SCHEDULE_ID + " TEXT,"
                + TriggersTable.COLUMN_NAME_PREDICATE + " TEXT,"
                + TriggersTable.COLUMN_NAME_PROGRESS + " DOUBLE,"
                + TriggersTable.COLUMN_NAME_GOAL + " DOUBLE,"
                + TriggersTable.COLUMN_NAME_START + " INTEGER,"
                + "FOREIGN KEY(" + TriggersTable.COLUMN_NAME_SCHEDULE_ID + ") REFERENCES " + ActionSchedulesTable.TABLE_NAME + "(" + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + ") ON DELETE CASCADE"
                + ");");

        Logger.debug("AutomationDataManager - Automation database created");
    }

    @Override
    protected void bindValuesToSqliteStatement(@NonNull String table, @NonNull SQLiteStatement statement, @NonNull ContentValues values) {
        if (TriggersTable.TABLE_NAME.equals(table)) {
            bind(statement, 1, values.getAsInteger(TriggersTable.COLUMN_NAME_TYPE));
            bind(statement, 2, values.getAsString(TriggersTable.COLUMN_NAME_SCHEDULE_ID));
            bind(statement, 3, values.getAsString(TriggersTable.COLUMN_NAME_PREDICATE));
            bind(statement, 4, values.getAsDouble(TriggersTable.COLUMN_NAME_PROGRESS));
            bind(statement, 5, values.getAsDouble(TriggersTable.COLUMN_NAME_GOAL));
            bind(statement, 6, values.getAsLong(TriggersTable.COLUMN_NAME_START));
        } else if (ActionSchedulesTable.TABLE_NAME.equals(table)) {
            bind(statement, 1, values.getAsString(ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID));
            bind(statement, 2, values.getAsString(ActionSchedulesTable.COLUMN_NAME_ACTIONS));
            bind(statement, 3, values.getAsLong(ActionSchedulesTable.COLUMN_NAME_START));
            bind(statement, 4, values.getAsLong(ActionSchedulesTable.COLUMN_NAME_END));
            bind(statement, 5, values.getAsInteger(ActionSchedulesTable.COLUMN_NAME_COUNT));
            bind(statement, 6, values.getAsInteger(ActionSchedulesTable.COLUMN_NAME_LIMIT));
            bind(statement, 7, values.getAsString(ActionSchedulesTable.COLUMN_NAME_GROUP));
        }
    }

    @Override
    protected SQLiteStatement getInsertStatement(@NonNull String table, @NonNull SQLiteDatabase db) {
        if (table.equals(TriggersTable.TABLE_NAME)) {
            String sql = this.buildInsertStatement(table, TriggersTable.COLUMN_NAME_TYPE,
                    TriggersTable.COLUMN_NAME_SCHEDULE_ID, TriggersTable.COLUMN_NAME_PREDICATE,
                    TriggersTable.COLUMN_NAME_PROGRESS, TriggersTable.COLUMN_NAME_GOAL,
                    TriggersTable.COLUMN_NAME_START);

            return db.compileStatement(sql);
        } else if (table.equals(ActionSchedulesTable.TABLE_NAME)) {
            String sql = this.buildInsertStatement(table, ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID,
                    ActionSchedulesTable.COLUMN_NAME_ACTIONS, ActionSchedulesTable.COLUMN_NAME_START,
                    ActionSchedulesTable.COLUMN_NAME_END, ActionSchedulesTable.COLUMN_NAME_COUNT,
                    ActionSchedulesTable.COLUMN_NAME_LIMIT, ActionSchedulesTable.COLUMN_NAME_GROUP);

            return db.compileStatement(sql);
        }

        return null;
    }

    @Override
    protected void onDowngrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        // Logs that the database is being downgraded
        Logger.debug("AutomationDataManager - Downgrading automation database from version " + oldVersion + " to "
                + newVersion + ", which will destroy all old data");

        // Drop the table and recreate it
        db.execSQL("DROP TABLE IF EXISTS " + TriggersTable.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + ActionSchedulesTable.TABLE_NAME);
        onCreate(db);
    }

    @Override
    protected void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }
    
    /**
     * Deletes a schedule given an ID.
     *
     * @param scheduleId The schedule ID.
     */
    void deleteSchedule(String scheduleId) {
        if (delete(ActionSchedulesTable.TABLE_NAME, ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + " = ?", new String[] { scheduleId }) < 0) {
            Logger.warn("AutomationDataManager - failed to delete schedule for schedule ID " + scheduleId);
        }
    }

    /**
     * Deletes schedules given a group.
     *
     * @param group The schedule group.
     */
    void deleteSchedules(String group) {
        if (delete(ActionSchedulesTable.TABLE_NAME, ActionSchedulesTable.COLUMN_NAME_GROUP + " = ?", new String[] { group }) < 0) {
            Logger.warn("AutomationDataManager - failed to delete schedules for group " + group);
        }
    }

    /**
     * Deletes all schedules.
     */
    void deleteSchedules() {
        if (delete(ActionSchedulesTable.TABLE_NAME, null, null) < 0) {
            Logger.warn("AutomationDataManager - failed to delete schedules");
        }
    }

    /**
     * Deletes schedules given a list of IDs.
     *
     * @param schedulesToDelete The list of schedule IDs.
     */
    void bulkDeleteSchedules(@NonNull List<String> schedulesToDelete) {
        String query = "DELETE FROM " + ActionSchedulesTable.TABLE_NAME + " WHERE " + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID;
        HashMap<String, List<String>> deleteMap = new HashMap<>();
        deleteMap.put(query, schedulesToDelete);
        updateLists(deleteMap);
    }

    /**
     * Gets a schedule for a given ID.
     *
     * @param id The schedule ID.
     * @return The {@link ActionSchedule} instance.
     */
    ActionSchedule getSchedule(String id) {
        String query = GET_SCHEDULES_QUERY + " WHERE a." + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + "=?";

        Cursor c = rawQuery(query, new String[] { id });
        List<ActionSchedule> list = generateSchedules(c);

        if (list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }

    /**
     * Gets schedules for a given group.
     *
     * @param group The schedule group.
     * @return The list of {@link ActionSchedule} instances.
     */
    List<ActionSchedule> getSchedules(String group) {
        String query = GET_SCHEDULES_QUERY + " WHERE a." + ActionSchedulesTable.COLUMN_NAME_GROUP + "=?" + ORDER_SCHEDULES_STATEMENT;
        Cursor c = rawQuery(query, new String[] { String.valueOf(group) });
        return generateSchedules(c);
    }

    /**
     * Gets all schedules.
     *
     * @return The list of {@link ActionSchedule} instances.
     */
    List<ActionSchedule> getSchedules() {
        String query = GET_SCHEDULES_QUERY + ORDER_SCHEDULES_STATEMENT;
        Cursor c = rawQuery(query, null);
        return generateSchedules(c);
    }

    /**
     * Bulk inserts schedules.
     *
     * @param schedules The list of {@link ActionScheduleInfo} instances.
     * @return A list of inserted {@link ActionSchedule} instances.
     */
    List<ActionSchedule> insertSchedules(List<ActionScheduleInfo> schedules) {
        Map<String, ActionSchedule> added = new HashMap<>();
        Set<ContentValues> schedulesToAdd = new HashSet<>();
        Set<ContentValues> triggersToAdd = new HashSet<>();

        for (ActionScheduleInfo actionScheduleInfo : schedules) {
            String id = UUID.randomUUID().toString();
            schedulesToAdd.add(getScheduleInfoContentValues(actionScheduleInfo, id));

            for (Trigger trigger : actionScheduleInfo.getTriggers()) {
                ContentValues value = getTriggerContentValues(trigger, id, actionScheduleInfo.getStart());
                triggersToAdd.add(value);
            }

            ActionSchedule schedule = new ActionSchedule(id, actionScheduleInfo, 0);
            added.put(schedule.getId(), schedule);
        }

        Map<String, ContentValues[]> toAdd = new LinkedHashMap<>();
        toAdd.put(ActionSchedulesTable.TABLE_NAME, schedulesToAdd.toArray(new ContentValues[schedulesToAdd.size()]));
        toAdd.put(TriggersTable.TABLE_NAME, triggersToAdd.toArray(new ContentValues[triggersToAdd.size()]));

        List<ActionSchedule> inserted = new ArrayList<>();
        List<ContentValues> contentValuesList = bulkInsert(toAdd).get(ActionSchedulesTable.TABLE_NAME);

        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                String insertedId = contentValues.getAsString(ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID);
                if (added.containsKey(insertedId)) {
                    inserted.add(added.get(insertedId));
                }
            }
        }

        return inserted;
    }

    // for update methods

    /**
     * Gets schedules for a given set of IDs.
     *
     * @param ids The set of schedule IDs.
     * @return The list of {@link ActionSchedule} instances.
     */
    List<ActionSchedule> getSchedules(Set<String> ids) {
        final List<ActionSchedule> schedules = new ArrayList<>(ids.size());

        performSubSetOperations(ids, MAX_ARG_COUNT, new SetOperation<String>() {
            @Override
            public void perform(List<String> subset) {

                String query = GET_SCHEDULES_QUERY + " WHERE a." + ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID + " IN ( " + UAStringUtil.repeat("?", subset.size(), ", ") + ")" + ORDER_SCHEDULES_STATEMENT;
                Cursor c = rawQuery(query, subset.toArray(new String[subset.size()]));
                schedules.addAll(generateSchedules(c));
            }
        });

        return schedules;
    }

    /**
     * Gets triggers for a given type.
     *
     * @param type The trigger type.
     * @return THe list of {@link TriggerEntry} instances.
     */
    List<TriggerEntry> getTriggers(int type) {
        List<TriggerEntry> triggers = new ArrayList<>();
        Cursor cursor = query(TriggersTable.TABLE_NAME, null, TriggersTable.COLUMN_NAME_TYPE + " =? AND " + TriggersTable.COLUMN_NAME_START + " < ?", new String[] { String.valueOf(type), String.valueOf(System.currentTimeMillis()) }, null, null);

        if (cursor == null) {
            return triggers;
        }

        // create triggers
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            TriggerEntry triggerEntry = generateTrigger(cursor);
            if (triggerEntry != null) {
                triggers.add(triggerEntry);
            }

            cursor.moveToNext();
        }

        cursor.close();
        return triggers;
    }

    /**
     * Bulk applies a series of queries and lists of IDs to update.
     *
     * @param updatesMap A map of queries to ID lists.
     */
    void updateLists(Map<String, List<String>> updatesMap) {
        if (updatesMap.isEmpty()) {
            Logger.verbose("AutomationDataManager - Nothing to update. Returning.");
            return;
        }

        final SQLiteDatabase db = getWritableDatabase();
        if (db == null) {
            Logger.error("AutomationDataManager - Unable to update automation rules.");
            return;
        }

        db.beginTransaction();

        for (final Map.Entry<String, List<String>> entry : updatesMap.entrySet()) {

            if (entry.getValue().isEmpty()) {
                continue;
            }

            performSubSetOperations(entry.getValue(), MAX_ARG_COUNT, new SetOperation<String>() {
                @Override
                public void perform(List<String> subset) {

                    String inStatement = UAStringUtil.repeat("?", subset.size(), ", ");
                    SQLiteStatement statement = db.compileStatement(entry.getKey() + " IN ( " + inStatement + " )");
                    for (int i = 0; i < subset.size(); i++) {
                        statement.bindString(i + 1, subset.get(i));
                    }

                    statement.execute();
                }
            });
        }

        db.setTransactionSuccessful();
        db.endTransaction();
    }

    long getScheduleCount() {
        final SQLiteDatabase db = getReadableDatabase();
        if (db == null) {
            return -1;
        }

        return DatabaseUtils.queryNumEntries(db, ActionSchedulesTable.TABLE_NAME);
    }

    // Helpers

    /**
     * Interface for operating on a subset of IDs.
     * @param <T> The list element type.
     */
    interface SetOperation<T> {
        void perform(List<T> subset);
    }

    /**
     * Performs an operation on a list of elements.
     *
     * @param ids The list of IDs.
     * @param subSetCount The subset size to batch in an operation.
     * @param operation The operation to perform.
     * @param <T> The list element type.
     */
    private static <T> void performSubSetOperations(Collection<T> ids, int subSetCount, SetOperation<T> operation) {
        List<T> remaining = new ArrayList<>(ids);

        while (!remaining.isEmpty()) {
            if (remaining.size() > subSetCount) {
                operation.perform(remaining.subList(0, subSetCount));
                remaining = remaining.subList(subSetCount, remaining.size());
            } else {
                operation.perform(remaining);
                remaining.clear();
            }
        }
    }

    /**
     * Generates a list of schedules from a cursor.
     *
     * @param cursor The {@link Cursor} instance.
     * @return The list of {@link ActionSchedule} instances.
     */
    private List<ActionSchedule> generateSchedules(Cursor cursor) {
        List<ActionSchedule> schedules = new ArrayList<>();

        if (cursor == null) {
            return schedules;
        }

        cursor.moveToFirst();

        // The cursor rows will be that of the schedules table joined with the triggers table - each
        // row will contain a single trigger paired with its correlated schedule. We only want to build
        // the schedule info once for every grouping of triggers. As we step through the sorted cursor
        // rows, this will track the last found schedule ID. When that ID has changed in a given row,
        // the current action schedule info builder can be completed and a new one started for the
        // next set of triggers.

        // Initialize the tracked schedule ID, count, and info builder.
        String lastId = "";
        int lastCount = 0;
        ActionScheduleInfo.Builder builder = null;

        while (!cursor.isAfterLast()) {
            // Retrieved the current row's schedule ID and count.
            String id = cursor.getString(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID));
            int count = cursor.getInt(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_COUNT));

            // If a new schedule ID, build and add the previous builder and begin a new one.
            if (!lastId.equals(id)) {
                if (builder != null) {
                    schedules.add(new ActionSchedule(lastId, builder.build(), lastCount));
                }

                // Set the new ID, count, and builder to track.
                builder = ActionScheduleInfo.newBuilder();
                lastId = id;
                lastCount = count;

                // Add the relevant schedule info to the new builder.
                builder.setLimit(cursor.getInt(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_LIMIT)))
                       .setGroup(cursor.getString(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_GROUP)))
                       .setStart(cursor.getLong(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_START)))
                       .setEnd(cursor.getLong(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_END)));

                JsonMap actionsMap;
                try {
                    actionsMap = JsonValue.parseString(cursor.getString(cursor.getColumnIndex(ActionSchedulesTable.COLUMN_NAME_ACTIONS))).optMap();
                } catch (JsonException e) {
                    actionsMap = JsonValue.NULL.optMap();
                }

                for (Map.Entry<String, JsonValue> entry : actionsMap.entrySet()) {
                    builder.addAction(entry.getKey(), entry.getValue());
                }
            }

            // If the row contains triggers, parse and add them to the builder.
            if (cursor.getColumnIndex(TriggersTable.COLUMN_NAME_TYPE) != -1) {
                TriggerEntry triggerEntry = generateTrigger(cursor);
                if (triggerEntry != null && builder != null) {
                    builder.addTrigger(triggerEntry);
                }
            }

            cursor.moveToNext();
        }

        // For the final grouping of triggers, build and add the schedule.
        if (builder != null) {
            schedules.add(new ActionSchedule(lastId, builder.build(), lastCount));
        }

        cursor.close();

        return schedules;
    }

    /**
     * Generates a trigger from a cursor.
     *
     * @param cursor The {@link Cursor} instance.
     * @return The {@link TriggerEntry} instances.
     */
    private TriggerEntry generateTrigger(Cursor cursor) {
        try {
            int type = cursor.getInt(cursor.getColumnIndex(TriggersTable.COLUMN_NAME_TYPE));
            double goal = cursor.getDouble(cursor.getColumnIndex(TriggersTable.COLUMN_NAME_GOAL));
            double count = cursor.getDouble(cursor.getColumnIndex(TriggersTable.COLUMN_NAME_PROGRESS));

            JsonValue predicateJson = JsonValue.parseString(cursor.getString(cursor.getColumnIndex(TriggersTable.COLUMN_NAME_PREDICATE)));
            JsonPredicate predicate = predicateJson.optMap().isEmpty() ? null : JsonPredicate.parse(predicateJson);
            String id = cursor.getString(cursor.getColumnIndex(TriggersTable._ID));
            String scheduleId = cursor.getString(cursor.getColumnIndex(TriggersTable.COLUMN_NAME_SCHEDULE_ID));

            //noinspection WrongConstant
            return new TriggerEntry(type, goal, predicate, id, scheduleId, count);
        } catch (JsonException e) {
            Logger.error("AutomationDataManager - failed to generate trigger from cursor.");
            return null;
        }
    }

    /**
     * Gets the {@link ContentValues} for a schedule.
     *
     * @param actionScheduleInfo The {@link ActionScheduleInfo} instance.
     * @param id The schedule ID.
     * @return The {@link ContentValues} instance.
     */
    private ContentValues getScheduleInfoContentValues(ActionScheduleInfo actionScheduleInfo, String id) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_SCHEDULE_ID, id);
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_ACTIONS, actionScheduleInfo.getActions().toString());
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_LIMIT, actionScheduleInfo.getLimit());
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_GROUP, actionScheduleInfo.getGroup());
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_COUNT, 0);
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_START, actionScheduleInfo.getStart());
        contentValues.put(ActionSchedulesTable.COLUMN_NAME_END, actionScheduleInfo.getEnd());

        return contentValues;
    }

    /**
     * Gets the {@link ContentValues} for a trigger.
     *
     * @param trigger The {@link Trigger} instance.
     * @param id The schedule ID.
     * @param start The schedule start time in MS.
     * @return The {@link ContentValues} instance.
     */
    private ContentValues getTriggerContentValues(Trigger trigger, String id, long start) {
        ContentValues value = new ContentValues();
        value.put(TriggersTable.COLUMN_NAME_TYPE, trigger.getType());
        value.put(TriggersTable.COLUMN_NAME_SCHEDULE_ID, id);
        value.put(TriggersTable.COLUMN_NAME_PREDICATE, trigger.getPredicate() == null ? null : trigger.getPredicate().toJsonValue().toString());
        value.put(TriggersTable.COLUMN_NAME_GOAL, trigger.getGoal());
        value.put(TriggersTable.COLUMN_NAME_PROGRESS, 0.0);
        value.put(TriggersTable.COLUMN_NAME_START, start);

        return value;
    }
}
