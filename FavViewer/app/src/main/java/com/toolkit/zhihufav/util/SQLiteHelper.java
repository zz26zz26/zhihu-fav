package com.toolkit.zhihufav.util;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import com.toolkit.zhihufav.ContentActivity;
import com.toolkit.zhihufav.MainActivity;
import com.toolkit.zhihufav.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created on 2018/3/9.
 */

// 不写访问限制的，均默认为包内可见；参考了SQLiteOpenHelper
public class SQLiteHelper {

    private static final String TAG = "SQLiteHelper";

    public static final String DEFAULT_FIELD = "title content";

    private String mKey = "";               // 查询关键词
    private String mSort = "";              // 排序列号
    private String mType = "";              // 筛选收藏夹和类别
    private String mField = DEFAULT_FIELD;  // 进行搜索的字段
    private String mTableName = "fav";

    private int mOffset;
    private boolean mHasMore;
    private String mOffsetString;

    private AsyncTask mAsyncTask;
    private SQLiteDatabase mDatabase;
    private ArrayList<String[]> mDataCache;
    private ArrayList<AsyncTaskListener> mTaskListener;
    private static WeakReference<SQLiteHelper> sReference;
    private final byte[] mLock = new byte[0];

    private static final String EMPTY_USER = "匿名用户";  // 知乎用户
    private static final String[] COLUMN_NAME =
            {"folder", "title", "author", "link", "content", "revision", "name", "serial"};
    private static final HashMap<String, Integer> COLUMN_INDEX = new HashMap<>(8);  // 用列名查列号
    static { for (int i = 0; i < COLUMN_NAME.length; i++) COLUMN_INDEX.put(COLUMN_NAME[i], i); }

    public interface AsyncTaskListener {
        void onStart(AsyncTask task);        // UI线程
        void onAsyncDone(AsyncTask task);    // 非UI线程，取消时也会调用
        void onFinish(AsyncTask task);       // UI线程
        void onCancel(AsyncTask task);       // UI线程
    }

    public static class SimpleAsyncTaskListener implements AsyncTaskListener {
        @Override
        public void onStart(AsyncTask task) {}

        @Override
        public void onAsyncDone(AsyncTask task) {}

        @Override
        public void onFinish(AsyncTask task) {}

        @Override
        public void onCancel(AsyncTask task) {}
    }

    public static SQLiteHelper getReference() {
        return sReference == null ? null : sReference.get();
    }

    public static int getColumnIndex(String colName) {
        return COLUMN_INDEX.get(colName);
    }

    public static String getLinkType(String s) {
        return s.contains("zhuanlan") ? "文章" : s.contains("pin") ? "想法" : "回答";
    }

