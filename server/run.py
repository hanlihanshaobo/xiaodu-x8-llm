"""启动入口：python server/run.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from brain.main import main  # noqa: E402

if __name__ == "__main__":
    main()
