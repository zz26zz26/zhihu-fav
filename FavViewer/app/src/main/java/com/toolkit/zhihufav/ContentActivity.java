package com.toolkit.zhihufav;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.SearchView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.toolkit.zhihufav.util.ConnectivityState;
import com.toolkit.zhihufav.util.SQLiteHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.util.List;


public class ContentActivity extends AppCompatActivity {

    private static final String TAG = "ContentActivity";

    private Intent mResult;
    private MenuItem mSearchItem;
    private ViewPager mViewPager;
    private ViewPagerAdapter mAdapter;
    private SharedPreferences mPreferences;
    private ConnectivityState mNetStateReceiver;
    private static WeakReference<Context> sReference;

    private Toolbar mToolBar;
    private ImageView mImageView;
    private Runnable mTitleImageTask;
    private AppBarLayout mAppBarLayout;
    private CollapsingToolbarLayout mToolbarLayout;
    private int mMaskAlpha;
    private int mTitleState;  // state有限状态 status偏向连续
    private boolean mTitleDoingExpand, mTitleDoingCollapse;

    static final int TITLE_COLLAPSED = -1;
    static final int TITLE_INTERMEDIATE = 0;
    static final int TITLE_EXPANDED = 1;

    private class OnPageChangeListener extends ViewPager.SimpleOnPageChangeListener {
        @Override
        public void onPageSelected(int position) {
            // 在setCurrentItemInternal/scrollToItem里，先调用smoothScrollTo/populate/setPrimaryItem
            // 再dispatchOnPageSelected/onPageSelected，但先调用的等动画完成才执行setPrimary，一般会慢
            // 然把页面滑动到位才松开，会立即执行setPrimary！与WebView有关的应放去setUserVisibleHint
            mResult.putExtra("position", position);      // result直接用的传入Intent
            mAdapter.setQuery(null, "", true);           // 重置查找状态
            mImageView.post(mTitleImageTask);            // 开始尝试设置标题图片
            setTitle(mAdapter.getPageTitle(position));   // position是滑动终点的索引(CurrentView是起点)
            if (position >= mAdapter.getCount() - mViewPager.getOffscreenPageLimit() - 1) {
                mAdapter.addSomeAsync(5);  // 要预载入的页得在开始预载的前一页就准备好
            }  // 提前页数不能比main的多，不然点到ListView不预载的最后一项时，刚开窗口就addSome，来不及notify抛异常
        }
    }

    public static ContentActivity getReference() {
        return sReference == null ? null : (ContentActivity) sReference.get();
    }


    private WebView getCurrentWebView() {
        // 不能用mViewPager.getChildAt(0)，其索引与实际顺序无关！
        Fragment fragment = mAdapter.getCurrentPage();
        View view = (fragment != null) ? fragment.getView() : null;
        return (view != null) ? (WebView) view.findViewById(R.id.webView_section) : null;
    }

    private int getCurrentWebViewScrollPos() {
        return PageFragment.getScrollPos(getCurrentWebView());
    }

    private int getCurrentWebViewMode() {
        return PageFragment.getContentMode(getCurrentWebView());
    }

    private void reloadCurrentWebView(int target_mode) {
        mAdapter.reloadToMode(getCurrentWebView(), target_mode);
    }

    private boolean goBackCurrentWebView() {
        WebView webView = getCurrentWebView();
        if (webView != null) {  // 用goBack不行，因为除了视频页都不是载入网址而是数据
            if (getCurrentWebViewMode() < PageFragment.MODE_START) {
                PageFragment.setContentMode(getCurrentWebView(), PageFragment.MODE_START);
                setTitleVisible(true);  // 复制控件用的返回键这里拦不到，只是为了保证能退出复制模式
                return true;
            } else if (getCurrentWebViewMode() > PageFragment.MODE_START) {
                reloadCurrentWebView(PageFragment.MODE_START);
                return true;
            } // else == MODE_START
        }
        return false;  // false表示没有处理，给外层
    }

