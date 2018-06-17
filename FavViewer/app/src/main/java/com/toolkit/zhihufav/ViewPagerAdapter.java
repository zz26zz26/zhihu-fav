package com.toolkit.zhihufav;

import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v13.app.FragmentStatePagerAdapter;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import com.toolkit.zhihufav.util.SQLiteHelper;

import java.io.File;
import java.util.ArrayList;

/**
 * Created on 2018/3/13.
 * Provide data for ContentActivity.
 */

// PagerAdapter会在加载当前页后预载左右两页，并在滑动后删除远端的页
// 原来的FragmentPagerAdapter会把每一页都存在FragmentManager里(用到getItem生成)
// 删除时清视图留实例，再次需要时(用到instantiateItem)在FragmentManager按标签查找
// FragmentStatePagerAdapter则会释放离当前页远的，只保留Fragment的状态
class ViewPagerAdapter extends FragmentStatePagerAdapter {

    private static final String TAG = "ViewPagerAdapter";

    private Context mContext;
    private Fragment mCurrentPage;           // 当前页面
    private SQLiteHelper mDataSource;        // 数据源
    private ArrayList<Bitmap> mTitleImages;  // 头图缓存，与super.Fragments均为只存ViewPager预载的页

    private int mCount;                      // 上次notify的数量(防止数据源更新过程中报未notify异常)
    private boolean mFinding;                // 正在查找
    private String mFindQuery;               // 查找的文本
    private WebView.FindListener mFindListener;
    private SQLiteHelper.AsyncTaskListener mTaskListener;


