import os
import re
import time
import sqlite3
import html.parser
import urllib.parse
import urllib.request


class fav_parser(html.parser.HTMLParser):
    def __init__(self):        # 变量放init外面相当于别的语言的static
        html.parser.HTMLParser.__init__(self)
        self.folder_name = ''  # 收藏夹名
        self.answer_head = []  # 问题标题
        self.answer_user = []  # 答主链接
        self.answer_name = []  # 答主昵称
        self.answer_text = []  # 答案内容
        self.answer_link = []  # 回答链接
        self.answer_time = []  # 编辑时间
        self.next_link  = ''   # 下一页链接 只有双引号的字符串可用单引号包住(反过来也行), 不必用r'...'
        self.__tmp_link = ''   # 页码区遇到下一页链接的标签前会碰到别的页码链接
        self.__tag_type = 0    # 1-页码/div/span/a; 2-收藏夹名/h2; 3-题目/h2/a; 4-答主/div/span/a; 5-内容

    def handle_timestamp(self, text):  # 格式: 发布/编辑于 2018-01-01 00:00 / 昨天 00:00 / 00:00 (即今天)
        text = text[text.find('于') + 1:].strip()
        text = text[:text.find(' ') if ' ' in text else len(text)]
        if '昨天' in text:
            text = time.strftime("%Y-%m-%d", time.localtime(time.time() - 86400))
        elif ':' in text:
            text = time.strftime("%Y-%m-%d", time.localtime())
        return text

    def handle_starttag(self, tag, attrs):
        if tag == 'p' and ('class', 'visible-expanded') in attrs:     # 注意各type对应的标签范围不能重叠
            self.__tag_type = 6
        elif tag == 'textarea':                                       # 不然handle_endtag可能提前重置type
            self.__tag_type = 5
        elif tag == 'span' and 'author-link-line' in sum(attrs, ()):  # 专栏/回答有 匿名/点赞无; sum转一维
            self.__tag_type = 4
        elif tag == 'h2' and ('class', 'zm-item-title') in attrs:     # 要在下面改[-1]之前append 默认匿名
            self.__tag_type = 3
        elif tag == 'h2' and ('id', 'zh-fav-head-title') in attrs:    # 私密收藏夹里面有个<i>图标不影响
            self.__tag_type = 2
        elif tag == 'div' and ('class', 'zm-invite-pager') in attrs:  # 恰好div里面没别的div, 直接用type判
            self.__tag_type = 1  # 只有一页时进不来此if, next_link不变

        if tag == 'a' and (self.__tag_type in (1, 4)):  # 下一页(1)和答主主页(4)都是链接
            self.__tmp_link = dict(attrs)['href']
        elif tag == 'div' and ('class', 'zm-item') in attrs:
            self.answer_head.append('')                 # 遇到新的先加个空白项 (想法没标题)
            self.answer_user.append('')
            self.answer_name.append('')                 # 等读到相应的标签或读完html再填内容
            self.answer_text.append('')
            self.answer_link.append('')
            self.answer_time.append('')
        elif tag == 'div' and 'data-entry-url' in sum(attrs, ()):
            url = dict(attrs)['data-entry-url']         # 纯图片回答没有[显示全部] 专栏没有[编辑于]
            if url[0] == '/':
                url = 'www.zhihu.com' + url             # 回答和想法的链接是相对路径
            elif '://' in url:
                url = url[url.find('://') + 3:]         # 专栏自带完整链接 只需去掉https://
            self.answer_link[-1] = url

    def handle_data(self, data):
        next_text = '下一页'
        data = data.strip()
        if self.__tag_type == 0 or len(data) == 0:        # 省得下面判断那么多
            pass
        elif self.__tag_type == 1 and data != next_text:  # 最后一页的[下一页]没链接
            self.__tmp_link = ''
        elif self.__tag_type == 1 and data == next_text:  # 若留着之前页码的链接会持续访问倒数第二页
            self.next_link = self.__tmp_link
        elif self.__tag_type == 2:                        # 收藏夹名前后有\n
            self.folder_name = data
        elif self.__tag_type == 3:                        # 标题在<h2>中的<a>里 跳过无内容标签
            self.answer_head[-1] = data
        elif self.__tag_type == 4:
            url = self.__tmp_link
            self.answer_name[-1] = data                   # 答主主页为/people/xxxx, 改为xxxx
            self.answer_user[-1] = url[url.rfind('/') + 1:]
            self.__tag_type = 0                           # 若让endtag对span也置0会让提取下页链接时提前退出
        elif self.__tag_type == 5:
            self.answer_text[-1] = data
        elif self.__tag_type == 6:
            self.answer_time[-1] = self.handle_timestamp(data)

    def handle_endtag(self, tag):
        if self.__tag_type > 0 and tag in ('p', 'h2', 'div', 'textarea'):
            self.__tag_type = 0  # 优先级: is(内存地址) > in > not > and > or


