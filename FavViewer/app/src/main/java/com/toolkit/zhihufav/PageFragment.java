package com.toolkit.zhihufav;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.NestedScrollView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.toolkit.zhihufav.util.ConnectivityState;
import com.toolkit.zhihufav.util.DiskLruCache;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Created on 2018/5/13.
 */

// 原名PlaceholderFragment，作为ViewPagerAdapter的静态内部类(防生命周期不可控时内存泄漏)
// 注意不要重写构造函数(重新显示或内存不足恢复时不会调构造，Fragment构造函数的注释说的)
public class PageFragment extends Fragment {

    private static final String TAG = "PageFragment";

    private static final String ARG_POS = "ARG_POS";
    private static final String ARG_LINK = "ARG_LINK";
    private static final String ARG_SCROLL = "ARG_SCROLL";
    private static final String ARG_CONTENT = "ARG_CONTENT";

    static final int MODE_COPY = -1;
    static final int MODE_START = 0;
    static final int MODE_IMAGE = 1;
    static final int MODE_VIDEO = 2;
    static final int MODE_BLANK = 3;

    private static int sTextZoom;
    private static int sBackColor;
    private static int sEntryPage;
    private static boolean sNightTheme;
    private static DiskLruCache mCache;
    // 以上static为各Fragment共用

    private long mLoadStartTime;
    private boolean mOnCreate;
    private boolean mCacheOnly;
    private boolean mChangeCacheModeOnPageFinish;
    private Runnable mPendingModeChange;

    private class OnTouchListener implements View.OnTouchListener {

        private Runnable flingChecker;
        private VelocityTracker tracker;
        private ViewConfiguration config = ViewConfiguration.get(getActivity());

        private int dragDirection;  // 0非拖动(点击) 1主要X轴(横向) 2主要Y轴(纵向)
        private int initialPointerId;  // 防止多点触控视为滑动，然后造成ViewPager闪退
        private long touchDownTime, touchUpTime, lastScrollY;
        private float touchStartX, touchStartY, lastMotionY, lastCenterY, initialTop;
        private boolean inNestedScroll, inNestedFling = false;

        private void startFlingScroll(WebView webView) {
            if (flingChecker == null) {
                final WebView v = webView;
                flingChecker = new Runnable() {
                    @Override
                    public void run() {
                        if (inNestedFling && getContentMode(v) == MODE_START && v.getScrollY() != lastScrollY) {
                            v.postDelayed(flingChecker, 250);  // 检查inNestedFling防止手动滑动时还去post
                            lastScrollY = v.getScrollY();  // 检查MODE防止destroy或开图片还去post
                            //Log.d(TAG, "WebView flinging");
                        } else inNestedFling = false;
                    }
                };
            }
            lastScrollY = 0;
            inNestedFling = true;
            webView.postDelayed(flingChecker, 250);
            webView.flingScroll((int) tracker.getXVelocity(), -(int) tracker.getYVelocity());
            Log.d(TAG, "WebView fling started, estimated vy = " + tracker.getYVelocity());
            // flingScroll之后要有新fling再按才能停下！普通点击或滑动不行
        }

        private void stopFlingScroll(WebView webView) {
            int thresh = config.getScaledTouchSlop() + 1;  // 移动距离要大于slop，且速度达到最小fling速度
            int duration = 5;  // 至少是3才能被识别为有效fling
            long eventTime = SystemClock.uptimeMillis();  // MotionEvent.obtain注释说必须用这个时间

            if (1000 * thresh / duration < config.getScaledMinimumFlingVelocity()) {  // 速度不够
                thresh = config.getScaledMinimumFlingVelocity() * duration / 1000 + 1;  // 路程来凑
            }

            // 先模拟一次惯性滚动，覆盖flingScroll的滚动
            MotionEvent downEvent = MotionEvent.obtain(eventTime, eventTime,
                    MotionEvent.ACTION_DOWN, 0, 0, 0);  // 倒数第2/3个参数是相对View的坐标
            webView.onTouchEvent(downEvent);  // 不要dispatch，过程太复杂
            downEvent.recycle();

            MotionEvent moveEvent = MotionEvent.obtain(eventTime, eventTime + 1,
                    MotionEvent.ACTION_MOVE, 0, 0, 0);  // eventTime不加1是跳动，没有滚动动画
            webView.onTouchEvent(moveEvent);  // 没有第一个MOVE也是跳动
            moveEvent.recycle();

            MotionEvent moveEvent2 = MotionEvent.obtain(eventTime, eventTime + duration,
                    MotionEvent.ACTION_MOVE, 0, thresh, 0);  // 至少+3；VelocityTracker用eventTime算速度
            webView.onTouchEvent(moveEvent2);  // 没有第二个MOVE直接不触发fling
            moveEvent2.recycle();

            MotionEvent upEvent = MotionEvent.obtain(eventTime, eventTime + duration,
                    MotionEvent.ACTION_UP, 0, thresh, 0);  // 不要移得太远，不然在下面单击之前就会跳很远
            webView.onTouchEvent(upEvent);
            upEvent.recycle();

            // 再模拟点击停掉惯性滚动；点击间隔小于10即可在onTouch里判为程序点击
            MotionEvent downEvent2 = MotionEvent.obtain(eventTime, eventTime + duration,
                    MotionEvent.ACTION_DOWN, 0, 0, 0);
            webView.onTouchEvent(downEvent2);
            downEvent2.recycle();

            MotionEvent upEvent2 = MotionEvent.obtain(eventTime, eventTime + duration,
                    MotionEvent.ACTION_UP, 0, -thresh, 0);  // 实际测量间隔是0-3ms
            webView.onTouchEvent(upEvent2);  // 模拟滑出界防止认成点击，也不能弄成横滑不然会换页
            upEvent2.recycle();

            // 停掉就重置，网页静止时调用此函数会往上跳动
            inNestedFling = false;
            Log.d(TAG, "WebView fling stopped");
        }

        private float getYPointerCenter(MotionEvent event) {
            int n = event.getPointerCount();
            int up_pointer = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
            float posY = 0.0f;
            for (int i = 0; i < n; i++) {
                if (i != up_pointer)  // 抬起的手指在ACTION_POINTER_UP里仍占一个位置，应除去(注意平均时n也要-1)
                    posY += event.getY(i);  // 多点时相当于取重心
            }
            return posY / (n - (up_pointer >= 0 ? 1 : 0));
        }

