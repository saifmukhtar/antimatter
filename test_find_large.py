import asyncio, sys, time, json
sys.path.append('core/shared-config/src')
sys.path.append('core/shared-fs/src')
from antimatter_fs.tree import build_file_tree

def get_counts(nodes):
    total = 0
    for n in nodes:
        total += 1
        if n.children:
            total += get_counts(n.children)
    return total

async def main():
    tree = await build_file_tree('/home/saif/antimatter')
    for n in tree:
        if n.is_directory and n.children:
            c = get_counts(n.children)
            if c > 100:
                print(f"{n.name}: {c}")

asyncio.run(main())
