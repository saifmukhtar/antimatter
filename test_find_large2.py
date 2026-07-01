import asyncio, sys
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
    for root_n in tree:
        if root_n.name == 'android' and root_n.children:
            for n in root_n.children:
                if n.is_directory and n.children:
                    c = get_counts(n.children)
                    if c > 100:
                        print(f"android/{n.name}: {c}")

asyncio.run(main())
