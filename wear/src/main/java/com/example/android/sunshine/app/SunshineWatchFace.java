/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private final String LOG_TAG = Engine.class.getSimpleName();

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        Calendar mCalendar;

        Paint mBackgroundPaint;
        Paint timePaint;
        Paint datePaint;
        Paint maxTempPaint;
        Paint minTempPaint;

        private final int iconSize = 64;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private static final String UPDATE_FORECAST_PATH = "/update-forecast";
        private static final String KEY_MAX_TEMP = "max_temp";
        private static final String KEY_MIN_TEMP = "min_temp";
        private static final String KEY_ICON = "icon";

        private GoogleApiClient googleApiClient;
        private InputStream assetInputStream;

        private String maxTemp;
        private String minTemp;
        private Bitmap icon;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            googleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            // Set paint colors
            timePaint = createTextPaint(resources.getColor(R.color.white));
            datePaint = createTextPaint(resources.getColor(R.color.grey));
            maxTempPaint = createTextPaint(resources.getColor(R.color.white));
            minTempPaint = createTextPaint(resources.getColor(R.color.grey));

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceivers();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceivers();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceivers() {
            Wearable.DataApi.addListener(googleApiClient, this);
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceivers() {
            Wearable.DataApi.removeListener(googleApiClient, this);
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources.
            Resources resources = SunshineWatchFace.this.getResources();
            float timeSize = resources.getDimension(R.dimen.time_size);
            float dateSize = resources.getDimension(R.dimen.date_size);
            float tempSize = resources.getDimension(R.dimen.temp_size);

            // Set paint sizes
            timePaint.setTextSize(timeSize);
            datePaint.setTextSize(dateSize);
            maxTempPaint.setTextSize(tempSize);
            minTempPaint.setTextSize(tempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    timePaint.setAntiAlias(!inAmbientMode);
                    datePaint.setAntiAlias(!inAmbientMode);
                    maxTempPaint.setAntiAlias(!inAmbientMode);
                    minTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Get current time in HH:MM
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            String time = String.format(
                    "%d:%02d",
                    mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE)
            );

            // Get current date
            Date date = mCalendar.getTime();
            String weekDay = new SimpleDateFormat("EE").format(date);
            String month = new SimpleDateFormat("MMM").format(date);
            int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
            int year = mCalendar.get(Calendar.YEAR);

            StringBuilder sb = new StringBuilder()
                    .append(weekDay)
                    .append(", ")
                    .append(month)
                    .append(" ")
                    .append(dayOfMonth)
                    .append(" ")
                    .append(year);
            String dateString = sb.toString();

            float xOffset = canvas.getWidth() / 2f;

            // Draw time
            float xTimeOffset = xOffset - timePaint.measureText(time, 0, time.length()) / 2f;
            canvas.drawText(time, xTimeOffset , mYOffset, timePaint);

            if (!isInAmbientMode()) {
                // Draw date
                float xDateOffset = xOffset - datePaint.measureText(dateString, 0, dateString.length()) / 2;
                float yDateOffset = mYOffset + datePaint.getTextSize() + 10;
                canvas.drawText(dateString, xDateOffset, yDateOffset, datePaint);

                // Draw divider
                float yDividerOffset = yDateOffset + datePaint.getTextSize();
                canvas.drawLine(xOffset - 28, yDividerOffset, xOffset + 28, yDividerOffset, datePaint);

                // Draw weather info
                float yIconOffset = yDividerOffset + 12;
                float xIconOffset = xOffset;

                if (icon != null) {
                    // Draw icon
                    Bitmap newIcon = Bitmap.createScaledBitmap(icon, iconSize, iconSize, true);
                    xIconOffset = xOffset - newIcon.getWidth() - 40;
                    canvas.drawBitmap(newIcon, xIconOffset, yIconOffset, new Paint());
                }

                float yTempOffset = yDividerOffset + datePaint.getTextSize() + 32;
                float xMaxTempOffset;
                if (minTemp != null && maxTemp != null) {
                    // Draw temp

                    if (xIconOffset == xOffset) {
                        xMaxTempOffset = xIconOffset - (maxTempPaint.measureText(maxTemp) / 2f) - (minTempPaint.measureText(minTemp) / 2f);
                    } else {
                        xMaxTempOffset = xIconOffset + iconSize + 8;
                    }
                    canvas.drawText(maxTemp, xMaxTempOffset, yTempOffset, maxTempPaint);


                    float xMinTempOffset = xMaxTempOffset + maxTempPaint.measureText(maxTemp) + 8;
                    canvas.drawText(minTemp, xMinTempOffset, yTempOffset, minTempPaint);
                }

            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /* From https://developer.android.com/training/wearables/data-layer/assets.html */
        public void loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            if (!googleApiClient.isConnected()) {
                ConnectionResult connectionResult = googleApiClient.blockingConnect(5, TimeUnit.SECONDS);
                if (!connectionResult.isSuccess()) {
                    Log.e(LOG_TAG, "Failed to connect to GoogleApiClient.");
                }
            }

            Log.d(LOG_TAG, "Loading Bitmap from Asset");
            // convert asset into a file descriptor
            Wearable.DataApi.getFdForAsset(googleApiClient, asset)
                    .setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.GetFdForAssetResult getFdForAssetResult) {
                            assetInputStream = getFdForAssetResult.getInputStream();

                            // decode the stream into a bitmap
                            icon = BitmapFactory.decodeStream(assetInputStream);
                            Log.d(LOG_TAG, "Bitmap from Asset loaded");
                            invalidate();
                        }
                    });
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            if (!googleApiClient.isConnected()) {
                ConnectionResult connectionResult = googleApiClient.blockingConnect(5, TimeUnit.SECONDS);
                if (!connectionResult.isSuccess()) {
                    Log.e(LOG_TAG, "Failed to connect to GoogleApiClient.");
                    return;
                }
            }

            System.out.println("Data Changed!!!");
            final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
            for (DataEvent event : events) {
                if (event.getType() == DataEvent.TYPE_CHANGED &&
                        event.getDataItem().getUri().getPath().equals(UPDATE_FORECAST_PATH)) {

                    processDataItem(event.getDataItem());
                }
            }
            dataEvents.release();
        }

        @Override
        public void onConnected(@Nullable Bundle connectionHint) {
            Log.d(LOG_TAG, "onConnected");
            Wearable.DataApi.addListener(googleApiClient, this);

            new AsyncTask(){
                @Override
                protected Object doInBackground(Object[] objects) {
                    DataItemBuffer dataItems = Wearable.DataApi.getDataItems(googleApiClient).await();

                    final List<DataItem> dataItemList = FreezableUtils.freezeIterable(dataItems);
                    Log.d(LOG_TAG, "dataItemList.size() " + dataItemList.size());
                    for (DataItem dataItem : dataItemList) {
                        String path = "/" + dataItem.getUri().getLastPathSegment();
                        Log.d(LOG_TAG, "dataItem.getUri() " + path);

                        if (path.equals(UPDATE_FORECAST_PATH)) {
                            Log.d(LOG_TAG, "Calling processDataItem()");
                            processDataItem(dataItem);

                        } else {
                            Log.d(LOG_TAG, "Wrong path");
                        }
                    }
                    dataItems.release();

                    return null;
                }
            }.execute();
        }

        private void processDataItem(DataItem dataItem) {
            Log.d(LOG_TAG, "In processDataItem()");

            DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
            System.out.println("dataMap: " + dataMap);

            String highTemp = dataMap.getInt(KEY_MAX_TEMP) + "\u00b0";
            System.out.println("MAX_TEMP: " + highTemp);
            maxTemp = highTemp;

            String lowTemp = dataMap.getInt(KEY_MIN_TEMP) + "\u00b0";
            System.out.println("MIN_TEMP: " + lowTemp);
            minTemp = lowTemp;

            Asset iconAsset = dataMap.getAsset(KEY_ICON);
            System.out.println("asset: " + iconAsset);
            loadBitmapFromAsset(iconAsset);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult);
            Wearable.DataApi.removeListener(googleApiClient, this);
            googleApiClient.connect();
        }
    }
}
