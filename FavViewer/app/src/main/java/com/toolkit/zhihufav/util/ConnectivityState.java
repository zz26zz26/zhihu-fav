package com.toolkit.zhihufav.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * Created on 2018/5/10.
 */

public class ConnectivityState extends BroadcastReceiver {

    private static final String TAG = "ConnectivityState";

    public static boolean isWifi = false;  // 不管有多少实例，没开wifi就是没开

    private OnStateChangeListener mListener;

    public interface OnStateChangeListener {
        void onChanged();
    }

//    // manifest.xml里要求receiver有默认构造函数，虽然应该是对静态接收器的要求
//    public ConnectivityState() {
//        super();
//    }

    public ConnectivityState(Context context) {
        super();
        isWifi = isWifiActive(context);
    }

    public void setListener(OnStateChangeListener listener) {
        mListener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "onReceive: " + intent.getAction());
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            if (isWifi != (isWifi = isWifiActive(context))) {  // !=从左到右算…似乎不易读 不好...
                mListener.onChanged();
            }
            Log.w(TAG, "WIFI is " + (isWifi ? "on" : "off"));
        }
    }

    private boolean isWifiActive(Context context) {
        Object manager = context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = ((ConnectivityManager) manager).getActiveNetworkInfo();
        return (info != null && info.getType() == ConnectivityManager.TYPE_WIFI);
    }  // 开了WiFi但还在用流量不算
}