# For scraping 'answer_user': (进答案/文章页才有头像文件名, 用文件名除重比用户名复杂)
# <img class="Avatar Avatar--large UserAvatar-inner" src="xxx_xl.jpg" 用户页(自带两条动态) X
# <img class="Avatar AuthorInfo-avatar" src="xxx_xs.jpg" 动态|问题页(更多回答和关于作者栏由js载入)
# <img class="Avatar Avatar--round AuthorInfo-avatar" src="xxx_xs.jpg" 专栏文章
# <img class="Avatar-hemingway AuthorIndex-avatar Avatar--xs" src="xxx_xs.jpg" 想法
# 之前的专栏是Avatar-hemingway PostIndex-authorAvatar Avatar--xs
class img_parser(html.parser.HTMLParser):
    def __init__(self):
        html.parser.HTMLParser.__init__(self)
        self.link = ''
    
    def feed(self, data):
        self.link = ''  # 防止返回上次得到的地址
        html.parser.HTMLParser.feed(self, data)
    
    def handle_starttag(self, tag, attrs):
        if tag == 'img':
            if ('class', 'Avatar AuthorInfo-avatar') in attrs or \
               ('class', 'Avatar Avatar--round AuthorInfo-avatar') in attrs or \
               ('class', 'Avatar-hemingway AuthorIndex-avatar Avatar--xs') in attrs:
                url = dict(attrs)['src']
                url = url[:url.rfind('_')] + url[url.rfind('.'):]
                self.link = url


##########################
#####  Helper Funcs  #####
##########################
def check_status(response):
    if response.status != 200:
        print('(code %d)' % (response.status))
        return 1  # fail
    else:
        return 0


def check_database(fav):
    ''' 查看获得的收藏夹网址是否已存在, 存在则可提前结束爬取 (不需检查答案的修改/删除情况时) '''
    conn = sqlite3.connect('fav.db')
    columns = conn.execute('PRAGMA table_info(fav);').fetchall()
    if len(columns) != 7:
        conn.close()
        return False

    link = conn.execute('SELECT link FROM fav WHERE folder="%s";' %
                        fav.folder_name).fetchall()
    conn.close()
    # 只要有一个在数据库里就返回非0, 注意link里每个都是tuple
    return sum([(f,) in link for f in fav.answer_link])


def extract_filename(src):
    ''' 提取图片链接的文件名 (去掉路径 尺寸 扩展名)

        同一张图链接可能不同(pic1|pic4.zhimg.com/50|80) 也可能尺寸不同(_b|_r)
    '''
    start = src.rfind('/') + 1
    end = len(src)  # 链接可能不含尺寸和扩展名
    if   src.find('_', start) > -1: end = src.rfind('_')
    elif src.find('.', start) > -1: end = src.rfind('.')
    src = src[start:end]
    return src


##########################
#####   Interfaces   #####
##########################
def update_database(fav):
    ''' 更新收藏夹数据库, fav的内容应属于同一个收藏夹 '''
    conn = sqlite3.connect('fav.db')
    columns = conn.execute('PRAGMA table_info(fav);').fetchall()  # 表中每列属性，无此表返回空
    if len(columns) != 7:
        if len(columns) != 0:  # 表/列名有.空格/关键字放在反引号``里
            conn.execute('''DROP TABLE IF EXISTS `fav_old`;''')
            conn.execute('''ALTER TABLE `fav` RENAME TO `fav_old`;''')
        conn.execute('''CREATE TABLE fav (folder   TEXT,
                                          title    TEXT,
                                          author   TEXT,
                                          link     TEXT,
                                          content  TEXT,
                                          revision TEXT,
                                          name     TEXT,
                                          PRIMARY KEY(folder, link));''')
    # conn.execute('CREATE UNIQUE INDEX ifav on fav (folder, link);') 建表时没弄唯一约束才用/索引的列应少插改
    # conn.execute('ALTER TABLE `fav` ADD COLUMN revision TEXT;')

    fav_rows = []
    for i in range(len(fav.answer_text)):  # 每行是一个(tuple)
        fav_rows.append((fav.folder_name, fav.answer_head[i], fav.answer_user[i], fav.answer_link[i],
                         fav.answer_text[i], fav.answer_time[i], fav.answer_name[i]))

    old_rows = conn.execute('SELECT * FROM fav WHERE folder="%s";' % fav.folder_name).fetchall()
    old_txt = [r[4] for r in old_rows]  # 判重只要link和content 但为了调试时看的完整就都要了
    old_lnk = [r[3] for r in old_rows]
    new_lnk = [r[3] for r in fav_rows]
    insert = [r for r in fav_rows if r[3] not in old_lnk]  # 递推式/推导式 [3:5]的区间是[3,5)
    update = [r for r in fav_rows if r[3] in old_lnk and r[4] not in old_txt]  # (即edited)内容没变的不显示
    delete = [r for r in old_rows if r[3] not in new_lnk]  # 也可能是要求修改

    if fav.next_link != '':  # 下一页链接非空说明提前退出 收藏夹没遍历完 没遍历到的都认为删除了
        print('Get', len(fav.answer_text), 'mostly new collection items')
        print('Including %d new and %d edited items.' % (len(insert), len(update)))
    else:
        print('Get all', len(fav.answer_text), 'collection items')
        print('Including %d new, %d edited and find %d deleted items.' %
              (len(insert), len(update), len(delete)))

    # REPLACE不能用WHERE, 唯一(组合)索引一致就先删再插(显然没变的也删) (primary key/unique/unique index都看)
    conn.executemany('INSERT OR REPLACE INTO fav VALUES (?,?,?,?,?,?,?);', fav_rows)
    conn.commit()
    conn.close()


