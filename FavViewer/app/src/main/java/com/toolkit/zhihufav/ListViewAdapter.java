package com.toolkit.zhihufav;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.toolkit.zhihufav.util.SQLiteHelper;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created on 2018/3/10.
 */


class ListViewAdapter extends BaseAdapter {

    private static final String TAG = "ListViewAdapter";

    private ArrayList<String> mInfo;            // 附加信息缓存
    private ArrayList<String> mTitle;           // 题目文本缓存
    private ArrayList<String> mSummary;         // 回答摘要缓存(以平滑滚动)
    private Map<String, Pattern> mPatternMap;   // 常用正则表达式模式

    private Context mContext;                   // 数据库位置
    private SQLiteHelper mDataSource;           // 数据库连接

    private int mCount;                         // 已显示表项数(防止mInfo等添加过程中报未notify异常)
    private int mResource;                      // 表项布局文件
    private LayoutInflater mInflater;           // 表项布局填充
    private static class ViewHolder {           // 表项布局内容
        TextView title;
        TextView summary;
        TextView info;
    }

    ListViewAdapter(Context context, int resource) {
        setPatternMap();
        mInfo = new ArrayList<>();
        mTitle = new ArrayList<>();
        mSummary = new ArrayList<>();
        mCount = 0;
        mDataSource = null;
        mContext = context;
        mResource = resource;
        mInflater = LayoutInflater.from(context);
    }

    void dbOpen() {
        if (mDataSource == null) {
            SQLiteHelper database = SQLiteHelper.getReference();
            if (database != null) {
                mDataSource = database;  // 可能杀后台之后从ContentActivity进来，在那里初始化了
            } else {
                mDataSource = new SQLiteHelper(mContext.getExternalFilesDir(null));
            }  // getExternalFilesDir帮建目录；若访问SD卡的根要权限

            addListener(new SQLiteHelper.AsyncTaskListener() {
                @Override
                public void onStart(AsyncTask task) {}

                @Override
                public void onAsyncFinish(AsyncTask task) {
                    onDataSourceUpdated(task);
                }

                @Override
                public void onFinish(AsyncTask task) {
                    notifyDataSetChanged();
                }
            });
        }
    }

    void dbClose() {
        clear();
        notifyDataSetInvalidated();
        mDataSource.close();
        mDataSource = null;
    }

    void clear() {
        mInfo.clear();   // 以前用ArrayAdapter的clear()会调用notifyDataSetChanged()
        mTitle.clear();  // 这样在doInBackground里用会出错(线程终止，但不抛异常)
        mSummary.clear();
        mDataSource.clear();
    }

    boolean hasMore() {
        return mDataSource != null && mDataSource.hasMore();
    }

    String getQueryText() {
        return mDataSource.getQueryKey();
    }

    String getQuerySort() {
        return mDataSource.getQuerySort();
    }

    String getQueryType() {
        return mDataSource.getQueryType();
    }

    String getQueryField() {
        return mDataSource.getQueryField();
    }

    String[] getFolderName() {
        return mDataSource.getFolderName();
    }

//    long tryQuery(String text, String field, String type) {
//        // 按正文搜索时(主要是搜英文)，容易把仅在html标签中出现的词也算入，不准…
//        if (mDataSource == null) return 0;
//        return mDataSource.getQueryCount(text, field, type);
//    }

    void setQuery(CharSequence text) {
        setQuery(text.toString(), null, null, null);
    }

    void setQuery(String text, String field, String type, String sort) {
        // 传入null时即不改变现状，但也要判断是否事实上没变
        // 如被kill后从ContentActivity启动，则查询已经恢复过，参数事实上没变，不要清掉重来
        if (mDataSource == null ||
                ((field == null || getQueryField().equals(field)) &&
                        (text == null || getQueryText().equals(text)) &&
                        (type == null || getQueryType().equals(type)) &&
                        (sort == null || getQuerySort().equals(sort)))) {
            return;
        }
        mDataSource.setQuery(text, field, type, sort);

        // 无关键字时只改field，结果不变不要清掉；但设置还是要改的
        if (text == null && getQueryText().isEmpty() && type == null && sort == null) return;

        clear();  // async会改Offset HasMore等，取消完(onCancelled)还得clear，这是没有要取消的task时用的
        cancelAsync(false);  // 更新查找词后立即停掉原来的按新的找，且不能等不然连续删字会卡
        notifyDataSetChanged();  // clear之后要调用一次不然没效果
    }

    void addSomeAsync() {
        addSomeAsync(10);
    }

