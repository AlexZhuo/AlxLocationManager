package vc.zz.qduxsh.alxlocationmanager;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.widget.Toast;

import com.alibaba.fastjson.JSON;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import cn.finalteam.okhttpfinal.BaseHttpRequestCallback;
import cn.finalteam.okhttpfinal.HttpRequest;
import cn.finalteam.okhttpfinal.RequestParams;


/**
 * Created by AlexLocation on 2016/6/6.
 */
public class AlxLocationManager implements GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener,LocationListener {
    public static final String GOOGLE_API_KEY = "AIzaSyDLToSD04V2ylySZaswsUWZX9s-WCGDx5g";
    public static final boolean autoChina = true;//中国境内的坐标是否自动转换成火星坐标系

    //下面这三个是没拿到第一次经纬度的时候耗电抓取经纬度的策略

    private static final int MAX_deviation = 60;//最小精确度限制
    public static final int FAST_UPDATE_INTERVAL = 10000; // 10 sec 平均更新时间,同时也是没有获取成功的刷新间隔，是耗电量的重要参数
    private static final int FATEST_INTERVAL = 5000; // 5 sec 最短更新时间
    public static final int FAST_DISPLACEMENT = 10; // 10 meters 为最小侦听距离

    //下面这个是省电抓取经纬度的策略
    private static final int SLOW_UPDATE_INTERVAL = 60000; // 60 sec 平均更新时间
    private static final int SLOW_INTERVAL = 30000; // 30 sec 最短更新时间
    private static final int SLOW_DISPLACEMENT = 500; // 500 meters 为最小侦听距离

    private Application context;//防止内存泄漏，不使用activity的引用
    private GoogleApiClient mGoogleApiClient;
    public static AlxLocationManager manager;//单例模式

    public STATUS currentStatus = STATUS.NOT_CONNECT;
    public float accuracy = 99999;//没有获得精度的时候是-1
    private Timer locationTimer;
    public String dataJson;//发送给谷歌API的WiFi和基站数据


    public enum STATUS{
        NOT_CONNECT,//没有连接相关硬件成功
        TRYING_FIRST,//第一次获取地理位置，此时gps指示图标闪烁，耗电量大
        LOW_POWER,//开启app拿到精确度的GPS之后，开启省电模式
        NOT_TRACK//当前没有开启跟踪模式
    }

    public final static boolean isDebugging = true;//是否显示toast开关