    /**
     * A {@link FragmentStatePagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    public ViewPagerAdapter(Context ctx, FragmentManager fm) {
        super(fm);
        mContext = ctx;
        mFindQuery = "";
        mFinding = false;
        mTitleImages = new ArrayList<>();
        mFindListener = new WebView.FindListener() {
            private Toast toast = null;

            private void makeToast(String msg) {
                if (toast == null) {
                    toast = Toast.makeText(mContext, msg, Toast.LENGTH_SHORT);
                } else {
                    toast.setText(msg);  // 若之前Toast的没消失，可以直接换文本，并重新计时
                }
                toast.show();
            }

            @Override
            public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting) {
                if (!isDoneCounting) return;  // 开始找时进一次，找到又进一次；包括找全部和找下一个

                if (!mFinding) {
                    mFinding = true;  // 之后不弹出提示
                    String msg = mContext.getString(R.string.search_no_result);
                    if (numberOfMatches > 0)
                        msg = mContext.getString(R.string.search_result_count, numberOfMatches);
                    makeToast(msg);
                } else if (numberOfMatches > 1) {
                    String msg = mContext.getString(R.string.search_last_result);
                    if (activeMatchOrdinal < numberOfMatches - 1)
                        msg = mContext.getString(R.string.search_result_index, activeMatchOrdinal + 1, numberOfMatches);
                    makeToast(msg);
                }
            }
        };

        SQLiteHelper database = SQLiteHelper.getReference();
        if (database != null) {
            mDataSource = database;  // 不必到dbOpen才开，因为不考虑在这里清空重建数据库
        } else {
            mDataSource = new SQLiteHelper(mContext.getExternalFilesDir(null));
        }
        mTaskListener = new SQLiteHelper.SimpleAsyncTaskListener() {
            @Override
            public void onFinish(AsyncTask task) {notifyDataSetChanged();}
        };
        addListener(mTaskListener);

        // 不设置默认是0(透明)，即显示窗口背景色
        int color = PageFragment.isNightTheme() ? R.color.item_background_night : R.color.item_background;
        PageFragment.setBackColor(mContext.getResources().getColor(color));
        PageFragment.openCache(mContext);
    }

    void dbDetach() {
        Log.w(TAG, "dbDetach");
        if (mDataSource != null) {
            removeListener(mTaskListener);
            mDataSource = null;
            notifyDataSetChanged();  // 置空后还要通知，免得别处addSome抛未notify异常
        }
        if (mTitleImages != null) {
            mTitleImages.clear();  // 没有引用的bitmap会被gc释放
        }
        PageFragment.closeCache();
    }

    void addSomeAsync(int minItemCount) {
        if (mDataSource != null) {
            mDataSource.addSomeAsync(minItemCount, false);
        }
    }

    void addListener(SQLiteHelper.AsyncTaskListener listener) {
        if (mDataSource != null) {
            mDataSource.addOnAsyncTaskListener(listener);
        }
    }

    void removeListener(SQLiteHelper.AsyncTaskListener listener) {
        if (mDataSource != null) {
            mDataSource.removeOnAsyncTaskListener(listener);
        }
    }

    long tryBaseQuery(String key, String field, String type) {
        if (mDataSource == null) return 0;
        return mDataSource.getQueryCount(key, field, type);
    }

    String[] getBaseQuery() {
        if (mDataSource != null) {
            return new String[] {mDataSource.getQueryKey(), mDataSource.getQueryField(),
                    mDataSource.getQueryType(), mDataSource.getQuerySort()};
        }
        return null;
    }

    void setBaseQuery(String[] query) {
        if (mDataSource != null && query != null && query.length == 4) {
            mDataSource.setQuery(query[0], query[1], query[2], query[3]);
        }
    }

    void setQuery(WebView webView, String query, boolean find_forward) {
        if (!mFindQuery.equals(query) || query.isEmpty()) {
            mFindQuery = query;  // 找新的或不找了都要重置一下
            mFinding = false;
            if (webView != null)
                webView.clearMatches();
            if (webView != null && !query.isEmpty()) {
                webView.setFindListener(mFindListener);  // 不每次都new了
                webView.findAllAsync(query);  // 找完触发监听
            }
        }
        else if (webView != null && mFinding) {
            webView.findNext(find_forward);  // 执行过findAll后也会触发监听
        }
    }

    void reloadToMode(WebView webView, int mode) {
        // 可能存在调用时mCurrentPage还没初始化的情况，如遇ViewPager首次layout不会调用setPrimaryItem
        if (webView != null) {
            switch (mode) {
                case PageFragment.MODE_START:  // 可能是图片页返回或是刷新
                    String home = getPageLink(PageFragment.getPageIndex(webView));
                    setQuery(webView, "", true);  // 不然刷新完搜索虽能移动，但不显示高亮
                    PageFragment.loadStartPage(webView, home);
                    break;

                case PageFragment.MODE_IMAGE:  // 图片和视频页只会在同类页刷新
                    String url = (String) webView.getTag(R.id.web_tag_url);  // getUrl是about:blank
                    PageFragment.loadImagePage(webView, url);
                    break;

                case PageFragment.MODE_VIDEO:
                    webView.reload();
                    break;
            }
        }
    }

    void updateTitleImageAsync(int position) {
        new AsyncTask<Integer, Void, Bitmap>() {
            int position;

            @Override
            protected Bitmap doInBackground(Integer[] params) {
                position = params[0];
                String link = getPageTitleImageLink(position);
                if (link == null) return null;
                File cache = PageFragment.getUrlCache(link);  // 读磁盘+读图会对动画造成可见卡顿(~50ms)
                if (cache == null) return null;
                return BitmapFactory.decodeFile(cache.getAbsolutePath());
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                super.onPostExecute(bitmap);
                while (mTitleImages.size() <= position) {
                    mTitleImages.add(null);
                }
                if (mTitleImages.get(position) != null) {
                    mTitleImages.get(position).recycle();
                }
                mTitleImages.set(position, bitmap);
            }
        }.executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, position);
    }


    void setEntryPage(int pos) {
        PageFragment.setEntryPage(pos);
    }

    Fragment getCurrentPage() {
        return mCurrentPage;
    }  // 差不多就是getPrimaryItem

    @Override
    public void setPrimaryItem(ViewGroup container, int position, Object object) {
        // 换页时本函数被调用3次，第一次时会调两页的setUserVisibleHint，一页显示 一页隐藏
        // 即第一次调用setUserVisibleHint时，mCurrentPage还没变(当然它有自己的参数用不到这个)
        // 后两次是在ViewPager的绘制过程中的onMeasure里调用的，且不再调setUserVisibleHint
        mCurrentPage = (Fragment) object;
        super.setPrimaryItem(container, position, object);
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        Log.w(TAG, "instantiateItem, position = " + position);
        updateTitleImageAsync(position);
        return super.instantiateItem(container, position);
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        Log.w(TAG, "destroyItem, position = " + position);
        if (mTitleImages.get(position) != null) {
            mTitleImages.get(position).recycle();
            mTitleImages.set(position, null);
        }
        super.destroyItem(container, position, object);
    }

    @Override
    public void notifyDataSetChanged() {
        mCount = mDataSource == null ? 0 : mDataSource.getCount();
        super.notifyDataSetChanged();  // 若getCount结果与上次notify此ViewPager时不同就会抛异常
    }

    @Override
    public Fragment getItem(int position) {
        // getItem is called to instantiate the fragment for the given page.
        // Return a PageFragment (defined as an independent class).
        return PageFragment.newInstance(position, getPageLink(position), getPageContent(position));
    }

    @Override
    public int getCount() {  // 这里调notifyChanged会递归
        return mCount;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        if (mDataSource == null) return null;
        String[] item = mDataSource.getItem(position);
        return (item == null) ? null : item[SQLiteHelper.getColumnIndex("title")];
    }

    String getPageUser(int position) {
        if (mDataSource == null) return null;
        String[] item = mDataSource.getItem(position);
        return (item == null) ? null : item[SQLiteHelper.getColumnIndex("name")];
    }

    String getPageDate(int position) {
        if (mDataSource == null) return null;
        String[] item = mDataSource.getItem(position);
        return (item == null) ? null : item[SQLiteHelper.getColumnIndex("revision")];
    }

    String getPageLink(int position) {
        if (mDataSource == null) return null;
        String[] item = mDataSource.getItem(position);
        return (item == null) ? null : "https://" + item[SQLiteHelper.getColumnIndex("link")];
    }

    String getPageContent(int position) {
        if (mDataSource == null) return null;
        String[] item = mDataSource.getItem(position);  // 下面return只有删库重建中才用上
        if (item == null) return "";

        String link = item[SQLiteHelper.getColumnIndex("link")];  // getPageLink会加上https前缀
        String content_html = item[SQLiteHelper.getColumnIndex("content")];
        // CSS边距由外到内：offset - margin - border - padding；颜色简写如#f35 = #ff3355 (不支持ARGB)
        // 属性顺序为top - right - bottom - left (不写全的时候 right=top, bottom=top, left=right)
        // 定位absolute不占原空间，而是直接覆盖；偏移原点在最近非默认定位的父级；可用上下左右:0+margin:auto居中
        // 设置figure内图片宽度满屏(-1.22em可满但又不能盖住blockquote的框，img的100%是父容器宽度)
        // 单纯img(公式图)要与文字垂直居中，而视频的图片不能超宽 (img:after加载成功后失效，但Edge都不支持)
        // 视频图片上的播放按钮，要绝对定位才能覆盖图片(外层a也要定位)，再用无内容区的边框画三角
        // 表格(ol ul)缩进和表项(li)序号位置在外；链接(a)取消了下划线，视频链接换透明色，图片满宽
        // 预格式化pre一般包裹code(若只弄code则只有有字的地方有背景)，非text代码不换行(auto表示过长时有滚动条)
        // 同理，设置属性white-space:pre可使css的content内容中的换行(\A)保留
        // 夜间模式对比度别太高，尤其黑底白字显得像那啥…另可调整一些颜色使得日夜模式都看得清(background:#123;)
        // 例：RoyalBlue #4169E1；Gray #808080；DarkGray #A9A9A9；LightSlateGray #778899；WhiteSmoke #F5F5F5
        String rule_color = " #aaa;";  // <hr>颜色，保留开头空格和结尾分号，日间不能太暗
        String note_color = "color:#777;";  // 图片说明文字和末尾答案信息颜色，日夜都能看清
        String block_back = "background:rgba(128,128,128,.1);";  // 底色即gray，之前是night ?"#333":"whitesmoke"
        String first_figure = link.startsWith("zhuanlan") ? "body>figure:first-child { display:none }" : "";  // 头图无边距margin-top:-1em;

        String css = "body { margin:1em 1.25em; word-wrap:break-word; line-height:1.6; font-size:16px; }" +
                     "blockquote { margin:1em 0; padding-left:0.7em; color:lightslategray; border-left:0.3em solid; }" +
                     "figure { position:relative; margin:16px -1.25em; }  blockquote figure { margin:16px -0.5em }" + first_figure +
                     "figure.gif-container::after { position:absolute; left:50%; top:50%; transform:translate(-52%,-45%); color:whitesmoke;" +
                                                   "content:'GIF'; font:bold 6vw arial; text-align:center; z-index:1; }" +
                     "img { position:relative; max-width:100%; vertical-align:middle; }  figure img { width:100% !important }" +
                     "img::before { position:absolute; width:100%; height:100%; content:'';" + block_back + "}" +
                     "figcaption { margin:1em; font-size:14px; text-align:center;" + note_color + "}" +
                     "hr { margin:32px auto; width:62%; border:none; border-top:1px solid " + rule_color + "}" +
                     "ol, ul { margin:1em 0; padding-left:1em; }  ol li, ul li { list-style-position:outside }" +
                     "a { text-decoration:none; color:royalblue; }  a.video-box .content { display:none }" +
                     "a.video-box { position:relative; display:block; margin:1em -1.25em; height:55vw; overflow:hidden;" +
                                   "border-radius:2px; box-shadow:0px 0px 5px rgba(0,0,0,.2);" + block_back + "}" +
                     "figure.gif-container::before, " +
                     "a.video-box::before { position:absolute; left:50%; top:50%; transform:translate(-50%,-50%); background:rgba(0,0,0,.3);" +
                                           "border:solid 0.8vw whitesmoke; border-radius:15vw; height:15vw; width:15vw; content:''; z-index:1; }" +
                     "a.video-box::after { position:absolute; left:50%; top:50%; transform:translate(-35%,-50%); border-left:5vw solid whitesmoke;" +
                                          "border-top:3vw solid transparent; border-bottom:3vw solid transparent; content:''; z-index:1; }" +
                     "a.video-box img { position:absolute; top:50%; transform:translateY(-50%); width:100%; }" +
                     "pre { margin:1em 0; padding:10px; border-radius:4px;" + block_back + "overflow:auto; }" +
                     "code { font-size:14px }"; //  code:not(.language-text) { word-wrap:normal }";  // overflow放code里没用
        String end = "<br><br><p style=\"text-align:right;" + note_color + "\">Q.E.D." +
                     "<br>作者：" + getPageUser(position) +
                     "<br>编辑于 " + getPageDate(position) + "</p>" +

                     "<script>" +
                     "function on_init() {" +
                     "    var img = document.getElementsByTagName('img');" +
                     "    var len = img.length;" +  // for-in会把元素属性一起遍历，for-of要5.1的浏览器才支持
                     "    for (var i = 0; i < len; i++) {" +  // 保留src属性以便有缓存时立即读取，没缓存的在此点击后正好出错换图
                     "        img[i].setAttribute('data-src', img[i].src);" +  // 就不在html里替换了，注意在绑定事件前设置
                     "        img[i].onclick = function () {" +
                     "            if (this.hasAttribute('data-src')) {" +  // 这里出异常lazy_load就跑不下去
                     "                this.src = this.getAttribute('data-src');" +  // 要防止多次点击造成出error前改src
                     "                this.removeAttribute('data-src');" +  // this指调用处最后一个点.之前的对象，即img
                     "                console.log('onClick: ' + this.src);" +
                     "            }" +  // 若这里又定义个函数f并由setTimeout调用，则f里的this指向window
                     "        };" +     // 可理解为最后是由window.setTimeout调用的f（图多时定时器太多会慢）
                     "        img[i].onerror = function () {" +  // 不能改成大写；若无src载入时也触发此
                     "            if (!this.hasAttribute('data-src')) {" +  // has('src')不行否则第二次data-src变空或file://
                     "                var f = this.hasAttribute('eeimg') || this.className === 'thumbnail';" +  // 公式/视频
                     "                this.setAttribute('data-src', this.src);" +
                     "                this.src = f ? '' : 'file:///android_asset/file_download_placeholder.svg';" +
                     "                console.log('onError: ' + this.getAttribute('data-src'));" +
                     "            }" +  // src=''不显示裂开图标，且保留img::before背景(但视频框里的图没有)；本地文件不存在都有图裂
                     "        };" +     // 与https共用时要开启混合模式才能访问file:// (当然每次都有警告)
//                     "        img[i].onload = function () {" +
//                     "            if (this.src.endsWith('.jpg') || this.src.endsWith('.png')) {" +
//                     "                var obj = this;" +
//                     "                var xhr = new XMLHttpRequest();" +
//                     "                xhr.onload = function () {" +  // readyState == 4 (DONE)
//                     "                    if (xhr.status === 200 && xhr.getResponseHeader('Content-Type') === 'image/gif') {" +
//                     "                        obj.parentNode.className = 'gif-container';" +
//                     "                    }" +
//                     "                };" +
//                     "                xhr.open('HEAD', this.src.substring(0, this.src.length - 4) + '.gif?head');" +
//                     "                xhr.send(null);" +  // HEAD的Request照样会被Intercept，要标记一下
//                     "            }" +  // TODO 超过一帧才标注/用webp原图更小但保存看不了/主要是现在刷新一次又从头来！！！搞得初始化很久(有缓存即触发此)
//                     "        };" +     // TODO 回答/文章页面里，动图的data-actualsrc直接是gif结尾；只有收藏里仍是jpg（但收藏里帮弄好专栏头图，html也简洁）
                     "        img[i].onclick();" +  // 有的图加载完才绑定事件，只好强行再加载一轮(反正是缓存)，使状态正确
                     "    }" +
                     "    ImageArray = img;" +  // 前面不带var的是window全局变量
                     "    ImageLoader = throttle(lazy_load(1), 250);" +  // onclick已经remove了data-src，这里再跑就出错
                     "}" +  // 一般用流量时disable禁止自动下图，用wifi时enable启用
                     "function load_first() { ImageArray[0].onclick(); }" +  // 反正js里没人调用，出异常正好不干
                     "function enable_load() { window.addEventListener('scroll', ImageLoader); }" +  // 重复add还是一个
                     "function disable_load() { window.removeEventListener('scroll', ImageLoader); }" +
                     "function active_load() { for (var i = 0; i < ImageArray.length; i++) ImageArray[i].onclick(); }" +
                     "function lazy_load(init) {" +
                     "    var img = ImageArray;" +
                     "    var n = 0, len = img.length;" +
                     "    var f = function () {" +
                     "        if (n >= len) { disable_load(); return; }" +
                     "        var boxHeight = document.body.clientHeight;" +  // scrollHeight是网页总高度
                     "        for (; n < len; n++) {" +  // getBoundingClientRect原点在显示区域左上角
                     "            if (img[n].getBoundingClientRect().top < boxHeight * 2)" +  // 提前一屏准备
                     "                img[n].onclick();" +  // img.offsetTop在父级css设置position后是相对父级的偏移
                     "            else if (img[n].src.length === 0 || img[n].src.indexOf('file:') === 0)" +  // 4.4没startsWith
                     "                break;" +  // 没缓存的才要等滑到它；已下好就让n继续++，全都下好即可提前disable
                     "        }" +
                     "        console.log(n >= len ? 'Load done!' : 'Next: img #' + n + ', dist = ' + " +
                     "                    (img[n].getBoundingClientRect().top - boxHeight * 2));" +
                     "    };" +
                     "    if (!init) { f(); }" +  // 用于单独调用时载入当前屏幕的图片
                     "    return f;" +
                     "}" +
                     "function throttle(func, wait) {" +    // 对于连续多次操作：
                     "    var last = 0, timeout = null;" +  // throttle操作中每间隔t执行一次
                     "    return function () {" +           // de-bounce等操作间隔>t执行(即仅最后一次)
                     "        var curr = new Date().valueOf();" +
                     "        clearTimeout(timeout);" +
                     "        if (curr - last > wait) {" +
                     "            func(); last = curr;" +
                     "        } else {" +
                     "            var f = function () { func(); last = curr + wait; };" +
                     "            timeout = setTimeout(f, wait);" +
                     "        }" +
                     "    }" +
                     "}" +
                     "function set_theme(t) {" +
                     "    var night = t;" +  // true即夜间
                     "    var img = ImageArray;" +  // 夜间把图片弄透明点不那么亮，公式和文字也要变色
                     "    document.getElementsByTagName('body')[0].style.color = night ? 'darkgray':'#111';" +
                     "    for (var i = 0; i < img.length; i++) {" +
                     "        if (img[i].hasAttribute('eeimg'))" +  // 0xa9/0xff = 66%, 0x11/0xff = 6.6%
                     "            img[i].style.WebkitFilter = night ? 'invert(66%)' : 'invert(6.6%)';" +  // .filter不行
                     "        else" +
                     "            img[i].style.opacity = night ? 0.6 : 1.0;" +
                     "    }" +
                     "}" +
                     "on_init();" +  // 默认不开启滚动时自动加载图片
                     "set_theme(" + PageFragment.isNightTheme() + ");" +
                     "console.log('Javascript initialized.');" +
                     "</script>";
        //"<noscript>Javascript unavailable.</noscript>"  // 旧版遇src=""会请求当前页面url；没有src直接没图
        //content_html = content_html.replaceAll("(<img[^>]*?) src=", "$1 data-src=");  // 加个?快些
        // 处理了<pre>内容与ViewPager左右滑动时的冲突(估计要js设置点击事件)，才能让代码块不换行显示
        // 但是有些<pre>块一行太长，且一般不分代码或文本，不便于判断滑动…还不如横屏

        String head = "<head><style>" + css + "</style></head>";  // <meta charset="utf-8" />
        String body = "<body>" + content_html + end + "</body>";
        // 之前用替换宽度为100%的方法使图片尺寸适合屏幕(没width属性的仍过大)
        //body = body.replaceAll("(<\\w+[^>]+ width=\")\\d*(\"[^>]+>)", "$1100%$2");  // $后只看第一个数字
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            head = head.replace("transform:", "-webkit-transform:");  // 4.4的浏览器不能识别标准变换
            head = head.replace("border:solid", "-webkit-border:solid");  // 边框不能圆角(内容能)，弄成这样不显示
        }
        content_html = head + body;
        return content_html;
    }

    String getPageTitleImageLink(int position) {
        if (mDataSource == null) return null;
        String[] item = mDataSource.getItem(position);
        if (item == null || !getPageLink(position).contains("zhuanlan")) return null;
        String html = item[SQLiteHelper.getColumnIndex("content")];
        if (!html.startsWith("<figure>")) return null;
        int begin = html.indexOf(" src=\"");
        return html.substring(begin + 6, html.indexOf("\"", begin + 6));
    }

    Bitmap getPageTitleImage(int position) {
        if (position < 0 || position >= mTitleImages.size()) return null;
        return mTitleImages.get(position);
    }

}
