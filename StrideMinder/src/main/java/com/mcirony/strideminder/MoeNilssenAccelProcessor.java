package com.mcirony.strideminder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.content.Context;
import android.os.Environment;
import android.widget.Toast;

public class MoeNilssenAccelProcessor {
    public static final double WALKING_RMS_THRESHOLD = 0.25; // This value was determined experimentally & is explained where used.
    Context ctx;
    GaitParamsDbAdapter db;

    public MoeNilssenAccelProcessor(Context c) {
        db = new GaitParamsDbAdapter(c);
        ctx = c;
    }

    /**
     * Takes a buffer of three-dimensional accelerometry data.
     * Normalises it to a consistent frequency.
     * Determines the direction of gravity by averaging measurements.
     * Rotates the frame of reference so that Z+ is up.
     * Performs autocorrelation on the corrected Z-axis time series.
     * Takes the root-mean-square of the autocorrelation series to determine whether the data represents walking.
     * Finds the first three peaks in the autocorrelation series and computers gait parameters from their locations and magnitudes.
     * Writes the gait parameters to the database.
     *
     * @param absoluteStartTimeMillisec The system time when recording started for this accelerometry block.
     * @param tOriginal Array of timestamps in nanoseconds. First timestamp should be 0, but if not this will be accounted for.
     * @param xOriginal Array of X acceleration values
     * @param yOriginal Array of Y acceleration values
     * @param zOriginal Array of Z acceleration values
     * @param writeToDatabase If true, write gait parameters to the database (only if walking is detected).
     * @param writeOutRawValues If true, write out accelerometry and autocorrelation to a CSV file for debugging.
     */
    public void processBuffers(long absoluteStartTimeMillisec, int bufferSize, double[] xOriginal, double[] yOriginal, double[] zOriginal, double[] tOriginal, boolean writeToDatabase, boolean writeOutRawValues) {

		// If the first timestamp is not 0, fix the timestamps.
        if(tOriginal[0] != 0) {
            for(int i = 0; i < bufferSize; i++) {
                tOriginal[i] -= tOriginal[0];
            }
        }

        double eventsDurationNano = tOriginal[bufferSize - 1]; // Duration in nanoseconds covered by this buffer

        double[] tNormalised = new double[bufferSize];
        double[] xNormalised = new double[bufferSize];
        double[] yNormalised = new double[bufferSize];
        double[] zNormalised = new double[bufferSize];

        int indexOfRecordAfterI = 1;
		
		/*
		 *  Because the sensor reporting frequency can and does change,
		 *  we need to interpolate a set of values which we can be
		 *  certain are at regular intervals from the timestamped
		 *  sensor events.
		 *  The first and last values don't need interpolation - by
		 *  definition they correspond perfectly in the source and 
		 *  output data sets. Avoid edge cases by handling them now.
		 */
        tNormalised[0] = tOriginal[0];
        xNormalised[0] = xOriginal[0];
        yNormalised[0] = yOriginal[0];
        zNormalised[0] = zOriginal[0];
        tNormalised[bufferSize-1] = tOriginal[bufferSize-1];
        xNormalised[bufferSize-1] = xOriginal[bufferSize-1];
        yNormalised[bufferSize-1] = yOriginal[bufferSize-1];
        zNormalised[bufferSize-1] = zOriginal[bufferSize-1];

        double proportion1, proportion2;

        for(int i = 1; i < bufferSize - 1; i++) {
            // time when normalised record i should occur = duration of block / relative (0 to 1) position of i
            tNormalised[i] = (((double)i * eventsDurationNano) / (double)bufferSize);

            // Iterate through events until the counter has just advanced past interpolatedTime
            while(tNormalised[i] > tOriginal[indexOfRecordAfterI]) {
                indexOfRecordAfterI++;
            }

            // Calculate the proportions of preceding and following accelerometer records to use in the interpolation
            // (time elapsed between preceding record and interpolated record) divided by (time between preceding and following record)
            proportion1 = (tNormalised[i] - tOriginal[indexOfRecordAfterI]) / (tOriginal[indexOfRecordAfterI] - tOriginal[indexOfRecordAfterI-1]);
            proportion2 = 1 - proportion1;

            xNormalised[i] = (xOriginal[indexOfRecordAfterI-1] * proportion1) + (xOriginal[indexOfRecordAfterI] * proportion2);
            yNormalised[i] = (yOriginal[indexOfRecordAfterI-1] * proportion1) + (yOriginal[indexOfRecordAfterI] * proportion2);
            zNormalised[i] = (zOriginal[indexOfRecordAfterI-1] * proportion1) + (zOriginal[indexOfRecordAfterI] * proportion2);
        }


        // Find the average of each axis
        double avgX = 0, avgY = 0, avgZ = 0;

        for(int i = 0; i < bufferSize ; i++){
            avgX += xNormalised[i];
            avgY += yNormalised[i];
            avgZ += zNormalised[i];
        }

        avgX = avgX / bufferSize;
        avgY = avgY / bufferSize;
        avgZ = avgZ / bufferSize;

        // Taking the average as a single vector, find its magnitude
        float avgMag = (float) Math.sqrt((avgX*avgX)+(avgY*avgY)+(avgZ*avgZ));
        // And normalise it to have magnitude 1.0
        avgX = avgX / avgMag;
        avgY = avgY / avgMag;
        avgZ = avgZ / avgMag;

        // Axis to rotate about = cross product of the average acceleration with (0, 0, 1)
        // axisX = (avgY*1)-(avgZ*0);
        double axisX = avgY;
        // axisY = (avgZ*0)-(avgX*1);
        double axisY = -avgX;
        // axisZ = (avgX*0)-(avgY*0);
        // Z component of axis will always be 0, so ignore it

        // cosine of rotation angle = angle between initial vector and target vector
        // = dot product of two vectors / product of their magnitudes
        // double cosTheta = (avgX * 0 + avgY * 0 + avgZ * 1) / (avgMag * 1.0);
        double cosTheta = avgZ / avgMag;
        double sinTheta = Math.sqrt(1-(cosTheta*cosTheta));

        // Construct the rotation matrix now! Don't care about gimbal lock because we're only doing one simple rotation.
        // Since Z component of axis is always zero as shown above, remove all terms that multiply by it.
        // Only interested in vertical autocorrelation here, so discard the X and Y axes too.
        // The disregarded values might be useful down the line, so they've been kept as comments.

        // double topLeft = cosTheta + (axisX*axisX*(1-cosTheta));
        // double topCentre = (axisX*axisY*(1-cosTheta)) - (axisZ*sinTheta);
        // double topRight = (axisX*axisZ*(1-cosTheta)) + (axisY*sinTheta);
        // double middleLeft = (axisY*axisX*(1-cosTheta)) + (axisZ*sinTheta);
        // double middleCentre = cosTheta + (axisY*axisY*(1-cosTheta));
        // double middleRight = (axisY*axisZ*(1-cosTheta)) - (axisX*sinTheta);

        // Redundant coefficients removed: double bottomLeft = (axisZ*axisX*(1-cosTheta)) - (axisY*sinTheta);
        double bottomLeft = -(axisY*sinTheta);
        // Redundant coefficients removed: double bottomCentre = (axisZ*axisY*(1-cosTheta)) + (axisX*sinTheta);
        double bottomCentre = (axisX*sinTheta);
        // Redundant coefficients removed: double bottomRight = cosTheta + (axisZ*axisZ*(1-cosTheta));
        double bottomRight = cosTheta;

        //double[] newX = new double[bufferLength];
        //double[] newY = new double[bufferLength];
        double[] newZ = new double[bufferSize];

        // Apply the rotation to the whole buffer
        // Ignore X and Y axes - we only care about vertical acceleration right now
        for(int i = 0; i < bufferSize; i++) {
            //newX[i] = xNormalised[i] * topLeft + yNormalised[i] * topCentre + zNormalised[i] * topRight;
            //newY[i] = xNormalised[i] * middleLeft + yNormalised[i] * middleCentre + zNormalised[i] * middleRight;
            newZ[i] = xNormalised[i] * bottomLeft + yNormalised[i] * bottomCentre + zNormalised[i] * bottomRight;
        }

        // Autocorrelate the data
        // newZ is not used above
        double[] autocorrelated = autocorrelate(newZ, newZ.length);

	
		/*
		 * RMS of autocorrelation is used to distinguish between walking and non-walking.
		 * The threshold used here was determined experimentally to give the best ratio
		 * of correct detections to false alarms.
		 * 
		 * Normalisation means the later values in the autocorrelation are
		 * increasingly unreliable, so only consider the first half of the data.
		 */
        double rms = 0;
        for(int i = 0; i < (autocorrelated.length / 2); i++) {
            rms += autocorrelated[i]*autocorrelated[i];
        }
        rms = Math.sqrt(rms / autocorrelated.length);
		
		/*
		 * If the RMS is below the threshold value, the user is most likely
		 * not walking, so don't attempt to analyse this block of data.
		 */
        if(rms <= WALKING_RMS_THRESHOLD) {
            return;
        }
		
		/*
		 * Seek out the points where the autocorrelation crosses X=0. We need 5:
		 * One descending from Peak 0, two (ascending and descending) around Peak 1,
		 * and two more around Peak 2.
		 * 
		 * Normalisation means the later values in the autocorrelation are
		 * increasingly unreliable, so only consider the first half of the data.
		 */
        int[] crossingLocations = new int[5];
        //int stepIndex = 0;
        int strideIndex = 0;
        double stepRegularity = 0, strideRegularity = 0;

        int crossingsFound = 0;

        for(int i = 0; i < (autocorrelated.length / 2); i++)
        {
            if(autocorrelated[i] < 0 && autocorrelated[i+1] >= 0 || autocorrelated[i] >= 0 && autocorrelated[i+1] < 0 ) {
                crossingLocations[crossingsFound] = i;
                crossingsFound++;
                if(crossingsFound >= 5) {
                    break;
                }

            }
        }
		
		/*
		 * Sanity check: If there are less than 5 crossings (ie peaks 0, 1 and 2 cannot all be found)
		 * this accelerometry is EXTREMELY unlikely to represent walking, and cannot be analysed anyway.
		 * Discard it.
		 */
        if(crossingsFound < 5) {
            return;
        }

        // Find the first non-trivial peak on the autocorrelograph
        for(int i = crossingLocations[1]; i <= crossingLocations[2]; i++) {
            if(autocorrelated[i] > stepRegularity) {
                stepRegularity = autocorrelated[i];
                //stepIndex = i;
            }
        }

        // Find the first non-trivial peak on the autocorrelograph
        for(int i = crossingLocations[3]; i <= crossingLocations[4]; i++) {
            if(autocorrelated[i] > strideRegularity) {
                strideRegularity = autocorrelated[i];
                strideIndex = i;
            }
        }


        // Time per stride = duration of buffer * (stride duration as a fraction of buffer duration)
        double strideTime = (eventsDurationNano / 1000000000L * (strideIndex / bufferSize));
        // Cadence = Strides per minute = 60 seconds / (duration of stride in seconds)
        double cadence = 60 / strideTime;
        // If full strides correlate well but successive steps don't, there is an asymmetry (e.g. a limp)
        double stepSymmetry = stepRegularity / strideRegularity;

        if(writeToDatabase) {
            db = db.open();
            db.insertGaitParams(absoluteStartTimeMillisec, stepRegularity, strideRegularity, stepSymmetry, cadence);
            db.close();
        }

        if(writeOutRawValues) {
            writeOutAccelAutocorrelation(absoluteStartTimeMillisec, zNormalised, autocorrelated);
        }
    }