    public static String htmlEscape(String s) {
        // 完全版在Html.escapeHtml()；但它连空格也换，关键词分隔符就没了
        // 但知乎里就用过这几个，连空格(&nbsp;)都没；注意先换&再换其他，不然&会变多
        //return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");  // 遍历了三遍
        int lastMatch = 0, len = s.length();
        StringBuilder builder = new StringBuilder(len);  // 很多情况下就是一个都没换，直接到return
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '&') {
                builder.append(s, lastMatch, i);  // 参考String.replace()，遇到匹配再一并append
                builder.append("&amp;");          // lastMatch=i+(替换前的串'&'的长度)，下同
                lastMatch = i + 1;
            } else if (c == '<') {
                builder.append(s, lastMatch, i);
                builder.append("&lt;");
                lastMatch = i + 1;
            } else if (c == '>') {
                builder.append(s, lastMatch, i);
                builder.append("&gt;");
                lastMatch = i + 1;
            }
        }
        return builder.append(s, lastMatch, len).toString();
    }

    public SQLiteHelper(File directory) {
        Log.w(TAG, "onCreate");
        String db_name = "/fav.db";
        String path = directory.getAbsolutePath() + db_name;  // getCanonicalPath可解析../但要处理异常
        try {  // 读不了就新建，不完整就重开，还有异常就该退出
            mDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
            mDatabase.query(mTableName, null, "", null, "", "", "");  // 参数可全null，无此表抛异常
        }
        catch (SQLiteException e) {
            if (mDatabase != null && mDatabase.isOpen()) mDatabase.close();
            new SqlScriptRunner(path).execute(path.replaceAll("\\.db$", ".sql"));  // 里面会新建文件
            mDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
        }  // 新建文件的路径若只写文件名，默认放在(root)/data/data/[PackageName]/database
        // 另外，Database还是要开，防止重建数据库期间的addSome直接返回(反正是后台，可以等)
        // 还有，sql文件若为QQ接收剪切过来会打不开，复制过来就可以

        sReference = new WeakReference<>(this);  // 能打开数据库才赋值
        mDataCache = new ArrayList<>();
        mHasMore = true;
        mOffset = 0;
        mOffsetString = null;
    }

    public void close() {
        Log.w(TAG, "onDestroy");
        clear();
        mDatabase.close();
        sReference = null;
        if (mTaskListener != null) {
            mTaskListener.clear();
            mTaskListener = null;
        }
    }

    public void clear() {
        mOffset = 0;
        mOffsetString = null;
        mHasMore = true;
        mDataCache.clear();
    }

    public void setQuery(String text, String field, String type, String sort) {  // 传入null时即不改变现状
        if (text  != null) mKey = text;
        if (field != null) mField = field;
        if (type  != null) mType = type;
        if (sort  != null) mSort = sort;
    }

    public void addOnAsyncTaskListener(AsyncTaskListener listener) {
        if (mTaskListener == null) {
            mTaskListener = new ArrayList<>();
        }
        if (listener != null) {
            mTaskListener.add(listener);
        }
        Log.w(TAG, "addListener, size = " + mTaskListener.size());
    }

    public void removeOnAsyncTaskListener(AsyncTaskListener listener) {
        if (mTaskListener != null) {
            mTaskListener.remove(listener);  // remove只删掉第一个匹配项
            Log.w(TAG, "removeListener, size = " + mTaskListener.size());
        }
    }

    public void addSomeAsync(int minItemCount, boolean waitForFinish) {  // 空转也去跑，有些监听要在后台跑
        if (mDatabase == null) return;  // 正在加载时忽略请求，此处不会取消当前任务
        if (mAsyncTask == null || mAsyncTask.getStatus() == AsyncTask.Status.FINISHED) {
            mAsyncTask = new SqlQueryRunner();  // 只能用一次
            ((SqlQueryRunner) mAsyncTask).execute(minItemCount);  // 默认后台单线程，防止更新UI冲突
            if (waitForFinish) {
                synchronized (mLock) {
                    try {mLock.wait(5000);}  // 最长大概是数据库被另一线程占用，最多等待30s
                    catch (Exception e) {Log.e(TAG, "async_add: " + e.toString());}
                }
            }
        }
    }

    public void cancelAsync(boolean waitForCancel) {
        if (mAsyncTask != null && mAsyncTask.getStatus() != AsyncTask.Status.FINISHED) {
            mAsyncTask.cancel(false);  // 可能会强行结束？造成最后的notify和clear不执行
            if (waitForCancel) {
                synchronized (mLock) {
                    try {mLock.wait(5000);}
                    catch (Exception e) {Log.e(TAG, "async_cancel: " + e.toString());}
                }
            }
            mAsyncTask = null;  // 若放在onCancelled里得多等一会(估计优先级不高)，造成addSome忽略执行
        }  // 此AsyncTask串行，在addSome后稍微排个队也没问题，反正之前那个已经取消了
    }

    public boolean hasMore() {
        return mHasMore;
    }

    public String getQueryKey() {
        return mKey;
    }

    public String getQuerySort() {
        return mSort;
    }

    public String getQueryType() {
        return mType;
    }

    public String getQueryField() {
        return mField;
    }

    public String[] getFolderName() {
        ArrayList<String> res = new ArrayList<>();
        String sql = "SELECT DISTINCT folder FROM " + mTableName + " ORDER BY folder COLLATE LOCALIZED";
        try {
            Cursor cursor = mDatabase.rawQuery(sql, null);
            while (cursor.moveToNext()) {
                res.add(cursor.getString(0));
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "getFolderName: " + e.toString());
        }
        return res.toArray(new String[res.size()]);  // 空间够大时，返回值就是new出的地址
//        return new String[]{"十八字以内无图回答，犀利精辟短小而精干", "一本正经地扯淡", "角 川 书 店 发 售"};
//        return new String[]{"aaaaaaaaa", "iiiiiiiii", "qqqqqqqqq", "MMMMMMMMM", "XXXXXXXXX", "WWWWWWWWW"};
    }

    public String[] getItem(int position) {
        if (position < 0 || position >= mDataCache.size()) return null;  // 只写大于不对啊
        return mDataCache.get(position);
    }

    public int getCount() {
        return mDataCache.size();
    }

    public long getQueryCount(String key, String field, String type) {
        ArrayList<String> args = getRawSql(key, field, type, null);  // 排序不影响数量
        String sql = args.remove(0);
        sql = sql.replace("SELECT *", "SELECT COUNT(DISTINCT link)");
        sql = sql.replace("GROUP BY link", "");  // 若分组则COUNT会计算每一组的数量
        sql = sql.replace("HAVING", sql.contains("WHERE") ? "AND" : "WHERE");  // type为空时没WHERE

        Log.w(TAG, "sql = " + sql);
        Log.w(TAG, "arg = " + args);

        long count = 0;
        try {  // 取得首行首列的值
            count = DatabaseUtils.longForQuery(mDatabase, sql, args.toArray(new String[args.size()]));
        } catch (Exception e) {
            Log.e(TAG, "query_count: " + e.toString());
        }
        return count;
    }


    private String keySimplify(String key) {
        // 前缀相同时，后面的关键词长度>=前面时没结果(前面的字少优先匹配了)，因此只留最长的
        // 但为了保持对外的原样，不能直接修改mKey，只能在生成sql时调用一下
        if (key == null || key.isEmpty()) return key;
        String[] keys = key.split("\\s+");
        StringBuilder new_key = new StringBuilder();
        for (int i = 0, n = keys.length; i < n; i++) {
            for (int j = 0; j < i; j++) {  // 不要和自己比
                if (keys[i].contains(keys[j])) {
                    keys[j] = "";
                } else if (keys[j].contains(keys[i])) {
                    keys[i] = "";
                }
            }
        }
        for (String k : keys) {
            if (!k.isEmpty()) {
                new_key.append(k).append(" ");
            }
        }
        return new_key.deleteCharAt(new_key.length() - 1).toString();
    }

    private ArrayList<String[]> fetch(int limit) {
        ArrayList<String> args = getRawSql(mKey, mField, mType, mSort);
        String sql = args.remove(0);

        // 中文列排序可在列名和ASC/DESC之间插入COLLATE LOCALIZED，但是上面的>=并不是这个规则
        // 因此只能在建表的时候就多加这句，如CREATE TABLE fav (title TEXT COLLATE LOCALIZED, ...)
        // LOCALIZED、UNICODE为安卓特有，SQLite自身还支持BINARY(默认)、RTRIM(忽略结尾空格)、NOCASE

        // 强行排序可用于减少OFFSET以节省时间，查询时加条件[列]>=[上次LIMIT结果最后一项在该列的值]，这样OFFSET用1即可
        // 按其他列排序时亦可用此技巧，最后几项重复时用offset记录重复数量（若整个LIMIT都是同一个值就在原offset上累加）
        int sortColumn, serialColumn = getColumnIndex("serial");
        if (!TextUtils.isEmpty(mSort)) {
            sortColumn = getColumnIndex(mSort.substring(0, mSort.indexOf(' ')));  // 去掉asc/desc
        } else {
            sortColumn = serialColumn;  // GROUP BY link即按link排序后取同link最后一个
            sql += " ORDER BY serial desc";  // 即使serial全空也能通过OFFSET分页，当然顺序和按link排一样
        }
        if (mOffset > 0) {  // 第一轮查询显然不必加条件，但之后不写比较条件会从头搜！
            String quote = sortColumn == serialColumn ? "" : "'";  // 序号列不用引号
            int ins_pos = sql.indexOf("ORDER BY") - 1;  // 在ORDER BY前的空格处插入
            String ins = sql.contains("HAVING") ? " AND " : " HAVING ";
            ins += "(" + COLUMN_NAME[sortColumn];
            if (mOffsetString != null) {
                ins += sql.substring(ins_pos).contains("desc") ? " <= " : " >= ";  // asc可不写
                ins += quote + mOffsetString + quote;
            }
            if (sortColumn == serialColumn) {
                ins += (mOffsetString != null) ? " OR serial" : "";
                ins += " IS NULL";  // 除序号列外都不会在第一轮后再遇到NULL
            }
            ins += ")";
            sql = sql.substring(0, ins_pos) + ins + sql.substring(ins_pos);
        }

        // 分页
        if (limit > 0) {
            sql += String.format(Locale.US, " LIMIT %d OFFSET %d", limit, mOffset);
        }

        Log.w(TAG, "sql = " + sql);
        Log.w(TAG, "arg = " + args);

        // 执行和处理查询数据
        Object[] row_data;
        String[] result_row;
        ArrayList<String[]> result = new ArrayList<>();
        int nameColumn = getColumnIndex("name");
        try {
            Cursor cursor = mDatabase.rawQuery(sql, args.toArray(new String[args.size()]));  // 不够大会分配新空间
            while (cursor.moveToNext()) {
                row_data = new Object[COLUMN_NAME.length];  // 初始化为null
                result_row = new String[COLUMN_NAME.length - 1];  // 序号列不直接显示在界面
                for (int col = 0; col < COLUMN_NAME.length; col++) {
                    if (!cursor.isNull(col))  // 值为null或抛异常，但其实可以继续跑
                        row_data[col] = col < COLUMN_NAME.length - 1 ?
                                (result_row[col] = cursor.getString(col)) : cursor.getLong(col);

                    if (col == sortColumn) {
                        if (Objects.equals(row_data[col], mOffsetString)) {  // 序号列可能都是null
                            mOffset++;    // 这一列的值都一样就一直加
                        } else {
                            mOffset = 1;  // 有不一样说明OffsetString能起作用；可以是""，如匿名用户的用户名
                            mOffsetString = Objects.toString(row_data[col], null);  // 传null得null
                        }
                    }
                    if (col == nameColumn && result_row[col].isEmpty()) {
                        result_row[col] = EMPTY_USER;  // 放mOffsetString之后，不然按名字排序时出错
                    }  // 主要是逆序时加载到排在最前的匿名用户("")后，又从"匿"字开始搜了，而不是""
                }
                result.add(result_row);  // 不new则result里全是最后一行的内容
            }
            cursor.close();
        } catch (Exception e) {  // 比如跑着跑着文件被删…
            Log.e(TAG, "async_fetch: " + e.toString());
        }
        mHasMore = (result.size() == limit);
        return result;
    }

    // FTS4可加速内容列查找，但*只能加在词后（自带中文分词会把标点之间看成一个词，且不能自定义规则）
    private ArrayList<String> getRawSql(String key, String field, String type, String sort) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + mTableName);
        ArrayList<String> args = new ArrayList<>();

        // 按类别筛选，含类型/包含/收藏夹名，其中类型和收藏夹名都是全选时为空
        if (!TextUtils.isEmpty(type)) {
            sql.append(" WHERE (");
            String[] types = type.split(" +");  // 收藏夹名称可含各种空白，遂只对空格转义并按其分割
            int index = 0;
            if (types[index].contains("type_")) {  // 先是类型，只有一项
                if (types[index].contains("type_article"))
                    sql.append("link > 'z'");  // "zhuanlan.zhihu..." > "z" | "www.zhihu.com..." < "z"
                else if (types[index].contains("type_answer"))
                    sql.append("link LIKE '%que%'");  // ".com" < "q" | "pin" < "q"
                else
                    sql.append("SUBSTR(link, 15, 1) = 'p'");  // 下标从1起
                index = 1;
            }

            if (index < types.length) {  // 再是包含
                for (; index < types.length; index++) {
                    if (types[index].contains("contain_image")) {
                        if (index > 0) sql.append(" AND ");
                        sql.append("content LIKE '%<img%'");
                    } else if (types[index].contains("contain_video")) {
                        if (index > 0) sql.append(" AND ");
                        sql.append("content LIKE '%<a class=\"video-box\"%'");
                    } else {
                        break;
                    }
                }
            }

            if (index < types.length) {  // 最后全是收藏夹名
                if (index > 0) sql.append(" AND ");
                sql.append("folder IN (");  // IN比连串的OR快
                for (int i = index; i < types.length; i++) {
                    sql.append("?");
                    args.add(types[i].substring("folder_".length()).replace("\0", " "));
                    sql.append((i < types.length - 1) ? ", " : ")");
                }
            }
            sql.append(")");
        }

        // 去重(分组后同一组的记录只输出最后一条，因此若group后才筛选folder可能会被去重查不到)
        sql.append(" GROUP BY ").append("link");

        // 关键词和搜索区域
        if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(field)) {
            int key_in_empty_user = 0;
            key = keySimplify(key);
            String cur_key;
            String[] fields = field.split("\\s+");  // 分空串得一个空串元素的数组
            String[] text_keys = key.split("\\s+");  // 标题/昵称/时间等不是html不用转义
            String[] content_keys = htmlEscape(key).split("\\s+");  // 内容爬下来时就是html

            sql.append(" HAVING (");
            for (int i = 0; i < fields.length; i++) {
                for (int j = 0; j < content_keys.length; j++) {
                    cur_key = (fields[i].equals("content") ? content_keys[j] : text_keys[j]);
                    sql.append(fields[i]).append(" LIKE ?");  // 预编译写法不必转义()[]/\''""
                    args.add("%" + cur_key + "%");            // 但还是要弄%(不然搜%%要很久)
                    if (cur_key.contains("%")) {              // 还要记得修改转义字符自身
                        cur_key = cur_key.replace("/", "//").replace("%", "/%");
                        args.set(args.size() - 1, "%" + cur_key + "%");
                        sql.append(" ESCAPE '/'");
                    }
                    if (fields[i].equals("name") && EMPTY_USER.contains(cur_key)) {
                        key_in_empty_user++;  // 正常用户名也可能含这些字，不能影响那些查询
                    }
                    if (j + 1 < content_keys.length)
                        sql.append(" AND ");  // 最后一个后不加连接词
                }
                if (i + 1 < fields.length)
                    sql.append(" OR ");  // OR优先级低，不必给AND加括号
            }
            if (key_in_empty_user == content_keys.length) {  // 所有关键词都要能匹配“匿名用户”
                sql.append(" OR name = ?");  // 不用LIKE..换成= ?
                args.add("");  // 参数直接是""，不要通配符了
            }
            sql.append(")");  // 最外层加一个即可，方便替换修改
        }

        // 排序
        if (!TextUtils.isEmpty(sort)) {
            sql.append(" ORDER BY ").append(sort);
        }

        args.add(0, sql.toString());
        return args;
    }

    // 用了类变量不能static，退出前要等它取消来防止内存泄漏；泛型分别指参数、进度、返回值类型
    private class SqlQueryRunner extends AsyncTask<Integer, Void, Void> {

        private final int PHASE_START = 0;
        private final int PHASE_ASYNC = 1;
        private final int PHASE_FINISH = 2;
        private final int PHASE_CANCEL = 3;

        private void listenerIterator(int phase) {
            if (mTaskListener != null) {
                for (int i = 0, n = mTaskListener.size(); i < n; i++) {
                    switch (phase) {
                        case PHASE_START:
                            mTaskListener.get(i).onStart(this);
                            break;
                        case PHASE_ASYNC:
                            mTaskListener.get(i).onAsyncDone(this);
                            break;
                        case PHASE_FINISH:
                            mTaskListener.get(i).onFinish(this);
                            break;
                        case PHASE_CANCEL:
                            mTaskListener.get(i).onCancel(this);
                            break;
                    }

                    if (n != mTaskListener.size()) {  // 循环中可能有remove自己的
                        n = mTaskListener.size();     // 且有先后顺序依赖(如notify后才能换页)
                        i--;                          // notifyDataSetChanged不考虑这些i直接逆序来
                    }
                }
            }
        }

        private boolean findInGroups(Matcher matcher) {
            int g = 0;                             // 记录出现过的组数
            int count = matcher.groupCount();      // 组数即pattern里括号的个数
            boolean[] found = new boolean[count];  // 每个关键词(组号)都出现过才算匹配，默认值false
            while (matcher.find()) {               // 其中组0必为非空，匹配的是html标签，不能算
                for (int i = 1; i <= count; i++) {
                    if (!found[i - 1] && matcher.group(i) != null) {
                        found[i - 1] = true;       // 没出现过的关键词才进来
                        if (++g == count)          // true变多时就看一下
                            return true;           // 若全true即可退出
                    }
                }
            }
            return false;
        }

        private boolean containsKey(String[] data, Pattern key, int[] field) {
            // 保证key非空，field也非空，只需看对应field是否匹配
            // 标题和用户名没有转义<&>，而pattern不匹配<>内的文字，遂要对其强制转义(反正也不长)
            int content_index = getColumnIndex("content");
            boolean any_contains = false;
            for (int fi : field) {
                if (fi == content_index)
                    any_contains = any_contains || findInGroups(key.matcher(data[fi]));
                else
                    any_contains = any_contains || findInGroups(key.matcher(htmlEscape(data[fi])));
            }
            return any_contains;  // 有一个字段匹配上就好
        }

        @Override
        protected Void doInBackground(Integer... params) {
            // 后台不能改UI，包括notifyDataSetChanged和Toast；要改去onProgressUpdate里面弄
            int target_count = params[0];
            List<String[]> raw_data;

            Log.i(TAG, "async_background: new task begins. query = " + mKey + ". field = " + mField);
            String key = keySimplify(mKey);  // 视频链接在video-box的css里设为不显示…哪天显示了就不用专门匹配了
            String ignore_key = "<a class=\"video-box\".*?</a>|<[^>]+>|";  // 视频标签内含普通标签
            Pattern pattern = null;
            String[] fields = mField.split("\\s+");
            int[] field_index = new int[0];
            // 零宽断言(?=.*A)(?=.*B).*可判断整个串同时有多关键词，但还需嵌入关键词不在标签内的条件
            // 不在标签内可用前/后没有标签的开/闭符号断言来判断，即(?<!<)A(?![^<>]*>)，替换上面的A
            // 替换后形如(?=.*(?<!<)A(?![^<>]*>))(?=.*(?<!<)B(?![^<>]*>))(?=.*(?<!<)C(?![^<>]*>)).*
            // 能匹配时很快，但不匹配时回溯完后退出，100字要50ms，1000字就要2s了(字x10 时间x40)
            if (!key.isEmpty() && !isCancelled() && target_count > 0 && mHasMore) {
                key = htmlEscape(key);  // \Q \E间原样匹配，不写则输入中含( [之类会闪退；注意要4个\
                key = "(\\Q" + key.replaceAll("\\s+", "\\\\E)|(\\\\Q") + "\\E)";  // 别再套大组，慢
                key = ignore_key + key;  // 只匹配html标签外的关键词(各种标签在组0，关键词在组1 2...)
                pattern = Pattern.compile(key, Pattern.CASE_INSENSITIVE);  // 仅找标题时这样显得复杂
            }
            if (!fields[0].isEmpty() && !isCancelled() && target_count > 0 && mHasMore) {
                field_index = new int[fields.length];
                for (int i = 0; i < fields.length; i++)
                    field_index[i] = getColumnIndex(fields[i]);
            }

            int fetch_accept, accept = 0;
            int fetch_size, min_fetch_size = Math.max(target_count, 5);
            float accept_ratio = 1.0f;
            while (accept < target_count) {  // 滤掉不含关键词的(如html标签里的br)
                if (!mHasMore) break;  // 不加就死循环(结果数不够时)；写在下一句后则失去最后一次查询
                if (isCancelled()) break;
                fetch_size = (int) ((target_count - accept) / accept_ratio);
                fetch_size = fetch_size < min_fetch_size ? min_fetch_size : fetch_size;

                raw_data = fetch(fetch_size);
                fetch_accept = 0;

                for (int i = 0; i < raw_data.size(); i++) {
                    if (isCancelled()) break;
                    if (pattern == null || containsKey(raw_data.get(i), pattern, field_index)) {
                        mDataCache.add(raw_data.get(i));
                        accept++;
                        fetch_accept++;
                    }
                }
                // 有效数据少下次就多要点，数据够了马上降下来，所以最新值比重较大
                accept_ratio = accept_ratio * 0.2f + (fetch_accept / fetch_size) * 0.8f;
            }

            listenerIterator(PHASE_ASYNC);  // 取消时也要让里面知道，比如进行clear

            synchronized (mLock) {
                mLock.notify();  // 不能在onCancelled里notify，因为它和wait同在UI线程会无限等待
            }
            Log.w(TAG, "async_background: task " + (isCancelled() ? "cancelled" : "accept = " + accept));
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            listenerIterator(PHASE_START);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);  // 此参数就是doInBackground的返回值
            listenerIterator(PHASE_FINISH);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            listenerIterator(PHASE_CANCEL);

            // ListViewAdapter的注意事项：取消完成才能clear，而不能只放在setQuery里标记取消后立即执行
            // 如标记取消时异步数据库恰好取到最后一组，则hasMore刚重置又被改成false，下个task直接返回空数组

            // 然而clear()放onCancelled也不行，考虑async取消后马上开了新的，但UI线程迟迟不onCancelled和clear
            // 然后getRaw还是用之前的offset导致没有数据，这些完成后才执行clear又把处理一半的数据删了
        }

    }

    // 可能退出后还要把文件加载完，需要static(不隐含引用外部类)和WeakReference防止内存泄漏
    private static class SqlScriptRunner extends AsyncTask<String, Void, Void> {

        private int count = 0;
        private SQLiteDatabase db;
        private WeakReference<MainActivity> mainRef;
        private WeakReference<SQLiteHelper> classRef;
        private WeakReference<ContentActivity> contentRef;

        SqlScriptRunner(String db_path) {
            super();  // 里面用自己的连接，免得转个屏就cancel
            db = SQLiteDatabase.openOrCreateDatabase(db_path, null);
            //db.setLocale(Locale.CHINA);  // 可按拼音排序…才怪
            classRef = new WeakReference<>(SQLiteHelper.getReference());
            mainRef = new WeakReference<>(MainActivity.getReference());
            contentRef = new WeakReference<>(ContentActivity.getReference());
        }

        @Override
        protected void onProgressUpdate(Void... values) {
            super.onProgressUpdate(values);  // 极限是99%，起点斜率比n/(n+50)小，不会一开始就跳到50%+
            double percent = 0.99 - Math.pow(2.0, -count / 200.0);
            if (contentRef.get() != null) {
                int p = (int) (percent * 100);  // 两个窗口同时存在时，显然content在上面
                contentRef.get().setTitle(contentRef.get().getString(R.string.search_more_result, p));
            } else if (mainRef.get() != null) {
                mainRef.get().setFooterViewProgress(percent);  // 1就隐藏进度条了
            }  // 没有窗口也等干完再结束，免得转个屏就cancel
        }

        @Override
        protected Void doInBackground(String... params) {
            String script_path = params[0];  // 脚本已写事务(BEGIN/COMMIT)，批量插入不会每次都开关
            try {
                BufferedReader br = new BufferedReader(new FileReader(script_path));
                StringBuilder sql = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null && !isCancelled()) {
                    sql.append(line).append("\n");  // readLine不含换行符
                    if (line.endsWith(";")) {  // 导出的sql已把'换成''；建表也是多行；内容行末可能为);
                        if (sql.substring(0, 6).equals("INSERT") && !line.endsWith(") ;")) continue;
                        if (line.startsWith("COMMIT")) count = Integer.MAX_VALUE - 1;  // 全部完成
                        db.execSQL(sql.substring(0, sql.length() - 2));  // 语句不能留最后的分号和换行
                        sql.delete(0, sql.length());  // setLength还要把后面空间置\0，这个快
                        publishProgress();  // 另一线程执行，设为MAX后还能跑几次，++放里面就溢出了
                        count++;
                    }
                }
                br.close();
            } catch (IOException e) {  // 包括FileNotFoundException，没文件就不更新咯
                Log.e(TAG, "execSqlScript: " + e.toString());
                if (count > 0 && count < Integer.MAX_VALUE) {
                    Log.e(TAG, "inTransaction (should be true) = " + db.inTransaction());
                    db.execSQL("COMMIT");  // BEGIN后没COMMIT则之后操作只能等(除非退出)
                    db.close();

                    File file = new File(db.getPath());  // 写到一半出错的文件就删掉
                    if (!file.delete()) return null;  // 删不掉…也不能怎样
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (db.isOpen()) {
                db.close();
            }
            if (classRef.get() != null && (mainRef.get() != null || contentRef.get() != null)) {
                classRef.get().addSomeAsync(10, false);  // 不做无用功
            }
        }
    }
}