    private int getStatusBarHeight() {
        int result = 0;  // @android:dimen/status_bar_height是系统私有属性
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    private void hideInputMethod() {
        // android.R.id.content gives the root view of current activity
        InputMethodManager manager = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(findViewById(android.R.id.content).getWindowToken(), 0);
    }

    private void onDisplayOptionsMenu(Menu menu) {
        int mode = getCurrentWebViewMode();
        MenuItem menuItem;  // 此函数每次点开都调用，若add则加入的项不会清除
        menuItem = menu.findItem(R.id.content_menu_entry); // 伪装的入口当然不要显示
        menuItem.setVisible(false);
        menuItem = menu.findItem(R.id.content_refresh);    // 刷新是什么时候都能显示
        menuItem.setVisible(true);
        menuItem = menu.findItem(R.id.content_copy_link);  // 链接也什么时候都能复制
        menuItem.setVisible(true);
        menuItem = menu.findItem(R.id.content_search);     // 图片视频模式不给找文本
        menuItem.setVisible(mode == PageFragment.MODE_START);
        menuItem = menu.findItem(R.id.content_text_size);  // 图片视频模式不给调字体
        menuItem.setVisible(mode == PageFragment.MODE_START);
        menuItem = menu.findItem(R.id.content_open_with);  // 图片视频模式不给乱打开
        menuItem.setVisible(mode == PageFragment.MODE_START);
        menuItem = menu.findItem(R.id.content_save);       // 首页视频模式不给乱保存
        menuItem.setVisible(mode == PageFragment.MODE_IMAGE);
        menuItem = menu.findItem(R.id.content_rich_data);  // 流量模式文本按情况改变
        menuItem.setVisible(mode == PageFragment.MODE_START);
        menuItem.setTitle(PageFragment.isRichGuy() ? R.string.poor_data : R.string.rich_data);
        menuItem = menu.findItem(R.id.content_night_mode); // 夜间模式文本按情况改变
        menuItem.setTitle(PageFragment.isNightTheme() ? R.string.stop_night_mode : R.string.night_mode);
        menuItem.setVisible(true);                         // 而且也是什么时候都显示
    }

    private void showOverflowMenu() {
        //mToolBar.showOverflowMenu();

        // 第一个参数Context要用this而不能是getApplicationContext()，否则theme不对
        final PopupMenu popupMenu = new PopupMenu(this, findViewById(R.id.content_menu_entry));
        popupMenu.inflate(R.menu.menu_content);
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                popupMenu.dismiss();
                if (item.getItemId() == R.id.content_search) {
                    item = mSearchItem;  // PopupMenu的item不带SearchView
                    MenuItemCompat.expandActionView(mSearchItem);
                }
                return onOptionsItemSelected(item);
            }
        });
        onDisplayOptionsMenu(popupMenu.getMenu());
        //popupMenu.show();  // 此菜单右边距不准确，且点击菜单项时没有动画效果(虽然系统的也没有)

        // 为了解决菜单样式问题用自由度更大的PopupWindow再包裹一层…
        final View popupView = getLayoutInflater().inflate(R.layout.popup_overflow, null);
        final PopupWindow popupWindow = new PopupWindow(popupView, 0, 0, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable());
        popupWindow.setAnimationStyle(R.style.popup_anim_alpha);
        // 动态添加上面可见的菜单项，并绑定点击事件
        ViewGroup container = (ViewGroup) ((ViewGroup) popupView).getChildAt(0);
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                popupWindow.dismiss();
                popupMenu.getMenu().performIdentifierAction(v.getId(), 0);  // 调用上面的菜单项方法
            }
        };
        for (int i = 0, n = popupMenu.getMenu().size(); i < n; i++) {
            MenuItem item = popupMenu.getMenu().getItem(i);
            if (item.isVisible()) {
                Button button = (Button) getLayoutInflater().inflate(R.layout.overflow_item, null);
                button.setId(item.getItemId());  // 最后一个参数不是null会自动给加一层LinearLayout
                button.setText(item.getTitle());
                button.setOnClickListener(listener);
                container.addView(button);
            }
        }
        popupWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        popupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);  // find不到时抛异常
        popupWindow.showAtLocation(findViewById(R.id.content_menu_entry), Gravity.TOP | Gravity.END, 0, 0);
    }


    public void closeSearchView() {
        if (mSearchItem != null) {
            MenuItemCompat.collapseActionView(mSearchItem);
        }
    }

    public void copyLink(String url) {
        if (url != null && !url.isEmpty()) {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = ClipData.newPlainText("Link", url);
            clip.setPrimaryClip(data);
            if (url.startsWith("http")) url = url.substring(url.indexOf("://") + 3);
            if (url.startsWith("link.zhihu.com")) url = url.substring(url.indexOf("%3A//") + 5);
            Toast.makeText(this, getString(R.string.link_copied, url), Toast.LENGTH_SHORT).show();
        }
    }

    public void toggleNightTheme() {
        boolean night = PageFragment.isNightTheme();

        if (night) setTheme(R.style.AppTheme_Night);
        else setTheme(R.style.AppTheme_NoActionBar);
        int toolbar_text_color_id = night ? android.R.attr.textColorPrimary :
                                            android.R.attr.textColorPrimaryInverse;
        try {
            int color;
            Resources.Theme theme = getTheme();
            Resources resources = getResources();
            TypedValue typedValue = new TypedValue();
            theme.resolveAttribute(R.attr.colorPrimary, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mToolbarLayout.setContentScrimColor(color);
            mToolbarLayout.setBackgroundColor(color);
            mAppBarLayout.setBackgroundColor(color);  // 4.4的透明状态栏靠这个改颜色
            mImageView.setDrawingCacheBackgroundColor(color);
            theme.resolveAttribute(toolbar_text_color_id, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            ((TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout)).setTextColor(color);
            //mToolbarLayout.setCollapsedTitleTextColor(color);  // 夜间重开又回到纯白了
            theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true);  // 只改这个状态栏不变
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mToolbarLayout.setStatusBarScrimColor(color);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {  // 4.4只能设为透明
                theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true);
                getWindow().setStatusBarColor(resources.getColor(typedValue.resourceId));
            }

            theme.resolveAttribute(R.attr.toolbar_mask_alpha, typedValue, true);
            resources.getValue(typedValue.resourceId, typedValue, true);
            mMaskAlpha = (int) (typedValue.getFloat() * 255);
            mImageView.setColorFilter(Color.argb(mMaskAlpha, 0, 0, 0));
            updateTitleBackground();  // 不然纯色时不变

            theme.resolveAttribute(android.R.attr.itemBackground, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            PageFragment.setBackColor(color);  // 此在新建网页时使用
            for (int i = 0, n = mViewPager.getChildCount(); i < n; i++) {
                WebView webView = (WebView) mViewPager.getChildAt(i).findViewById(R.id.webView_section);
                if (webView != null) {
                    webView.setBackgroundColor(color);
                    webView.setTag(R.id.web_tag_in_html, mAdapter.getPageContent(PageFragment.getPageIndex(webView)));
                    if (PageFragment.getContentMode(webView) <= PageFragment.MODE_START) {
                        webView.loadUrl("javascript:set_theme(" + night + ")");  // 图片/视频页没字不用
                    }
                }
            }
//            // 相当于屏幕截图，但是这样动图/视频就卡了
//            view.setDrawingCacheEnabled(true);
//            final Bitmap localBitmap = Bitmap.createBitmap(view.getDrawingCache());
//            view.setDrawingCacheEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "toggleNightTheme: " + e.toString());
        }
    }

    public int getTitleState() {
        return mTitleState;
    }

    public int getTitleHeight() {
        return mAppBarLayout.getTotalScrollRange();
    }

    public void setTitleDoingExpand(boolean expand) {
        mTitleDoingExpand = expand;
        mTitleDoingCollapse = !expand;
    }

    public void setTitleExpanded(boolean expand) {
        setTitleDoingExpand(expand);  // 强制给动画留出时间
        mAppBarLayout.setExpanded(expand);
    }

    public void setTitleVisible(boolean visible) {
        final AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) mToolbarLayout.getLayoutParams();
        final int toggleFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED;  // scroll取消是一直展开
        if (visible) {
            mToolbarLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    params.setScrollFlags(params.getScrollFlags() | toggleFlags); // 触发AppBar的OffsetChanged
                }
            }, 300);  // 等自带控件消失后填补空位
            mToolbarLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setTitle(getTitle());
                    mToolbarLayout.animate().alpha(1).start();
                }
            }, 500);  // 等动画完成改标题才能居中
        } else {
            params.setScrollFlags(params.getScrollFlags() & ~toggleFlags);  // 取消属性隐藏后剩个空的AppBar
            mToolbarLayout.animate().alpha(0).start();  // 改Toolbar还是没用
        }
    }

    public void setTitleImage(Bitmap bm, ObjectAnimator anim) {
        if (mMaskAlpha == 0) {  // 没改过；xml只能设置整体透明度，正好借来解析主题属性
            mMaskAlpha = (int) (mImageView.getAlpha() * 255);
            mImageView.setAlpha(0.0f);  // setAlpha(float)是整体透明度；setAlpha(int)是图片的
            mImageView.setColorFilter(Color.argb(mMaskAlpha, 0, 0, 0));  // 对透明/null不叠加(4.4好像会叠)
            mImageView.setDrawingCacheBackgroundColor(((ColorDrawable) mAppBarLayout.getBackground()).getColor());
            anim.setDuration(450);  // 大于ViewPager.smoothScrollTo时长，不然可见卡顿
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {updateTitleBackground();}
            });
        }

        Log.w(TAG, "setTitleImage: animation to begin.");
        // 直接把上一张图设为背景不行，因为背景不能进行crop_center的缩放
        // TransitionDrawable效果不好，因为ImageView会按其中一张图进行统一拉伸，也不便停止动画
        if (anim.isRunning()) anim.cancel();  // cancel保持当前状态，end改成结束状态，都触发onAnimationEnd
        mImageView.animate().cancel();  // 这个动画停止后再cancel不会先调用onAnimationStart；但上面的会

        anim.start();  // 无图也动，为了结束后触发onAnimationEnd清背景
        mImageView.animate().alpha(bm == null ? 0.0f : 1.0f).start();

        // 不要动画时，只写这两句即可
        mImageView.setTag(bm);
        mImageView.setImageBitmap(bm);

        float shadow_radius = (bm == null) ? 0f : 20f;  // 有图时给文字加阴影；半径超过25闪退
        ((TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout)).
                setShadowLayer(shadow_radius, 0, 0, Color.DKGRAY);
    }


    private void updateTitleBackground() {
        Bitmap cache = null;
        Drawable background = mImageView.getBackground();  // 位图占内存，背景没东西就不截图
        if (mImageView.getTag() != null || mImageView.getAlpha() > 0) {  // 变透明过程中算有东西
            mImageView.buildDrawingCache();  // 此前不能recycle图片或背景，有图时draw最耗时，只能在UI线程
            cache = Bitmap.createBitmap(mImageView.getDrawingCache());  // 内存中复制一份
            mImageView.destroyDrawingCache();  // recycle本次build的bitmap，不然下次还是这张图
        }
        if (background instanceof BitmapDrawable)  // null进不来；getBitmap保证非空
            ((BitmapDrawable) background).getBitmap().recycle();  // 改背景重置透明度
        mImageView.setBackground(cache == null ? null : new BitmapDrawable(getResources(), cache));
    }

    private void setTitleListener() {
        // 只应在onCreate时，已初始化完毕这些变量后调用！
        mAppBarLayout.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
            @Override
            public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
//                Log.w(TAG, "offsetChanged: " + verticalOffset + ", scrollY = " + getCurrentWebViewScrollPos());
                int collapseOffset = mAppBarLayout.getTotalScrollRange();
                if (verticalOffset >= 0)
                    mTitleState = TITLE_EXPANDED;   // 完全展开时offset为0
                else if (verticalOffset <= -collapseOffset)
                    mTitleState = TITLE_COLLAPSED;  // 完全折叠为-1*高度(隐藏时更负)
                else
                    mTitleState = TITLE_INTERMEDIATE;

                // 不允许展开时要阻止手指在标题栏下拉(展开一点再阻止不然会死循环)
                boolean forceAnimate = mTitleDoingExpand || mTitleDoingCollapse;
                if (mTitleState != TITLE_COLLAPSED && !forceAnimate) {
                    if (getCurrentWebViewMode() != PageFragment.MODE_START)
                        mAppBarLayout.setExpanded(false, false);  // 有动画会死循环，下面也是
                    if (getCurrentWebViewScrollPos() > 10)  // >0易在滚动微小不同步时突然折叠
                        mAppBarLayout.setExpanded(false, false);  // 查找时也不行(高亮在屏幕底看不到)
                }
                if (mTitleState == TITLE_EXPANDED)
                    mTitleDoingExpand = false;  // 展开动画完成再重置
                if (mTitleState == TITLE_COLLAPSED)
                    mTitleDoingCollapse = false;  // 不要一起重置：开始时一般恰满足对面条件，重置就没了

                // 自带渐变有延时，自己加一层文本透明度渐变，在折叠一半多一点就全透明
                int alpha = (int) (255 * (1 + 1.5f * verticalOffset / collapseOffset));  // verticalOffset是负数
                if (alpha < 0) alpha = 0;
                TextView textView = (TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout);
                textView.setTextColor((alpha << 24) + (textView.getCurrentTextColor() & 0xFFFFFF));
            }
        });

        // 标题栏折叠时的点击，查找文本时点查找下一个按钮偏下时会误触发
        mToolBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getCurrentWebViewMode() == PageFragment.MODE_START &&
                        !MenuItemCompat.isActionViewExpanded(mSearchItem)) {
                    WebView webView = getCurrentWebView();
                    if (getCurrentWebViewScrollPos() > 10) {
                        PageFragment.setScrollPos(webView, 0, true);
                        mToolBar.postDelayed(new Runnable() {
                            @Override
                            public void run() {setTitleExpanded(true);}
                        }, 200);
                        // 等上滚动画快结束再展开；注意getScrollY不会立即变0，直接去展开会被阻止
                    } else if (mTitleState != TITLE_EXPANDED) {
                        setTitleExpanded(true);  // 网页已到顶不用上滚动画
                    } else {
                        String link = mAdapter.getPageTitleImageLink(PageFragment.getPageIndex(webView));
                        if (link != null && webView != null) {  // 有专栏头图才去下
                            webView.loadUrl("javascript:load_first()");
                            PageFragment.performClickImage(webView, link);
                            mImageView.removeCallbacks(mTitleImageTask);  // 有delayed就要防止按太快
                            mImageView.postDelayed(mTitleImageTask, 1000);  // 下载要时间
                        }
                    }
                }
            }
        });

        // 标题栏展开时的点击查找同问题下的回答
        mToolbarLayout.setOnClickListener(new View.OnClickListener() {
            private Toast toast = null;
            private long lastClickTime = 0;
            private int fastClickCount = 0;
            private CheckForClicks pendingCheckForClicks = new CheckForClicks();
            class CheckForClicks implements Runnable {
                @Override
                public void run() {
                    performQuery(fastClickCount);
                    fastClickCount = 0;
                }
            }

            private void performQuery(int clicks) {
                int pos = mViewPager.getCurrentItem();
                String key, field, message;
                switch (clicks) {
                    case 1:
                        key = mAdapter.getPageTitle(pos).toString();
                        field = "title";
                        message = getString(R.string.no_same_title);
                        break;
                    case 2:
                        key = mAdapter.getPageUser(pos);
                        field = "name";
                        message = getString(R.string.no_same_user);
                        break;
                    case 3:
                        key = mAdapter.getPageDate(pos);
                        field = "revision";
                        message = getString(R.string.no_same_date);
                        break;
                    default:
                        return;  // 连续点击太多次就不管咯
                }

                // 在manifest里设置main的launchMode="singleTask"，则startActivity时可重用最初的
                // 但main设置singleTask会把其上的窗口都杀掉，因此即使本窗口设为singleTop也不能复用
                // 另main启动本窗口用的startActivityForResult使singleTop无效
                if (mAdapter.tryBaseQuery(key, field, "") > 1) {
                    mAdapter.dbDetach();  // 免得后面addSomeAsync后说没notify

                    // 不必担心Result造成滚动，main会清空，滚动不了…
                    // 除非之前就在搜这个标题不会清空，但这样正好需要滚…
                    // 另改动画必须在startActivity或finish后调用
                    mResult.putExtra("key", key);
                    mResult.putExtra("field", field);
                    finish();  // startActivity和NavUtils.navigateUpFromSameTask会忽略Result
                    overridePendingTransition(R.anim.popup_show_bottom, R.anim.popup_hide_bottom);
                } else {
                    if (toast != null) toast.cancel();
                    (toast = Toast.makeText(ContentActivity.this, message, Toast.LENGTH_SHORT)).show();
                }  // UI的Context别用getApplicationContext()，生命周期太长，Activity都Destroy了还在
            }

            @Override
            public void onClick(View v) {
                long delta_time = System.currentTimeMillis() - lastClickTime;
                if (delta_time < ViewConfiguration.getDoubleTapTimeout()) {
                    v.removeCallbacks(pendingCheckForClicks);  // 要移除之前的，不然ClickCount会清0
                }
                v.postDelayed(pendingCheckForClicks, ViewConfiguration.getDoubleTapTimeout());
                lastClickTime = System.currentTimeMillis();
                fastClickCount++;
            }
        });
    }


    @Override
    public void setTitle(CharSequence title) {
        // 若ToolBarLayout用setTitleEnabled(true)则ToolBar的Title不管用
        super.setTitle(title);
        mToolBar.setTitle(title);
        mToolbarLayout.setTitle(title);
        ((TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout)).setText(title);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w(TAG, "onCreate: position = " + getIntent().getIntExtra("position", 0));
        mPreferences = getSharedPreferences("Settings", MODE_PRIVATE);
        PageFragment.setTextZoom(null, mPreferences.getInt("TextZoom", 85));
        PageFragment.setRichGuy(mPreferences.getBoolean("RichGuy", false));
        PageFragment.setNightTheme(mPreferences.getBoolean("NightTheme", false));
        setTheme(PageFragment.isNightTheme() ? R.style.AppTheme_Night : R.style.AppTheme_NoActionBar);

        // super.onCreate里也是先改主题再调super
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content);
        sReference = new WeakReference<Context>(this);

        NestedScrollView scrollView = (NestedScrollView) findViewById(R.id.scroll_container);
        scrollView.setFillViewport(true);  // 这个设置是必须的，否则里面的ViewPager不可见

        // 要求manifest里设置主题为AppTheme.NoActionBar
        mToolBar = (Toolbar) findViewById(R.id.toolbar_content);
        mImageView = (ImageView) findViewById(R.id.imageView_title);
        mAppBarLayout = (AppBarLayout) findViewById(R.id.app_bar);
        mToolbarLayout = (CollapsingToolbarLayout) findViewById(R.id.toolbar_layout);
        mToolbarLayout.setExpandedTitleColor(0x00FFFFFF);  // 透明的白色(默认的透明是黑色的)
        mTitleImageTask = new Runnable() {
            private ObjectAnimator animator =
                    ObjectAnimator.ofInt(mImageView, "imageAlpha", 0, 255);
            @Override
            public void run() {
                int pos = mResult.getIntExtra("position", -1);  // 这个比CurrentWebView更新及时
                Bitmap bitmap = mAdapter.getPageTitleImage(pos);
                if (bitmap != mImageView.getTag()) setTitleImage(bitmap, animator);  // 都是null不必进去
                if (bitmap == null && mAdapter.getPageTitleImageLink(pos) != null) {
                    if (ConnectivityState.network == 0) return;  // 流量还能手动下，没网就真的算了
                    mAdapter.updateTitleImageAsync(pos);
                    mImageView.removeCallbacks(this);  // 防止按太快出历史遗留问题
                    mImageView.postDelayed(this, 1500);  // 没拿到图+有需要才再试；退出时会取消
                    Log.w(TAG, "mTitleImageTask: I will be back in 1.5 seconds!");
                }
            }
        };
        setTitleListener();

        setSupportActionBar(mToolBar);
        ActionBar actionBar = getSupportActionBar();  // 继承Activity时用getActionBar
        if (actionBar != null) {  // 返回只需在Manifest加android:parentActivityName=".MainActivity"
            actionBar.setDisplayShowHomeEnabled(false);  // 去掉标题栏图标
            actionBar.setDisplayHomeAsUpEnabled(true);   // 启用返回按钮事件(onOptionsItemSelected)
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mToolBar.getLayoutParams();
        params.topMargin = getStatusBarHeight();  // 想在透明状态栏显示图片，需手动把标题栏往下挪
        mToolBar.setLayoutParams(params);         // 用fitsSystemWindows则状态栏的位置只能空在那

        mNetStateReceiver = new ConnectivityState(this);
        mNetStateReceiver.setListener(new ConnectivityState.OnStateChangeListener() {
            @Override
            public void onChanged() {
                PageFragment.changeCacheMode(getCurrentWebView());  // 两边的页换到时会调整下载策略
                if (PageFragment.isRichGuy() || ConnectivityState.isWifi) mImageView.post(mTitleImageTask);
                else mImageView.removeCallbacks(mTitleImageTask);  // 飞行模式啥的；上面换模式时会下图
            }
        });
        registerReceiver(mNetStateReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));

        mResult = getIntent();  // 直接利用；下面setCurrentItem用到mResult
        setResult(RESULT_OK, mResult);  // 不换页就返回也得有个结果

        setTitle(getString(R.string.loading));
        String[] query = (savedInstanceState != null) ? savedInstanceState.getStringArray("query") : null;
        final int position = (savedInstanceState != null) ? savedInstanceState.getInt("position") :
                                                            mResult.getIntExtra("position", 0);

        // Create the mAdapter that will return a fragment for each sections of the activity.
        mAdapter = new ViewPagerAdapter(this, getFragmentManager());  // 这里可能遇到删库
        mAdapter.setBaseQuery(query);     // 转屏时设置后和原来一样(这个set不clear)
        mAdapter.notifyDataSetChanged();  // 更新一次Count才知道要不要addSome
        mAdapter.setEntryPage(position);  // 除此外的其他页等滑到才加载图片

        // Set up the ViewPager with the sections mAdapter.
        mViewPager = (ViewPager) findViewById(R.id.container);
        mViewPager.addOnPageChangeListener(new OnPageChangeListener());
        mViewPager.setAdapter(mAdapter);  // 有数据下面才能换页

        // 清内存重入需要add，而从main点进来时不用
        if (position + 1 > mAdapter.getCount()) {  // position+1才是数量，正数才会执行(被删库时得久一点)
            mAdapter.addSomeAsync(position + 1 - mAdapter.getCount());  // 加在adapter的notify的监听后
            mAdapter.addListener(new SQLiteHelper.SimpleAsyncTaskListener() {
                @Override
                public void onFinish(AsyncTask task) {
                    mAdapter.removeListener(this);
                    mViewPager.setCurrentItem(position);
                    setTitle(mAdapter.getPageTitle(position));
                    if (position == 0) mImageView.post(mTitleImageTask);
                    for (int i = 0, n = mViewPager.getChildCount(); i < n; i++) {
                        // 异步期间可能按url和tag为null加载过，即about:blank
                        WebView webView = (WebView) mViewPager.getChildAt(i).findViewById(R.id.webView_section);
                        if (PageFragment.getContentMode(webView) == PageFragment.MODE_BLANK) {
                            String html = mAdapter.getPageContent(PageFragment.getPageIndex(webView));
                            webView.setTag(R.id.web_tag_in_html, html);
                            mAdapter.reloadToMode(webView, PageFragment.MODE_START);
                        }  // 不用重建数据库时，查数据库比生成窗口快，窗口建好时已能正常加载，不必再刷新
                    }  // 清内存前Tag也会保存在Fragment的savedInstanceState里…其实整个for只用于意外情况
                }

                @Override
                public void onCancel(AsyncTask task) {
                    mAdapter.removeListener(this);
                }
            });
        } else {
            mViewPager.setCurrentItem(position);  // 非0时顺便触发换页监听
            setTitle(mAdapter.getPageTitle(position));  // 0页则不触发换页监听，要手动来
            if (position == 0) mImageView.post(mTitleImageTask);  // 较耗时不重复；onCreate里没layout会闪退
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.w(TAG, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);  // 先让他更新资源再继续，主要是R.style.xxx对应的值

        // 重新layout一个ContentView然后把布局参数复制过来
        // Activity的setContentView()方法的实现在PhoneWindow里
        View newContentView = getLayoutInflater().inflate(R.layout.activity_content,
                (ViewGroup) getWindow().getDecorView(), false);
        Toolbar newToolBar = (Toolbar) newContentView.findViewById(R.id.toolbar_content);
        AppBarLayout newAppBarLayout = (AppBarLayout) newContentView.findViewById(R.id.app_bar);
        CollapsingToolbarLayout newToolbarLayout = (CollapsingToolbarLayout) newContentView.findViewById(R.id.toolbar_layout);
        TextView oldTitle = (TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout);
        TextView newTitle = (TextView) newToolbarLayout.findViewById(R.id.textView_toolbarLayout);

        // 先把状态栏高度加上去
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) newToolBar.getLayoutParams();
        params.topMargin = getStatusBarHeight();
        newToolBar.setLayoutParams(params);

        // 主要是多处关联的app_bar_height、actionBarSize和字体大小会改变
        mAppBarLayout.setLayoutParams(newAppBarLayout.getLayoutParams());
        mToolbarLayout.setLayoutParams(newToolbarLayout.getLayoutParams());
        mToolbarLayout.setCollapsedTitleTextAppearance(R.style.TextAppearance_Toolbar_Title);
        mToolbarLayout.setExpandedTitleTextAppearance(R.style.TextAppearance_Toolbar_Title);
        mToolbarLayout.setExpandedTitleColor(0x00FFFFFF);  // 同onCreate
        mToolBar.setLayoutParams(newToolBar.getLayoutParams());  // 外有layout时toolbar的样式无效
        //mToolBar.setTitleTextAppearance(this, R.style.TextAppearance_Toolbar_Title);
        oldTitle.setLayoutParams(newTitle.getLayoutParams());
        oldTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, newTitle.getTextSize());
