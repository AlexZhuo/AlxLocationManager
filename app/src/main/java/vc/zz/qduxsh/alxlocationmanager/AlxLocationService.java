package vc.zz.qduxsh.alxlocationmanager;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;


import java.util.ArrayList;
import java.util.List;

public class AlxLocationService extends IntentService {
    private ArrayList<String> PROVIDER_ARRAY;

    public static boolean isDestory;
    private String locationProvider;
    private LocationManager locationManager;
    private LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i("AlexLocation", "GPS -> onProviderEnabled");
            getBestLocationProvider();
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.i("AlexLocation", "GPS -> onProviderDisabled");
            getBestLocationProvider();
        }
    };
    private LocationListener networkLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i("AlexLocation", "Network -> onProviderEnabled");
            getBestLocationProvider();
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.i("AlexLocation", "Network -> onProviderDisabled");
            getBestLocationProvider();
        }
    };
    private LocationListener passiveLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i("AlexLocation", "Passive -> onProviderEnabled");
            getBestLocationProvider();
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.i("AlexLocation", "Passive -> onProviderDisabled");
            getBestLocationProvider();
        }
    };

    public AlxLocationService() {
        super("GPS");
        PROVIDER_ARRAY = new ArrayList<>();
        PROVIDER_ARRAY.add(LocationManager.GPS_PROVIDER);
        PROVIDER_ARRAY.add(LocationManager.NETWORK_PROVIDER);
        PROVIDER_ARRAY.add(LocationManager.PASSIVE_PROVIDER);
        isDestory = false;
    }

    private synchronized void getBestLocationProvider() {
        if (locationManager == null) {
            locationProvider = null;
            return;
        }

        List<String> providers = locationManager.getAllProviders();
        if (providers == null || providers.size() <= 0) {
            locationProvider = null;
            return;
        }

        String bestProvider = null;
        Location bestLocation = null;
        for (String provider : providers) {
            Log.i("AlexLocation", "getBestLocationProvider  ->  provider => " + provider);
            if ((provider != null) && (PROVIDER_ARRAY.contains(provider))) {
                Location location = locationManager.getLastKnownLocation(provider);
                Log.i("AlexLocation", "getBestLocationProvider  ->  location => " + location);
                if (location == null) {
                    continue;
                }

                Log.i("AlexLocation", "getBestLocationProvider  ->  bestLocation => " + bestLocation);
                if (bestLocation == null) {
                    bestLocation = location;
                    bestProvider = provider;
                    continue;
                }

                Log.i("AlexLocation", "getBestLocationProvider  ->  location.getAccuracy() => " + location.getAccuracy() + "  bestLocation.getAccuracy() => " + bestLocation.getAccuracy());
                if (Float.valueOf(location.getAccuracy()).compareTo(bestLocation.getAccuracy()) >= 0) {
                    bestLocation = location;
                    bestProvider = provider;
                }
            }
        }

        locationProvider = bestProvider;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.i("AlexLocation", " onHandleIntent --> start");
        locationProvider = null;
        locationManager = null;

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }

        List<String> allProviders = locationManager.getAllProviders();
        Log.i("AlexLocation", "AllProviders  -> " + allProviders);
        if (allProviders != null) {
            for (String provider : allProviders) {
                Log.i("AlexLocation", "AllProviders  ->  provider => " + provider);
                if ((provider != null) && (PROVIDER_ARRAY.contains(provider))) {
                    if (LocationManager.GPS_PROVIDER.equals(provider)) {
                        Log.i("AlexLocation", "AllProviders  ->  provider => add gpsLocationListener");
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, AlxLocationManager.FAST_UPDATE_INTERVAL, 0, gpsLocationListener);
                    } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
                        Log.i("AlexLocation", "AllProviders  ->  provider => add networkLocationListener");
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, AlxLocationManager.FAST_UPDATE_INTERVAL, 0, networkLocationListener);
                    } else if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
                        Log.i("AlexLocation", "AllProviders  ->  provider => add passiveLocationListener");
                        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, AlxLocationManager.FAST_UPDATE_INTERVAL, 0, passiveLocationListener);
                    }
                }
            }
        }

        while (!isDestory) {
            getBestLocationProvider();
            Log.i("AlexLocation", "locationProvider => " + locationProvider);

            updateLocation();
            Log.i("AlexLocation","是否要停下"+isDestory);
            if(isDestory)return;//防止GPS指示灯长时间闪烁，造成耗电的问题
            if ((locationProvider != null) && (PROVIDER_ARRAY.contains(locationProvider))) {//如果成功获取到了位置
                try {
                    if (!isWrongPosition(MyLocation.getInstance().latitude,MyLocation.getInstance().longitude)) {//当前获取到的经纬度
                        isDestory = true;
                    } else {
                        Thread.sleep(AlxLocationManager.FAST_UPDATE_INTERVAL);//如果获取的是不正确的经纬度
                    }
                } catch (InterruptedException ex) {
                    Log.i("AlexLocation", " onHandleIntent ", ex);
                }
            } else {//如果没有成功获取到位置
                try {
                    Thread.sleep(AlxLocationManager.FAST_UPDATE_INTERVAL);
                } catch (Exception ex) {
                    Log.i("AlexLocation", " onHandleIntent ", ex);
                }
            }
        }
    }

    private void updateLocation() {
        Log.i("AlexLocation", " ----> updateLocation <---- locationProvider => " + locationProvider);
        if ((locationProvider != null) && (!locationProvider.equals("")) && (PROVIDER_ARRAY.contains(locationProvider))) {
            try {
                Location currentLocation = locationManager.getLastKnownLocation(locationProvider);
                Log.i("AlexLocation","通过旧版service取到GPS，经度"+currentLocation.getLongitude()+" 纬度"+currentLocation.getLatitude()+"  来源"+locationProvider);
                if(AlxLocationManager.isDebugging) Toast.makeText(getApplicationContext(),"通过Android原生方法！！！"+"经度"+currentLocation.getLongitude()+" 纬度"+currentLocation.getLatitude(),Toast.LENGTH_LONG).show();
                Log.i("AlexLocation", "locationProvider -> " + locationProvider + "  currentLocation -> " + currentLocation);
                if (currentLocation != null) {
                    final double newLatitude = currentLocation.getLatitude();
                    final double newLongitude = currentLocation.getLongitude();
                    final float accuracy = currentLocation.getAccuracy();
                    Log.i("AlexLocation", "locationProvider -> " + newLatitude + " : " + newLongitude + "精确度"+accuracy);
                    if(!isWrongPosition(newLatitude,newLongitude))AlxLocationManager.recordLocation(this,newLatitude,newLongitude,accuracy);
                    if(AlxLocationManager.manager!=null)AlxLocationManager.manager.currentStatus = AlxLocationManager.STATUS.NOT_TRACK;
                    //精确的GPS记录完毕可以停掉这个子线程了
                    if(!isWrongPosition(newLatitude,newLongitude))isDestory=true;
                }
            } catch (Exception ex) {
                Log.i("AlexLocation", " updateLocation ", ex);
            }
        }
    }

    /**
     * 判断经纬度是否是0,0
     * @param latitude
     * @param longitude
     * @return
     */
    public static boolean isWrongPosition(double latitude,double longitude){
        if(Math.abs(latitude)<0.01 && Math.abs(longitude)<0.1)return true;
        return false;
    }

    @Override
    public void onDestroy() {
        Log.i("AlexLocation", " --> onDestroy");
        super.onDestroy();
        isDestory = true;

        if ((locationManager != null) && (gpsLocationListener != null)) {
            locationManager.removeUpdates(gpsLocationListener);
        }

        if ((locationManager != null) && (networkLocationListener != null)) {
            locationManager.removeUpdates(networkLocationListener);
        }

        if ((locationManager != null) && (passiveLocationListener != null)) {
            locationManager.removeUpdates(passiveLocationListener);
        }
    }
}
