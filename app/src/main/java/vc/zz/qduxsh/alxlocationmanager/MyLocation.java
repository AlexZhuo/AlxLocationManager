package vc.zz.qduxsh.alxlocationmanager;

/**
 * Created by Administrator on 2016/9/22.
 */
public class MyLocation {
    public double latitude;
    public double longitude;
    private static MyLocation myLocation;
    MyLocation(){}

    public MyLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static MyLocation getInstance(){
        if(myLocation == null)myLocation = new MyLocation();
        return myLocation;
    }
}