        private boolean dispatchNestedScroll(WebView webView, MotionEvent event) {
            // 假装WebView实现了简化的NestedScrollingChild接口，用此方法通知父级嵌套滑动
            // 原生嵌套先让父级消费，并据此对本event进行offset来达到共同滑动(父级全消费此处就不动)，不必CANCEL
            // 然而又要考虑WebView的位置的运动并不好算，且由于位置在动而手指就在那，似乎滑快就会出现嵌套不同步
            // 多点触控时WebView滑动距离与Nested不同，以前只用CANCEL/DOWN换消费方，多指上滑(一静一动)标题栏不折叠
            int action = event.getAction();  // UP必然只剩一个手指，MOVE则包含所有手指，都不会有Mask

            if (dragDirection == 1) {  // 这里调onTouchEvent则横滑画面会抖动，且隔壁网页能竖滑(横滑闪退)
                if (MotionEvent.ACTION_UP != action) {  // 第一个就是UP说明以后也不会有事件了
                    ViewPager pager = (ViewPager) webView.getParent().getParent();  // 不判断两指闪退
                    webView.requestDisallowInterceptTouchEvent(false);  // 只有这句可能给Nested拦截
                    pager.getParent().requestDisallowInterceptTouchEvent(true);  // 这样确保给ViewPager
                    Log.d(TAG, "ViewPager intercept");  // 其onIntercept要x>2y才拦，onTouchEvent是x>y
                }
            } else if (dragDirection == 2) {
                // 在标题栏展开完时(网页必已在顶)，手指下滑留WebView(边缘滑动发光)，但上滑要给(收标题栏)
                // 在标题栏折叠完时(只看网页在顶)，要把手指上滑(dy<0)留WebView处理，而下滑要给(出标题栏)
                // 滑一段才能判断，此前别传给网页，否则顶部上滑在thresh内造成网页滑动，会使标题栏突然折叠
                // 若放弃阻止父级拦截后再调用阻止，事件还是只到父级，只能硬塞给网页处
                NestedScrollView scroller = (NestedScrollView) webView.getParent().getParent().getParent();
                ContentActivity activity = (ContentActivity) getActivity();
                MotionEvent parentEvent = MotionEvent.obtain(event.getDownTime(), event.getEventTime(), 0, 0, 0, 0);
                parentEvent.setAction(action >= MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_MOVE : action);
                // 多指时把结果转为单指事件给父级，则POINTER_DOWN和POINTER_UP都可视为MOVE
                float movY = getYPointerCenter(event) - lastCenterY;  // tracker用加速度预测速度，减速就可能使速度变号
                float velY = MotionEvent.ACTION_MOVE == action ? movY : 0;  // POINTER_DOWN之类就只改Center不传父级
                //Log.d(TAG, "NestedScroll         @cy = " + (lastCenterY + movY) + ", y = " + (lastMotionY + velY));
                int toolbarState = activity.getToolbarState();  // 定了direction就不用thresh了
                if (toolbarState == ContentActivity.TOOLBAR_INTERMEDIATE ||  // 伸缩中显然要给标题栏
                        toolbarState == ContentActivity.TOOLBAR_EXPANDED && velY < 0 ||
                        toolbarState == ContentActivity.TOOLBAR_COLLAPSED && webView.getScrollY() == 0 && velY > 0) {
                    if (!inNestedScroll && MotionEvent.ACTION_UP != action) {
                        parentEvent.setAction(MotionEvent.ACTION_DOWN);  // DOWN可重置积累的下滑距离
                        Log.d(TAG, "NestedScroll DOWN    @vy = " + velY);  // 但CANCEL自己后就不动了
                    }
                    event.setAction(MotionEvent.ACTION_CANCEL);  // 可取消长按；4.4在CANCEL后仍会响应MOVE
                    parentEvent.setLocation(event.getX(), lastMotionY + velY);
                    scroller.onTouchEvent(parentEvent);  // dispatch流程多比较卡，还可能循环传递事件
                    inNestedScroll = true;
                } else {
                    // 不必判断(velY != 0)，手指不动vel都会振荡
                    // 停止分发给Nested，留给WebView自己，第一次停止要用CANCEL通知Nested
                    // 注意第一次就是UP就别改，不然标题栏折叠+网页在顶部时快速下滑，此时DOWN未传给Nested…
                    // 而之后的第一个MOVE便超过阈值传来作为Nested的DOWN…
                    // 此时若下一个就是UP，然后还给WebView一个DOWN就会引发长按
                    if (inNestedScroll && MotionEvent.ACTION_UP != action) {
                        parentEvent.setAction(MotionEvent.ACTION_CANCEL);
                        scroller.onTouchEvent(parentEvent);
                        Log.d(TAG, "NestedScroll CANCEL  @vy = " + velY);
                        event.setAction(MotionEvent.ACTION_DOWN);  // 5.0后若之前CANCEL过要再DOWN才会动
                    }
                    inNestedScroll = false;
                }
                if (MotionEvent.ACTION_UP == action && ContentActivity.TOOLBAR_EXPANDED != toolbarState && 0 == webView.getScrollY()) {
                    // 以前用上滑够快就让网页响应滚动来触发fling，但手指轻触快速上滑时会出现网页在滚而标题栏折一点又展开了
                    tracker.computeCurrentVelocity(1000, config.getScaledMaximumFlingVelocity());  // 不限制能一口气滑到底
                    int minFlingVel = Math.max(config.getScaledMinimumFlingVelocity(), activity.getToolbarHeight()) * 20;
                    if (tracker.getYVelocity() < -minFlingVel) {  // 至少要能把标题栏折叠完才能开始fling
                        activity.setToolBarExpanded(false);
                        startFlingScroll(webView);
                    }
                }
                lastMotionY += velY;
                lastCenterY += movY;
                parentEvent.recycle();
            }
            return false;  // false才能让CANCEL传到onTouchEvent
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            WebView webView = (WebView) v;
            boolean consumed = false;
            long currentTime = SystemClock.uptimeMillis();  // 与event.getDownTime时间格式一致
            int id = event.getPointerId(event.getActionIndex());  // id每根手指唯一；index总是从0开始
            int action = event.getActionMasked();
            float top = event.getRawY() - event.getY();  // 可把各手指都转为RawY
            float deltaX = event.getRawX() - touchStartX;
            float deltaY = event.getRawY() - touchStartY;  // Raw只能取Index=0的手指位置
            float thresh = config.getScaledTouchSlop();

//            String[] s = {"DOWN", "UP", "MOVE", "CANCEL", "OUTSIDE", "POINTER_DOWN", "POINTER_UP"};
//            if (tracker != null) tracker.computeCurrentVelocity(1000);
//            if (action != MotionEvent.ACTION_MOVE)
//                Log.d(TAG, "action = " + s[action] + (action < 5 && id < 1 ? "" : " #" + id) +
//                        ". vy = " + (tracker == null ? 0 : tracker.getYVelocity(id)) + ". y = " + (event.getY(event.getActionIndex()) + top));
//            else for (int i = 0, n = event.getPointerCount(); i < n; i++)
//                Log.d(TAG, "action = MOVE" + (n < 2 && id < 1 ? "" : " #" + event.getPointerId(i)) +
//                        ". vy = " + (tracker == null ? 0 : tracker.getYVelocity(event.getPointerId(i))) + ". y = " + (event.getY(i) + top));

            // 总体上，层层调用dispatch，直到底或遇到on(Intercept)Touch(Event)返回true的View(Group)结束，
            // 最后若到底都没有遇到返回true的，还会再层层往回调用onTouch(Event)，也是遇一个true就停
            // 即父级优先拦截事件，子级优先处理事件；View无子级遂没有onIntercept，dispatch也只给自己

            // 具体地，触摸事件开始后(均以DOWN开始)，由最外层Activity的dispatchTouchEvent开始层层向内
            // 在每次ACTION_DOWN时都会先重置DisallowIntercept为false，再开始处理DOWN事件
            // dispatch先调用onInterceptTouchEvent，若DOWN时返回false则之后的MOVE等仍经本函数传子级onTouch；
            // 若返回true则表示拦截，本函数接收一个CANCEL后都由本ViewGroup的onTouch处理，且不往下传
            // dispatch再调用onTouch，若在DOWN返回false则之后不再来，但会调用onTouchEvent(LongClick在此)；
            // 若返回true则不再往回调用，且之后的MOVE、UP事件也能够在onTouch里接收到，也不影响
            // 当然，没有设置onTouch监听或onTouch返回false时还会调用onTouchEvent，情况类似
            // 可点击的View自带的onTouchEvent能返回true，所以onTouch即使返回false也还能收到MOVE等
            // 当上层在DOWN之后才开始拦截，下层将收到一个CANCEL后再也接收不到事件

            // 在此处，默认时父级NestedScroll拦截长移动的ACTION_MOVE(即此处只能拿到点击/长按)
            // 此函数即使返回true也只能在收到少量ACTION_MOVE后得ACTION_CANCEL，并没有ACTION_UP
            // 长按时能收到大量ACTION_MOVE，但最后也可能是ACTION_CANCEL；只有点击才是ACTION_UP
            // 另若仅要求ViewPager的父级不拦截，此处仍只得ACTION_CANCEL；遂此处只能搞自力更生
            // 另有大问题：父级在thresh内不拦截，其收不到DOWN和足量的MOVE，不能响应超高速fling

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getRawX();  // Down时只有一个手指，不必指定PointerId
                    touchStartY = event.getRawY();  // 需用Raw，否则标题栏折叠使ViewPager上移时deltaY不变
                    touchDownTime = event.getDownTime();  // 用于判断长按和程序点击
                    initialPointerId = event.getPointerId(0);
                    initialTop = top;
                    dragDirection = 0;
                    inNestedScroll = false;
                    lastMotionY = lastCenterY = event.getY();
                    if (tracker == null) tracker = VelocityTracker.obtain();
                    if (inNestedFling) stopFlingScroll(webView);  // 只用弄停flingScroll造成的滚动
                    webView.requestDisallowInterceptTouchEvent(true);  // 同时会通知到所有父级
                    break;  // 复制/图片/视频页全程阻止父级拦截(防换页/出标题)

                case MotionEvent.ACTION_MOVE: // 滑远又滑回来时不再白白消费thresh距离
                    if (dragDirection == 0 && Math.abs(deltaX) > Math.max(2 * thresh, Math.abs(deltaY)))
                        dragDirection = 1;    // 满足此条件一出去ViewPager能立刻响应
                    if (dragDirection == 0 && Math.abs(deltaY) > thresh)
                        dragDirection = 2;    // 图片模式也用此判断是否为点击(之前只看位移不看路程)
                    if (dragDirection == 0)
                        lastMotionY = lastCenterY = event.getY();  // 放前俩if之后，即没定方向才改(此时必为单指)
                    if (tracker != null) {
                        MotionEvent v_event = MotionEvent.obtain(event);  // 要单独弄，不然滑动会跳
                        v_event.offsetLocation(0, top - initialTop);  // 计算用的相对坐标在标题栏伸缩时不变
                        tracker.addMovement(v_event);
                        v_event.recycle();
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (dragDirection == 0 && id != initialPointerId)  // 多点触控即不是点击
                        dragDirection = 2;  // 也不能翻页，就只剩拖标题栏了(不给则展开时网页可上滑)
                    break;

                case MotionEvent.ACTION_UP:
                    if (dragDirection == 0) {  // 没滑动+没多点触控才是点击
                        WebView.HitTestResult result = webView.getHitTestResult();
                        switch (getContentMode(webView)) {
                            case MODE_START:  // 视频封面SRC_IMAGE_ANCHOR_TYPE；播放页图IMAGE_TYPE
                                if (result.getType() == WebView.HitTestResult.IMAGE_TYPE) {
                                    if (result.getExtra().startsWith("http") &&   // 文件不行(空src在loadImage里也是直接返回)
                                            currentTime - touchDownTime < 500 &&  // 长按不行(ViewConfiguration.getLongPressTimeout()
                                            currentTime - touchDownTime > 10 &&   // 机按不行(人超轻触可4ms但不自然)
                                            currentTime - touchUpTime > 600) {    // 连击不行(免得生成太多异步)
                                        new RawImageLoader(PageFragment.this).executeOnExecutor(
                                                AsyncTask.THREAD_POOL_EXECUTOR, result.getExtra(),
                                                (String) webView.getTag(R.id.web_tag_in_html));
                                        currentTime -= 300;  // 防止点开图片后马上再点一次就放大
                                    }
                                }
                                break;
                            case MODE_IMAGE:  // 视频模式进来也放不大
                                if (currentTime - touchUpTime < 300) {  // ViewConfiguration.getDoubleTapTimeout()
                                    if (webView.zoomOut())              // 能缩小(out)就缩到最小
                                        for (int i = 0; i < 7; i++)     // 放最大也就能缩小7次
                                            webView.zoomOut();
                                    else
                                        for (int i = 0; i < 5; i++)
                                            webView.zoomIn();
                                }
                                break;
                        }
                        touchUpTime = currentTime;
                    }
                    break;
            }

            if (getContentMode(webView) == MODE_START && dragDirection > 0) {  // UP等事件也要传
                consumed = dispatchNestedScroll(webView, event);  // 放tracker.recycle之前
            }  // 里面修改event的action没事，因为下面用的action是最开始的缓存

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
            }

