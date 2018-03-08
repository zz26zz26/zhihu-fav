import http.cookiejar
import urllib.request
import html.parser
import sqlite3
import time
import os

class fav_parser(html.parser.HTMLParser):
    answer_head = []
    answer_user = []
    answer_data = []
    answer_link = []
    folder_name = '' # 收藏夹名称
    next_link = ''   # 只有双引号的字符串可用单引号包住(反过来也行)，不必用r'...'
    __tmp_link = ''  # 页码区遇到下一页链接的标签前会碰到别的页码
    __tag_type = 0   # 0-不管 1-页码/div/span/a 2-收藏夹名/h2 3-题目/h2/a 4-答主/div/span/a 5-内容/textarea

    def handle_starttag(self, tag, attrs):
        if tag == 'textarea':
            self.__tag_type = 5
        elif tag == 'a' and ('data-hovercard') in sum(attrs, ()):     # 专栏/回答有 匿名无; sum转一维大法
            self.__tag_type = 4
        elif tag == 'h2' and ('class', 'zm-item-title') in attrs:     # 要在下面改[-1]之前append
            self.__tag_type = 3;  self.answer_user.append('')
        elif tag == 'h2' and ('id', 'zh-fav-head-title') in attrs:    # 私密收藏夹里面有个<i>图标不影响
            self.__tag_type = 2
        elif tag == 'div' and ('class', 'zm-invite-pager') in attrs:  # 恰好div里面没别的div，直接用type判
            self.__tag_type = 1  # 只有一页时进不来此if，next_link不变

        if self.__tag_type == 1 and tag == 'a':
            self.__tmp_link = dict(attrs)['href']
        elif self.__tag_type == 4 and tag == 'a':
            url = dict(attrs)['href']                      # 原为/people/xxx
            self.answer_user[-1] = url[url.rfind('/')+1:]  # 改为xxx
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
        if self.__tag_type > 0 and tag in ('div', 'h2', 'textarea'):
            self.__tag_type = 0  # 优先级: is(内存地址) > in > not > and > or

# When scraping 'answer_user': (进入答案/文章页才能得到头像文件名，比用用户名除重难)
# <img class="Avatar Avatar--large UserAvatar-inner" src="xxx_xl.jpg" ... 用户页(自带两条动态) X
# <img class="Avatar AuthorInfo-avatar" src="xxx_xs.jpg" ... 动态|问题页(更多回答和关于作者栏均由js载入) 快！
# <img class="Avatar-hemingway PostIndex-authorAvatar Avatar--xs" src="xxx_xs.jpg" ... 专栏文章 X
class img_parser(html.parser.HTMLParser):
    def __init__(self):
        self.link = ''  # 放外面是类变量了，所有实例共用，外部也可用类名访问
        html.parser.HTMLParser.__init__(self)
    
    def feed(self, data):
        self.link = ''  # 防止返回上次得到的地址
        html.parser.HTMLParser.feed(self, data)
    
    def handle_starttag(self, tag, attrs):
        if tag == 'img':
            if ('class', 'Avatar AuthorInfo-avatar') in attrs or \
               ('class', 'Avatar-hemingway PostIndex-authorAvatar Avatar--xs') in attrs:
                url = dict(attrs)['src']
                url = url[0:url.rfind('_')] + url[url.rfind('.'):]  # 没扩展名是png
                self.link = url


########## Prepare for Scraping ##########
fav = fav_parser()
fav.next_link = '?page=1'
entry_url = 'https://www.zhihu.com/collection/186197750'
cookie = ''
header = {'Cookie': cookie}


########## Collection Folder Scraping Begins ##########
while len(fav.next_link) > 0:
    print('Requesting', fav.next_link[1:], end=' ')
    t0 = time.clock()
    request = urllib.request.Request(entry_url + fav.next_link, headers=header)
    response = urllib.request.urlopen(request)
    page_html = response.read().decode()
    print('returns status code %d (%.3fs)' % (response.status, time.clock() - t0), end=' ')

    fav.next_link = ''
    fav.feed(page_html)
    if len(fav.answer_head) == len(fav.answer_user) and \
       len(fav.answer_user) == len(fav.answer_link) and \
       len(fav.answer_link) == len(fav.answer_data):
        time.sleep(1.5)
        print('')
    else:
        print('-', len(fav.answer_head), len(fav.answer_user), len(fav.answer_link), len(fav.answer_data))

insert_rows = []
for i in range(len(fav.answer_data)):
    insert_rows.append((fav.folder_name, fav.answer_head[i], fav.answer_user[i],
                        fav.answer_link[i], fav.answer_data[i]))
print('Find', len(fav.answer_data), 'collection items')

conn = sqlite3.connect('fav.db')
# conn.execute('''CREATE TABLE fav (folder  TEXT,
#                                   title   TEXT,
#                                   author  TEXT,
#                                   link    TEXT,
#                                   content TEXT);''')  # 表/列名有空格才要放在``里
# conn.execute('DELETE FROM fav WHERE folder="测试";')
conn.executemany('INSERT INTO fav VALUES (?,?,?,?,?);', insert_rows)
conn.commit()
conn.close()
# exit()

########## Image Scraping Begins ##########
img = img_parser()
failed = []
existed = [f[0:-4] for f in os.listdir('./avatar')]  # 去掉.jpg
new_user = set(fav.answer_user) - set(existed)  # 集合除重，再除已下载的头像文件
print('Find', len(new_user), 'new avatars')

for user in new_user:
    t0 = time.clock()
    link = fav.answer_link[fav.answer_user.index(user)]  # 答案页比用户页载入快
    print("Requesting avatar of", user, end=' ')
    request = urllib.request.Request(link, headers=header)  # 有的用户/问题登录才能看，要cookie
    response = urllib.request.urlopen(request)
    page_html = response.read().decode()

    img.feed(page_html)
    if len(img.link) > 0:
        request = urllib.request.Request(img.link, headers={'Referer': link})  # 图片防盗链，头部与爬网页不同
        response = urllib.request.urlopen(request)
        if response.status != 200:
            print('returns %d' % (response.status), end=' ')
            failed.append(user)
        print('(%dk)' % (int(response.length / 1024)), end=' ')
        pic = open('avatar/' + user + '.jpg', 'wb')  # 文件夹得先建好
        pic.write(response.read())  # read完response.length就变0了
        pic.close()
    else:
        print('but failed', end=' ')
        failed.append(user)
    print('(%.3fs)' % (time.clock() - t0))

print(len(failed), 'avatar failed')
