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

    public static int network = 0;  // 初始化为没网
    public static boolean isWifi = false;  // 不管有多少实例，没开wifi就是没开

    private OnStateChangeListener mListener;

    public interface OnStateChangeListener {
        void onChanged();
    }

    @SuppressWarnings("unused")
    public ConnectivityState() {
        super();  // manifest.xml里要求receiver有默认构造函数，虽然应该是对静态接收器的要求
    }

    public ConnectivityState(Context context) {
        super();
        network = getNetworkState(context);
    }

    public void setListener(OnStateChangeListener listener) {
        mListener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "onReceive: " + intent.getAction());
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            if (network != (network = getNetworkState(context))) {  // !=从左到右求值…但这么写不易读
                if (mListener != null) {
                    mListener.onChanged();
                }
            }
            Log.w(TAG, "WIFI is " + (isWifi ? "on" : "off"));
        }
    }

    private int getNetworkState(Context context) {
        Object manager = context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = ((ConnectivityManager) manager).getActiveNetworkInfo();
        isWifi = (info != null && info.getType() == ConnectivityManager.TYPE_WIFI);
        return info == null ? 0 : 1 + (isWifi ? 1 : 0);   // 开了WiFi但还在用流量不算WiFi
    }
}