def export_database():
    ''' 导出数据库到sql脚本文件, 建表参数适配安卓 (方便按拼音排序) '''
    print('Exporting to file ')
    lines = []
    lines.append('BEGIN TRANSACTION;\n')
    lines.append('CREATE TABLE fav (folder   TEXT,\n')
    lines.append('                  title    TEXT COLLATE LOCALIZED,\n')
    lines.append('                  author   TEXT,\n')
    lines.append('                  link     TEXT,\n')
    lines.append('                  content  TEXT,\n')
    lines.append('                  revision TEXT,\n')
    lines.append('                  name     TEXT COLLATE LOCALIZED,\n')
    lines.append('                  PRIMARY KEY(folder, link));\n')

    t0 = time.clock()
    conn = sqlite3.connect('fav.db')
    rows = conn.execute('SELECT * FROM fav').fetchall()
    for row in rows:
        lines.append("INSERT INTO fav VALUES('")
        lines.append("','".join([s.replace("'", "''") for s in row]))
        lines.append("');\n")

    lines.append('COMMIT;')
    print('(make:%.3fs)' % (time.clock() - t0), end=' ')

    t0 = time.clock()
    script = open('fav.sql', 'w', encoding='utf-8')
    script.writelines(lines)  # 比一行行write快
    script.close()
    print('(write:%.3fs)' % (time.clock() - t0))


def get_data(entry_url, header, ignore_old = False):
    ''' 获取收藏夹的内容, 可选只要新增项 (即忽略收藏后又被修改的答案) '''
    fav = fav_parser()
    fav.next_link = '?page=1'

    while len(fav.next_link) > 0:
        print('Requesting', fav.next_link[1:], end=' ')
        t0 = time.clock()
        request = urllib.request.Request(entry_url + fav.next_link, headers=header)
        response = urllib.request.urlopen(request)
        print('(load:%.3fs)' % (time.clock() - t0), end=' ')
        if check_status(response): continue  # fav.next_link还没改 正好重来; 而且网页有问题时没下页链接

        t0 = time.clock()
        page_html = response.read().decode()  # 有问题就read会抛异常
        print('(read:%.3fs)' % (time.clock() - t0), end=' ')

        t0 = time.clock()
        fav.next_link = ''
        fav.feed(page_html)
        print('(feed:%.3fs)' % (time.clock() - t0))
        
        if ignore_old and check_database(fav):
            break
        else:
            time.sleep(2)  # 1s太短可能被搞

    return fav