    public static AlxLocationManager getInstance(){
        return manager;
    }
    /**
     * 注册gps监听服务
     * @param context
     */
    public static void onCreateGPS(final Application context){
        if(manager!=null && manager.mGoogleApiClient!=null)return;
        Log.i("AlexLocation","准备开启gps");
        manager = new AlxLocationManager();
        manager.context = context;
        manager.mGoogleApiClient  = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(manager)
                .addOnConnectionFailedListener(manager)
                .addApi(LocationServices.API)
                .build();
        manager.mGoogleApiClient.connect();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if(manager!=null && manager.currentStatus!= STATUS.NOT_CONNECT)return;//如果连接GPS硬件到9s后还没成功
                Log.i("AlexLocation","该手机没有安装谷歌框架服务,使用Android原生获取吧");
                Toast.makeText(manager.context,"警告：你没有安装谷歌服务框架，请root后安装",Toast.LENGTH_LONG).show();
                context.startService(new Intent(manager.context, AlxLocationService.class));
                if(manager.locationTimer==null)manager.locationTimer = new Timer();
                try {
                    manager.locationTimer.scheduleAtFixedRate(new LocationTask(),0,FAST_UPDATE_INTERVAL);//10s获取一次
                }catch (Exception e){
                    Log.i("AlexLocation","开启locationtask出现异常",e);
                }
                new AlxAsynTask<Void,Void,String>(){//在子线程中获取附近的基站和wifi信息

                    @Override
                    protected String doInBackground(Void... params) {
                        GeoLocationAPI geoLocationAPI = null;
                        try {
                            geoLocationAPI = getCellInfo(manager.context);//得到基站信息,通过基站进行定位
                        }catch (Exception e){
                            Log.i("AlexLocation","获取附近基站信息出现异常",e);
                        }
                        if(geoLocationAPI ==null){
                            Log.i("AlexLocation","获取基站信息失败");
                            return "{}";
                        }
                        getWifiInfo(manager.context, geoLocationAPI);
                        String json = geoLocationAPI.toJson();//这里使用gson.toJson()会被混淆，推荐使用手动拼json
                        Log.i("AlexLocation","准备发给google的json是"+json);
                        return json;
                    }

                    @Override
                    protected void onPostExecute(String json) {
                        super.onPostExecute(json);
                        //开启子线程请求网络
                        if(manager!=null && context!=null)manager.sendJsonByPost(json,"https://www.googleapis.com/geolocation/v1/geolocate?key="+GOOGLE_API_KEY);
                        else return;
                        Toast.makeText(context,"没有安装谷歌框架！！！发给google api 的json是；："+json,Toast.LENGTH_LONG).show();
                    }
                }.executeDependSDK();
            }
        },9000);
    }

    public static class LocationTask extends TimerTask {

        @Override
        public void run() {
            Log.i("AlexLocation","location task 执行"+"manager是"+manager+"    destroy是"+AlxLocationService.isDestory);
            if(manager==null || !AlxLocationService.isDestory)return;//如果之前被destroy了，就重开一个
            manager.context.startService(new Intent(manager.context, AlxLocationService.class));//使用安卓原生API获取地理位置
        }
    }

    /**
     * 进入某些页面，重新刷GPS
     * @param context
     */
    public static void restartGPS(Application context){
        stopGPS();//先停止当前的GPS
        onCreateGPS(context);//重启GPS
    }



    /**
     * 停止gps服务，用来省电
     */
    public static void stopGPS(){
        if(manager==null)return;
        pauseGPS();
        manager.mGoogleApiClient = null;
        manager = null;
    }

    /**
     * 当app被放到后台时，暂停GPS
     */
    public static void pauseGPS(){
        Log.i("Alex","准备暂停GPS");
        if(manager==null || manager.mGoogleApiClient==null || manager.currentStatus== STATUS.NOT_CONNECT || manager.currentStatus == STATUS.NOT_TRACK)return;
        try {
            LocationServices.FusedLocationApi.removeLocationUpdates(manager.mGoogleApiClient, manager);
            manager.currentStatus = STATUS.NOT_CONNECT;
            if (manager.mGoogleApiClient.isConnected() || manager.mGoogleApiClient.isConnecting()) manager.mGoogleApiClient.disconnect();
            manager.mGoogleApiClient = null;
        }catch (Exception e) {
            Log.i("AlexLocation","暂停GPS出现异常",e);
        }
    }
    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.i("AlexLocation","connect gps成功");
        if(currentStatus != STATUS.NOT_CONNECT)return;//有些手机会多次连接成功
        currentStatus = STATUS.TRYING_FIRST;
        if(!getCurrentLocation()) {//得到当前gps并记录
            //如果没有成功拿到当前经纬度，那么就通过实时位置监听去不断的拿，直到拿到为止
            //如果没有拿到经纬度，就一直监听
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, createFastLocationRequest(), this);//创建位置监听
            new AlxAsynTask<Void,Void,String>(){//在子线程中获取附近的基站和wifi信息

                @Override
                protected String doInBackground(Void... params) {
                    GeoLocationAPI geoLocationAPI = null;
                    try {
                        geoLocationAPI = getCellInfo(context);//得到基站信息,通过基站进行定位
                    }catch (Exception e){
                        Log.i("AlexLocation","获取附近基站信息出现异常",e);
                    }
                    if(geoLocationAPI ==null){
                        Log.i("AlexLocation","获取基站信息失败");
                        return "{}";
                    }
                    getWifiInfo(context, geoLocationAPI);
                    String json = geoLocationAPI.toJson();//这里使用gson.toJson()会被混淆，推荐使用手动拼json
                    Log.i("AlexLocation","准备发给goggle的json是"+json);
                    return json;
                }

                @Override
                protected void onPostExecute(String json) {
                    super.onPostExecute(json);
                    //发送json数据到谷歌，等待谷歌返回结果
                    sendJsonByPost(json,"https://www.googleapis.com/geolocation/v1/geolocate?key="+GOOGLE_API_KEY);
                    Toast.makeText(context,"恭喜你安装了谷歌框架，发给google api 的json是；："+json,Toast.LENGTH_LONG).show();
                }
            }.executeDependSDK();

        }else {//获取了最后一次硬件记录的经纬度，不进行追踪
            currentStatus = STATUS.NOT_TRACK;
        }
    }

    /**
     * 拿到最近一次的硬件经纬度记录,只用精确度足够高的时候才会采用这种定位
     * @return
     */
    public boolean getCurrentLocation(){
        Location mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        Log.i("AlexLocation","得到last Location的gps是=="+mLastLocation);
        if (mLastLocation == null) return false;//在少数情况下这里有可能是null
        double latitude = mLastLocation.getLatitude();//纬度
        double longitude = mLastLocation.getLongitude();//经度
        double altitude = mLastLocation.getAltitude();//海拔
        float last_accuracy = mLastLocation.getAccuracy();//精度
        Log.i("AlexLocation","last Location的精度是"+last_accuracy);
        String provider = mLastLocation.getProvider();//传感器
        float bearing = mLastLocation.getBearing();
        float speed = mLastLocation.getSpeed();//速度
        if(isDebugging)Toast.makeText(context,"获取到last location经纬度  "+"纬度"+latitude+"  经度"+longitude+ "精确度"+last_accuracy,Toast.LENGTH_LONG).show();
        Log.i("AlexLocation","获取last location成功，纬度=="+latitude+"  经度"+longitude+"  海拔"+altitude+"   传感器"+provider+"   速度"+speed+ "精确度"+last_accuracy);
        if(last_accuracy < MAX_deviation){
            recordLocation(context,latitude,longitude,accuracy);
            this.accuracy = last_accuracy;
        }else {
            Log.i("AlexLocation","精确度太低，放弃last Location");
        }
        return last_accuracy < MAX_deviation;
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    /**
     * 注册完位置跟踪策略后，每隔一段时间会调用的这个方法，同时会拿到当前的位置
     * @param location
     */
    @Override
    public void onLocationChanged(Location location) {
        if(currentStatus == STATUS.LOW_POWER)Log.i("Alex","现在是低电力的定位策略");
        Log.i("AlexLocation","位置改变了"+location);
        if(location==null)return;
        if(isDebugging) Toast.makeText(context,"获取到了最新的GPS    "+location.toString()+" 精度是"+location.getAccuracy(),Toast.LENGTH_LONG).show();
        if(location.getAccuracy() < MAX_deviation ){//精度如果太小就放弃
            recordLocation(context,location.getLatitude(),location.getLongitude(),location.getAccuracy());
            this.accuracy = location.getAccuracy();
        }else {
            Log.i("Alex","精确度太低，准备放弃最新的位置");
        }
        if(location.getAccuracy()>50 || currentStatus!= STATUS.TRYING_FIRST)return;//如果现在是高电量模式，那么就停止当前监听，采用低电量监听
        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient,this);//成功获取到之后就降低频率来省电
        Log.i("AlexLocation","准备开启省电策略");
        currentStatus = STATUS.LOW_POWER;
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, createLowPowerLocationRequest(), this);//创建位置监听
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    /**
     * 当手机移动后通过回调获得移动后的经纬度，在这个函数里配置相应的刷新频率
     * 建立一个耗电的，尽快的拿到当前经纬度的策略
     */
    private static LocationRequest createFastLocationRequest() {
        LocationRequest mLocationRequest = LocationRequest.create();
        mLocationRequest.setInterval(FAST_UPDATE_INTERVAL);
        mLocationRequest.setFastestInterval(FATEST_INTERVAL);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);//耗电模式
        mLocationRequest.setSmallestDisplacement(FAST_DISPLACEMENT);
        return mLocationRequest;
    }

    /**
     * 建立一个省电的跟踪经纬度的策略
     * 注意此方法要慎用，使用了低电量模式以后，精确度会大幅下降
     * @return
     */
    private static LocationRequest createLowPowerLocationRequest() {
        LocationRequest mLocationRequest = LocationRequest.create();
        mLocationRequest.setInterval(SLOW_UPDATE_INTERVAL);
        mLocationRequest.setFastestInterval(SLOW_INTERVAL);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);//省电模式，PRIORITY_LOW_POWER要慎用，会导致定位精确度大幅下降，能偏到好几十公里以外去
        mLocationRequest.setSmallestDisplacement(SLOW_DISPLACEMENT);
        return mLocationRequest;
    }

    /**
     * 将获取到的经纬度记录在本地
     * @param context
     */
    public static void recordLocation(Context context, double latitude, double longitude, float accuracy){
        SharedPreferences sharedPreferences = context.getSharedPreferences("lastLocationRecord", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        //中国境内换算成火星坐标系
        MyLocation myLocation = gps84_To_Gcj02(latitude,longitude);
        if(myLocation != null && autoChina){
            latitude = myLocation.latitude;
            longitude = myLocation.longitude;
        }
        editor.putString("latitude", String.valueOf(latitude));
        editor.putString("longitude", String.valueOf(longitude));
        editor.putFloat("accuracy",accuracy);
        editor.apply();
        Log.i("AlexLocation","最终经过火星转换的结果是latitude="+latitude+"   longitude="+longitude+"   accuracy="+accuracy);
        MyLocation myLocationStatic = MyLocation.getInstance();
        //当以前没有记录，或者30s内连续获取的数据（Google的数据，手机自带GPS返回的数据）根据accuracy择优录取
        if(myLocationStatic.updateTime == 0 || System.currentTimeMillis() - myLocationStatic.updateTime > SLOW_INTERVAL || accuracy <= myLocationStatic.accuracy) {
            myLocationStatic.latitude = latitude;
            myLocationStatic.longitude = longitude;
            myLocationStatic.accuracy = accuracy;
            myLocationStatic.updateTime = System.currentTimeMillis();
            if(isDebugging)Toast.makeText(context,"最终记录的经过火星转换的结果是latitude="+latitude+"   longitude="+longitude+"   accuracy="+accuracy,Toast.LENGTH_LONG).show();
        }else {
            Log.i("AlexLocation","本次位置获取不精确，放弃");
        }
    }

    /**
     * 从sharedPreference中获取上次开启app时候的地理位置,前面的是纬度，后面的是经度
     * @return
     */
    public static double[] getOldLocation(Context context){
        SharedPreferences sharedPreferences = context.getSharedPreferences("lastLocationRecord", Context.MODE_PRIVATE);
        String latitudeStr = sharedPreferences.getString("latitude","");
        String longitudeStr = sharedPreferences.getString("longitude","");
        float accuracy = sharedPreferences.getFloat("accuracy",9999);
        Log.i("AlexLocation","SP里的精确度"+accuracy);
        if(latitudeStr.length()==0 || longitudeStr.length()==0)return null;
        double[] latlng = {-1,-1};
        try {
            latlng[0] = new Double(latitudeStr);
            latlng[1] = new Double(longitudeStr);
        }catch (Exception e){
            Log.i("AlexLocation","解析经纬度出现异常",e);
        }
        return latlng;
    }

    /**
     * 得到附近的基站信息，准备传给谷歌
     */
    public static GeoLocationAPI getCellInfo(Context context){
        //通过TelephonyManager 获取lac:mcc:mnc:cell-id
        GeoLocationAPI cellInfo = new GeoLocationAPI();
        TelephonyManager mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if(mTelephonyManager==null)return cellInfo;
        // 返回值MCC + MNC
        /*# MCC，Mobile Country Code，移动国家代码（中国的为460）；
        * # MNC，Mobile Network Code，移动网络号码（中国移动为0，中国联通为1，中国电信为2）；
        * # LAC，Location Area Code，位置区域码;
        * # CID，Cell Identity，基站编号；
        * # BSSS，Base station signal strength，基站信号强度。
        */
        String operator = mTelephonyManager.getNetworkOperator();
        Log.i("AlexLocation","获取的基站信息是"+operator);
        if(operator==null || operator.length()<5){
            Log.i("AlexLocation","获取基站信息有问题,可能是手机没插sim卡");
            return cellInfo;
        }
        int mcc = Integer.parseInt(operator.substring(0, 3));
        int mnc = Integer.parseInt(operator.substring(3));
        int lac;
        int cellId;
        // 中国移动和中国联通获取LAC、CID的方式
        CellLocation cellLocation = mTelephonyManager.getCellLocation();
        if(cellLocation==null){
            Log.i("AlexLocation","手机没插sim卡吧");
            return cellInfo;
        }
        if(mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
            Log.i("AlexLocation","当前是gsm基站");
            GsmCellLocation location = (GsmCellLocation)cellLocation;
            lac = location.getLac();
            cellId = location.getCid();
            //这些东西非常重要，是根据基站获得定位的重要依据
            Log.i("AlexLocation", " MCC移动国家代码 = " + mcc + "\t MNC移动网络号码 = " + mnc + "\t LAC位置区域码 = " + lac + "\t CID基站编号 = " + cellId);
        }else if(mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            // 中国电信获取LAC、CID的方式
            Log.i("AlexLocation","现在是cdma基站");
            CdmaCellLocation location1 = (CdmaCellLocation) mTelephonyManager.getCellLocation();
            lac = location1.getNetworkId();
            cellId = location1.getBaseStationId();
            cellId /= 16;
        }else {
            Log.i("AlexLocation","现在不知道是什么基站");
            return cellInfo;
        }
        cellInfo.radioType = determine2g3g4g(context);//这里填要慎重，要是填的不对就会报404 notFound
        cellInfo.homeMobileCountryCode = mcc;
        cellInfo.homeMobileNetworkCode = mnc;
        cellInfo.carrier = getCarrier(operator);
        cellInfo.considerIp = considerIP(context);//这里要判断是否采用了vpn，在wifi的时候可以用ip辅助定位，但是如果是用的2g3g4g信号那就只能用基站，ip会不准
        ArrayList<GoogleCellTower> towers = new ArrayList<>(1);
        GoogleCellTower bigTower = new GoogleCellTower();//这个塔是当前连接的塔，只有一个，但是对定位有决定性的作用
        bigTower.cellId = cellId;
        bigTower.mobileCountryCode = mcc;
        bigTower.mobileNetworkCode = mnc;
        bigTower.locationAreaCode = lac;
        bigTower.signalStrength = 0;
        towers.add(bigTower);
        cellInfo.cellTowers = towers;
        // 获取邻区基站信息
        if(Build.VERSION.SDK_INT<17) {//低版的android系统使用getNeighboringCellInfo方法
            List<NeighboringCellInfo> infos = mTelephonyManager.getNeighboringCellInfo();
            if(infos==null){
                Log.i("AlexLocation","手机型号不支持基站定位1");
                return cellInfo;
            }
            if(infos.size()==0)return cellInfo;//附近没有基站
            towers = new ArrayList<>(infos.size());
            StringBuffer sb = new StringBuffer("附近基站总数 : " + infos.size() + "\n");
            for (NeighboringCellInfo info1 : infos) { // 根据邻区总数进行循环
                GoogleCellTower tower = new GoogleCellTower();
                sb.append(" LAC : " + info1.getLac()); // 取出当前邻区的LAC
                tower.locationAreaCode = info1.getLac();
                tower.mobileCountryCode = mcc;
                tower.mobileNetworkCode = mnc;
                tower.signalStrength = info1.getRssi();
                sb.append(" CID : " + info1.getCid()); // 取出当前邻区的CID
                tower.cellId = info1.getCid();
                sb.append(" BSSS : " + (-113 + 2 * info1.getRssi()) + "\n"); // 获取邻区基站信号强度
                towers.add(tower);
            }
            Log.i("AlexLocation","基站信息是"+sb);
        }else {//高版android系统使用getAllCellInfo方法，并且对基站的类型加以区分
            List<CellInfo> infos = mTelephonyManager.getAllCellInfo();
            if(infos!=null) {
                if(infos.size()==0)return cellInfo;
                towers = new ArrayList<>(infos.size());
                for (CellInfo i : infos) { // 根据邻区总数进行循环
                    Log.i("AlexLocation", "附近基站信息是" + i.toString());//这里如果出现很多cid
                    GoogleCellTower tower = new GoogleCellTower();
                    if(i instanceof CellInfoGsm){//这里的塔分为好几种类型
                        Log.i("AlexLocation","现在是gsm基站");
                        CellIdentityGsm cellIdentityGsm = ((CellInfoGsm)i).getCellIdentity();//从这个类里面可以取出好多有用的东西
                        if(cellIdentityGsm==null)continue;
                        tower.locationAreaCode = cellIdentityGsm.getLac();
                        tower.mobileCountryCode = cellIdentityGsm.getMcc();
                        tower.mobileNetworkCode = cellIdentityGsm.getMnc();
                        tower.signalStrength = 0;
                        tower.cellId = cellIdentityGsm.getCid();
                    }else if(i instanceof CellInfoCdma){
                        Log.i("AlexLocation","现在是cdma基站");
                        CellIdentityCdma cellIdentityCdma = ((CellInfoCdma)i).getCellIdentity();
                        if(cellIdentityCdma==null)continue;
                        tower.locationAreaCode = lac;
                        tower.mobileCountryCode = mcc;
                        tower.mobileNetworkCode = cellIdentityCdma.getSystemId();//cdma用sid,是系统识别码，每个地级市只有一个sid，是唯一的。
                        tower.signalStrength = 0;
                        cellIdentityCdma.getNetworkId();//NID是网络识别码，由各本地网管理，也就是由地级分公司分配。每个地级市可能有1到3个nid。
                        tower.cellId = cellIdentityCdma.getBasestationId();//cdma用bid,表示的是网络中的某一个小区，可以理解为基站。
                    }else if(i instanceof CellInfoLte) {
                        Log.i("AlexLocation", "现在是lte基站");
                        CellIdentityLte cellIdentityLte = ((CellInfoLte) i).getCellIdentity();
                        if(cellIdentityLte==null)continue;
                        tower.locationAreaCode = lac;
                        tower.mobileCountryCode = cellIdentityLte.getMcc();
                        tower.mobileNetworkCode = cellIdentityLte.getMnc();
                        tower.cellId = cellIdentityLte.getCi();
                        tower.signalStrength = 0;
                    }else if(i instanceof CellInfoWcdma && Build.VERSION.SDK_INT>=18){
                        Log.i("AlexLocation","现在是wcdma基站");
                        CellIdentityWcdma cellIdentityWcdma = ((CellInfoWcdma)i).getCellIdentity();
                        if(cellIdentityWcdma==null)continue;
                        tower.locationAreaCode = cellIdentityWcdma.getLac();
                        tower.mobileCountryCode = cellIdentityWcdma.getMcc();
                        tower.mobileNetworkCode = cellIdentityWcdma.getMnc();
                        tower.cellId = cellIdentityWcdma.getCid();
                        tower.signalStrength = 0;
                    }else {
                        Log.i("AlexLocation","不知道现在是啥基站");
                    }
                    towers.add(tower);
                }
            }else {//有些手机拿不到的话，就用废弃的方法，有时候即使手机支持，getNeighboringCellInfo的返回结果也常常是null
                Log.i("AlexLocation","通过高版本SDK无法拿到基站信息，准备用低版本的方法");
                List<NeighboringCellInfo> infos2 = mTelephonyManager.getNeighboringCellInfo();
                if(infos2==null || infos2.size()==0){
                    Log.i("AlexLocation","该手机确实不支持基站定位，已经无能为力了");
                    return cellInfo;
                }
                towers = new ArrayList<>(infos2.size());
                StringBuffer sb = new StringBuffer("附近基站总数 : " + infos2.size() + "\n");
                for (NeighboringCellInfo i : infos2) { // 根据邻区总数进行循环
                    GoogleCellTower tower = new GoogleCellTower();
                    sb.append(" LAC : " + i.getLac()); // 取出当前邻区的LAC
                    tower.age = 0;
                    tower.locationAreaCode = i.getLac();
                    tower.mobileCountryCode = mcc;
                    tower.mobileNetworkCode = mnc;
                    sb.append(" CID : " + i.getCid()); // 取出当前邻区的CID
                    tower.cellId = i.getCid();
                    sb.append(" BSSS : " + (-113 + 2 * i.getRssi()) + "\n"); // 获取邻区基站信号强度
                    towers.add(tower);
                }
                Log.i("AlexLocation","基站信息是"+sb);
            }
        }
        cellInfo.cellTowers = towers;
        return cellInfo;
    }

    /**
     * 看看现在用wifi流量还是手机流量，如果是wifi返回true
     * @param context
     * @return
     */
    public static boolean isWifiEnvironment(Context context){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if(networkInfo==null){
            Log.i("AlexLocation","现在没有联网");
            return false;
        }
        int netType = networkInfo.getType();
        switch (netType){
            case ConnectivityManager.TYPE_WIFI:
                Log.i("AlexLocation","现在是wifi网络,可以用ip定位");
                return true;
            case ConnectivityManager.TYPE_VPN://这个基本没用
                Log.i("AlexLocation","现在是VPN网络");
                break;
            case ConnectivityManager.TYPE_MOBILE:
                Log.i("AlexLocation","现在是移动网络,不能用ip定位");
                int subType = networkInfo.getSubtype();
                Log.i("AlexLocation","移动网络子类是"+subType+"  "+networkInfo.getSubtypeName());//能判断是2g/3g/4g网络
                break;
            default:
                Log.i("AlexLocation","不知道现在是什么网络");
                break;
        }
        return false;
    }

    /**
     * 看看现在是wifi联网还是用的流量，如果是wifi返回true，因为wifi的时候可以用ip定位,但如果这时候是vpn，那就不能用ip定位
     * @param context
     */
    public static boolean considerIP(Context context){
        boolean considerIP = true;//默认是考虑
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivityManager==null)return true;
        if(!isWifiEnvironment(context))return false;//如果现在不是wifi网络，就不能用ip定位
        if(Build.VERSION.SDK_INT<21) {//旧版本安卓获取网络状态
            NetworkInfo[] networkInfos = connectivityManager.getAllNetworkInfo();
            if(networkInfos==null)return true;
            for(NetworkInfo i:networkInfos){
                if(i==null)continue;
                Log.i("AlexLocation","现在的网络是"+i.getTypeName()+i.getType()+"   "+i.getSubtypeName());//WIFI,VPN,MOBILE+LTE
                if(i.getType()== ConnectivityManager.TYPE_VPN){
                    Log.i("AlexLocation","现在用的是VPN网络，不能用ip定位");
                    considerIP = false;
                    break;
                }
            }
        }else {//新版
            Network[] networks = connectivityManager.getAllNetworks();
            if(networks==null)return true;
            for(Network n:networks){
                if(n==null)continue;
                NetworkInfo networkInfo = connectivityManager.getNetworkInfo(n);
                if(networkInfo==null)continue;
                Log.i("AlexLocation","现在的网络是"+networkInfo.getTypeName()+networkInfo.getType()+"   "+networkInfo.getSubtypeName());//WIFI,VPN,MOBILE+LTE
                if(networkInfo.getType()== ConnectivityManager.TYPE_VPN){
                    Log.i("AlexLocation","现在用的是VPN网络，不能用ip定位");
                    considerIP = false;
                    break;
                }
            }
        }
        return considerIP;
    }

    /**
     * 判断当前手机在2g，3g，还是4g，用于发给谷歌
     */
    public static String determine2g3g4g(Context context){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivityManager==null)return null;
        if(Build.VERSION.SDK_INT<21) {//旧版本安卓获取网络状态
            NetworkInfo[] networkInfos = connectivityManager.getAllNetworkInfo();
            if(networkInfos==null)return null;
            for(NetworkInfo i:networkInfos){
                if(i==null)continue;
                Log.i("AlexLocation","正在查看当前网络的制式"+i.getTypeName()+i.getType()+"   "+i.getSubtypeName());//WIFI,VPN,MOBILE+LTE
                if(i.getType()!= ConnectivityManager.TYPE_MOBILE)continue;//只看流量
                else Log.i("AlexLocation","现在是移动网络");
                return determine2g3g4g(i);
            }
        }else {//新版
            Network[] networks = connectivityManager.getAllNetworks();
            if(networks==null)return null;
            for(Network n:networks){
                if(n==null)continue;
                NetworkInfo networkInfo = connectivityManager.getNetworkInfo(n);
                if(networkInfo==null)continue;
                Log.i("AlexLocation","正在查看当前网络的制式"+networkInfo.getTypeName()+networkInfo.getType()+"   "+networkInfo.getSubtypeName());//WIFI,VPN,MOBILE+LTE
                if(networkInfo.getType()!= ConnectivityManager.TYPE_MOBILE) continue;//只看流量
                return determine2g3g4g(networkInfo);
            }
        }
        return null;
    }

    /**
     * 看看现在用的是几g，什么网络制式
     * @param info
     * @return
     */
    public static String determine2g3g4g(NetworkInfo info){
        if(info==null)return null;
        switch (info.getSubtype()){
            case TelephonyManager.NETWORK_TYPE_LTE:
                Log.i("AlexLocation","手机信号是lte");
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                Log.i("AlexLocation","手机信号是edge");
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPAP";
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "EVDO_0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "EVDO_A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "EVDO_B";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "IDEN";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "EHRPD";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "RTT";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                return "UNKNOWN";
        }
        return null;

    }

    /**
     * 得到附近的wifi信息，准备传给谷歌
     * @param context
     * @param geoLocationAPI
     * @return
     */
    public static GeoLocationAPI getWifiInfo(Context context, GeoLocationAPI geoLocationAPI){
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if(wifiManager == null)return geoLocationAPI;
        Log.i("AlexLocation","准备开始扫描附近wifi");
        wifiManager.startScan();
        //准备所有附近wifi放到wifi列表里，包括现在正连着的wifi
        ArrayList<AlxScanWifi> lsAllWIFI = new ArrayList<AlxScanWifi>();
        List<ScanResult> lsScanResult = wifiManager.getScanResults();//记录所有附近wifi的搜索结果
        if(lsScanResult == null){
            Log.i("AlexLocation","搜索附近wifi热点失败");
            return geoLocationAPI;
        }
        for (ScanResult result : lsScanResult) {
            Log.i("AlexLocation","发现一个附近的wifi::"+result.SSID+"  mac地址是"+result.BSSID+"   信号强度是"+result.level);
            if(result == null)continue;
            AlxScanWifi scanWIFI = new AlxScanWifi(result);
            lsAllWIFI.add(scanWIFI);//防止重复
        }
        ArrayList<GoogleWifiInfo> wifiInfos = new ArrayList<>(lsAllWIFI.size());
        for (AlxScanWifi w:lsAllWIFI){
            if(w == null)continue;
            GoogleWifiInfo wifiInfo = new GoogleWifiInfo();
            wifiInfo.macAddress = w.mac.toUpperCase();//记录附近每个wifi路由器的mac地址
            wifiInfo.signalStrength = w.dBm;//通过信号强度来判断距离
            wifiInfo.channel = w.channel;//通过信道来判断ssid是否为同一个
            wifiInfos.add(wifiInfo);
        }
        geoLocationAPI.wifiAccessPoints = wifiInfos;
        return geoLocationAPI;
    }

    /**
     * 扫描附近wifi之后，记录wifi节点信息的类
     */
    public static class AlxScanWifi implements Comparable<AlxScanWifi> {
        public final int dBm;
        public final String ssid;
        public final String mac;
        public short channel;
        public AlxScanWifi(ScanResult scanresult) {
            dBm = scanresult.level;
            ssid = scanresult.SSID;
            mac = scanresult.BSSID;//BSSID就是传说中的mac
            channel = getChannelByFrequency(scanresult.frequency);
        }
        public AlxScanWifi(String s, int i, String s1, String imac) {
            dBm = i;
            ssid = s1;
            mac = imac;
        }

        /**
         * 根据信号强度进行排序
         * @param wifiinfo
         * @return
         */
        public int compareTo(AlxScanWifi wifiinfo) {
            int i = wifiinfo.dBm;
            int j = dBm;
            return i - j;
        }

        /**
         * 为了防止添加wifi的列表重复，复写equals方法
         * @param obj
         * @return
         */
        public boolean equals(Object obj) {
            boolean flag = false;
            if (obj == this) {
                flag = true;
                return flag;
            } else {
                if (obj instanceof AlxScanWifi) {
                    AlxScanWifi wifiinfo = (AlxScanWifi) obj;
                    int i = wifiinfo.dBm;
                    int j = dBm;
                    if (i == j) {
                        String s = wifiinfo.mac;
                        String s1 = this.mac;
                        if (s.equals(s1)) {
                            flag = true;
                            return flag;
                        }
                    }
                    flag = false;
                } else {
                    flag = false;
                }
            }
            return flag;
        }
        public int hashCode() {
            int i = dBm;
            int j = mac.hashCode();
            return i ^ j;
        }

    }

    /**
     * 根据频率获得信道
     *
     * @param frequency
     * @return
     */
    public static short getChannelByFrequency(int frequency) {
        short channel = -1;
        switch (frequency) {
            case 2412:
                channel = 1;
                break;
            case 2417:
                channel = 2;
                break;
            case 2422:
                channel = 3;
                break;
            case 2427:
                channel = 4;
                break;
            case 2432:
                channel = 5;
                break;
            case 2437:
                channel = 6;
                break;
            case 2442:
                channel = 7;
                break;
            case 2447:
                channel = 8;
                break;
            case 2452:
                channel = 9;
                break;
            case 2457:
                channel = 10;
                break;
            case 2462:
                channel = 11;
                break;
            case 2467:
                channel = 12;
                break;
            case 2472:
                channel = 13;
                break;
            case 2484:
                channel = 14;
                break;
            case 5745:
                channel = 149;
                break;
            case 5765:
                channel = 153;
                break;
            case 5785:
                channel = 157;
                break;
            case 5805:
                channel = 161;
                break;
            case 5825:
                channel = 165;
                break;
        }
        Log.i("AlexLocation","信道是"+channel);
        return channel;
    }

    /**
     * 根据国家代码获取通信运营商名字
     * @param operatorString
     * @return
     */
    public static String getCarrier(String operatorString){
        if(operatorString == null)
        {
            return "0";
        }

        if(operatorString.equals("46000") || operatorString.equals("46002"))
        {
            //中国移动
            return "中国移动";
        }
        else if(operatorString.equals("46001"))
        {
            //中国联通
            return "中国联通";
        }
        else if(operatorString.equals("46003"))
        {
            //中国电信
            return "中国电信";
        }

        //error
        return "未知";
    }

    /**
     * 用于向谷歌根据基站请求经纬度的封装基站信息的类
     */
    public static class GeoLocationAPI {

        /**
         * homeMobileCountryCode : 310 移动国家代码（中国的为460）；
         * homeMobileNetworkCode : 410 和基站有关
         * radioType : gsm
         * carrier : Vodafone 运营商名称
         * considerIp : true
         * cellTowers : []
         * wifiAccessPoints : []
         */

        public int homeMobileCountryCode;//设备的家庭网络的移动国家代码 (MCC)
        public int homeMobileNetworkCode;//设备的家庭网络的移动网络代码 (MNC)。
        public String radioType;//radioType：移动无线网络类型。支持的值有 lte、gsm、cdma 和 wcdma。虽然此字段是可选的，但如果提供了相应的值，就应该将此字段包括在内，以获得更精确的结果。
        public String carrier;//运营商名称。
        public boolean considerIp;//指定当 Wi-Fi 和移动电话基站的信号不可用时，是否回退到 IP 地理位置。请注意，请求头中的 IP 地址不能是设备的 IP 地址。默认为 true。将 considerIp 设置为 false 以禁用回退。
        public List<GoogleCellTower> cellTowers;
        public List<GoogleWifiInfo> wifiAccessPoints;

        public String toJson(){
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("homeMobileCountryCode",homeMobileCountryCode);
                jsonObject.put("homeMobileNetworkCode",homeMobileNetworkCode);
                jsonObject.put("radioType",radioType);
                jsonObject.put("carrier",carrier);
                jsonObject.put("considerIp",considerIp);
                if(cellTowers!=null){
                    JSONArray jsonArray = new JSONArray();
                    for (GoogleCellTower t:cellTowers) jsonArray.put(t.toJson());
                    jsonObject.put("cellTowers",jsonArray);
                }
                if(wifiAccessPoints!=null){
                    JSONArray jsonArray = new JSONArray();
                    for (GoogleWifiInfo w:wifiAccessPoints) jsonArray.put(w.toJson());
                    jsonObject.put("wifiAccessPoints",jsonArray);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jsonObject.toString();


        }

    }

    /**
     * 封装和基站有关的数据，准备发给谷歌
     */
    public static class GoogleCellTower {
        /*
        GSM:
                {
          "cellTowers": [
            {
              "cellId": 42,
              "locationAreaCode": 415,
              "mobileCountryCode": 310,
              "mobileNetworkCode": 410,
              "age": 0,
              "signalStrength": -60,
              "timingAdvance": 15
            }
          ]
        }
        WCDMA
        {
          "cellTowers": [
            {
              "cellId": 21532831,
              "locationAreaCode": 2862,
              "mobileCountryCode": 214,
              "mobileNetworkCode": 7
            }
          ]
        }

         */
        //下面的是必填
        int cellId;//（必填）：小区的唯一标识符。在 GSM 上，这就是小区 ID (CID)；CDMA 网络使用的是基站 ID (BID)。WCDMA 网络使用 UTRAN/GERAN 小区标识 (UC-Id)，这是一个 32 位的值，由无线网络控制器 (RNC) 和小区 ID 连接而成。在 WCDMA 网络中，如果只指定 16 位的小区 ID 值，返回的结果可能会不准确。
        int locationAreaCode;//（必填）：GSM 和 WCDMA 网络的位置区域代码 (LAC)。CDMA 网络的网络 ID (NID)。
        int mobileCountryCode;//（必填）：移动电话基站的移动国家代码 (MCC)。
        int mobileNetworkCode;//（必填）：移动电话基站的移动网络代码。对于 GSM 和 WCDMA，这就是 MNC；CDMA 使用的是系统 ID (SID)。
        int signalStrength;//测量到的无线信号强度（以 dBm 为单位）。
        //下面的是选填
        int age;//自从此小区成为主小区后经过的毫秒数。如果 age 为 0，cellId 就表示当前的测量值。
        int timingAdvance;//时间提前值。

        public JSONObject toJson(){
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("cellId",cellId);
                jsonObject.put("locationAreaCode",locationAreaCode);
                jsonObject.put("mobileCountryCode",mobileCountryCode);
                jsonObject.put("mobileNetworkCode",mobileNetworkCode);
                jsonObject.put("signalStrength",signalStrength);
                jsonObject.put("age",age);
                jsonObject.put("timingAdvance",timingAdvance);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jsonObject;
        }
    }

    /**
     * 向谷歌服务器根据附近wifi请求位置的json
     */
    public static class GoogleWifiInfo {

        /**
         * macAddress : 01:23:45:67:89:AB
         * signalStrength : -65
         * age : 0
         * channel : 11
         * signalToNoiseRatio : 40
         */

        public String macAddress;//（必填）Wi-Fi 节点的 MAC 地址。分隔符必须是 :（冒号），并且十六进制数字必须使用大写字母。
        public int signalStrength;//测量到的当前信号强度（以 dBm 为单位）。
        public int age;//自从检测到此接入点后经过的毫秒数。
        public short channel;//客户端与接入点进行通信的信道
        public int signalToNoiseRatio;//测量到的当前信噪比（以 dB 为单位）。

        public JSONObject toJson(){
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("signalStrength",signalStrength);
                jsonObject.put("age",age);
                jsonObject.put("macAddress",macAddress);
                jsonObject.put("channel",channel);
                jsonObject.put("signalToNoiseRatio",signalToNoiseRatio);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return  jsonObject;
        }
    }


    /**
     * 使用httpclient发送一个post的json请求
     * @param url
     * @return
     */
    public void sendJsonByPost(String json, String url){
        this.dataJson = json;
        RequestParams params = new RequestParams();
        params.applicationJson(JSON.parseObject(json));
        HttpRequest.post(url, params, new BaseHttpRequestCallback<String>(){
            @Override
            protected void onSuccess(String s) {
                super.onSuccess(s);
                 /*
                谷歌返回的json如下
                {
                   "location": {
                    "lat": 1.3553794,
                    "lng": 103.86774439999999
                   },
                   "accuracy": 16432.0
                  }
                 */
                if(s==null || context==null)return;
                String result = s;
                Log.i("AlexLocation","成功"+s);
                if(isDebugging)Toast.makeText(context,"谷歌成功，返回json是"+result,Toast.LENGTH_LONG).show();
                if(result==null || result.length()<10 || !result.startsWith("{")){
                    Log.i("AlexLocation","返回格式不对"+result);
                }
                JSONObject returnJson = null;
                try {
                    returnJson = new JSONObject(result);
                    JSONObject location = returnJson.getJSONObject("location");
                    if(location==null){
                        Log.i("AlexLocation","条件不足，无法确定位置");
                        return;
                    }
                    double latitude = location.getDouble("lat");
                    double longitute = location.getDouble("lng");
                    double google_accuracy = returnJson.getDouble("accuracy");
                    Log.i("AlexLocation","谷歌返回的经纬度是"+latitude+"  :  "+longitute+"  精度是"+google_accuracy);
                    if(isDebugging)Toast.makeText(context,"谷歌返回的经纬度是"+latitude+"  :  "+longitute+"  精度是"+google_accuracy,Toast.LENGTH_LONG).show();
                    if(currentStatus== STATUS.NOT_CONNECT || google_accuracy<accuracy){
                        Log.i("Alex","决定采用基站和wifi定位，旧的精确度是"+accuracy);
                        accuracy = (float) google_accuracy;
                        recordLocation(context,latitude,longitute,accuracy);//当没有从GPS获取经纬度成功，或者GPS的获取经纬度精确度不高，则使用基站和wifi的结果
                    }
                } catch (JSONException e) {
                    Log.i("AlexLocation","条件不足，无法确定位置2",e);
                }

            }

            @Override
            public void onFailure(int errorCode, String msg) {
                super.onFailure(errorCode, msg);
                if(msg==null)return;
                //这里如果报404异常的话，一般是根据当前的基站cid无法查到相关信息
                //如果返回值是 400  Bad Request，说明有的必填项没有填
                Log.i("AlexLocation","失败了"+msg+"   "+errorCode);
                if(errorCode==0)Log.i("AlexLocation","谷歌没有根据现有条件查询到经纬度");
                if(isDebugging)Toast.makeText(context,"谷歌查询失败",Toast.LENGTH_LONG).show();
            }
        });
    }



    /**
     * 检查当前Wifi网卡状态
     */
    public void checkNetCardState(WifiManager mWifiManager) {
        if (mWifiManager.getWifiState() == 0) {
            Log.i("AlexLocation", "网卡正在关闭");
        } else if (mWifiManager.getWifiState() == 1) {
            Log.i("AlexLocation", "网卡已经关闭");
        } else if (mWifiManager.getWifiState() == 2) {
            Log.i("AlexLocation", "网卡正在打开");
        } else if (mWifiManager.getWifiState() == 3) {
            Log.i("AlexLocation", "网卡已经打开");
        } else {
            Log.i("AlexLocation", "没有获取到状态");
        }
    }

    public static void  getConnectedWifiInfo(WifiManager wifiManager){
        if(wifiManager==null)return;
        WifiInfo wifiConnection = wifiManager.getConnectionInfo();
        if (wifiConnection != null) {//获取当前链接的wifi信息，没什么用
            String wifiMAC = wifiConnection.getBSSID();
            int i = wifiConnection.getRssi();
            String s1 = wifiConnection.getSSID();
            String mac = wifiConnection.getMacAddress();//注意这里的mac是手机的mac而不是热点的mac
            Log.i("AlexLocation","手机的mac地址是"+mac);
        }
    }

    /**
     * 根据经纬度计算两点间的距离
     * @param lat_a
     * @param lng_a
     * @param lat_b
     * @param lng_b
     * @return
     */
    public static double getGPSDistance(double lat_a, double lng_a, double lat_b, double lng_b) {
        final double M_PI = 3.14159265358979323846264338327950288, EARTH_RADIUS = 6378138.0;
        final double dd = M_PI / 180.0;

        double lon2 = lng_b;
        double lat2 = lat_b;

        double x1 = lat_a * dd, x2 = lat2 * dd;
        double y1 = lng_a * dd, y2 = lon2 * dd;
        double distance = (2 * EARTH_RADIUS * Math.asin(Math.sqrt(2 - 2 * Math.cos(x1)
                * Math.cos(x2) * Math.cos(y1 - y2) - 2 * Math.sin(x1)
                * Math.sin(x2)) / 2));
        Log.i("AlexLocation","位置发生了移动，距离是"+distance);
        if(isDebugging && manager!=null)Toast.makeText(manager.context,"位置移动了"+distance+"米",Toast.LENGTH_LONG).show();
        return distance;
    }

    public static double pi = 3.1415926535897932384626;
    public static double a = 6378245.0;
    public static double ee = 0.00669342162296594323;
/**
      * 84 to 火星坐标系 (GCJ-02) World Geodetic System ==> Mars Geodetic System
      *
      * @param lat
      * @param lon
      * @return
      */
         public static MyLocation gps84_To_Gcj02(double lat, double lon) {
             if (outOfChina(lat, lon)) {return null;}
               double dLat = transformLat(lon - 105.0, lat - 35.0);
               double dLon = transformLon(lon - 105.0, lat - 35.0);
               double radLat = lat / 180.0 * pi;
               double magic = Math.sin(radLat);
               magic = 1 - ee * magic * magic;
               double sqrtMagic = Math.sqrt(magic);
               dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi);
               dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * pi);
               double mgLat = lat + dLat;
               double mgLon = lon + dLon;
               return new MyLocation(mgLat, mgLon);
           }

    public static double transformLat(double x, double y) {
              double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
              ret += (20.0 * Math.sin(6.0 * x * pi) + 20.0 * Math.sin(2.0 * x * pi)) * 2.0 / 3.0;
              ret += (20.0 * Math.sin(y * pi) + 40.0 * Math.sin(y / 3.0 * pi)) * 2.0 / 3.0;
              ret += (160.0 * Math.sin(y / 12.0 * pi) + 320 * Math.sin(y * pi / 30.0)) * 2.0 / 3.0;
              return ret;
          }

    public static double transformLon(double x, double y) {
              double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
              ret += (20.0 * Math.sin(6.0 * x * pi) + 20.0 * Math.sin(2.0 * x * pi)) * 2.0 / 3.0;
              ret += (20.0 * Math.sin(x * pi) + 40.0 * Math.sin(x / 3.0 * pi)) * 2.0 / 3.0;
              ret += (150.0 * Math.sin(x / 12.0 * pi) + 300.0 * Math.sin(x / 30.0 * pi)) * 2.0 / 3.0;
              return ret;
          }

    /**
     * 根据真实经纬度判断在不在中国境内，采用方形判断
     * @param lat
     * @param lon
     * @return
     */
    public static boolean outOfChina(double lat, double lon) {
          if (lon < 72.004 || lon > 137.8347)
                  return true;
          if (lat < 0.8293 || lat > 55.8271)
                  return true;
          return false;
    }
}