package com.mcirony.strideminder;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.TimeSeries;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainMenu extends Activity implements View.OnClickListener {
    final int ONE_HOUR = 3600000;
    final int ONE_DAY = ONE_HOUR * 24;
    final int ONE_MONTH = ONE_DAY * 31;
    final int ONE_YEAR = ONE_DAY * 365;
    final int ORANGE = 0xFFFF8800;
    final int GREEN = 0xFF00CC00;

    private GraphicalView mChart;

    /**
     * When the view is created, display the last day of hourly data in the chart area by default.
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_menu);

        // Connect the interface
        findViewById(R.id.serviceToggleButton).setOnClickListener(this);
        findViewById(R.id.dayButton).setOnClickListener(this);
        findViewById(R.id.monthButton).setOnClickListener(this);
        findViewById(R.id.yearButton).setOnClickListener(this);

        // Ensure the service start/stop button is in the correct state
        syncServiceButton();

        // Show the day chart by default
        showDay();
    }

    /**
     * Handle click events from buttons.
     * @param view The view generating the callback
     */
    @Override
    public void onClick(View view) {
        switch(view.getId()) {
            case R.id.serviceToggleButton: serviceToggle();
                break;
            case R.id.dayButton: showDay();
                break;
            case R.id.monthButton: showMonth();
                break;
            case R.id.yearButton: showYear();
                break;
        }
    }

    /**
     * Make sure the service start/stop button matches the service's current state
     */
    private void syncServiceButton() {
        if(serviceIsRunning()) {
            findViewById(R.id.serviceBar).setBackgroundColor(GREEN);
            ((TextView)findViewById(R.id.serviceStatusText)).setText(R.string.service_running);
            ((ToggleButton)findViewById(R.id.serviceToggleButton)).setChecked(true);
        } else {
            findViewById(R.id.serviceBar).setBackgroundColor(ORANGE);
            ((TextView)findViewById(R.id.serviceStatusText)).setText(R.string.service_stopped);
            ((ToggleButton)findViewById(R.id.serviceToggleButton)).setChecked(false);
        }
    }

    /**
     * Starts the accelerometry service if it's stopped, and stops it if it's running.
     */
    private void serviceToggle() {
        Intent i = new Intent(this, AccelDataCollectorService.class);
        if(serviceIsRunning()) {
            stopService(i);
            (Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT)).show();
        } else {
            startService(i);
            (Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT)).show();
        }
        syncServiceButton();
    }

    /**
     * Determines whether the accelerometry service is running.
     * @return True if the service is currently running.
     */
    private boolean serviceIsRunning() {
        boolean serviceIsRunning = false;
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.service.getClassName().equals(AccelDataCollectorService.class.getName())) {
                    return true;
                }
            }
        return false;
    }

    private void showDay() {
        GaitParamsDbAdapter db = new GaitParamsDbAdapter(getApplicationContext());
        db.open();
        Cursor c = db.getHourlyGaitParams(db.getLastTimestamp() - ONE_DAY, db.getLastTimestamp());
        displayChart(c);
    }

    private void showMonth() {
        GaitParamsDbAdapter db = new GaitParamsDbAdapter(getApplicationContext());
        db.open();
        Cursor c = db.getHourlyGaitParams(db.getLastTimestamp() - ONE_MONTH, db.getLastTimestamp());
        displayChart(c);
    }

    private void showYear() {
        GaitParamsDbAdapter db = new GaitParamsDbAdapter(getApplicationContext());
        db.open();
        Cursor c = db.getHourlyGaitParams(db.getLastTimestamp() - ONE_YEAR, db.getLastTimestamp());
        displayChart(c);
    }

    private void displayChart(Cursor c)
    {
        if(c.getCount() == 0) {
            Log.w("StrideMinder", "FOUND YOUR PROBLEM THERE (empty cursor)");
            return;
        }

        String[] titles = new String[] { "Stride regularity", "Stride symmetry" };

        int numValues = c.getCount();

        List<Date[]> xValues = new ArrayList<Date[]>();
        List<double[]> yValues = new ArrayList<double[]>();
        Date[] timestampsArray = new Date[numValues];
        double[] strideRegArray = new double[numValues];
        double[] strideSymArray = new double[numValues];
        int timestampIndex = c.getColumnIndex(GaitParamsDbAdapter.KEY_TIMESTAMP);
        int strideRegIndex = c.getColumnIndex(GaitParamsDbAdapter.KEY_STRIDE_REGULARITY);
        int strideSymIndex = c.getColumnIndex(GaitParamsDbAdapter.KEY_STRIDE_SYMMETRY);

        // Set up and populate the two data sets to display
        TimeSeries strideRegularity = new TimeSeries("Stride Regularity");
        TimeSeries strideSymmetry = new TimeSeries("Stride Symmetry");

        for(int i = 0; c.moveToNext(); i++) {
            strideRegularity.add(new Date(c.getLong(timestampIndex)), c.getDouble(strideRegIndex));
            strideSymmetry.add(new Date(c.getLong(timestampIndex)), c.getDouble(strideSymIndex));
        }

        // Add the two data sets to a container
        XYMultipleSeriesDataset datasets = new XYMultipleSeriesDataset();
        datasets.addSeries(strideRegularity);
        datasets.addSeries(strideSymmetry);

        // Set up renderers for the two data sets
        XYSeriesRenderer regularityRenderer = new XYSeriesRenderer();
        regularityRenderer.setColor(Color.CYAN);
        regularityRenderer.setLineWidth(2);
        regularityRenderer.setFillPoints(false);

        XYSeriesRenderer symmetryRenderer = new XYSeriesRenderer();
        symmetryRenderer.setColor(Color.MAGENTA);
        symmetryRenderer.setLineWidth(2);
        symmetryRenderer.setFillPoints(false);

        // Add the two renderers to a container & set up properties
        XYMultipleSeriesRenderer renderer = new XYMultipleSeriesRenderer();
        renderer.addSeriesRenderer(regularityRenderer);
        renderer.addSeriesRenderer(symmetryRenderer);
        renderer.setChartTitle("Gait Parameters");
        renderer.setZoomButtonsVisible(false);
        //renderer.setXLabels(5);
        renderer.setShowGrid(true);

        FrameLayout chart_container = (FrameLayout)findViewById(R.id.chartContainer);

        // Creating an intent to plot line chart using dataset and multipleRenderer
        mChart = (GraphicalView)ChartFactory.getLineChartView(getBaseContext(), datasets, renderer);

        // Add the graphical view mChart object into the Linear layout .
        chart_container.addView(mChart);
    }
}
