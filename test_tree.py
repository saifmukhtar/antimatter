import asyncio, sys, time, json
sys.path.append('core/shared-config/src')
sys.path.append('core/shared-fs/src')
from antimatter_fs.tree import build_file_tree

def count_nodes(nodes):
    total = 0
    for n in nodes:
        total += 1
        if n.children:
            total += count_nodes(n.children)
    return total

async def main():
    tree = await build_file_tree('/home/saif/antimatter')
    total = count_nodes(tree)
    payload = json.dumps([n.model_dump() for n in tree])
    print(f'Total nodes: {total}, JSON size: {len(payload)/(1024*1024):.2f} MB')

asyncio.run(main())
