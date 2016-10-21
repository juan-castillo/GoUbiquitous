package com.example.android.sunshine.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ForecastListenerService extends WearableListenerService implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private final String LOG_TAG = ForecastListenerService.class.getSimpleName();
    private static final String UPDATE_FORECAST_PATH = "/update-forecast";
    private static final String KEY_MAX_TEMP = "max_temp";
    private static final String KEY_MIN_TEMP = "min_temp";
    private static final String KEY_ICON = "icon";

    private GoogleApiClient googleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        googleApiClient.connect();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        if (!googleApiClient.isConnected()) {
            ConnectionResult connectionResult = googleApiClient.blockingConnect(30, TimeUnit.SECONDS);
            if (!connectionResult.isSuccess()) {
                Log.e(LOG_TAG, "Failed to connect to GoogleApiClient.");
                return;
            }
        }

        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        for (DataEvent event : events) {
            if (event.getType() == DataEvent.TYPE_CHANGED &&
                    event.getDataItem().getUri().getPath().equals(UPDATE_FORECAST_PATH)) {


                //TODO: How to connect this data to the actual CanvasWatchFaceService????
                //DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                //Asset icon = dataMap.getAsset(KEY_ICON);

                /*
                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                Asset profileAsset = dataMapItem.getDataMap().getAsset("profileImage");
                Bitmap bitmap = loadBitmapFromAsset(profileAsset);
                */

            }
        }

    }

    /* From https://developer.android.com/training/wearables/data-layer/assets.html */
    public Bitmap loadBitmapFromAsset(Asset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Asset must be non-null");
        }

        if (!googleApiClient.isConnected()) {
            ConnectionResult connectionResult = googleApiClient.blockingConnect(30, TimeUnit.SECONDS);
            if (!connectionResult.isSuccess()) {
                Log.e(LOG_TAG, "Failed to connect to GoogleApiClient.");
                return null;
            }
        }

        // convert asset into a file descriptor and block until it's ready
        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(googleApiClient, asset)
                .await().getInputStream();
        googleApiClient.disconnect();

        if (assetInputStream == null) {
            Log.w(LOG_TAG, "Requested an unknown Asset.");
            return null;
        }
        // decode the stream into a bitmap
        return BitmapFactory.decodeStream(assetInputStream);
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(LOG_TAG, "onConnected: " + connectionHint);
        Wearable.DataApi.addListener(googleApiClient, this);
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