    /**
     * Compute autocorrelation of an array.
     * @param input The array to compute autocorrelation for.
     * @param maxDisp The maximum displacement to use in calculating autocorrelation.
     * @return An array of length maxDisp (or input.length) of autocorrelations
     */
    private static double[] autocorrelate(double[] input, int maxDisp) {

        // Sanity check: Maximum displacement can't be greater than the input array size.
        if(maxDisp > input.length){
            maxDisp = input.length;
        }
        double[] toReturn = new double[maxDisp];

        // Compute the mean of the input array
        double mean = 0;
        for(int i = 0; i < input.length; i++) {
            mean += input[i];
        }
        mean = mean / (double) input.length;

        // Compute the variance of the input array
        double variance = 0;
        double error;
        for(int i = 0; i < input.length; i++) {
            error = input[i] - mean;
            variance += error*error;
        }
        variance = variance / (double) input.length;

        // Compute autocorrelation.
        // For every displacement i between 0 and maxDisp...
        double coefficient;
        for(int i = 0; i < maxDisp; i++) {
            coefficient = 0;
            // Compute correlation between element j and its displaced counterpart j-i.
            for(int j = i; j < input.length; j++) {
                coefficient += ((input[j] - mean) * (input[j-i] - mean)) / variance;
            }
            // Divide by the number of elements which overlapped at this time lag.
            toReturn[i] = coefficient / (input.length - i);
        }

        return(toReturn);
    }

