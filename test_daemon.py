import asyncio
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
    print("Total:", get_counts(tree))
asyncio.run(main())
