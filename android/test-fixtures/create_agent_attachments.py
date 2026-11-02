"""Create synthetic document-picker files in a new/empty test directory (never overwrite)."""

import base64
import pathlib
import sys


def main():
    directory = pathlib.Path(sys.argv[1])
    directory.mkdir(parents=True, exist_ok=True)
    png = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS1cAAAAASUVORK5CYII="
    )
    for name, data in [
        ("Attachment screenshot.png", png),
        ("Attachment data.bin", bytes(i % 251 for i in range(256 * 1024))),
    ]:
        with (directory / name).open("xb") as output:
            output.write(data)
    with (directory / "Attachment too large.bin").open("xb") as output:
        output.truncate(20 * 1024 * 1024 + 1)


if __name__ == "__main__":
    main()