            return consumed;  // false表示没有消费事件，仍向下传递(才能点击/滑动)
        }

    }

    private class CachedWebViewClient extends WebViewClient {

        private class CachedInputStream extends FilterInputStream {

            private static final String TAG = "CachedInputStream";

            private String url;
            private DiskLruCache.Editor cache;
            private OutputStream cacheStream;
            private int max_read_len;  // 默认均为0
            private int expect_len;
            private int real_len;

            private CachedInputStream(String url, DiskLruCache.Editor cache) throws IOException {
                super(null);
                this.url = url;  // 刷新缓冲区后mark即失效，无论markLimit多大，所以limit设为INT_MAX无效
                this.cache = cache;  // cache的OutputStream出IO异常时不抛，而是等commit时自动改为abort
                this.cacheStream = cache.newOutputStream(0);
            }

            @Override
            public int available() throws IOException {
                return (super.in == null) ? 0 : super.available();  // 最底层就返回0；read前会调用一次
            }

            @Override
            public long skip(long n) throws IOException {
                return (super.in == null) ? 0 : super.skip(n);  // 源码显示可能调用的就这四个方法
            }

            @Override
            public int read(@NonNull byte[] b, int off, int len) throws IOException {
                // https://chromium.googlesource.com/chromium/src.git/+/master/android_webview/java/src/org/chromium/android_webview/InputStreamUtil.java
                // 源码说明WebView的InputStreamUtil是调用此读取，且用线程池里的线程（不是UI也不是IO）
                // 遂在此可先缓存再return出去，也可弄耗时操作（点开图时直接close，图片没下完也不管）
                try {
                    if (super.in == null) {  // 为避免在WebView有限的IO线程里长时间操作，只能留到这真正初始化
                        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setConnectTimeout(10000);  // nginx默认60s
                        conn.setReadTimeout(10000);  // get自动调connect，如没网就抛异常
                        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {  // 不然0kb文件也成缓存了
                            super.in = conn.getInputStream();
                            this.expect_len = conn.getContentLength();
                        } else {
                            max_read_len = -1;
                            conn.disconnect();
                            return -1;
                        }
                    }

                    int read_len = super.read(b, off, len);  // 可能抛连接超时
                    if (read_len != -1 && max_read_len != -1) {  // 若写max_len>0则永远进不来…
                        cacheStream.write(b, off, read_len);  // b从WebView传来，长度不定，一般4096
                        max_read_len = Math.max(read_len, max_read_len);  // 似乎最大2048
                        real_len += read_len;
                    }
                    return read_len;
                } catch (Exception e) {
                    Log.e(TAG, "read " + url + " : " + e.toString());
                    max_read_len = -1;
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    cacheStream.flush();
                    cacheStream.close();
                    if (max_read_len > 0 && expect_len == real_len) {
                        Log.w(TAG, "close " + url + " : max_transmission_unit = " + max_read_len +
                                ", total_byte = " + real_len + ", " + expect_len);
                        cache.commit();
                    } else {
                        Log.e(TAG, "close " + url + " : wasted_byte = " + real_len + ", expect_byte = " + expect_len);
                        cache.abort();
                    }
                } catch (Exception e) {  // 缓存写不了什么的可以下次再试，不必抛出去
                    Log.e(TAG, "close " + url + " : " + e.toString());
                }
                if (super.in != null)
                    super.close();  // 这里的异常就会抛出去
            }
        }

        @Override @SuppressWarnings("deprecation")  // 4.4用新版函数拿不到url
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Log.i(TAG, "shouldOverrideUrlLoading: " + url);
            if ((url.contains("//www.zhihu.com/video/") || url.contains("//v.vzuu.com/video/")) &&
                    url.startsWith("http")) {  // 大概是想防止阴险的访问file://吧
                loadVideoPage(view, url);
                return false;  // 可重定向，且只加一条历史记录
            }

            ContentActivity activity = ContentActivity.getReference();
            if (activity != null)
                activity.copyLink(url);
            return true;  // 本函数已处理，浏览器就不要管了
        }

        @Override @SuppressWarnings("deprecation")  // 7.0似乎只有新版函数
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            // 注意此函数不在UI线程，不能改界面，不能调getSettings()，网页返回或关掉也不一定会停
            // 还有此函数不能搞耗时操作，不然网速慢时会卡死几十秒(WebView的IO线程有限，能换页但全是白屏)
            // 进来的请求包括favicon.ico、js、图片、视频、重定向；setBlockNetworkLoads也会先进来
            // 图片都自己存一份，方便长按保存图片，没网也能知道是动图了；公式图也得存不然不能导出
            Log.i(TAG, "shouldInterceptRequest: " + url);
            if (url.endsWith(".jpg") || url.endsWith(".png") || url.endsWith(".gif") ||
                    url.endsWith(".webp") || url.contains("equation?tex=")) {  // 出现频率高者在前
                String name = getUrlHash(url);  // 公式图根据tex代码命名，出错这次就不存了(edit抛异常)
                String type = url.contains("equation?tex=") ? "svg+xml" : url.substring(url.lastIndexOf('.') + 1);
                try {
                    InputStream inStream = null;
                    DiskLruCache.Snapshot snapshot = mCache.get(name);
                    if (snapshot != null) {
                        inStream = snapshot.getInputStream(0);
                    } else if (!mCacheOnly) {
                        DiskLruCache.Editor editor = mCache.edit(name);  // 里面会flush
                        if (editor != null) {
                            try {
                                inStream = new CachedInputStream(url, editor);
                            } catch (Exception e) {
                                Log.e(TAG, "shouldInterceptRequest: " + e.toString() + ", abort");
                                editor.abort();
                            }
                        }
                    }
                    type = "image/" + (type.equals("jpg") ? "jpeg" : type);  // jpg不改jpeg也能加载
                    return new WebResourceResponse(type, "utf-8", inStream);  // 流null触发图片onError
                } catch (Exception e) {
                    Log.e(TAG, "shouldInterceptRequest: " + e.toString());
                }
            }
            if (url.endsWith("?head")) url = url.substring(0, url.lastIndexOf('?'));  // 自己的标记别传出去
            return super.shouldInterceptRequest(view, url);  // 默认返回null，WebView自己处理
        }

        @Override @RequiresApi(Build.VERSION_CODES.N)
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return shouldOverrideUrlLoading(view, request.getUrl().toString());
        }

        @Override @RequiresApi(Build.VERSION_CODES.N)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return shouldInterceptRequest(view, request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Log.i(TAG, "onPageStarted: " + url);
            super.onPageStarted(view, url, favicon);
            mLoadStartTime = System.currentTimeMillis();
        }

        @Override
        public void onPageFinished(WebView view, String url) {  // 创建(预载)/刷新/返回都触发，js下图不触发
            super.onPageFinished(view, url);
            long load_time = System.currentTimeMillis() - mLoadStartTime;
            Log.i(TAG, "onPageFinished (" + load_time + "ms): " + url);

            if (getContentMode(view) == MODE_START) {
                if (!mOnCreate || getPageIndex(view) == sEntryPage) {  // 创建预载页等切换到再下图，而创建首页/刷新/返回立刻下图
                    mLoadStartTime = -System.currentTimeMillis();  // FIXME 到LONG_MAX/2(1亿年)之后相减可造成溢出
                    mChangeCacheModeOnPageFinish = true;  // 这样才能去changeCacheMode
                    changeCacheMode(view);  // Finish后调用js才有效
                    if (sEntryPage > 0) sEntryPage = -2;  // 非创建能进来说明是换页预载，再来这一页时就不要搞特殊了
                }

                final WebView webView = view;  // 即使返回前缩回最小了，还是可能放大首页
                if (load_time < 1500) {  // 若用setLoadsImagesAutomatically下完图才触发；js初始化久，也会看一半回头
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {recallScrollPos(webView);}
                    }, 50);  // 20以下无效，30长文不行
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {recallScrollPos(webView);}
                    }, 500);  // 200图多不行；最多刷新后2s跳
                }
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (getContentMode(webView) == MODE_START)  // 2s够点开图片再放大了，不能在这缩小
                            for (int i = 0; i < 7; i++)
                                webView.zoomOut();
                    }
                }, 2000);  // 机子慢1s不行
            }
            mOnCreate = false;  // 放在使用之后啊…
        }

    }

    private static class RawImageLoader extends AsyncTask<String, String, String> {

        private int contentLength;
        private WeakReference<PageFragment> fragmentRef;

        private RawImageLoader(PageFragment fragment) {
            super();
            fragmentRef = new WeakReference<>(fragment);
        }

        private WebView getVisibleWebView() {
            PageFragment fragment = fragmentRef.get();
            if (fragment == null) return null;

            View view = fragment.getView();  // 本Fragment可见且网页在看图模式才载入新网址
            if (view == null || !fragment.getUserVisibleHint()) return null;

            return (WebView) view.findViewById(R.id.webView_section);
        }

        @Override
        protected String doInBackground(String... params) {
            HttpURLConnection conn = null;
            String url = params[0];
            String html = params[1];

            if (url.contains("?") || url.contains("_r") || !url.contains("_")) {
                publishProgress(url);  // 公式(含?)是服务器生成的矢量图，不能乱加后缀
                return null;           // 不含_或含_r说明已是原图，直接载入，不用再管
            }

            // url_后缀(边长)：均为等比例缩小，缩不了给原图，打死不会放大
            // 测试用例：https://pic3.zhimg.com/v2-6872fdd56e0a82b0ec144dd04b2de093.webp
            // s(25) is(34) xs(50) im(68) l(100) xl(200) xll(400)，按短边算比例，能缩时居中裁成正方形
            // 180x120 200x112 400x224 1200x500(专栏头图)，长边超限会裁剪(上面在不能缩时即使长边超限也不裁)
            // 60w(60) 250x0(250) qhd(480) b(600) hd(720) fhd(1080) r(原图)=无后缀，按宽度缩
            String raw_img_url = url.replaceFirst("(?:_[^_/]+)?\\.\\w+$", "");  // 弄成无后缀

            {   // 首先确认缓存有url指定的图，说明至少小图已下载完成，有得显示，才能开始
                // 然后看看缓存里有没有对应的gif/jpg大图，没有再去尝试联网
                String raw_name = raw_img_url.substring(raw_img_url.lastIndexOf('/') + 1);
                DiskLruCache.Snapshot snapshot = null;
                try {
                    if ((snapshot = mCache.get(getUrlHash(url))) != null) {  // 下载中也是null
                        publishProgress(url);
                    } else {
                        return null;
                    }

                    if ((snapshot = mCache.get(raw_name + "_r.gif")) != null) {
                        return raw_img_url + "_r.gif";
                    }
                    if ((snapshot = mCache.get(raw_name + "_r.jpg")) != null) {
                        return raw_img_url + "_r.jpg";
                    }
                } catch (IOException e) {
                    Log.w(TAG, "GetGif: doInBackground: " + e.toString());
                } finally {
                    if (snapshot != null) {
                        snapshot.close();  // 前面return也会执行这里的finally
                    }
                }
            }

            try {  // 优先判断gif，毕竟gif也可能比较大…若先判断了尺寸，则大的动图也会去下jpg
                URL url_info = new URL(raw_img_url + "_r.gif");
                conn = (HttpURLConnection) url_info.openConnection();  // 没网时抛异常
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);      // 设置超时，防止停不掉造成内存泄漏
                conn.setRequestMethod("HEAD");  // 只要response头部信息；默认GET会把剩下也要了
                //conn.setRequestProperty("Host", url_info.getHost());  // 好像不弄也行
                //conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 5.1.1; zh-cn; MI 4S Build/LMY47V)");
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    contentLength = conn.getContentLength();  // 近似大小，最后输出别太精确
                    if (conn.getContentType().equals("image/gif"))  // 非动图时是jpeg/png/webp
                        return raw_img_url + "_r.gif";
                }
            } catch (Exception e) {
                Log.e(TAG, "GetGif: doInBackground: " + e.toString());
            } finally {
                if (conn != null)
                    conn.disconnect();  // keepAlive可复用，disconnect放回连接池
            }

            {   // 若之前没有return，也可看有没有大图(断网别取消，不然本来是小图的都看不了)
                // x-oss-meta-width/height不论请求s/xl都是原图尺寸，但只有专栏的图片才有此属性
                // 当前WebViewer的html中img标签内的data-rawwidth(没有的一般够大或是公式/视频图)
                // 提取网址中的文件名以加速匹配，然后找出此文件名所在<img>标签，再在其中找宽高
                // 标签内没有所需属性时，截出的字串一般就是链接，直接转换会抛异常，耗时x10
                int sub_start, sub_end;
                String hash = url.substring(url.lastIndexOf('/') + 1);
                if (hash.contains(".")) hash = hash.substring(0, hash.lastIndexOf('.'));
                if (hash.contains("_")) hash = hash.substring(0, hash.lastIndexOf('_'));
                sub_start = html.lastIndexOf('<', html.indexOf(hash)) + 1;
                sub_end = html.indexOf('>', sub_start);
                String img = html.substring(sub_start, sub_end);

                sub_start = img.indexOf('"', img.indexOf("data-rawwidth")) + 1;
                sub_end = img.indexOf('"', sub_start);
                int width = sub_start < 1 ? 0 : Integer.parseInt(img.substring(sub_start, sub_end));
                Log.w(TAG, "image raw width = " + width);
                if (width >= 800)  // 只比600宽一点的原图就不用下了；压缩不看高度
                    return raw_img_url + "_r.jpg";
            }

            return null;  // 前面没return就来到这，如小图就不必重定向
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values == null || values.length == 0) return;
            loadImagePage(getVisibleWebView(), values[0]);
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            if (s == null) return;

            loadImagePage(getVisibleWebView(), s);
            ContentActivity activity = ContentActivity.getReference();
            if (activity != null) {  // 0.1M以上的才显示文件大小
                String extra = contentLength > 1.1e5 ? ((contentLength * 10 >> 20) * .1f) + "M的" : "";
                String msg = s.substring(s.lastIndexOf('.') + 1).toUpperCase().equals("GIF") ?
                        "其实是我" + extra + "GIF哒" :
                        "其实我还有" + extra + "大图哒";
                Toast.makeText(activity, msg, extra.isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
        }

    }


    public static PageFragment newInstance(int position, String link, String content) {
        PageFragment fragment = new PageFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_POS, position);
        args.putInt(ARG_SCROLL, 0);
        args.putString(ARG_LINK, link);
        args.putString(ARG_CONTENT, content);
        fragment.setArguments(args);  // 用参数传递数据，而不是所有实例共用的静态成员
        return fragment;
    }

    public static String getUrlHash(String url) {
        if (url.contains("equation?tex=")) {
            try {
                if (url.contains("equation?tex=")) {  // 公式图根据tex代码命名，出错这次就不存了
                    MessageDigest md = MessageDigest.getInstance("MD5");  // MD5(32位16进制)/SHA-1(40位)
                    md.update(url.substring(url.lastIndexOf('=') + 1).getBytes());  // 安卓默认UTF-8
                    StringBuilder sb = new StringBuilder(39);  // url里的tex中/=已转义，.没转
                    for (byte b : md.digest()) {  // %02x；& 0xff使负数byte转int时前面是0，而非符号位
                        sb.append((b & 0xff) < 16 ? "0" : "").append(Integer.toHexString(b & 0xff));
                    }
                    return sb.insert(0, "eq-").append(".svg").toString();
                }
            } catch (Exception e) {
                Log.e(TAG, "getUrlHash: " + e.toString());
                return null;
            }
        }
        return url.substring(url.lastIndexOf('/') + 1);
    }

    public static File getContentCache(WebView webView) {
        if (webView != null && getContentMode(webView) == MODE_IMAGE) {
            String url = (String) webView.getTag(R.id.web_tag_url);
            String file_name = getUrlHash(url);
            DiskLruCache.Snapshot snapshot = null;
            try {
                if ((snapshot = mCache.get(file_name)) != null) {
                    return new File(mCache.getDirectory(), file_name + ".0");
                }
            } catch (IOException e) {
                Log.e(TAG, "getContentCache: " + e.toString());
            } finally {
                if (snapshot != null) {
                    snapshot.close();
                }
            }
        }
        return null;
    }

    public static void setBackColor(int color) {
        sBackColor = color;
    }

    public static void setEntryPage(int page) {
        sEntryPage = page;
    }

    public static void openCache(Context ctx) {
        if (mCache == null || mCache.isClosed()) {
            try {
                int ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
                mCache = DiskLruCache.open(ctx.getExternalCacheDir(), ver, 1, 98 << 20);  // 98M
            } catch (Exception e) {
                Log.e(TAG, "openCache: " + e.toString());
            }
        }
    }

    public static void closeCache() {
        if (mCache != null) {
            try {
                mCache.flush();  // 不然下载了啥都没记录
                mCache.close();
            } catch (Exception e) {
                Log.e(TAG, "closeCache: " + e.toString());
            }
            mCache = null;
        }
    }

    public static boolean isNightTheme() {
        return sNightTheme;
    }

    public static void setNightTheme(boolean nightTheme) {
        sNightTheme = nightTheme;
    }

    public static void toggleNightTheme() {
        sNightTheme = !sNightTheme;

        Activity activity;
        if ((activity = ContentActivity.getReference()) != null) {
            ((ContentActivity) activity).toggleNightTheme();
        }
        if ((activity = MainActivity.getReference()) != null) {
            ((MainActivity) activity).toggleNightTheme();
        }
    }

    public static int getTextZoom() {
        return sTextZoom;
    }

    public static void setTextZoom(WebView webView, int zoom) {
        sTextZoom = zoom;
        if (webView != null) {
            webView.getSettings().setTextZoom(zoom);
        }
    }

    public static int getPageIndex(WebView webView) {
        if (webView != null)
            return (int) webView.getTag(R.id.web_tag_index);
        return -1;
    }

    public static int getContentMode(WebView webView) {
        if (webView != null)
            return (int) webView.getTag(R.id.web_tag_mode);
        return 0;
    }

    public static void setContentMode(WebView webView, int mode) {
        if (webView != null)
            webView.setTag(R.id.web_tag_mode, mode);
    }

    public static int getScrollPos(WebView webView) {
        if (webView != null) {
//            View scroller = (View) webView.getParent();
//            return scroller.getScrollY();
            return webView.getScrollY();
        }
        return 0;
    }

    public static void setScrollPos(WebView webView, int y, boolean animate) {
        if (webView != null) {
            if (animate) {  // 先模拟一次点击停止惯性滚动，并且要注意别点开图片
                long eventTime = SystemClock.uptimeMillis();  // obtain注释说必须用这个时间
                MotionEvent downEvent = MotionEvent.obtain(eventTime, eventTime + 10,
                        MotionEvent.ACTION_DOWN, 0, 0, 0);
                webView.dispatchTouchEvent(downEvent);  // 能进到onTouch里停止程序惯性滚动
                downEvent.recycle();  // 手动的惯性滚动是靠这里的模拟点击停掉的

                MotionEvent upEvent = MotionEvent.obtain(eventTime, eventTime + 15,
                        MotionEvent.ACTION_UP, 0, -100, 0);  // 实际测量间隔是0-3ms
                webView.dispatchTouchEvent(upEvent);  // 模拟滑出界防止认成点击，也不能弄成横滑不然会换页
                upEvent.recycle();

                // 停下来就可以安心动画了
                ObjectAnimator anim = ObjectAnimator.ofInt(webView, "scrollY", webView.getScrollY(), 0);
                anim.start();  // 默认300ms，修改用setDuration()
            } else {
                webView.setScrollY(y);
            }
        }
    }

    public static void storeScrollPos(WebView webView) {
        if (webView != null && getContentMode(webView) <= MODE_START)  // 图片/视频/空白页就别改
            webView.setTag(R.id.web_tag_scroll, getScrollPos(webView));
    }  // 下面有仨都调用了

    public static void recallScrollPos(WebView webView) {
        if (webView != null) {
            int pos = (int) webView.getTag(R.id.web_tag_scroll);
            if (getContentMode(webView) == MODE_START && pos > 0)
                setScrollPos(webView, pos, false);
        }
    }


    public static void loadStartPage(WebView webView, String url) {
        if (webView == null || TextUtils.isEmpty(url)) {
            return;  // 空地址当然就直接忽略，保持原状；除非再写个loadBlankPage
        }

        if (webView.zoomOut()) {
            for (int i = 0; i < 7; i++)
                webView.zoomOut();  // 缩回最小，不然从放大的图片返回点击位置有偏差
        }

        PageFragment fragment = (PageFragment) webView.getTag(R.id.web_tag_fragment);
        if (fragment != null) {  // 要改浏览器所在的Fragment的参数
            fragment.mCacheOnly = true;  // 先禁止联网，没缓存的会由js替换为占位图
            fragment.mChangeCacheModeOnPageFinish = false;  // PageFinish会设为true
            webView.removeCallbacks(fragment.mPendingModeChange);  // 防止多次快速刷新造成流量自动下图
        }

        storeScrollPos(webView);
        setContentMode(webView, MODE_START);
        webView.setTag(R.id.web_tag_url, url);
        WebSettings settings = webView.getSettings();
        settings.setTextZoom(sTextZoom);  // 不然等划完了才缩放不好看
        settings.setBuiltInZoomControls(false);  // 禁止手动缩放
        settings.setJavaScriptEnabled(true);  // 夜间模式和懒加载还是得用
        String html = (String) webView.getTag(R.id.web_tag_in_html);
        webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", null);
        // 点击外链/公式图链接时，baseUrl不完整不触发shouldOverrideUrlLoading，至少要http://www.zhihu.com
        //webView.clearHistory();  // load完成后此网址才进历史记录，所以load下一句写clear没用
    }

    public static void loadImagePage(WebView webView, String url) {
        if (webView == null || TextUtils.isEmpty(url)) {
            return;
        }

        PageFragment fragment = (PageFragment) webView.getTag(R.id.web_tag_fragment);
        if (fragment != null) {  // 要改浏览器所在的Fragment的参数
            fragment.mCacheOnly = false;  // 立即允许连网，不然不能下原图
            webView.removeCallbacks(fragment.mPendingModeChange);
        }

        storeScrollPos(webView);  // 里面有判断，图片刷新时不改
        setContentMode(webView, MODE_IMAGE);
        String color = "rgba(128,128,128,0.1)";  // 同<pre>标签背景，注意不要"background:"和";"
        String html = "<html><body style=\"margin:0\">" +
//                          "<img src=\"" + url + "\" width=\"100%\" " +
//                          "style=\"position:absolute; display:block; " +
//                          "top:50%; left:50%; transform:translate(-50%,-50%);\">" +
//                          // 上下这两种显示超过屏幕高度的图时，显示不了中间以上的内容
//                          "<img src=\"" + url + "\" width=\"100%\" " +
//                          "style=\"position:absolute; display:block; " +
//                          "top:0; left:0; right:0; bottom:0; margin:auto;\">" +
                      "<div style=\"background:" + color + " url(" + url + ") no-repeat center center;" +
                      "background-size:contain; width:100%; height:100%\" />" +
                      "</body></html>";

        WebSettings settings = webView.getSettings();
        settings.setSupportZoom(true);          // 默认开
        settings.setBuiltInZoomControls(true);  // 默认关
        settings.setDisplayZoomControls(false); // 开上面默认开
        settings.setJavaScriptEnabled(false);   // 图片不给用
        webView.setTag(R.id.web_tag_url, url);
        webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", null);

        ContentActivity activity = ContentActivity.getReference();
        if (activity != null) {
            activity.setToolBarExpanded(false);
            activity.closeSearchView();
        }
    }

    public static void loadVideoPage(WebView webView, String url) {
        if (webView == null || TextUtils.isEmpty(url)) {
            return;
        }

        PageFragment fragment = (PageFragment) webView.getTag(R.id.web_tag_fragment);
        if (fragment != null) {  // 要改浏览器所在的Fragment的参数
            fragment.mCacheOnly = false;  // 立即允许连网，不然不能下封面
            webView.removeCallbacks(fragment.mPendingModeChange);
        }

        // 要载入网页需要此后自己load(url)
        storeScrollPos(webView);  // 里面有判断，重定向时不改
        setContentMode(webView, MODE_VIDEO);
        webView.setTag(R.id.web_tag_url, url);
        webView.getSettings().setJavaScriptEnabled(true);

        ContentActivity activity = ContentActivity.getReference();
        if (activity != null) {
            activity.setToolBarExpanded(false);
            activity.closeSearchView();
        }
    }

    public static void changeCacheMode(final WebView webView) {
        if (webView != null && getContentMode(webView) == MODE_START) {
            final PageFragment fragment = (PageFragment) webView.getTag(R.id.web_tag_fragment);
            if (fragment == null) return;  // 快速换页时会出现webView还在fragment已空

            if (fragment.mPendingModeChange == null) {
                fragment.mPendingModeChange = new Runnable() {
                    @Override
                    public void run() {
                        if (getContentMode(webView) == MODE_START) {
                            fragment.mCacheOnly = false;  // 改了这个不管流量或wifi都会下载，不改则点击图片不能下载
                            if (ConnectivityState.isWifi) {  // 不然开wifi后页面首屏内有图也不加载
                                Log.w(TAG, "changeCacheMode: enable_load " + webView.getTag(R.id.web_tag_url));
                                webView.loadUrl("javascript:lazy_load()");  // PageFinish后才能调js
                                webView.loadUrl("javascript:enable_load()");
                            } else {
                                Log.w(TAG, "changeCacheMode: disable_load " + webView.getTag(R.id.web_tag_url));
                                webView.loadUrl("javascript:disable_load()");
                            }
                        }
                    }
                };
            }

            // 载入(创建/刷新/返回)完成、换页/开关Wifi都进来，但从[开始载入]到[载入结束后2s]之间不能修改
            // ！注意频繁刷新和来回换页不要让CacheOnly正常修改后被未取消的postDelayed改回去，造成用流量下完剩余所有图！
            if (fragment.mChangeCacheModeOnPageFinish) {
                fragment.mChangeCacheModeOnPageFinish = false;  // js初始化完就PageFinish，但js改的图片src要等过后才加载
                webView.postDelayed(fragment.mPendingModeChange, 2000);  // 一般1s够 TODO 2s加载不完缓存图剩余可能用流量下
            } else if (System.currentTimeMillis() - -fragment.mLoadStartTime > 2000) {  // time是正数时(载入中)不能进来
                webView.post(fragment.mPendingModeChange);
            }  // else即载入期间的调用，直接忽略
        }
    }

    private void resumePageLoad() {  // 换页时的目标页调用；换页时隐藏的页和点刷新/返回时不会
        View view = getView();       // 得到onCreateView创建的rootView
        if (view != null) {          // Activity刚启动时就是null
            final WebView webView = (WebView) view.findViewById(R.id.webView_section);
            webView.clearMatches();  // 清空搜索结果，关闭复制控件

            // 快速换页可能看一眼就destroy，也使有wifi时不立即联网
            webView.postDelayed(new Runnable() {
                @Override
                public void run() {changeCacheMode(webView);}
            }, 1000);

            ContentActivity activity = ContentActivity.getReference();
            if (activity != null && webView.getScrollY() > 0)
                activity.setToolBarExpanded(false);
        }
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
//        Log.w(TAG, "onCreateView " + getArguments().getInt(ARG_POS));
        int pos = getArguments().getInt(ARG_POS);
        int scroll = getArguments().getInt(ARG_SCROLL);
        String link = getArguments().getString(ARG_LINK);
        String content = getArguments().getString(ARG_CONTENT);
        View rootView = inflater.inflate(R.layout.fragment_content, container, false);
        WebView webView = (WebView) rootView.findViewById(R.id.webView_section);
        setContentMode(webView, MODE_BLANK);  // 先把标签都初始化，免得之后取不到抛异常
        webView.setTag(R.id.web_tag_index, pos);
        webView.setTag(R.id.web_tag_scroll, scroll);  // MODE不是START，下面loadPage不改滚动位置
        webView.setTag(R.id.web_tag_in_html, content); // 一直是首页的源码
        webView.setTag(R.id.web_tag_fragment, this);
        webView.setWebViewClient(new CachedWebViewClient());
        webView.setOnTouchListener(new OnTouchListener());
        webView.setBackgroundColor(sBackColor);  // 防夜间快速换页时闪过白色，xml里WebView的background没用
//        WebSettings settings = webView.getSettings();
//        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);  // 视频的js就要新的吧
//        settings.setLoadsImagesAutomatically(false);  // 只加载页面文字，等切换到再图片
//        //setBlockNetworkLoads还会尝试加载图片，然后请求被此拦截返回一个不能下载的图…不用js就改不了了

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
//            WebView.setWebContentsDebuggingEnabled(true);  // 可在桌面Chrome调试
//        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        String saved_link = null;
        if (savedInstanceState != null) {  // 转屏或清内存重入时恢复页面
            saved_link = savedInstanceState.getString(ARG_LINK, "");
            if (saved_link.contains("zhimg.com/")) {
                loadImagePage(webView, saved_link);

            } else if (saved_link.contains("vzuu.com/video/")) {
                loadVideoPage(webView, saved_link);
                webView.loadUrl(saved_link);

            } else {
                saved_link = null;  // 没用上
            }
        }
        if (saved_link == null) {
            loadStartPage(webView, link);  // 里面会限制连网；若进来是图片/视频页则不会限网
        }
        mOnCreate = true;
        return rootView;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
//        Log.w(TAG, "onSaveInstanceState " + getArguments().getInt(ARG_POS));
        super.onSaveInstanceState(outState);
        View view = getView();
        if (view != null) {  // outState和getArguments重启都会帮忙保存，WebView的Tag不会
            View webView = view.findViewById(R.id.webView_section);
            storeScrollPos((WebView) webView);  // 若在首页就会更新滚动位置
            getArguments().putString(ARG_CONTENT, (String) webView.getTag(R.id.web_tag_in_html));
            getArguments().putInt(ARG_SCROLL, (int) webView.getTag(R.id.web_tag_scroll));
            outState.putString(ARG_LINK, (String) webView.getTag(R.id.web_tag_url));
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
//        Log.w(TAG, "setUserVisibleHint #" + getArguments().getInt(ARG_POS) + " " + isVisibleToUser);
        super.setUserVisibleHint(isVisibleToUser);  // 划走的页也会调用(VisibleToUser为false)
        if (isVisibleToUser) {  // CurrentItem那页触发时它在Activity中还没生成
            resumePageLoad();
        }
    }

    @Override
    public void onDestroyView() {
//        Log.w(TAG, "onDestroyView " + getArguments().getInt(ARG_POS));
        View view = getView();  // 等到onDestroy时getView已是null
        if (view != null) {  // 5.1要在webView调用自身destroy之前解绑并手动destroy防内存泄漏
            final WebView webView = (WebView) view.findViewById(R.id.webView_section);
            if (webView.getParent() != null) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            setContentMode(webView, MODE_BLANK);  // 免得destroy后还去changeCacheMode
            webView.removeCallbacks(mPendingModeChange);
            webView.setTag(R.id.web_tag_fragment, null);  // 其实循环引用也能回收(没被gc_root引用就能收)
            mPendingModeChange = null;
            webView.loadUrl("about:blank");  // 放视频时转屏还有声音(退程序没事)，当然现在转屏不重启了
            webView.destroy();
        }
        super.onDestroyView();
    }

}