def get_data_detail(fav, header):
    ''' 获取收藏项的详细信息 (回答页才给gif地址 专栏和想法点进去才有日期) '''
    pattern = re.compile(r'<img[^>]*\ssrc="([^"]*)"[^>]*>')

    for i in range(len(fav.answer_link)):
        link = 'https://' + fav.answer_link[i]
        fav_imgs = [m for m in pattern.finditer(fav.answer_text[i])]
        if 'zhuanlan' not in link and 'pin' not in link and len(fav_imgs) < 1: continue
        
        print('Requesting', link, end=' ')
        t0 = time.clock()
        request = urllib.request.Request(link, headers=header)
        response = urllib.request.urlopen(request)
        print('(%.3fs)' % (time.clock() - t0), end=' ')
        if check_status(response): continue

        # gif网址的对应补充 (page里的图会比fav的多个头像和一些占位svg)
        page_html = response.read().decode()  # 有分组时findall只返回分组的内容
        page_srcs = [s for s in pattern.findall(page_html) if s.endswith('.gif')]
        page_gifs = [extract_filename(s) for s in page_srcs]  # 各元素要与page_srcs对应
        if len(page_gifs) > 0:
            for fav_img in fav_imgs[::-1]:  # 插入点后的匹配区间都得变 因此从后往前才方便
                fav_src = extract_filename(fav_img.group(1))
                if fav_src not in page_gifs: continue
                idx = page_gifs.index(fav_src)  # index找不到会抛异常
                pos = fav_img.end(1) + 1  # 在原html中src的引号后插入 保留原src以载入静止图
                add = ' data-actualsrc="' + page_srcs[idx] + '"'  # 用新属性装gif地址
                fav.answer_text[i] = fav.answer_text[i][:pos] + add + fav.answer_text[i][pos:]
            print(' - found %d GIF(s)' % len(page_gifs), end=' ')

        # 文章/想法页的编辑日期提取
        if 'zhuanlan' in link:  # 找div里的span内的文本(标签中间即>之后<之前)
            time_pos = page_html.find('<div class="ContentItem-time"')
            time_pos = page_html.find('<span ', time_pos)
            time_pos = page_html.find('>', time_pos) + 1
            fav.answer_time[i] = fav.handle_timestamp(page_html[time_pos : page_html.find('<', time_pos)])
        elif 'pin' in link:
            time_pos = page_html.find('<div class="HoverTitle"')
            time_pos = page_html.find('data-hover-title=', time_pos)
            time_pos = page_html.find('"', time_pos) + 1
            fav.answer_time[i] = fav.handle_timestamp(page_html[time_pos : page_html.find('"', time_pos)])

        time.sleep(1.5)
        print('')


def get_avatar(fav, header):
    ''' 获取答主头像 '''
    if not os.path.exists("avatar"):
        os.mkdir("avatar")

    img = img_parser()
    failed = []
    existed = [f[0:-4] for f in os.listdir('./avatar')]  # 文件名去掉'.jpg'
    new_user = set(fav.answer_user) - set(existed)  # 集合除重，再除已下载的头像文件
    if len(new_user) > 0:
        print('Find', len(new_user), 'new avatars')

    for user in new_user:
        print("Requesting avatar of", user, end=' ')
        t0 = time.clock()
        link = 'https://' + fav.answer_link[fav.answer_user.index(user)]  # 答案页比用户页载入快
        request = urllib.request.Request(link, headers=header)  # 有的用户/问题登录才能看 要cookie
        response = urllib.request.urlopen(request)
        if check_status(response): failed.append(user); continue
        
        img.feed(response.read().decode())
        if len(img.link) == 0: failed.append(user); print('(link not found)'); continue
        
        host = urllib.parse.urlparse(img.link).hostname
        request = urllib.request.Request(img.link, headers={'Host': host})
        response = urllib.request.urlopen(request)  # 图片防盗链, 头部与爬网页不同
        print('(%dk, %.3fs)' % (int(response.length / 1024), time.clock() - t0))
        if check_status(response): failed.append(user); continue
        
        pic = open('avatar/' + user + '.jpg', 'wb')  # 文件夹得先建好
        pic.write(response.read())  # read完response.length就变0了
        pic.close()
        time.sleep(1)  # 不等会被搞

    if len(failed) > 0:
        print(len(failed), 'avatar failed')


##########################
#####   Run Script   #####
##########################
if __name__ == '__main__':  # 脚本模式运行此文件时进入
    t0 = time.clock()  # ↓ 登录信息的Cookie
    header = {'Cookie': ''}

    all_fav = 'https://www.zhihu.com/collections/mine'  # 自带10个收藏夹其余动态加载(个人主页只带4个)
    request = urllib.request.Request(all_fav, headers=header)
    response = urllib.request.urlopen(request)
    page_html = response.read().decode()

    fav_entry = []
    fav_title = []
    next_entry = page_html.find('/collection/')
    while next_entry >= 0:
        fav_entry.append('https://www.zhihu.com' + page_html[next_entry : page_html.find('"', next_entry)])
        fav_title.append(page_html[page_html.find('>', next_entry) + 1 : page_html.find('</', next_entry)])
        next_entry = page_html.find('/collection/', next_entry + 1)

    # 可直接设置fav_entry链接爬取指定的收藏夹(此时上面all_fav后到这一行之间的代码都可删去)
    # fav_entry = ['https://www.zhihu.com/collection/106496199']
    # fav_title = ['十八字以内...']  # 这个随便设 只要len与entry一致
    for i in range(0, len(fav_entry)):
        print('\n%s (%s)' % (fav_entry[i], fav_title[i]))
        fav = get_data(fav_entry[i], header, ignore_old=True)
        get_data_detail(fav, header)
        update_database(fav)
        get_avatar(fav, header)
    
    print('\n')
    export_database()
    
    print('\nall tasks complete, taking up %.3fs' % (time.clock() - t0))
