# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "tomli",
#     "tomli-w",
# ]
# ///
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
import tomli
import tomli_w

def build_gateway():
    core_dir = Path("/home/saif/antimatter/core")
    gateway_dir = core_dir / "gateway"
    
    # Shared modules to bundle
    shared_modules = [
        "shared-config",
        "shared-crypto",
        "shared-protocol",
        "shared-fs"
    ]
    
    print("Preparing isolated build environment...")
    with tempfile.TemporaryDirectory() as temp_dir:
        build_dir = Path(temp_dir) / "gateway_build"
        
        # 1. Copy gateway source to build_dir
        shutil.copytree(gateway_dir, build_dir)
        
        # 2. Copy shared libraries into the gateway's src directory
        gateway_src_dir = build_dir / "src"
        if not gateway_src_dir.exists():
            gateway_src_dir.mkdir(parents=True)
            
        for module in shared_modules:
            shared_src = core_dir / module / "src"
            if shared_src.exists():
                for item in shared_src.iterdir():
                    if item.is_dir() and item.name != "__pycache__":
                        dest = gateway_src_dir / item.name
                        if dest.exists():
                            shutil.rmtree(dest)
                        shutil.copytree(item, dest)
                        print(f"Bundled {item.name} into gateway/src")
        
        # 3. Patch pyproject.toml to remove workspace dependencies and auto-merge external ones
        pyproject_path = build_dir / "pyproject.toml"
        with open(pyproject_path, "rb") as f:
            pyproject = tomli.load(f)
            
        merged_dependencies = set()
            
        # Collect external dependencies from shared modules
        for module in shared_modules:
            shared_toml = core_dir / module / "pyproject.toml"
            if shared_toml.exists():
                with open(shared_toml, "rb") as f:
                    shared_data = tomli.load(f)
                    if "project" in shared_data and "dependencies" in shared_data["project"]:
                        for d in shared_data["project"]["dependencies"]:
                            if not d.startswith("antimatter-shared-"):
                                merged_dependencies.add(d)
        
        # Merge with gateway dependencies
        if "project" in pyproject and "dependencies" in pyproject["project"]:
            gateway_deps = pyproject["project"]["dependencies"]
            # Add existing gateway deps (excluding internal ones)
            for d in gateway_deps:
                if not d.startswith("antimatter-shared-"):
                    merged_dependencies.add(d)
            
            # Apply merged dependencies back
            pyproject["project"]["dependencies"] = sorted(list(merged_dependencies))
            
        # Remove tool.uv.sources for the shared dependencies
        if "tool" in pyproject and "uv" in pyproject["tool"] and "sources" in pyproject["tool"]["uv"]:
            sources = pyproject["tool"]["uv"]["sources"]
            keys_to_remove = [k for k in sources.keys() if k.startswith("antimatter-shared-")]
            for k in keys_to_remove:
                del sources[k]
                
        # Write patched pyproject.toml
        with open(pyproject_path, "wb") as f:
            tomli_w.dump(pyproject, f)
            
        print("Patched pyproject.toml (removed workspace dependencies)")
        
        # 4. Build the package
        print("Running uv build...")
        subprocess.run(["uv", "build"], cwd=build_dir, check=True)
        
        # 5. Copy built distribution back to gateway/dist
        dist_dir = gateway_dir / "dist"
        if dist_dir.exists():
            shutil.rmtree(dist_dir)
        shutil.copytree(build_dir / "dist", dist_dir)
        
        print(f"Build successful! Wheels and sdist are available in {dist_dir}")

if __name__ == "__main__":
    build_gateway()
