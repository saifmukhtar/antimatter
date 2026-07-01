import os
import asyncio
from pathlib import Path
from antimatter_protocol.models import FileNode

from antimatter_shared_config.config import load_config

def _build_tree_sync(root_path: str, ignore_list: set[str], max_depth: int = 12, current_depth: int = 0) -> list[FileNode]:
    """Synchronous recursive tree builder."""
    if current_depth > max_depth:
        return []

    nodes: list[FileNode] = []
    try:
        with os.scandir(root_path) as it:
            for entry in it:
                if entry.name in ignore_list:
                    continue
                    
                is_dir = entry.is_dir()
                size = entry.stat().st_size if not is_dir else None
                
                children = None
                if is_dir:
                    children = _build_tree_sync(entry.path, ignore_list, max_depth, current_depth + 1)
                
                nodes.append(FileNode(
                    name=entry.name,
                    is_directory=is_dir,
                    path=entry.path,
                    size=size,
                    children=children
                ))
    except PermissionError:
        pass
    except FileNotFoundError:
        pass
        
    # Sort directories first, then alphabetically
    nodes.sort(key=lambda x: (not x.is_directory, x.name.lower()))
    return nodes

async def build_file_tree(root_path: str, max_depth: int = 12) -> list[FileNode]:
    """Async file tree builder that won't block the event loop."""
    config = load_config()
    ignore_list = set(config.ignored_folders)
    return await asyncio.to_thread(_build_tree_sync, root_path, ignore_list, max_depth, 0)
