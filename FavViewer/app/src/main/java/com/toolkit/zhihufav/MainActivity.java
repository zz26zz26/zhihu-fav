package com.toolkit.zhihufav;

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.GridLayout;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.toolkit.zhihufav.util.SQLiteHelper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int ContentActivityCode = 0;

    private View mFooterView;
    private Toolbar mToolbar;
    private String mQueryText;
    private ListView mListView;
    private MenuItem mSearchItem;
    private MenuItem mFilterItem;
    private ImageView mImageView;
    private PopupWindow mPopupWindow;
    private ListViewAdapter mAdapter;

    private String[] mFolderList;                // 筛选窗口收藏夹列表，使删库重建中也能改夜间模式
    private Runnable mPendingQueryTask;          // 回桌面被清内存后可能重新赋值，removeCallback不掉
    private ArrayList<QueryInfo> mQueryHistory;  // 发起新查询时保存之前看到的位置
    private boolean mPopHistoryOnRecreate;       // 清内存重入时恢复，区别于切窗口/回桌面之类
    private static WeakReference<Context> sReference;

    private static class QueryInfo implements Parcelable {
        int position;
        int offset;
        String key;
        String field;
        String type;
        String sort;

        QueryInfo() {}

        QueryInfo(Parcel in) {
            position = in.readInt();
            offset = in.readInt();
            key = in.readString();
            field = in.readString();
            type = in.readString();
            sort = in.readString();
        }

        public static final Creator<QueryInfo> CREATOR = new Creator<QueryInfo>() {
            @Override
            public QueryInfo createFromParcel(Parcel in) {
                return new QueryInfo(in);
            }

            @Override
            public QueryInfo[] newArray(int size) {
                return new QueryInfo[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(position);
            dest.writeInt(offset);
            dest.writeString(key);
            dest.writeString(field);
            dest.writeString(type);
            dest.writeString(sort);
        }

        @Override
        public String toString() {
            return String.format(Locale.CHINA,
                    "position=%d, offset=%d, key=%s, field=%s, type=%s, sort=%s",
                    position, offset, key, field, type, sort);
        }
    }

    private class onScrollListener implements AbsListView.OnScrollListener {
        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {}

        @Override  // 此接口必须实现这两个方法；碰footer以外的项、打开窗口、返回、notify后都会触发
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            int lastVisibleItem = firstVisibleItem + visibleItemCount - 1;  // 即getLastVisiblePosition
            if (lastVisibleItem >= totalItemCount - 1 - 2) {  // 露头就算可见，包括了FooterView先-1
                if (mAdapter.hasMore())  // -2与ContentActivity提前量一致，参见其onPageChangeListener
                    mAdapter.addSomeAsync();  // 若不判断hasMore会循环触发(因notify)；dbOpen前有一次调用
            }
        }
    }

    private class onItemClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Context context = getApplicationContext();  // 若用Activity.this在清内存时就没了
            Intent intent = new Intent(context, ContentActivity.class);
            intent.putExtra("position", position);
            startActivityForResult(intent, ContentActivityCode);
        }
    }

    private class onQueryTextListener implements SearchView.OnQueryTextListener {
        @Override
        public boolean onQueryTextSubmit(String query) {
            onQueryTextChange(query);
            return true;  // 已消费
        }

        @Override
        public boolean onQueryTextChange(String newText) {  // 查找标题时的setQuery不会进来
            newText = newText.trim().replaceAll("\\s+", " ").toLowerCase();  // 输入一堆空格不管
            if (!newText.equals(mAdapter.getQueryText())) {
                pushQueryHistory(2);  // 第一次记录首页，第二次后都是更新搜索(postDelayed里也有push)
                mQueryText = newText;
                mListView.removeCallbacks(mPendingQueryTask);   // 连续修改延迟查询
                mListView.postDelayed(mPendingQueryTask, 300);  // 取消task差不多这么久
                //Log.w(TAG, "onQueryTextChange: " + mQueryText);
            }
            return true;
        }
    }

    private class onActionExpandListener implements MenuItemCompat.OnActionExpandListener {  // MenuItem.OnActionExpandListener
        @Override
        public boolean onMenuItemActionExpand(MenuItem item) {
            if (item.getItemId() == R.id.search) {
                mQueryText = mAdapter.getQueryText();  // 已打开时也能进来，如查找标题时就可以
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);
                searchView.setQuery(mQueryText, false);
            }
            return true;  // false会阻止打开
        }

        @Override
        public boolean onMenuItemActionCollapse(MenuItem item) {  // SearchView的onClose无反应
            if (mQueryHistory != null && mQueryHistory.size() > 0) {  // 还进行过别的查找
                popQueryHistory();               // 退出查找时都会清空文本，pop里已经弄了
                onMenuItemActionExpand(item);    // 更新查找框内的词
                if (mQueryHistory.size() > 0) {  // pop后还不到首页则阻止关闭
                    return false;
                }
            }
            setInputMethodVisible(false);
            return true;
        }
    }

    private class onPopupTouchListener implements View.OnTouchListener {

        private int activePointerId;
        private VelocityTracker tracker;
        private float rawOffsetX, rawOffsetY;
        private float touchDownX, touchDownY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screen_width = metrics.widthPixels;
            int popup_width = mPopupWindow.getContentView().getWidth();
            int popup_left = screen_width - popup_width;
            float touchSlop = 16 * metrics.density;
            float deltaX = event.getRawX() - Math.abs(touchDownX);
            float deltaY = event.getRawY() - Math.abs(touchDownY);

            int action = event.getActionMasked();
            int id = event.getPointerId(event.getActionIndex());  // id每根手指唯一；index总是从0开始
//            String TAG = "popup_onTouch";
//            String[] s = {"DOWN", "UP", "MOVE", "CANCEL", "OUTSIDE", "POINTER_DOWN", "POINTER_UP"};
//            if (action != MotionEvent.ACTION_MOVE)
//                Log.i(TAG, "action = " + s[action] + (action < 5 && id < 1 ? "": " #" + id));

            switch (action) {  // Masked后才有纯的PTR_UP
                // ListView和PopupWindow都绑定此事件
                // 使用touchDownX记录绑定的控件：负数表示ListView，正数表示PopupWindow，0表示无弹窗
                case MotionEvent.ACTION_DOWN:
                    touchDownX = 0;
                    boolean can_pop = !mPopupWindow.isShowing() && event.getRawX() > screen_width - touchSlop;
                    boolean can_drag = mPopupWindow.isShowing() && event.getRawX() > popup_left;
                    if (can_pop || can_drag) {
                        if (tracker != null) tracker.clear();
                        else tracker = VelocityTracker.obtain();
                        touchDownX = event.getRawX() + 1;  // 使之>0
                        touchDownY = event.getRawY();  // Y没那么多事
                        rawOffsetX = touchDownX - event.getX();
                        rawOffsetY = touchDownY - event.getY();
                        activePointerId = event.getPointerId(0);
                        mPopupWindow.getContentView().animate().cancel();  // 点外面直接dismiss暂停不了
                        touchDownX -= mPopupWindow.getContentView().getTranslationX();  // 同防止跳变
                        if (touchDownX <= 0) touchDownX = 1;  // 打开动画中暂停也算drag；得保证正数

                        if (can_pop) {
                            onDisplayPopupWindow();  // 没弹出，且屏幕右侧边缘按下
                            //mPopupWindow.setClippingEnabled(false);  // false允许在屏幕外 x正向往右
                            //mPopupWindow.update((int) (event.getRawX()), 0, -1, -1, true);  // y正向往上
                            // 但popup不便动画，遂先让窗口到位，而里面View在屏幕外，拖动时改变View的位置
                            // 正好popup的背景透明，且动画只影响窗口矩形内的画面，不会影响视觉效果
                            mPopupWindow.showAtLocation(mListView, Gravity.END | Gravity.BOTTOM, 0, 0);
                            mPopupWindow.getContentView().setTranslationX(popup_width);
                            touchDownX = -event.getRawX() - 1;  // 使之<0
                        }
                        return touchDownX < 0;  // 要弹窗时不能返回false，不然MOVE和UP就被ListView用去了
                    }  // 而已弹出后由于弹窗是顶层，则不能返回true，不然里面按钮无法点击
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (touchDownX != 0) {
                        float translation = event.getRawX();  // touchDownX<0说明窗口正在弹出
                        translation -= (touchDownX > 0 ? touchDownX : popup_left);
                        if (tracker != null) {
                            tracker.addMovement(event);
                            if (Math.abs(deltaY) > Math.max(touchSlop, Math.abs(deltaX)) &&
                                    touchDownX > 0 && canPopupWindowScroll()) {
                                mPopupWindow.getContentView().animate().translationX(0).start();
                                touchDownX = 0;  // 已弹出+能滚动的情况下，纵向滑动直接不管
                                tracker.recycle();
                                tracker = null;
                            }
                        }
                        if (id == activePointerId && translation >= 0 && translation <= popup_width)
                            mPopupWindow.getContentView().setTranslationX(translation);
                        return touchDownX < 0;  // 弹窗时要阻止ListView使用，不然滑少了会点开新窗口
                    }  // 已弹出后不能返回true，不然里面的ScrollView没响应
                    break;

                case MotionEvent.ACTION_POINTER_UP:  // 抄自ViewPager.onSecondaryPointerUp()
                    if (touchDownX > 0) {  // 弹出后才管换手指，这时恰好换后touchDownX仍大于0
                        if (id == activePointerId) {  // 抬起了正在追踪的手指就要换了
                            int newPointerIndex = (event.getActionIndex() == 0 ? 1 : 0);  // 至少有俩
                            float translationX = mPopupWindow.getContentView().getTranslationX();
                            touchDownX = event.getX(newPointerIndex) + rawOffsetX - translationX;
                            touchDownY = event.getY(newPointerIndex) + rawOffsetY;  // 避免跳变
                            activePointerId = event.getPointerId(newPointerIndex);
                            if (tracker != null) tracker.clear();
                        }
                    }  // 已弹出当然返回false
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (touchDownX != 0) {
                        float flingVelocity = 500 * metrics.density;
                        float velocity = 0.0f;
                        float finalX;
                        if (tracker != null) {
                            if (Math.abs(deltaX) > Math.abs(deltaY)) {
                                tracker.computeCurrentVelocity(1000);  // 单位px/s
                                velocity = tracker.getXVelocity(activePointerId);
                            }
                            tracker.recycle();
                            tracker = null;
                        }
//                        Log.w("popup_onTouch", "dx = " + deltaX + ". v = " + velocity);

                        if (Math.abs(deltaX) > touchSlop) {  // 顺势而动
                            if ((mPopupWindow.getContentView().getTranslationX() > popup_width / 2 &&
                                    Math.abs(velocity) < flingVelocity) || velocity > flingVelocity)
                                finalX = popup_width;  // 没拖出过一半或反着拖就取消掉
                            else
                                finalX = 0;
                        } else {  // 维持原状
                            if (touchDownX < 0)
                                finalX = popup_width;  // 同上之后会dismiss
                            else
                                finalX = 0;
                        }
                        mPopupWindow.getContentView().animate().translationX(finalX).start();
                        return touchDownX < 0;  // 弹窗时拦截表项的点击，防止开新窗口
                    }  // 已弹出后不能返回true，不然按钮还是没响应，且ScrollView没有惯性滚动
                    break;
            }
            return false;  // 全都返回true则ListView都滑不动
        }
    }


    public static MainActivity getReference() {
        return sReference == null ? null : (MainActivity) sReference.get();
    }

