package com.mcirony.strideminder;

import java.util.Calendar;
import java.util.Date;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Handles database transactions.
 * There are four parameters tracked in the database: step regularity, stride regularity, stride symmetry, and cadence.
 * Each parameter, as generated, pertains to one ten-second stretch of time.
 * The parameters are written in this form to RAW_TABLE.
 * At the end of every hour, they're averaged out and written as a single data point to HOURLY_TABLE.
 * Likewise, at the end of every day and every month, the values are averaged out and written to DAILY_TABLE and MONTHLY_TABLE.
 */
public class GaitParamsDbAdapter {

    private final Context context;
    private DatabaseHelper dbhelper;
    private SQLiteDatabase database;

    private static final int DATABASE_VERSION = 2;

    private static final String DATABASE_NAME = "data";
    public static final String RAW_TABLE = "gaitparamsraw";
    public static final String HOURLY_TABLE = "gaitparamshourly";
    public static final String DAILY_TABLE = "gaitparamsdaily";
    public static final String MONTHLY_TABLE = "gaitparamsmonthly";

    public static final String KEY_ROWID = "_id";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_STEP_REGULARITY = "step_regularity";
    public static final String KEY_STRIDE_REGULARITY = "stride_regularity";
    public static final String KEY_STRIDE_SYMMETRY = "step_symmetry";
    public static final String KEY_CADENCE = "cadence";

    private static final String TAG = "GaitParamsDbAdapter";


