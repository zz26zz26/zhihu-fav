# zhihu-fav
* 爬取多个收藏夹只支持登录后从 https://www.zhihu.com/collections 及类似页面开始，爬私密收藏夹也要用登录信息
* 若要用登录信息，需先在浏览器登录，然后找到scrape.py最后的Run Script部分，把Cookie粘贴到header处再运行脚本
* 登录Cookie获取：在收藏夹页面打开F12-网络，刷新，点击url和地址栏相同的那项html，复制右侧请求标头里的Cookie值
