# zhihu-fav
* 爬取多个收藏夹只支持登录后从 https://www.zhihu.com/collections 及类似页面开始，爬取私密收藏夹时也要登录
* 要使用登录信息，只需找到scrape.py最后的Run Script部分，把在浏览器登录后的Cookie粘贴到header处之后再运行该脚本
* Cookie获取方法：打开F12-网络，刷新，点击url和地址栏相同的那项html，找到右侧请求标头里的Cookie项，复制值
