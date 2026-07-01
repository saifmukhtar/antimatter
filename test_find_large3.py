import asyncio, sys, time, json
sys.path.insert(0, 'core/shared-config/src')
sys.path.insert(0, 'core/shared-fs/src')
from antimatter_fs.tree import build_file_tree

def get_counts(nodes):
    total = 0
    for n in nodes:
        total += 1
        if n.children:
            total += get_counts(n.children)
    return total

async def main():
    start = time.time()
    tree = await build_file_tree('/home/saif/antimatter')
    total = get_counts(tree)
    print(f"Total nodes: {total}, Time: {time.time()-start:.2f}s")

asyncio.run(main())