//        ViewGroup.LayoutParams params = mAppBarLayout.getLayoutParams();
//        params.height = resources.getDimensionPixelSize(R.dimen.app_bar_height);
//        mAppBarLayout.setLayoutParams(params);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        // 如果转屏要重启，可以保存Adapter之类的复杂配置；退出之类的时候不会调用此函数
        Log.w(TAG, "onRetainCustomNonConfigurationInstance");
        return super.onRetainCustomNonConfigurationInstance();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {  // 别用有outPersistentState的重载，不调用的
        Log.w(TAG, "onSaveInstanceState: position = " + mResult.getIntExtra("position", 0));
        super.onSaveInstanceState(outState);
        outState.putInt("position", mResult.getIntExtra("position", 0));
        outState.putStringArray("query", mAdapter.getBaseQuery());
    }

    @Override
    protected void onPause() {
        super.onPause();
        mImageView.removeCallbacks(mTitleImageTask);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_content, menu);

        mSearchItem = menu.findItem(R.id.content_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(mSearchItem);
        //SearchView searchView = (SearchView) menuItem.getActionView();  // ActionBar时用这
        searchView.setQueryHint(getString(R.string.content_search_hint));
        searchView.setSubmitButtonEnabled(true);  // 显示按钮方便查找下一个
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mAdapter.setQuery(getCurrentWebView(), query, true);
                setTitleExpanded(false);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText.isEmpty()) {  // 清除/退出查找都会引发变空串；但改用Toolbar后就没了
                    mAdapter.setQuery(getCurrentWebView(), "", true);
                }
                return false;
            }
        });
        MenuItemCompat.setOnActionExpandListener(mSearchItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);
                searchView.setQuery("", true);  // 改用Toolbar后这些都要自己做
                hideInputMethod();
                return true;
            }
        });

        View goButton = null;  // 长按查找的提交按钮时往前找
        try {  //View goButton = searchView.findViewById(R.id.search_go_btn);  // 用这返回null
            Field fieldGoButton = searchView.getClass().getDeclaredField("mGoButton");  // 4.4返回null
            fieldGoButton.setAccessible(true);  // 强行可访问；class是整个虚拟机共用(当然也包括里面的field名)
            goButton = (View) fieldGoButton.get(searchView);  // 因此获取反射的field时要给定class的实例
        } catch (Exception e) {
            Log.e(TAG, "onCreateOptionsMenu: " + e.toString());
            try {
                Field fieldGoButton = searchView.getClass().getDeclaredField("mSubmitButton");
                fieldGoButton.setAccessible(true);
                goButton = (View) fieldGoButton.get(searchView);
            } catch (Exception ee) {
                Log.e(TAG, "onCreateOptionsMenu: " + ee.toString());
            }
        }
        if (goButton != null) {
            goButton.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    SearchView searchView = (SearchView) MenuItemCompat.getActionView(mSearchItem);
                    mAdapter.setQuery(getCurrentWebView(), searchView.getQuery().toString(), false);
                    setTitleExpanded(false);
                    return true;  // 不引发Click
                }
            });
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 为了方便修改菜单主题，系统菜单已设为基本不显示(除了显示第一项以伪装溢出菜单，不然就没菜单了)
        // 点击伪装的溢出菜单按钮后弹出的是自定义菜单，当然事件还能直接用的系统菜单的事件
        //onDisplayOptionsMenu(menu);  // 有自定义菜单时不能调用此函数，系统菜单应只显示伪装按钮
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case android.R.id.home:  // 标题栏返回按钮(ToolBar开搜索时的返回是搜索控件自己的)
                onBackPressed();
                return true;

            case R.id.content_menu_entry:  // 伪装的溢出菜单按钮
                showOverflowMenu();
                return true;

            case R.id.content_search:
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);
                searchView.onActionViewExpanded();  // 改用ToolBar后得默认展开
                return true;

            case R.id.content_refresh:  // 刷新，直接调WebView的reload是按照url(about:blank)弄的
                reloadCurrentWebView(getCurrentWebViewMode());
                return true;

            case R.id.content_copy_link:
                WebView webView = getCurrentWebView();
                if (webView != null) copyLink((String) webView.getTag(R.id.web_tag_url));
                return true;

            case R.id.content_night_mode:
                PageFragment.toggleNightTheme();
                mPreferences.edit().putBoolean("NightTheme", PageFragment.isNightTheme()).apply();
                return true;

            case R.id.content_rich_data:
                PageFragment.setRichGuy(!PageFragment.isRichGuy());
                PageFragment.changeCacheMode(getCurrentWebView());
                if (PageFragment.isRichGuy()) mImageView.post(mTitleImageTask);
                mPreferences.edit().putBoolean("RichGuy", PageFragment.isRichGuy()).apply();
                return true;

            case R.id.content_save:
                File src = PageFragment.getContentCache(getCurrentWebView());  // 空WebView返回null
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File dest = new File(dir, "fav");  // 放在图片的子文件夹，fav恰好会被文件管理视为收藏...
                String path = null;
                if (src != null && (dest.exists() || dest.mkdirs())) {
                    try {  // mkdirs需要WRITE_EXTERNAL_STORAGE权限
                        String src_name = src.getName();  // 要去掉结尾的.0
                        dest = new File(dest, src_name.substring(0, src_name.length() - 2));
                        FileChannel in = new FileInputStream(src).getChannel();
                        FileChannel out = new FileOutputStream(dest).getChannel();
                        in.transferTo(0, in.size(), out);  // OutputStream会创建文件(文件夹存在)
                        in.close();  // 用FileChannel可比BufferedStream再快1倍
                        out.close();

                        path = dest.getAbsolutePath().replace(
                                Environment.getExternalStorageDirectory().getAbsolutePath(),
                                getString(R.string.external_storage));
                    } catch (Exception e) {
                        Log.e(TAG, "onOptionsItemSelected_save: " + e.toString());
                        if (dest.isFile() && dest.delete()) {
                            dest = dir;
                        }
                    }
                }
                String msg = dest.isFile() ? getString(R.string.content_saved, path) : getString(R.string.content_not_saved);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return true;

            case R.id.content_text_size:
                hideInputMethod();
                int color_id = PageFragment.isNightTheme() ?
                        R.color.window_background_night : R.color.window_background;
                View view = getLayoutInflater().inflate(R.layout.popup_zoom, null);
                PopupWindow window = new PopupWindow(view, 0, 0, true);
                window.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
                window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setOutsideTouchable(true);  // 允许点外边取消，有背景才能点外边(4.4只能用此函数)
                window.setBackgroundDrawable(new ColorDrawable(getResources().getColor(color_id)));
                window.setAnimationStyle(R.style.popup_anim_bottom);
                window.showAtLocation(findViewById(android.R.id.content), Gravity.BOTTOM, 0, 0);
                window.setOnDismissListener(new PopupWindow.OnDismissListener() {
                    @Override
                    public void onDismiss() {  // 左右已经载入的网页也要改缩放
                        for (int i = 0, n = mViewPager.getChildCount(); i < n; i++) {
                            WebView webView = (WebView) mViewPager.getChildAt(i).findViewById(R.id.webView_section);
                            PageFragment.setTextZoom(webView, PageFragment.getTextZoom());
                        }
                        mPreferences.edit().putInt("TextZoom", PageFragment.getTextZoom()).apply();
                    }
                });

                SeekBar mSeekBar = (SeekBar) view.findViewById(R.id.seekBar);  // 每格15，中间100
                mSeekBar.setProgress((PageFragment.getTextZoom() - 70) / 15);
                mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int text_zoom = 70 + 15 * progress;
                        PageFragment.setTextZoom(getCurrentWebView(), text_zoom);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
                //mSeekBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.popup_show_bottom));
                return true;

            case R.id.content_open_with:  // 用其他应用打开
                String currentUrl = mAdapter.getPageLink(mViewPager.getCurrentItem());
                if (currentUrl != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(currentUrl));
                    // uri用zhihu://只能接questions/answers/people/ 专栏不行；而用http://要选应用
                    List<ResolveInfo> pkgInfo = getPackageManager().queryIntentActivities(intent, 0);
                    for (ResolveInfo pkg : pkgInfo)  // 知乎日报(zhihu.daily)不掺和；没知乎用浏览器
                        if (pkg.activityInfo.packageName.contains("com.zhihu.android"))
                            intent.setPackage(pkg.activityInfo.packageName);
                    startActivity(intent);
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        Log.w(TAG, "onActionModeStarted");
        super.onActionModeStarted(mode);
        if (getCurrentWebViewMode() == PageFragment.MODE_START) {
            PageFragment.setContentMode(getCurrentWebView(), PageFragment.MODE_COPY);
            setTitleExpanded(false);
        }
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        Log.w(TAG, "onActionModeFinished");
        super.onActionModeFinished(mode);
        if (getCurrentWebViewMode() < PageFragment.MODE_START) {
            PageFragment.setContentMode(getCurrentWebView(), PageFragment.MODE_START);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showOverflowMenu();  // 菜单显示后再按键都会取消菜单，且不触发此事件
            return true;         // KeyDown也拦不到
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (goBackCurrentWebView()) return;
        super.onBackPressed();  // 这里最后会调用this.finish();
    }  // 实体键盘的返回键

    @Override
    protected void onDestroy() {
        Log.w(TAG, "onDestroy");
        super.onDestroy();
        unregisterReceiver(mNetStateReceiver);
        mImageView.removeCallbacks(mTitleImageTask);
        mAdapter.dbDetach();
        sReference = null;
    }

}
