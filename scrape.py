import os
import time
import sqlite3
import html.parser
import urllib.request


class fav_parser(html.parser.HTMLParser):
    def __init__(self):        # 放外面所有被实例共用 即新对象也有之前的答案
        html.parser.HTMLParser.__init__(self)
        self.folder_name = ''  # 收藏夹名
        self.answer_head = []  # 问题
        self.answer_user = []  # 答主
        self.answer_data = []  # 内容
        self.answer_link = []  # 链接
        self.next_link  = ''   # 下一页链接
        self.__tmp_link = ''   # 页码区遇到下一页链接的标签前会碰到别的页码链接
        self.__tag_type = 0    # 1-页码/div/span/a; 2-收藏夹名/h2; 3-题目/h2/a; 4-答主/div/span/a; 5-内容

    def handle_starttag(self, tag, attrs):
        if tag == 'textarea':  # 只有双引号的字符串可用单引号包住(反过来也行), 不必用r'...'
            self.__tag_type = 5
        elif tag == 'span' and 'author-link-line' in sum(attrs, ()):  # 专栏/回答有 匿名/点赞无; sum转一维
            self.__tag_type = 4
        elif tag == 'h2' and ('class', 'zm-item-title') in attrs:     # 要在下面改[-1]之前append 默认匿名
            self.__tag_type = 3;  self.answer_user.append('')
        elif tag == 'h2' and ('id', 'zh-fav-head-title') in attrs:    # 私密收藏夹里面有个<i>图标不影响
            self.__tag_type = 2
        elif tag == 'div' and ('class', 'zm-invite-pager') in attrs:  # 恰好div里面没别的div, 直接用type判
            self.__tag_type = 1  # 只有一页时进不来此if, next_link不变

        if self.__tag_type == 1 and tag == 'a':
            self.__tmp_link = dict(attrs)['href']
        elif self.__tag_type == 4 and tag == 'a':
            url = dict(attrs)['href']                      # 原为/people/xxxx
            self.answer_user[-1] = url[url.rfind('/')+1:]  # 改为xxxx
            self.__tag_type = 0                            # 如果在end才置0会让提取下页链接时提前退出
        elif tag == 'div' and 'data-entry-url' in sum(attrs, ()):
            url = dict(attrs)['data-entry-url']            # 只有图片的回答没有[显示全部] 专栏没有[编辑于..]
            if url[0] == '/':                              # 专栏自带完整链接
                url = 'https://www.zhihu.com' + url
            self.answer_link.append(url)

    def handle_data(self, data):
        if self.__tag_type == 0 or self.__tag_type == 4:
            pass                                           # 省得下面判断那么多
        elif self.__tag_type == 1 and data != '下一页':
            self.__tmp_link = ''                           # 最后一页的[下一页]没链接
        elif self.__tag_type == 1 and data == '下一页':
            self.next_link = self.__tmp_link               # 若留着之前页码的链接会持续访问倒数第二页
        elif self.__tag_type == 2:
            self.folder_name = data.strip()                # 前后有\n
        elif self.__tag_type == 3 and len(data) > 0:
            self.answer_head.append(data)
        elif self.__tag_type == 5:
            self.answer_data.append(data)

    def handle_endtag(self, tag):
        if self.__tag_type > 0 and tag in ('h2', 'div', 'textarea'):
            self.__tag_type = 0  # 优先级: is(内存地址) > in > not > and > or


# For scraping 'answer_user': (进答案/文章页才有头像文件名, 用文件名除重比用户名复杂)
# <img class="Avatar Avatar--large UserAvatar-inner" src="xxx_xl.jpg" 用户页(自带两条动态) X
# <img class="Avatar AuthorInfo-avatar" src="xxx_xs.jpg" 动态|问题页(更多回答和关于作者栏由js载入) 快！
# <img class="Avatar-hemingway PostIndex-authorAvatar Avatar--xs" src="xxx_xs.jpg" 专栏文章
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
               ('class', 'Avatar-hemingway PostIndex-authorAvatar Avatar--xs') in attrs:
                url = dict(attrs)['src']
                url = url[0:url.rfind('_')] + url[url.rfind('.'):]  # 没扩展名得png, 太大
                self.link = url


##########################
#####   Interfaces   #####
##########################
def get_data(entry_url, header):
    fav = fav_parser()
    fav.next_link = '?page=1'

    while len(fav.next_link) > 0:
        print('Requesting', fav.next_link[1:], end=' ')
        t0 = time.clock()
        request = urllib.request.Request(entry_url + fav.next_link, headers=header)
        response = urllib.request.urlopen(request)
        print('(%.3fs)' % (time.clock() - t0), end=' ')
        if response.status != 200:
            print('(status code %d)' % (response.status, ), end=' ')
            continue  # fav.next_link还没改 正好重来; 而且网页有问题时没下页链接

        page_html = response.read().decode()  # 有问题就read会抛异常
        fav.next_link = ''
        fav.feed(page_html)
        if len(fav.answer_head) == len(fav.answer_user) and \
           len(fav.answer_user) == len(fav.answer_link) and \
           len(fav.answer_link) == len(fav.answer_data):
            print(''); time.sleep(2)  # 1s太短可能被搞
        else:
            print('-', len(fav.answer_head), len(fav.answer_user), len(fav.answer_link), len(fav.answer_data))
    
    return fav


