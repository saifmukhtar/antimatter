import urllib.parse
gateway_pub = "a+b/c="
params = {"x25519_pub": gateway_pub}
print(urllib.parse.urlencode(params))
