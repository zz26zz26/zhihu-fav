# zhihu-fav
## 功能概述
* 爬虫基于Python 3.6，使用SQLite存储，数据包括收藏夹、作者、编辑时间、链接、问题和回答
* 登录后使用可防止遗漏登录才能看的问题或文章；爬取网页间隔1-2s，一般来说是比较安全的
## 使用说明
* 爬取多个收藏夹只支持登录后从 https://www.zhihu.com/collections 及类似页面开始，爬私密收藏夹也要用登录信息
* 若要用登录信息，需先在浏览器登录，然后找到scrape.py最后的Run Script部分，把Cookie粘贴到header处再运行脚本
* 登录Cookie获取：在收藏夹页面打开F12-网络，刷新，点击url和地址栏相同的那项html，复制右侧请求标头里的Cookie值
