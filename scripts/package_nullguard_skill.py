"""Create a reproducible, self-contained skill ZIP without caches or engine binaries."""
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / "skills" / "nullguard-audit"


def package(output):
    if not (SKILL / "SKILL.md").is_file() or not (SKILL / "LICENSE").is_file():
        raise ValueError("Skill instructions and license are required")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(SKILL.rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix == ".pyc":
                continue
            if path.suffix not in {".md", ".py", ".yaml", ".yml"} and path.name != "LICENSE":
                raise ValueError("Unexpected skill resource: " + str(path))
            info = zipfile.ZipInfo("nullguard-audit/" + path.relative_to(SKILL).as_posix(), (2024, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes())
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "target" / "skills" / "nullguard-audit.zip")
    print(package(parser.parse_args().output))
