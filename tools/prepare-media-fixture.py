"""Prepare the deterministic local WebM before either bounded browser smoke starts."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--ffmpeg", help="Explicit ffmpeg executable; otherwise use imageio-ffmpeg")
args = parser.parse_args()
if args.ffmpeg:
    executable = args.ffmpeg
else:
    sys.path.insert(0, str(ROOT / ".codex-temp/validation-tools"))
    import imageio_ffmpeg
    executable = imageio_ffmpeg.get_ffmpeg_exe()
folder = ROOT / ".codex-temp/media-fixture"
folder.mkdir(parents=True, exist_ok=True)
target = folder / "long.webm"
subprocess.run([
    executable, "-y", "-f", "lavfi", "-i", "testsrc2=duration=60:size=640x360:rate=15",
    "-an", "-c:v", "libvpx-vp9",
    "-b:v", "300k", str(target)
], check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
report = {"source": "synthetic lavfi test pattern, no audio track", "durationSeconds": 60,
          "bytes": target.stat().st_size, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
(folder / "fixture.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report))
