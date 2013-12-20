package com.mcirony.strideminder;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.TimeSeries;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.util.Date;

public class MainMenu extends Activity implements View.OnClickListener {
    final long ONE_HOUR = 3600000;
    final long ONE_DAY = ONE_HOUR * 24;
    final long ONE_MONTH = ONE_DAY * 31;
    final long ONE_YEAR = ONE_DAY * 365;
    final int ORANGE = 0xFFFF8800;
    final int GREEN = 0xFF00CC00;

    private GraphicalView chart;

    /**
     * Registers the view as listener for the control buttons and displays the last day of hourly data in the chart area.
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_menu);

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
     * Handles click events from buttons.
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
     * Makes sure the service start/stop button matches the service's current state.
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
     * Starts the accelerometry service if it's stopped, stops it if it's running.
     */
    private void serviceToggle() {
        Intent i = new Intent(this, AccelDataCollectorService.class);
        if(serviceIsRunning()) {
            stopService(i);
        } else {
            startService(i);
        }
        syncServiceButton();
    }

    /**
     * Determines whether the accelerometry service is running.
     * @return True if the service is currently running.
     */
    private boolean serviceIsRunning() {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.service.getClassName().equals(AccelDataCollectorService.class.getName())) {
                    return true;
                }
            }
        return false;
    }

    /**
     * Requests the last 24 hours of hourly averages and displays them in the chart area.
     */
    private void showDay() {
        GaitParamsDbAdapter db = new GaitParamsDbAdapter(getApplicationContext());
        db.open();
        Cursor c = db.getHourlyGaitParams(db.getLastTimestamp() - ONE_DAY, db.getLastTimestamp());
        displayChart(c);
    }

    /**
     * Requests the last 31 days of daily averages and displays them in the chart area.
     */
    private void showMonth() {
        GaitParamsDbAdapter db = new GaitParamsDbAdapter(getApplicationContext());
        db.open();
        Cursor c = db.getDailyGaitParams(db.getLastTimestamp() - ONE_MONTH, db.getLastTimestamp());
        displayChart(c);
    }

    /**
     * Requests the last 12 months of monthly averages and displays them in the chart area.
     */
    private void showYear() {
        GaitParamsDbAdapter db = new GaitParamsDbAdapter(getApplicationContext());
        db.open();
        Cursor c = db.getMonthlyGaitParams(db.getLastTimestamp() - ONE_YEAR, db.getLastTimestamp());
        displayChart(c);
    }

    /**
     * Takes a DB cursor containing time-stamped gait parameters, formats the contents, and displays them as a chart.
     * @param c The data set to display.
     */
    private void displayChart(Cursor c)
    {
        // Sanity check. Don't try and display the contents of an empty cursor.
        if(c.getCount() == 0) {
            return;
        }

        // Get the column numbers for the data we need.
        int timestampIndex = c.getColumnIndex(GaitParamsDbAdapter.KEY_TIMESTAMP);
        int strideRegIndex = c.getColumnIndex(GaitParamsDbAdapter.KEY_STRIDE_REGULARITY);
        int strideSymIndex = c.getColumnIndex(GaitParamsDbAdapter.KEY_STRIDE_SYMMETRY);

        // Set up and populate the two data sets to display.
        TimeSeries strideRegularity = new TimeSeries("Stride Regularity");
        TimeSeries strideSymmetry = new TimeSeries("Stride Symmetry");

        while(c.moveToNext()) {
            strideRegularity.add(new Date(c.getLong(timestampIndex)), c.getDouble(strideRegIndex));
            strideSymmetry.add(new Date(c.getLong(timestampIndex)), c.getDouble(strideSymIndex));
        }

        // Bundle the two data sets into a container.
        XYMultipleSeriesDataset datasets = new XYMultipleSeriesDataset();
        datasets.addSeries(strideRegularity);
        datasets.addSeries(strideSymmetry);

        // Set up renderers for the two data sets.
        XYSeriesRenderer regularityRenderer = new XYSeriesRenderer();
        regularityRenderer.setColor(Color.CYAN);
        regularityRenderer.setLineWidth(2);
        regularityRenderer.setFillPoints(false);

        XYSeriesRenderer symmetryRenderer = new XYSeriesRenderer();
        symmetryRenderer.setColor(Color.MAGENTA);
        symmetryRenderer.setLineWidth(2);
        symmetryRenderer.setFillPoints(false);

        // Bundle the two renderers into another container & set up properties.
        XYMultipleSeriesRenderer renderer = new XYMultipleSeriesRenderer();
        renderer.addSeriesRenderer(regularityRenderer);
        renderer.addSeriesRenderer(symmetryRenderer);
        renderer.setChartTitle("Gait Parameters");
        renderer.setZoomButtonsVisible(false);
        //renderer.setXLabels(5);
        renderer.setShowGrid(true);

        // Creating an intent to plot line chart using dataset and multipleRenderer
        chart = ChartFactory.getLineChartView(getBaseContext(), datasets, renderer);

        // Place the chart in its designated container.
        FrameLayout chartContainer = (FrameLayout)findViewById(R.id.chartContainer);
        chartContainer.addView(chart);
    }
}
