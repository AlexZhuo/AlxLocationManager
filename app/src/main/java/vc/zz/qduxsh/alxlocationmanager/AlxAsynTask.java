package vc.zz.qduxsh.alxlocationmanager;

import android.os.AsyncTask;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Alex on 2016/4/19.
 * 用于替换系统自带的AsynTask，使用自己的单线程池，防止有某些sdk喜欢用asynTask阻塞线程池
 */
public abstract class AlxAsynTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {
    private static ExecutorService photosThreadPool;//用于加载大图和评论的线程池
    public void executeDependSDK(Params...params){
        if(photosThreadPool==null)photosThreadPool = Executors.newSingleThreadExecutor();
        if(Build.VERSION.SDK_INT<11) super.execute(params);
        else super.executeOnExecutor(photosThreadPool,params);
    }

}