    /**
     * Database creation SQL statements
     */
    private static final String RAW_TABLE_CREATE =
            "create table " + RAW_TABLE + " (" + KEY_ROWID + " integer primary key autoincrement, " +
                    KEY_TIMESTAMP + " double not null, " + KEY_STEP_REGULARITY + " double not null, " +
                    KEY_STRIDE_REGULARITY + " double not null, " + KEY_STRIDE_SYMMETRY + " double not null, " +
                    KEY_CADENCE + " double not null);";
    private static final String HOURLY_TABLE_CREATE =
            "create table " + HOURLY_TABLE + " (" + KEY_ROWID + " integer primary key autoincrement, " +
                    KEY_TIMESTAMP + " double not null, " + KEY_STEP_REGULARITY + " double not null, " +
                    KEY_STRIDE_REGULARITY + " double not null, " + KEY_STRIDE_SYMMETRY + " double not null, " +
                    KEY_CADENCE + " double not null);";
    private static final String DAILY_TABLE_CREATE =
            "create table " + DAILY_TABLE + " (" + KEY_ROWID + " integer primary key autoincrement, " +
                    KEY_TIMESTAMP + " double not null, " + KEY_STEP_REGULARITY + " double not null, " +
                    KEY_STRIDE_REGULARITY + " double not null, " + KEY_STRIDE_SYMMETRY + " double not null, " +
                    KEY_CADENCE + " double not null);";
    private static final String MONTHLY_TABLE_CREATE =
            "create table " + MONTHLY_TABLE + " (" + KEY_ROWID + " integer primary key autoincrement, " +
                    KEY_TIMESTAMP + " double not null, " + KEY_STEP_REGULARITY + " double not null, " +
                    KEY_STRIDE_REGULARITY + " double not null, " + KEY_STRIDE_SYMMETRY + " double not null, " +
                    KEY_CADENCE + " double not null);";

    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(RAW_TABLE_CREATE);
            db.execSQL(HOURLY_TABLE_CREATE);
            db.execSQL(DAILY_TABLE_CREATE);
            db.execSQL(MONTHLY_TABLE_CREATE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                    + newVersion + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS " + RAW_TABLE);
            onCreate(db);
        }
    }

    public GaitParamsDbAdapter(Context c) {
        context = c;
    }

    public GaitParamsDbAdapter open() throws SQLException {
        dbhelper = new DatabaseHelper(context);
        database = dbhelper.getWritableDatabase();
        return this;
    }

    public void close() {
        database.close();
    }

    public long insertGaitParams(long timestamp, double stepRegularity, double strideRegularity, double strideSymmetry, double cadence) {
        ContentValues newRecord = new ContentValues();

        newRecord.put(KEY_TIMESTAMP, timestamp);
        newRecord.put(KEY_STEP_REGULARITY, stepRegularity);
        newRecord.put(KEY_STRIDE_REGULARITY, strideRegularity);
        newRecord.put(KEY_STRIDE_SYMMETRY, strideSymmetry);
        newRecord.put(KEY_CADENCE, cadence);
    	
    	/*
    	 *  If the last record and the new record straddle an hour, day or month boundary,
    	 *  calculate averages and put them in the appropriate table.
    	 */
        long lastTimestamp = getLastTimestamp();
        // Sanity check: Don't start trying to average things if this is the first record in the database.
        if(lastTimestamp > 0) {
            if(lastTimestamp < startOfHour(timestamp)) {
                Cursor c = getRawGaitParams(startOfHour(lastTimestamp), startOfHour(timestamp));
                double hourStepReg = 0, hourStrideReg = 0, hourStrideSym = 0, hourCadence = 0;
                while(c.moveToNext()) {
                    hourStepReg += c.getDouble(c.getColumnIndex(KEY_STEP_REGULARITY));
                    hourStrideReg += c.getDouble(c.getColumnIndex(KEY_STRIDE_REGULARITY));
                    hourStrideSym += c.getDouble(c.getColumnIndex(KEY_STRIDE_SYMMETRY));
                    hourCadence += c.getDouble(c.getColumnIndex(KEY_CADENCE));
                }
                hourStepReg = hourStepReg / c.getCount();
                hourStrideReg = hourStrideReg / c.getCount();
                hourStrideSym = hourStrideSym / c.getCount();
                hourCadence = hourCadence / c.getCount();

                ContentValues hourNewRecord = new ContentValues();

                hourNewRecord.put(KEY_TIMESTAMP, startOfHour(lastTimestamp));
                hourNewRecord.put(KEY_STEP_REGULARITY, hourStepReg);
                hourNewRecord.put(KEY_STRIDE_REGULARITY, hourStrideReg);
                hourNewRecord.put(KEY_STRIDE_SYMMETRY, hourStrideSym);
                hourNewRecord.put(KEY_CADENCE, hourCadence);

                if(lastTimestamp < startOfDay(timestamp)) {
                    c = getHourlyGaitParams(startOfDay(lastTimestamp), startOfDay(timestamp));
                    double dayStepReg = 0, dayStrideReg = 0, dayStrideSym = 0, dayCadence = 0;
                    while(c.moveToNext()) {
                        dayStepReg += c.getDouble(c.getColumnIndex(KEY_STEP_REGULARITY));
                        dayStrideReg += c.getDouble(c.getColumnIndex(KEY_STRIDE_REGULARITY));
                        dayStrideSym += c.getDouble(c.getColumnIndex(KEY_STRIDE_SYMMETRY));
                        dayCadence += c.getDouble(c.getColumnIndex(KEY_CADENCE));
                    }
                    dayStepReg = dayStepReg / c.getCount();
                    dayStrideReg = dayStrideReg / c.getCount();
                    dayStrideSym = dayStrideSym / c.getCount();
                    dayCadence = dayCadence / c.getCount();

                    ContentValues dayNewRecord = new ContentValues();

                    dayNewRecord.put(KEY_TIMESTAMP, startOfDay(lastTimestamp));
                    dayNewRecord.put(KEY_STEP_REGULARITY, dayStepReg);
                    dayNewRecord.put(KEY_STRIDE_REGULARITY, dayStrideReg);
                    dayNewRecord.put(KEY_STRIDE_SYMMETRY, dayStrideSym);
                    dayNewRecord.put(KEY_CADENCE, dayCadence);

                    if(lastTimestamp < startOfMonth(timestamp)) {
                        c = getDailyGaitParams(startOfMonth(lastTimestamp), startOfMonth(timestamp));
                        double monthStepReg = 0, monthStrideReg = 0, monthStrideSym = 0, monthCadence = 0;
                        while(c.moveToNext()) {
                            monthStepReg += c.getDouble(c.getColumnIndex(KEY_STEP_REGULARITY));
                            monthStrideReg += c.getDouble(c.getColumnIndex(KEY_STRIDE_REGULARITY));
                            monthStrideSym += c.getDouble(c.getColumnIndex(KEY_STRIDE_SYMMETRY));
                            monthCadence += c.getDouble(c.getColumnIndex(KEY_CADENCE));
                        }
                        monthStepReg = monthStepReg / c.getCount();
                        monthStrideReg = monthStrideReg / c.getCount();
                        monthStrideSym = monthStrideSym / c.getCount();
                        monthCadence = monthCadence / c.getCount();

                        ContentValues monthNewRecord = new ContentValues();

                        monthNewRecord.put(KEY_TIMESTAMP, startOfMonth(lastTimestamp));
                        monthNewRecord.put(KEY_STEP_REGULARITY, monthStepReg);
                        monthNewRecord.put(KEY_STRIDE_REGULARITY, monthStrideReg);
                        monthNewRecord.put(KEY_STRIDE_SYMMETRY, monthStrideSym);
                        monthNewRecord.put(KEY_CADENCE, monthCadence);

                        database.insertOrThrow(MONTHLY_TABLE, null, monthNewRecord);
                    }
                    database.insertOrThrow(DAILY_TABLE, null, dayNewRecord);
                }
                database.insertOrThrow(HOURLY_TABLE, null, hourNewRecord);
            }
        }
        return database.insertOrThrow(RAW_TABLE, null, newRecord);
    }

    /**
     * Return a Cursor on RAW_TABLE for the specified time period.
     * @param start Start of time period (msec since epoch)
     * @param end End of time period (msec since epoch)
     * @return A Cursor giving every record in RAW_TABLE between the specified times.
     */
    public Cursor getRawGaitParams(long start, long end) {
        String[] cols = {KEY_TIMESTAMP, KEY_STEP_REGULARITY, KEY_STRIDE_REGULARITY, KEY_STRIDE_SYMMETRY};
        String filter = KEY_TIMESTAMP + " BETWEEN " + start + " AND " + end;
        return database.query(RAW_TABLE, cols, filter, null, null, null, null);
    }

    /**
     * Return a Cursor on HOURLY_TABLE for the specified time period.
     * @param start Start of time period (msec since epoch)
     * @param end End of time period (msec since epoch)
     * @return A Cursor giving every record in HOURLY_TABLE between the specified times.
     */
    public Cursor getHourlyGaitParams(long start, long end) {
        String[] cols = {KEY_TIMESTAMP, KEY_STEP_REGULARITY, KEY_STRIDE_REGULARITY, KEY_STRIDE_SYMMETRY};
        String filter = KEY_TIMESTAMP + " BETWEEN " + start + " AND " + end;
        return database.query(HOURLY_TABLE, cols, filter, null, null, null, null);
    }

    /**
     * Return a Cursor on DAILY_TABLE for the specified time period.
     * @param start Start of time period (msec since epoch)
     * @param end End of time period (msec since epoch)
     * @return A Cursor giving every record in DAILY_TABLE between the specified times.
     */
    public Cursor getDailyGaitParams(long start, long end) {
        String[] cols = {KEY_TIMESTAMP, KEY_STEP_REGULARITY, KEY_STRIDE_REGULARITY, KEY_STRIDE_SYMMETRY};
        String filter = KEY_TIMESTAMP + " BETWEEN " + start + " AND " + end;
        return database.query(DAILY_TABLE, cols, filter, null, null, null, null);
    }

    /**
     * Return a Cursor on MONTHLY_TABLE for the specified time period.
     * @param start Start of time period (msec since epoch)
     * @param end End of time period (msec since epoch)
     * @return A Cursor giving every record in MONTHLY_TABLE between the specified times.
     */
    public Cursor getMonthlyGaitParams(long start, long end) {
        String[] cols = {KEY_TIMESTAMP, KEY_STEP_REGULARITY, KEY_STRIDE_REGULARITY, KEY_STRIDE_SYMMETRY};
        String filter = KEY_TIMESTAMP + " BETWEEN " + start + " AND " + end;
        return database.query(MONTHLY_TABLE, cols, filter, null, null, null, null);
    }

    /**
     * Returns the time of the last entry made in this database.
     * @return The time of the last entry (msec since epoch)
     */
    public long getLastTimestamp() {
        String[] cols = {KEY_TIMESTAMP};
        String filter = KEY_TIMESTAMP + " = (SELECT MAX(" + KEY_TIMESTAMP + ") FROM " + RAW_TABLE + ")";
        Cursor c = database.query(RAW_TABLE, cols, filter, null, null, null, null);

        if(c.moveToFirst()) {
            return c.getLong(0);
        } else {
            return 0;
        }
    }

    /**
     * Returns the start of the hour the given time falls into.
     * @param time Time in msec since epoch
     * @return Time of the start of the hour
     */
    private long startOfHour(long time) {
        Date d = new Date(time);
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.MINUTE, 0);
        return c.getTimeInMillis();
    }

    /**
     * Returns the start of the day the given time falls into.
     * @param time Time in msec since epoch
     * @return Time of the start of the day
     */
    private long startOfDay(long time) {
        Date d = new Date(time);
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.HOUR_OF_DAY, 0);
        return c.getTimeInMillis();
    }

    /**
     * Returns the start of the month the given time falls into.
     * @param time Time in msec since epoch
     * @return Time of the start of the month
     */
    private long startOfMonth(long time) {
        Date d = new Date(time);
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.DAY_OF_MONTH, 0);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        return c.getTimeInMillis();
    }
}