    private void writeOutAccelAutocorrelation(long startTime, double[] z, double[] autocorr) {

        boolean externalStorageAvailable = false;
        boolean externalStorageWriteable = false;
        String state = Environment.getExternalStorageState();

        // Make sure we can read and write external storage before continuing.
        if (state.equals(Environment.MEDIA_MOUNTED)) {
            externalStorageAvailable = externalStorageWriteable = true;
        } else if (state.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
            externalStorageAvailable = true;
            externalStorageWriteable = false;
        } else {
            externalStorageAvailable = externalStorageWriteable = false;
        }

        if(externalStorageAvailable && externalStorageWriteable) {
            String filename = "accel_logs/accel_" + filenameDTS(startTime) + ".csv";

            StringBuffer sb = new StringBuffer("Z-accel,autocorrelation\n");
            for(int i = 0; i < z.length; i++) {
                sb.append(z[i] + "," + autocorr[i] + "\n");
            }

            File output = new File(Environment.getExternalStorageDirectory(), filename);

            try {
                output.createNewFile();
                FileOutputStream fos = new FileOutputStream(output, true);
                fos.write(sb.toString().getBytes());
                fos.flush();
                fos.close();
                (Toast.makeText(ctx, "Wrote out file.", Toast.LENGTH_SHORT)).show();
            } catch(IOException e) {
                (Toast.makeText(ctx, "Couldn't write file: " + e.getMessage(), Toast.LENGTH_LONG)).show();
            }
        }
    }

    /**
     * Turns a time into a string for timestamping output files.
     * @param time Time to convert (msec since epoch)
     * @return A string of the format 2011-07-19_19H13M26S
     */
    private String filenameDTS(long time) {
        Date date = new Date(time);
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH'H'mm'M'ss'S'");
        return df.format(date);
    }

}
