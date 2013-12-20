package com.mcirony.strideminder;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Runs in the background collecting accelerometry data.
 * This service maintains a partial wake lock when running and does not self-terminate.
 */
public class AccelDataCollectorService extends Service implements SensorEventListener{
    MoeNilssenAccelProcessor mnap;
    PowerManager pm;
    PowerManager.WakeLock wl;
    SensorManager sm;
    Sensor acc;

    // Buffers holding X, Y, Z accel values and relative timestamps of SensorEvents
    double[] bufferX;
    double[] bufferY;
    double[] bufferZ;
    double[] bufferT;
    int currentBufferIndex;

    // Start time is recorded when initBuffers() is called
    long bufferStartTimeMillisec;
    long bufferStartTimeNanosec;

    long blockDurationNanosec = 10000000000L;    // Duration at which to cut off buffer and process data (in nanoseconds)
    int bufferSize = 1500;    // Initial capacity of arrays - set to accommodate 10 seconds of updates approx. 0.01 seconds apart + 50%.
    boolean bufferReady = false;    // True when buffers are ready for writing, false when they need to be initialised

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    /**
     * Gets the sensors and wakelock this service needs to run.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        sm = (SensorManager)getSystemService(SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mnap = new MoeNilssenAccelProcessor(getApplicationContext());

        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StrideMinder WakeLock");
        wl.acquire();
    }

    /**
     * Releases the sensors and wakelock.
     */
    public void onDestroy() {
        super.onDestroy();
        sm.unregisterListener(this, acc);
        wl.release();
    }

    /**
     * Requests accelerometer updates at the highest possible frequency (generally 100Hz).
     * Params are all boilerplate.
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST);
        return START_STICKY;
    }

    /**
     * Handles a possible change in sensor accuracy. Required by SensorEventListener.
     * @param sensor
     * @param accuracy
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Receive timestamped accelerometer data and store it in the buffers.
     * @param event The SensorEvent containing the accelerometer data.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        // Init buffers if required
        if(!bufferReady) {
            initBuffers(System.currentTimeMillis(), event.timestamp);
        }

        // Store values from Event in buffers
        bufferX[currentBufferIndex] = event.values[0];
        bufferY[currentBufferIndex] = event.values[1];
        bufferZ[currentBufferIndex] = event.values[2];
        bufferT[currentBufferIndex] = event.timestamp - bufferStartTimeNanosec;
        currentBufferIndex++;

        // When a recording block has been completed, send the buffers for processing.
        // Autocorrelation can take a few seconds, so process in another thread to avoid blocking this one.
        if(event.timestamp >= bufferStartTimeNanosec + blockDurationNanosec){
            Log.w("StrideMinder", "Processing Buffers");
            new Thread(new Runnable() {
                public void run() {
                    mnap.processBuffers(bufferStartTimeMillisec, currentBufferIndex, bufferX, bufferY, bufferZ, bufferT, true, false);
                }
            }).start();

            bufferReady = false;
        }
    }


    /**
     * Sets up the accelerometry buffers for the next sampling window
     * @param msec Starting time (milliseconds since epoch)
     * @param nsec Starting time (nanoseconds - locally consistent but not an absolute measurement)
     */
    private void initBuffers(long msec, long nsec) {
        bufferStartTimeMillisec = msec;
        bufferStartTimeNanosec = nsec;
        bufferX = new double[bufferSize];
        bufferY = new double[bufferSize];
        bufferZ = new double[bufferSize];
        bufferT = new double[bufferSize];
        currentBufferIndex = 0;
        bufferReady = true;
    }
}