def update_database(fav):
    fav_rows = []  # 一次更新的都属于同一个收藏夹
    for i in range(len(fav.answer_data)):
        fav_rows.append((fav.folder_name, fav.answer_head[i], fav.answer_user[i],
                            fav.answer_link[i], fav.answer_data[i]))  # 每行是一个(tuple)
    print('Found', len(fav.answer_data), 'collection items')

    conn = sqlite3.connect('fav.db')
    columns = conn.execute('PRAGMA table_info(fav);').fetchall()  # 表中每列属性，无此表返回空
    if len(columns) != 5:
        conn.execute('''ALTER TABLE `fav` RENAME TO `fav_old`;''')  # 表/列名有.空格/关键字放在反引号``里
        conn.execute('''CREATE TABLE fav (folder  TEXT,
                                          title   TEXT,
                                          author  TEXT,
                                          link    TEXT,
                                          content TEXT,
                                          PRIMARY KEY(folder, link));''')
    # conn.execute('CREATE UNIQUE INDEX ifav on fav (folder, link);') 建表时没弄唯一约束才用/索引的列应少插改

    old = conn.execute('SELECT link, content FROM fav WHERE folder="%s";' % fav.folder_name).fetchall()
    old_lnk = [r[0] for r in old]
    new_lnk = [r[3] for r in fav_rows]
    insert = [r for r in fav_rows if r[3] not in old_lnk]  # 递推式/推导式 [3:5]的区间是[3,5)
    update = [r for r in fav_rows if r[3] in old_lnk and r[3:5] not in old]  # 内容没变的去掉
    delete = [r for r in old if r[0] not in new_lnk]
    print('Including %d new, %d edited and found %d deleted items.' % (len(insert), len(update), len(delete)))

    # REPLACE不能用WHERE, 唯一(组合)索引一致就先删再插(显然没变的也删) (primary key/unique/unique index都看)
    conn.executemany('INSERT OR REPLACE INTO fav VALUES (?,?,?,?,?);', fav_rows)
    conn.commit()
    conn.close()


def get_avatar(fav, header):
    img = img_parser()
    failed = []
    existed = [f[0:-4] for f in os.listdir('./avatar')]  # 去掉'.jpg'
    new_user = set(fav.answer_user) - set(existed)  # 集合除重，再除已下载的头像文件
    print('Found', len(new_user), 'new avatars')

    def check_status(response):
        if response.status != 200:
            print('(code %d)' % (response.status), end=' ')
            failed.append(user)
            return 1  # fails
        else:
            return 0

    for user in new_user:
        print("Requesting avatar of", user, end=' ')
        t0 = time.clock()
        link = fav.answer_link[fav.answer_user.index(user)]  # 答案页比用户页载入快
        request = urllib.request.Request(link, headers=header)  # 有的用户/问题登录才能看 要cookie
        response = urllib.request.urlopen(request)
        if check_status(response): continue
        
        img.feed(response.read().decode())
        if len(img.link) == 0: failed.append(user); continue
        
        request = urllib.request.Request(img.link, headers={'Referer': link})
        response = urllib.request.urlopen(request) # 图片防盗链, 头部与爬网页不同
        print('(%dk)' % (int(response.length / 1024)), end=' ')
        print('(%.3fs)' % (time.clock() - t0))
        if check_status(response): continue
        
        pic = open('avatar/' + user + '.jpg', 'wb')  # 文件夹得先建好
        pic.write(response.read())  # read完response.length就变0了
        pic.close()

    print(len(failed), 'avatar failed')


##########################
#####   Run Script   #####
##########################
if __name__ == '__main__':  # 脚本模式运行此文件时进入; F12后复制主html的cookie即可
    header = {'Cookie': ''}

    all_fav = 'https://www.zhihu.com/collections/mine'  # 个人主页的收藏只自带4个 其余动态加载
    request = urllib.request.Request(all_fav, headers=header)
    response = urllib.request.urlopen(request)
    page_html = response.read().decode()

    fav_entry = []
    next_entry = page_html.find('/collection/')
    while next_entry >= 0:
        fav_entry.append('https://www.zhihu.com' + page_html[next_entry:page_html.find('"', next_entry)])
        next_entry = page_html.find('/collection/', next_entry + 1)

    for entry in fav_entry[:]:
        print('\n' + entry)
        fav = get_data(entry, header)
        update_database(fav)
        get_avatar(fav, header)