    void addSomeAsync(int minItemCount) {
        if (mDataSource != null) {
            mDataSource.addSomeAsync(minItemCount, false);  // 干完在后台调用onDataSourceUpdated
        }
    }

    void cancelAsync(boolean waitForCancel) {
        if (mDataSource != null) {
            mDataSource.cancelAsync(waitForCancel);
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

    void formatTimeInfo(boolean formatDuration) {
        int rev_index = SQLiteHelper.getColumnIndex("revision");
        long now = System.currentTimeMillis();  // 与下面getTime都是1970年算起
        String divider = mContext.getString(R.string.info_divider);
        String regex = String.format(Locale.CHINA, "(?<=%1$s).*?(?=%1$s)", divider);
        Pattern pattern = Pattern.compile(regex);  // 匹配两个分隔号之间(不包括分隔号)
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);  // 自带无此格式

        for (int i = 0, z = mInfo.size(); i < z; i++) {
            String[] row = mDataSource.getItem(i);
            if (row == null) break;

            String revision = row[rev_index];
            if (formatDuration) {
                try {
                    long days = dateFormat.parse(revision).getTime();
                    days = (now - days + 86399999) / 86400000;  // 向上取整；单位ms
                    if (days < 31)
                        revision = String.valueOf(days) + " 天前";
                    else if (days < 366)
                        revision = String.valueOf(days / 30) + " 个月前";
                    else
                        revision = String.valueOf((int) (days / 365.25f)) + " 年前";
                } catch (Exception e) {
                    Log.e(TAG, "formatTimeInfo: " + e.toString());
                }
            }
            mInfo.set(i, pattern.matcher(mInfo.get(i)).replaceFirst(revision));
        }
    }

    @Override
    public void notifyDataSetChanged() {
        mCount = mInfo.size();
        super.notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mCount;  // 直接用DataSource或Info的长度会引发未notify的异常
    }

    @Override
    public long getItemId(int position) {
        return position;
    }  // 抄自ArrayAdapter

    @Override
    public Object getItem(int position) {
        return mDataSource == null ? null : mDataSource.getItem(position);
    }

    @Override
    public @NonNull View getView(int position, View convertView, @NonNull ViewGroup parent) {
        // 第一次填满屏幕前convertView会有null；之后就算clear也能重用
        // 屏幕里每项都要建个容器(ViewHolder)来记录项里面的控件，不是整个类共用一个
        ViewHolder holder;
        boolean app_theme = PageFragment.isNightTheme();  // item空显然view必空
        Object item_theme = (convertView == null) ? null : convertView.getTag(R.id.tag_theme);

        if (item_theme == null || app_theme != (boolean) item_theme) {
            convertView = mInflater.inflate(mResource, null);  // 不写null就异常
            holder = new ViewHolder();
            holder.info = (TextView) convertView.findViewById(R.id.textView_info);
            holder.title = (TextView) convertView.findViewById(R.id.textView_title);
            holder.summary = (TextView) convertView.findViewById(R.id.textView_summary);
            convertView.setTag(holder);  // 这样即使在异步加载也能顺序不乱，因为移出屏幕时tag会被覆盖
            convertView.setTag(R.id.tag_theme, app_theme);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        // 构造完填数据；基类方法只允许xml有一个文本框，有layout会出错
        // 有时先clear过一会才notify，此期间可能出现position越界，因此要处理异常
        try {
            String info = mInfo.get(position);
            String title = mTitle.get(position);
            String summary = mSummary.get(position);

            holder.title.setText(Html.fromHtml(title));  // 4.4只能用这种调用
            holder.summary.setText(Html.fromHtml(summary));
            holder.info.setText(Html.fromHtml(info));
        } catch (Exception e) {
            holder.title.setText(String.valueOf(position));
            holder.summary.setText(e.toString());
            holder.info.setText(e.getLocalizedMessage());
            Log.e(TAG, "getView: " + e.toString());
        }
        return convertView;
    }


    private void setPatternMap() {
        // html中要显示的<写作&lt; 因此直接用<>判断标签
        // 视频标签a内无嵌套 不用平衡组，但要匹配最近的</a>
        // (?!xx)指字符串不含xx，其后接.表示不匹配空串(regex把两个字符之间都视为有一个空串)
        // 标签p br li pre figure blockquote之类都没属性，都改空格；正则比10+个replace快
        mPatternMap = new HashMap<>();
        mPatternMap.put("image", Pattern.compile("<img [^>]+>"));  // (?:(?!</a>).)*与.*?同效但慢？
        mPatternMap.put("video", Pattern.compile("<a class=\"video-box\".*?</a>"));  // ?:不建组可快些
        mPatternMap.put("space", Pattern.compile("</?(?:br|figure|p|blockquote|h.|li|pre)>"));  // 频率高者在前
        mPatternMap.put("label", Pattern.compile("<[^>]+>"));  // 加粗(b)/span/div等就不会空格；h.包括h1-h6和hr
        mPatternMap.put("white", Pattern.compile("\\s+"));

        // {P}除{Pc}，用于截取有关键词的文本片段 ({Pi}如前引号 {Ps}如前括号 {Pc}如_- {Z}如空格回车)
        mPatternMap.put("cutter", Pattern.compile("[\\p{Pd}\\p{Ps}\\p{Pe}\\p{Pi}\\p{Pf}\\p{Po}\\p{Z}]"));
        // {P}除{Pc}{Pi}{Ps}，用于跳过不能在行首的标点，使截取片段的行首符合规则
        mPatternMap.put("skipper", Pattern.compile("[\\p{Pd}\\p{Pe}\\p{Pf}\\p{Po}\\p{Z}]"));
    }

    private String getTextFromHtml(String html) {
        // 图片/视频先不换成文字，毕竟数据库里搜[视频]是找不到的(故意这么写文字的除外)
        html = mPatternMap.get("image").matcher(html).replaceAll(" \0 ");
        html = mPatternMap.get("video").matcher(html).replaceAll(" \1 ");
        html = mPatternMap.get("space").matcher(html).replaceAll(" ");  // 一些标签换为空格
        html = mPatternMap.get("label").matcher(html).replaceAll("");   // 其他所有标签去掉
        html = mPatternMap.get("white").matcher(html).replaceAll(" ");  // 还要替换连续空格
        return html;
    }

    private void onDataSourceUpdated(AsyncTask task) {
        String text = getQueryText();
        String field = getQueryField();
        Pattern filter = null;  // \Q \E间原样匹配，否则若输入中含( [之类会闪退；注意4个\
        if (!text.isEmpty()) {  // 此正则与读数据库时要匹配所有关键词不同，这里匹配到任一个就可替换
            text = SQLiteHelper.htmlEscape(text);  // 这个不会转义空格，而Html.escapeHtml会
            text = "(\\Q" + text.replaceAll("\\s+", "\\\\E|\\\\Q") + "\\E)";  // 分在大组便于替换
            filter = Pattern.compile(text, Pattern.CASE_INSENSITIVE);
        }

        int count = mDataSource.getCount();  // 只处理新增的部分
        for (int i = mCount; i < count; i++) {
            if (task != null && task.isCancelled()) break;
            filterData((String[]) getItem(i), filter, field);
        }
        if (!field.contains("revision")) {
            if (task == null || !task.isCancelled())
                formatTimeInfo(true);  // 前面弄出来的就是false的样式
        }

        if (task != null && task.isCancelled()) {
            clear();
        }
    }

    private void filterData(String[] raw_data, Pattern filter, String field) {
        String lnk = raw_data[SQLiteHelper.getColumnIndex("link")];
        String name = raw_data[SQLiteHelper.getColumnIndex("name")];
        String title = raw_data[SQLiteHelper.getColumnIndex("title")];
        String folder = raw_data[SQLiteHelper.getColumnIndex("folder")];
        String content = raw_data[SQLiteHelper.getColumnIndex("content")];
        String revision = raw_data[SQLiteHelper.getColumnIndex("revision")];
        // 无关键字全部true；但有关键字时搜和不搜的字段都默认为false，不能因为不搜的字段通过最后判断
        // 亦即要找时必有(filter != null)
        boolean in_name = filter == null;
        boolean in_title = filter == null;
        boolean in_content = filter == null;
        boolean in_revision = filter == null;

        String highlight_wrapper = "<font color=\"#FF6347\">$1</font>";  // 17种标准色就orange认不出


        // 由于显示时TextView按html解析，一些字符需要转义才能正常显示；注意&要先换
        // 而且恰好filter里也是转义过的来匹配，因此都转义就对了
        // 标题也要高亮，因此要做html转义
        title = SQLiteHelper.htmlEscape(title);
        if (!in_title && field.contains("title")) {
            Matcher matcher = filter.matcher(title);
            if (matcher.find()) {  // 标题有也要高亮，但本身不是html 要转义才能正常显示<&>
                title = matcher.replaceAll(highlight_wrapper);
            }
        }


        // 链接/图片等标签不显示文本，图片的html长约260 视频约710
        // 长文本按照标签分为1000字符的块，只要找到关键词且转为纯文本后有足够的字数即算完成
        // 只需注意视频标签比一般的延展长，弄两个matcher比较位置即可
        String block;
        Matcher video = mPatternMap.get("video").matcher(content);
        Matcher space = mPatternMap.get("space").matcher(content);  // 显示为空格的标签(如div就不空格)
        Matcher label = mPatternMap.get("label").matcher(content);  // 只按标签分可能断句在关键词中间
        int target_len = 400, target_start = 0;  // 竖屏时每行最多30个中文 60个字母，横屏x2
        int block_len = 1000, block_start = 0, block_end;
        boolean has_video = video.find();
        StringBuilder str = new StringBuilder(target_len);

        // 不够长或没找到都不能走(因为即使不用找内容时也要截取足够长的片段来显示)
        while (!in_content || str.length() < target_start + target_len) {
            block_end = Math.min(block_start + block_len, content.length());
            if (block_start == block_end) break;  // 到尾都要退，包括很短/没关键词的回答

            // 先找默认长度的分块结尾之后的标签结尾(没有空格标签还要找普通标签，免得断句在标签内)
            // 理论上处理1000+字无段落纯文本将不能分块(以保证不在关键词处断句)
            if (space.find(block_end))  // space指的是显示时是空格的html标签
                block_end = space.end();
            else if (label.find(block_end))
                block_end = label.end();
            else
                block_end = content.length();
            // 再看是否处于视频标签中，是就要把结尾再往后延(上次结果没用到，则不能找下一个)
            if (has_video && video.start() < block_end && video.end() >= block_end) {
                block_end = video.end();
                has_video = video.find();
            }
            block = content.substring(block_start, block_end);
            block = getTextFromHtml(block);

            if (!in_content && field.contains("content")) {
                Matcher matcher = filter.matcher(block);
                if (matcher.find()) {  // 只用第一个匹配，然后往前找断句处
                    in_content = true;
                    String tmp_str = str.toString() + block;
                    int start = -1;  // 正则版lastIndexOf，减40是最少留前面40字，下面减60是最多留60字，40-60间有标点可留多些
                    Matcher cut = mPatternMap.get("cutter").matcher(tmp_str.substring(0, Math.max(0, matcher.start() + str.length() - 40)));
                    while (cut.find())
                        start = cut.start();
                    while (start >= 0 && mPatternMap.get("skipper").matcher(String.valueOf(tmp_str.charAt(start))).find())
                        start++;  // 前引号括号外的其他符号(。；！等，可连续出现)只要后面的文本
                    start = Math.max(Math.max(0, start), matcher.start() + str.length() - 60);  // 关键词在末尾时宁可少显示文本
                    target_start = start;  // 即使匹配的就是符号也几乎能找到更近的标点
                }
            }
            str.append(block);  // 不要trim，不然ASCII小于32的都没了，显然也会删\0 \1这些
            block_start = block_end;
        }
        content = str.substring(target_start, Math.min(str.length(), target_start + target_len));
        content = (target_start > 0 ? "..." : "") + content;  // 下面替换若放循环里会提前超字数！
        if (filter != null && field.contains("content"))  // 字段里不要求找正文就不弄高亮
            content = filter.matcher(content).replaceAll(highlight_wrapper);
        // 注意连续空格已清除，替换过程中\0 \1不一定两边都有空格
        content = content.replace("\0", "[图片]").replace("\1", "[视频]");  // 放在标亮后，免得找"图片"时[图片]也亮了


        // 最后的附加信息也要在这里处理，若在getView里每次显示都调用
        // 每次调用也就是0点后"x天前"有点变化，不如自己notify
        name = SQLiteHelper.htmlEscape(name);
        if (!in_name && field.contains("name")) {
            Matcher matcher = filter.matcher(name);
            if (matcher.find())
                name = matcher.replaceAll(highlight_wrapper);
        }
        if (!in_revision && field.contains("revision")) {  // 日期不会有<&>符号，就不必转义了
            Matcher matcher = filter.matcher(revision);
            if (matcher.find())
                revision = matcher.replaceAll(highlight_wrapper);
        }
        // 如果想按照编辑时间找，则直接显示原本的时间，方便使用时学习关键词格式
        String divider = mContext.getString(R.string.info_divider);
        String info = "来自: " + folder + divider + revision + divider + name +
                (lnk.startsWith("zhuanlan") ? "的文章" : "的回答");


        mTitle.add(title);
        mSummary.add(content);
        mInfo.add(info);
    }

}
