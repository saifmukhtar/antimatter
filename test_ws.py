import asyncio
import websockets
import json

async def test():
    uri = "ws://127.0.0.1:8765"
    async with websockets.connect(uri) as ws:
        await ws.send(json.dumps({"type": "GET_FILES", "path": None}))
        print("Sent GET_FILES")
        while True:
            response = await ws.recv()
            data = json.loads(response)
            print("Received:", data.get("type"))
            if data.get("type") == "FILE_TREE":
                print("Tree size:", len(data.get("tree", [])))
                break
asyncio.run(test())