//    // 让footer在结果数太少(可见最后一项)时填满屏幕，注意输入法界面使listView底部偏高(则高度偏小，Child也会少)
//    // 调用mListView.postDelayed(new Runner() {@Override里面调此函数}, 100);
//    public void setProperFooterViewHeight() {
//        DisplayMetrics metrics = this.getResources().getDisplayMetrics();
//        float scale = metrics.density;
//        int default_height = (int) (72 * scale + 0.5f);  // 72dp->px
//        int proper_height = 0;  // 结果够多用默认高度，不然划到底时可见footer高度接近两个表项
//        if (mListView.getChildCount() >= mListView.getCount()) {
//            int[] position = new int[2];
//            mListView.getLocationOnScreen(position);
//            //Log.w("SetFooter", String.format("position = (%d, %d)", position[0], position[1]));
//            proper_height = metrics.heightPixels - position[1];  // listView的完全高度
//            if (mListView.getCount() > 1)  // 只有footer时不减即填满
//                proper_height -= mListView.getChildAt(mListView.getCount() - 2).getBottom();
//            else
//                proper_height -= mListView.getPaddingTop();
//        }  // 结果填不满屏幕时ChildCount可能比Count还大
//        mFooterView.setMinimumHeight(Math.max(default_height, proper_height));
//    }

    public void setFooterViewProgress(double progress) {
        // 文本框刚开始加载不显示；进度条在加载完成不显示
        mFooterView.findViewById(R.id.textView_footer).setVisibility(progress == 0 ? View.GONE : View.VISIBLE);
        mFooterView.findViewById(R.id.progressBar_footer).setVisibility(progress == 1 ? View.GONE : View.VISIBLE);

        String text = (progress == 1 ? (mAdapter.getCount() == 0 ?  // 第二个之前是search_no_more_result
                getString(R.string.search_no_result) :
                getString(R.string.search_result_count, mAdapter.getCount())) :
                getString(R.string.search_more_result, (int) (progress * 100)));
        ((TextView) mFooterView.findViewById(R.id.textView_footer)).setText(text);
    }

    public void setListViewSelection(int position, int y) {
        y -= mListView.getPaddingTop();  // 减去就精准了
        mListView.setSelectionFromTop(position, y);  // y是position项的顶部离表顶部的距离(正向往下)
    }

    public void setFilterIcon() {
        if (mFilterItem != null) {  // 任何一项与默认值不同即可认为启用筛选
            if (!mAdapter.getQueryType().isEmpty() || !mAdapter.getQuerySort().isEmpty() ||
                    !mAdapter.getQueryField().equals(SQLiteHelper.DEFAULT_FIELD)) {
                mFilterItem.setIcon(R.drawable.ic_filter_list_white_24dp);
            } else {
                mFilterItem.setIcon(R.drawable.ic_menu_white_24dp);
            }
        }
    }

    public void expandSearchView() {
        if (mSearchItem != null) {
            onOptionsItemSelected(mSearchItem);  // 顺序不能反，不然清内存重入后能开搜索框但没字
            MenuItemCompat.expandActionView(mSearchItem);
        }
    }

    protected void pushQueryHistory(int max_size) {
        if (mQueryHistory == null) {
            mQueryHistory = new ArrayList<>(3);  // 最多首页一个，搜索一个，搜标题一个(切换前要存)
        }
        QueryInfo info = new QueryInfo();
        info.position = mListView.getFirstVisiblePosition();  // 这样在横竖屏转换时不会有太大偏差
        info.offset = (mListView.getChildCount() > 0) ? mListView.getChildAt(0).getTop() : 0;
        info.key = mAdapter.getQueryText();
        info.field = mAdapter.getQueryField();
        info.type = mAdapter.getQueryType();
        info.sort = mAdapter.getQuerySort();

        if (mQueryHistory.size() < 1 || (mQueryHistory.size() + 1 <= max_size &&
                !info.key.equals(mQueryHistory.get(mQueryHistory.size() - 1).key))) {
            mQueryHistory.add(info);  // 至少关键词得变才能添加
        } else {
            mQueryHistory.set(mQueryHistory.size() - 1, info);
        }
        Log.w(TAG, "pushQueryHistory, size = " + mQueryHistory.size() + ". key = " + info.key);
    }

    protected void popQueryHistory() {
        if (mQueryHistory == null || mQueryHistory.size() < 1) return;
        if (mQueryHistory.get(mQueryHistory.size() - 1).key.equals(mQueryText)) {
            mQueryHistory.remove(mQueryHistory.size() - 1);  // 一样的跳且最多跳过一次
            //Log.w(TAG, "popQueryHistory skip once");  // 跳多时若搜索框搜了词再清空，首页的记录也跳过了
            if (mQueryHistory.size() < 1) return;
        }
        final QueryInfo info = mQueryHistory.remove(mQueryHistory.size() - 1);
        //Log.w(TAG, "QueryInfo: " + info);
        mQueryText = info.key;  // 重启时让onCreateOptionsMenu继续处理；关闭搜索框时把此值置空
        mAdapter.setQuery(info.key, info.field, info.type, info.sort);  // 若需要则清空，getCount为0
        mAdapter.addSomeAsync(info.position + 10 - mAdapter.getCount());  // 没清空时不必加载太多
        mAdapter.addListener(new SQLiteHelper.AsyncTaskListener() {
                @Override
                public void onStart(AsyncTask task) {}

                @Override
                public void onAsyncFinish(AsyncTask task) {
                    if (task.isCancelled()) {
                        mAdapter.removeListener(this);  // 嗯用一次就丢
                    }
                }

                @Override
                public void onFinish(AsyncTask task) {
                    mAdapter.removeListener(this);  // 一定要丢掉不然滑到底会乱跳
                    setListViewSelection(info.position, info.offset);
                }
            });  // 没加载也可能要滚动
        setFilterIcon();  // 重启时这里的MenuItem是null，之后onCreateOptionsMenu还有一次
        Log.w(TAG, "popQueryHistory, size = " + mQueryHistory.size());
    }

    protected void toggleNightTheme() {
        boolean night = PageFragment.isNightTheme();

        if (night) setTheme(R.style.AppTheme_Night);
        else setTheme(R.style.AppTheme_NoActionBar);  // 不会自动刷新UI
        int toolbar_text_color_id = night ? android.R.attr.textColorPrimary :
                                            android.R.attr.textColorPrimaryInverse;
        try {
            int color;
            Resources.Theme theme = getTheme();
            Resources resources = getResources();
            TypedValue typedValue = new TypedValue();
            theme.resolveAttribute(R.attr.colorPrimary, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mToolbar.setBackgroundColor(color);
            theme.resolveAttribute(toolbar_text_color_id, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mToolbar.setTitleTextColor(color);
            theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mListView.setBackgroundColor(color);
            theme.resolveAttribute(R.attr.toolbar_divider, typedValue, true);
            mImageView.setImageResource(typedValue.resourceId);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {  // 4.4只能是黑白/透明，不用改
                theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true);
                getWindow().setStatusBarColor(ResourcesCompat.getColor(resources, typedValue.resourceId, null));
            }
        } catch (Exception e) {
            Log.e("NightTheme_main", e.toString());
        }

        mPopupWindow = newPopupWindow();  // 重新布局颜色就对了
        mListView.removeFooterView(mFooterView);  // Footer不删了再加会造成setFooter无效
        mFooterView = LayoutInflater.from(this).inflate(R.layout.listview_footer, null);
        mListView.addFooterView(mFooterView, null, false);
        mAdapter.notifyDataSetChanged();  // 通知后ListView会自动去重新getView
        setFooterViewProgress(1.0);  // xml里默认加载，改为完成(否则没新的加载前一直转圈)
    }


    private void setInputMethodVisible(boolean visible) {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (visible) {
            manager.toggleSoftInput(0, InputMethodManager.HIDE_NOT_ALWAYS);
        } else {
            manager.hideSoftInputFromWindow(findViewById(android.R.id.content).getWindowToken(), 0);
        }  // android.R.id.content gives the root view of current activity
    }

    // 无端设想：时段筛选、多列排序、包含公式、包含动图(难)、包含视频链接(如b站的)
    private void setPopupWindowFolder(PopupWindow pw) {
        boolean resize = false;
        GridLayout list = (GridLayout) pw.getContentView().findViewById(R.id.gridLayout_folder);
        View template = list.getChildAt(0);  // xml里有1个不可见项作为模板
        GridLayout.LayoutParams t_param = (GridLayout.LayoutParams) template.getLayoutParams();
        String invisible_tag = ((String) template.getTag()).substring("folder_".length());
        for (String folder : mFolderList) {
            if (folder.equals(invisible_tag)) continue;
            CheckBox box = new CheckBox(this);  // 默认没有ID
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);  // 填满剩余空间
            params.setMargins(t_param.leftMargin, t_param.topMargin, t_param.rightMargin, t_param.bottomMargin);
            box.setPadding(template.getPaddingLeft(), template.getPaddingTop(), template.getPaddingRight(), template.getPaddingBottom());
            box.setText(folder);
            box.setTag("folder_" + folder);
            box.setMaxEms(7);
            box.setMaxLines(1);
            box.setEllipsize(TextUtils.TruncateAt.END);
            list.addView(box, 0, params);  // 插入在不可见项之前，不然会有空白
            if (folder.length() > 10) resize = true;  // 默认2列，若有的收藏夹名字很长就改为一列
        }
        if (resize) {  // 不设行列号即可自动排列
            list.setColumnCount(1);
            for (int i = 0, n = list.getChildCount(); i < n; i++) {
                ((CheckBox) list.getChildAt(i)).setMaxEms(15);
            }
        }
    }

    private void setPopupWindowListener(PopupWindow pw) {
        final PopupWindow window = pw;
        final View view = window.getContentView();

        // 自身触摸滑动事件
        window.setTouchInterceptor(new onPopupTouchListener());

        // 设置动画插值以及动画完毕再dismiss
        view.animate().setInterpolator(new TimeInterpolator() {
            @Override
            public float getInterpolation(float t) {
                t -= 1.0f;  // 抄自ViewPager的Interpolator
                return t * t * t * t * t + 1.0f;
            }
        });
        view.animate().setListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                if (view.getTranslationX() == view.getWidth()) {
                    window.dismiss();
//                    Log.w("onAnimationEnd", "window dismissed");
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });

        // 底部按钮点击事件
        view.findViewById(R.id.button_filter_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPopupWindowCheckBox("", "", SQLiteHelper.DEFAULT_FIELD);
            }
        });

        view.findViewById(R.id.button_filter_ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewGroup group;
                // 类别
                String type = null;
                StringBuilder type_builder = new StringBuilder();
                group = (ViewGroup) view.findViewById(R.id.linearLayout_type);  // 文章/回答
                for (int i = 0, checked = 0, n = group.getChildCount(); i < n; i++) {
                    CheckBox box = (CheckBox) group.getChildAt(i);
                    if (box.isChecked()) {
                        checked++;
                        type_builder.append(box.getTag()).append(" ");
                    }
                    if (checked == n)  // 全选就是没选
                        type_builder.delete(0, type_builder.length());  // delete不置0，比setLength快
                }
                group = (ViewGroup) view.findViewById(R.id.gridLayout_other);  // 图片/视频
                for (int i = 0, n = group.getChildCount(); i < n; i++) {
                    CheckBox box = (CheckBox) group.getChildAt(i);
                    if (box.isChecked())  // 没选就是没选，不必置空；也因此放要置空的后面
                        type_builder.append(box.getTag()).append(" ");  // 免得自己的东西被删
                }
                StringBuilder folder_builder = new StringBuilder();
                group = (ViewGroup) view.findViewById(R.id.gridLayout_folder);  // 收藏夹名称
                for (int i = 0, checked = 0, n = group.getChildCount(); i < n; i++) {
                    CheckBox box = (CheckBox) group.getChildAt(i);
                    if (box.isChecked() && box.getVisibility() == View.VISIBLE) {
                        checked++;
                        folder_builder.append(box.getTag()).append(" ");
                    }
                    if (checked == n - 1)  // 全选就是没选(有个隐藏，隐藏的勾不算)
                        folder_builder.delete(0, folder_builder.length());  // delete不置0，比setLength快
                    if (i == n - 1 && checked == 0)  // 全没选找隐藏
                        folder_builder.append((view.findViewById(R.id.checkBox)).getTag()).append(" ");
                }
                type_builder.append(folder_builder);
                if (type_builder.length() > 0)  // assert type_builder.charAt(type_builder.length() - 1) == ' ';
                    type_builder.deleteCharAt(type_builder.length() - 1);
                if (!mAdapter.getQueryType().contentEquals(type_builder))
                    type = type_builder.toString();  // 有变化才改

                // 排序
                String sort = null;
                StringBuilder sort_builder = new StringBuilder();
                group = (ViewGroup) view.findViewById(R.id.gridLayout_sort);
                for (int i = 0, n = group.getChildCount(); i < n; i++)
                    if (((RadioButton) group.getChildAt(i)).isChecked())
                        sort_builder.append(group.getChildAt(i).getTag());
                RadioGroup radioGroup = (RadioGroup) view.findViewById(R.id.radioGroup_order);
                if (sort_builder.length() > 0 && radioGroup.getCheckedRadioButtonId() >= 0)  // 没选列会出错
                    sort_builder.append(" ").append(view.findViewById(radioGroup.getCheckedRadioButtonId()).getTag());
                if (!mAdapter.getQuerySort().contentEquals(sort_builder))
                    sort = sort_builder.toString();  // 有变化才改

                // 搜索字段
                String field = null;
                StringBuilder field_builder = new StringBuilder();
                group = (ViewGroup) view.findViewById(R.id.gridLayout_field);
                for (int i = 0, n = group.getChildCount(); i < n; i++)
                    if (((CheckBox) group.getChildAt(i)).isChecked())
                        field_builder.append(group.getChildAt(i).getTag()).append(" ");
                if (field_builder.length() > 0)  // 去掉最后空格，免得空格也算变化
                    field_builder.deleteCharAt(field_builder.length() - 1);
                if (!mAdapter.getQueryField().contentEquals(field_builder))
                    field = field_builder.toString();  // 有变化才改

                window.dismiss();
                if (field != null || type != null || sort != null) {
                    mAdapter.setQuery(null, field, type, sort);  // 里面会判断结果有变化才清空
                    if (!(mAdapter.getQueryText().isEmpty() && type == null && sort == null)) {
                        mAdapter.addSomeAsync();  // 无关键字时只改field，结果不会变
                        mListView.setSelection(0);
                    } else {  // 只改field时，若含编辑时间就以原始格式显示(改别的会重载入，顺便弄了)
                        mAdapter.formatTimeInfo(!field.contains("revision"));
                        mAdapter.notifyDataSetChanged();
                    }
                }
                setFilterIcon();  // setQuery改完后才能设置
            }
        });

        // 已选中的选框长按后可反选其他
        View.OnLongClickListener long_check = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!((CheckBox) v).isChecked())
                    return true;  // 未选中的长按无效，与单选组一致

                ViewGroup grid = (ViewGroup) v.getParent();
                for (int i = 0, n = grid.getChildCount(); i < n; i++)
                    if (!grid.getChildAt(i).equals(v)) {
                        CheckBox box = (CheckBox) grid.getChildAt(i);
                        box.setChecked(!box.isChecked());
                    }
                return true;  // 返回true不执行单击事件
            }
        };
        ViewGroup group;
        group = (ViewGroup) view.findViewById(R.id.linearLayout_type);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnLongClickListener(long_check);
        group = (ViewGroup) view.findViewById(R.id.gridLayout_folder);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnLongClickListener(long_check);
        group = (ViewGroup) view.findViewById(R.id.gridLayout_field);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnLongClickListener(long_check);
        group = (ViewGroup) view.findViewById(R.id.gridLayout_other);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnLongClickListener(long_check);

        // 类别和搜索区域无选中项提示
        View.OnClickListener no_check = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int checked = 0;
                ViewGroup grid = (ViewGroup) v.getParent();
                for (int i = 0, n = grid.getChildCount(); i < n; i++) {
                    if (((CheckBox) grid.getChildAt(i)).isChecked())
                        checked++;
                }
                if (checked == 0)
                    Toast.makeText(MainActivity.this, R.string.filter_no_select_hint, Toast.LENGTH_SHORT).show();
            }
        };
        group = (ViewGroup) view.findViewById(R.id.linearLayout_type);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnClickListener(no_check);
        group = (ViewGroup) view.findViewById(R.id.gridLayout_field);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnClickListener(no_check);

        // 升降序按钮长按取消所有排序
        View.OnLongClickListener cleaner = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                RadioButton button = (RadioButton) v;
                if (button.isChecked()) {  // 不clear则按刚取消掉的按钮不能选中
                    ((RadioGroup) view.findViewById(R.id.radioGroup_order)).clearCheck();
                    ViewGroup grid = (ViewGroup) view.findViewById(R.id.gridLayout_sort);
                    for (int i = 0, n = grid.getChildCount(); i < n; i++)
                        ((RadioButton) grid.getChildAt(i)).setChecked(false);
                }
                return true;  // 处理了返回true
            }
        };
        // 升降序按钮选择后自动选择排序列
        View.OnClickListener checker = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int checked = 0;
                ViewGroup grid = (ViewGroup) view.findViewById(R.id.gridLayout_sort);
                for (int i = 0, n = grid.getChildCount(); i < n; i++)
                    if (((RadioButton) grid.getChildAt(i)).isChecked())
                        checked++;
                if (checked == 0)
                    ((RadioButton) grid.getChildAt(0)).setChecked(true);
            }
        };
        group = (ViewGroup) view.findViewById(R.id.radioGroup_order);
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            group.getChildAt(i).setOnClickListener(checker);
            group.getChildAt(i).setOnLongClickListener(cleaner);
        }

        // 排序列按钮选择后自动设置为升序，同时取消其他选择(手动RadioGroup)
        View.OnClickListener setter = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RadioGroup group = (RadioGroup) view.findViewById(R.id.radioGroup_order);
                if (((RadioButton) v).isChecked() && group.getCheckedRadioButtonId() == -1)
                    group.check(R.id.radioButton_asc);

                ViewGroup grid = (ViewGroup) view.findViewById(R.id.gridLayout_sort);
                for (int i = 0, n = grid.getChildCount(); i < n; i++)
                    if (grid.getChildAt(i) != v)
                        ((RadioButton) grid.getChildAt(i)).setChecked(false);
            }
        };
        group = (ViewGroup) view.findViewById(R.id.gridLayout_sort);
        for (int i = 0, n = group.getChildCount(); i < n; i++)
            group.getChildAt(i).setOnClickListener(setter);
    }

    private void setPopupWindowCheckBox(String type, String sort, String field) {
        // 类别组按钮状态
        ViewGroup group;
        View view = mPopupWindow.getContentView();
        group = (ViewGroup) view.findViewById(R.id.linearLayout_type);  // 文章/回答
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            CheckBox box = (CheckBox) group.getChildAt(i);
            box.setChecked(!type.contains("type_"));  // 不设置等于全选
            if (type.contains((String) box.getTag()))  // 有设置时才根据设置打勾
                box.setChecked(true);
        }
        group = (ViewGroup) view.findViewById(R.id.gridLayout_folder);  // 收藏夹筛选
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            CheckBox box = (CheckBox) group.getChildAt(i);
            box.setChecked(!type.contains("folder_"));  // 同上，即有收藏夹名时默认不勾，否则默认勾选
            if (type.contains((String) box.getTag()))  // 有收藏夹名时找到自己的显然要勾
                box.setChecked(true);
        }
        group = (ViewGroup) view.findViewById(R.id.gridLayout_other);  // 包含图片/视频
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            CheckBox box = (CheckBox) group.getChildAt(i);
            box.setChecked(type.contains((String) box.getTag()));  // 不设置就不选，没有默认
        }

        // 排序组按钮状态
        group = (ViewGroup) view.findViewById(R.id.gridLayout_sort);  // 排序列名
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            RadioButton button = (RadioButton) group.getChildAt(i);
            button.setChecked(sort.startsWith((String) button.getTag()));
        }
        if (sort.endsWith("sc")) {
            int order_id = sort.endsWith("asc") ? R.id.radioButton_asc : R.id.radioButton_desc;
            ((RadioButton) view.findViewById(order_id)).setChecked(true);  // 升降序
        } else
            ((RadioGroup) view.findViewById(R.id.radioGroup_order)).clearCheck();

        // 搜索字段框状态
        field = field.trim();
        group = (ViewGroup) view.findViewById(R.id.gridLayout_field);
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            CheckBox box = (CheckBox) group.getChildAt(i);
            box.setChecked(field.isEmpty() || field.contains((String) box.getTag()));
        }
    }

    private void onDisplayPopupWindow() {
        // 显示窗口前更新窗口高度和按钮状态
        // 要使输入法不自动弹出，可在manifest加android:windowSoftInputMode="adjustPan"
        if (mPopupWindow == null) return;
        setInputMethodVisible(false);  // 输入法挤占窗口底部的按钮空间，listView的高度也会不对
        mPopupWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        mPopupWindow.setHeight(mListView.getHeight());
        mPopupWindow.getContentView().setTranslationX(0);  // 滑一点就回去时此值未重置(点标题栏按钮会错)
        setPopupWindowCheckBox(mAdapter.getQueryType(), mAdapter.getQuerySort(), mAdapter.getQueryField());
    }

    private boolean canPopupWindowScroll() {
        ScrollView scroller = (ScrollView) mPopupWindow.getContentView().findViewById(R.id.scrollView_filter);
        return (scroller.getScrollY() > 0 || scroller.canScrollVertically(1));  // 可往上或往下滚
    }

    private PopupWindow newPopupWindow() {
        // newPopupWindowInstance名字太长emm
        View view = getLayoutInflater().inflate(R.layout.popup_filter, null);
        PopupWindow window = new PopupWindow(view, 0, 0, true);
        window.setOutsideTouchable(true);  // 允许点外边取消
        window.setBackgroundDrawable(new ColorDrawable());  // 有背景才能点外边，即使是透明
        window.setAnimationStyle(R.style.popup_anim_end);
//        window.showAtLocation(mListView, Gravity.END | Gravity.BOTTOM, 0, 0);
//        window.showAsDropDown(findViewById(R.id.filter));  // 自带动画；图标要always显示不然搜索时闪退
        setPopupWindowFolder(window);  // 先添加完表项才能设置监听
        setPopupWindowListener(window);  // 此时全局的mPopupWindow还没变
        return window;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w(TAG, "onCreate. State = " + savedInstanceState);
        boolean night = getSharedPreferences("Settings", MODE_PRIVATE).getBoolean("NightTheme", false);
        setTheme(night ? R.style.AppTheme_Night : R.style.AppTheme_NoActionBar);
        PageFragment.setNightTheme(night);  // 目前必须弄不然ListView的item布局主题会错

        super.onCreate(savedInstanceState);  // 这里面也是先改主题再调super，不过要安卓6.0
        setContentView(R.layout.activity_main);
        sReference = new WeakReference<Context>(this);

        mToolbar = (Toolbar) findViewById(R.id.toolbar_main);
        mImageView = (ImageView) findViewById(R.id.imageView_divider);
        setSupportActionBar(mToolbar);  // 要求manifest里设置主题为AppTheme.NoActionBar
        mToolbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!MenuItemCompat.isActionViewExpanded(mSearchItem)) {
                    if (mListView.getFirstVisiblePosition() > 50) {
                        mListView.setSelection(50);
                    }
                    mListView.smoothScrollToPositionFromTop(0, 0, 300);  // 量大时不能在时限内到顶
                }
            }
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayShowHomeEnabled(false);  // 去掉标题栏图标

        mAdapter = new ListViewAdapter(this, R.layout.listview_item);
        mListView = (ListView) findViewById(R.id.listView_data);
        mFooterView = LayoutInflater.from(this).inflate(R.layout.listview_footer, null);
        mListView.addFooterView(mFooterView, null, false);  // 不可点击
        mListView.setOnScrollListener(new onScrollListener());  // 立刻触发OnScroll
        mListView.setOnTouchListener(new onPopupTouchListener());
        mListView.setOnItemClickListener(new onItemClickListener());
        mListView.setAdapter(mAdapter);  // 要在setAdapter前加addFooterView，不然不显示

        mPendingQueryTask = new Runnable() {
            @Override
            public void run() {
                mAdapter.setQuery(mQueryText);
                mAdapter.addSomeAsync();    // 空串和只有空格时点提交不触发Submit
                mListView.setSelection(0);  // 清空后回到列表顶端
                pushQueryHistory(2);        // 第二次以后都是更新搜索
            }
        };

        mAdapter.dbOpen();  // 可能是异步操作；其他的listener要在open后添加getCount才准
        mAdapter.addListener(new SQLiteHelper.AsyncTaskListener() {
            @Override
            public void onStart(AsyncTask task) {
                setFooterViewProgress(0.0);
            }

            @Override
            public void onAsyncFinish(AsyncTask task) {}

            @Override
            public void onFinish(AsyncTask task) {
                setFooterViewProgress(mAdapter.hasMore() ? 0.0 : 1.0);  // adapter要notify后getCount才准
            }
        });
        mAdapter.addListener(new SQLiteHelper.AsyncTaskListener() {
            @Override
            public void onStart(AsyncTask task) {}

            @Override
            public void onAsyncFinish(AsyncTask task) {}

            @Override
            public void onFinish(AsyncTask task) {
                mAdapter.removeListener(this);
                mFolderList = mAdapter.getFolderName();  // 数据库开完才能获取收藏夹列表
                mPopupWindow = newPopupWindow();  // 会用到上面的收藏夹列表
            }
        });  // 一次性的筛选窗口初始化
        if (savedInstanceState == null) {
            mAdapter.addSomeAsync();  // 若数据库为空在上面异步完成后也会调用
        } else {
            mQueryHistory = savedInstanceState.getParcelableArrayList("mQueryHistory");
            mPopHistoryOnRecreate = true;  // 之后的onResume里再pop
        }  // 重启时onCreate -> onStart -> onPostCreate -> onActivityResult -> onNewIntent -> onResume
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.w(TAG, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);

        boolean night = getSharedPreferences("Settings", MODE_PRIVATE).getBoolean("NightTheme", false);
        int title_color = night ? android.R.attr.textColorPrimary : android.R.attr.textColorPrimaryInverse;
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(title_color, typedValue, true);
        title_color = ResourcesCompat.getColor(getResources(), typedValue.resourceId, null);

        View newContentView = getLayoutInflater().inflate(R.layout.activity_main,
                (ViewGroup) getWindow().getDecorView(), false);
        Toolbar newToolBar = (Toolbar) newContentView.findViewById(R.id.toolbar_main);
        mToolbar.setLayoutParams(newToolBar.getLayoutParams());
        mToolbar.setTitleTextAppearance(this, R.style.TextAppearance_Toolbar_Title);  // 没有单独设置大小的
        mToolbar.setTitleTextColor(title_color);  // 颜色也没有get方法，只能设置样式再重弄颜色了

        if (mPopupWindow.isShowing()) {
            mPopupWindow.getContentView().postDelayed(new Runnable() {
                @Override
                public void run() {mPopupWindow.update(-1, mListView.getHeight());}
            }, 150);
        }  // 100ms可能无效，200ms延迟明显
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.w(TAG, "onActivityResult. Intent = " + data);
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ContentActivityCode && data != null) {  // 返回时滚动到看到的项
            int top = data.getIntExtra("position", 0);
            int last = mListView.getLastVisiblePosition();
            int first = mListView.getFirstVisiblePosition();
            if (top <= first || top >= last) {  // 在屏幕范围内不移(头尾只露出一半的不算)
                top -= (last - first) / 2;      // 其余时在完整的项基础上稍微下移一点
                top = Math.max(0, top);         // top小于0列表会忽略此操作
                float scale = getResources().getDisplayMetrics().density;
                int dy = (int) (50 * scale + 0.5f);  // 50dp->px
                mListView.setSelectionFromTop(top, (top > 0) ? -dy : 0);  // top为0时不必往下
            }
            onNewIntent(data);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // 设为SingleTask时清内存后的重入也调用此(onCreate后)，注意参数intent与getIntent()不同，后者是LAUNCHER
        // 然而回桌面后从桌面点图标也触发这个，还把别的窗口都关了，于是改为在ActivityResult里处理
        //super.onNewIntent(intent);  // 不是系统来调用时就不调用super了
        //Log.w(TAG, "onNewIntent. Intent = " + intent);
        if (intent.hasExtra("key") && intent.hasExtra("field")) {
            mPopHistoryOnRecreate = false;  // 这里已经开始addSome，onResume就不要pop(里面也有addSome)
            mQueryText = intent.getStringExtra("key");  // 清内存重入时让onCreateOptionsMenu继续处理
            mAdapter.setQuery(mQueryText, intent.getStringExtra("field"), "", null);
            mAdapter.addSomeAsync();
            expandSearchView();   // 修改后展开才会自动改搜索词
            setFilterIcon();      // 这个和setQuery应该是一伙的
            pushQueryHistory(3);  // 改后再保存历史(不然再push是覆盖普通搜索那条)
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPopHistoryOnRecreate) {
            Log.w(TAG, "onResume");
            popQueryHistory();  // 里面调用addSomeAsync，但从ContentActivity返回时可能调用无效(如就一个结果)
            setFooterViewProgress(mAdapter.hasMore() ? 0.0 : 1.0);  // 重启时默认转圈，若调用无效就一直转
            mPopHistoryOnRecreate = false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // 开新窗口、切换程序、回桌面、锁屏等都会进来，当然退出时不会
        // 设置了android:id属性的控件可以保存和恢复数据，其他就要自己来
        Log.w(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(outState);
        pushQueryHistory(3);
        outState.putParcelableArrayList("mQueryHistory", mQueryHistory);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 只有窗口创建的时候会进来一次，一般在onCreate和onNewIntent之后
        //Log.w(TAG, "onCreateOptionsMenu");
        getMenuInflater().inflate(R.menu.menu_main, menu);

        mFilterItem = menu.findItem(R.id.filter);

        mSearchItem = menu.findItem(R.id.search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(mSearchItem);
        //SearchView searchView = (SearchView) mSearchItem.getActionView();  // ActionBar时用这
        searchView.setQueryHint(getString(R.string.main_search_hint));
        searchView.setOnQueryTextListener(new onQueryTextListener());
        MenuItemCompat.setOnActionExpandListener(mSearchItem, new onActionExpandListener());
        //mSearchItem.setOnActionExpandListener(new onActionExpandListener());  // ActionBar时用这

        if (mQueryText != null && !mQueryText.isEmpty()) {
            expandSearchView();  // 清内存后从ContentActivity点标题重入时恢复搜索框状态
        }
        setFilterIcon();  // 筛选图标也要对应恢复
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.search:
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);
                searchView.onActionViewExpanded();  // 改用ToolBar后得默认展开，不然要点两次
                searchView.requestFocus();  // 第二次点开就不自动聚焦文本框了
                setInputMethodVisible(true);  // 聚焦也不自动弹出输入法了
                return true;
            case R.id.filter:
                onDisplayPopupWindow();
                mPopupWindow.showAtLocation(mListView, Gravity.END | Gravity.BOTTOM, 0, 0);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        Log.w(TAG, "onDestroy");
        super.onDestroy();  // 开子窗口onPause 切到后台onStop 清理内存onDestroy
        mListView.removeCallbacks(mPendingQueryTask);
        mPendingQueryTask = null;
        mAdapter.cancelAsync(true);  // 取消了才能安全关掉数据库
        mAdapter.dbClose();
        sReference = null;
    }
}
