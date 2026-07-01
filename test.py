import urllib.parse
params = {"key": "ab+cd"}
query = urllib.parse.urlencode(params)
print(query)
