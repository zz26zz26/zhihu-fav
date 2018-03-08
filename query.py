import sqlite3
conn = sqlite3.connect('fav.db')  # (folder, title, author, link, content)
cursor = conn.cursor()
# cursor.execute('PRAGMA database_list;')  # connect打开的文件
# cursor.execute('SELECT name FROM sqlite_master WHERE type="table";')  # 文件中所有表名
# cursor.execute('PRAGMA table_info(fav);')  # 表中每列属性
# cursor.execute('DELETE FROM fav WHERE folder="测试";')
# cursor.execute('UPDATE fav SET author="";')
# cursor.execute('SELECT folder, author FROM fav;')
# cursor.execute('SELECT folder, count(*) FROM fav GROUP BY folder;') # title GLOB "*铁*" OR
cursor.execute('SELECT folder, title, link FROM fav WHERE content GLOB "*吴驾翔*" GROUP BY link;')
rows = cursor.fetchall()

print(len(rows), 'results')
for row in rows:
    print(row)

conn.close()
