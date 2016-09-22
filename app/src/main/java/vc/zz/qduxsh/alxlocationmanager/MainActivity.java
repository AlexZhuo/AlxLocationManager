package vc.zz.qduxsh.alxlocationmanager;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import cn.finalteam.okhttpfinal.OkHttpFinal;
import cn.finalteam.okhttpfinal.OkHttpFinalConfiguration;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        OkHttpFinalConfiguration.Builder builder = new OkHttpFinalConfiguration.Builder();
        OkHttpFinal.getInstance().init(builder.build());
    }

    @Override
    protected void onStart() {
        super.onStart();
        //google API返回的经纬度结果推荐去http://www.gpsspg.com/maps.htm查看具体位置，防止国内被混淆

        //开启位置监听
        AlxLocationManager.onCreateGPS(getApplication());
    }

    @Override
    protected void onStop() {
        super.onStop();
        AlxLocationManager.stopGPS();
    }
}
