#!/usr/bin/env python3
"""
Download all photos from a Google Photos shared album URL.

Usage:
    python download_google_album.py <album_url> [output_dir]

Example:
    python download_google_album.py "https://photos.google.com/share/AF1Qip..." ./my_photos
"""

import os
import re
import sys
import json
import time
import argparse
import requests
from pathlib import Path
from urllib.parse import urlparse, unquote


def extract_photo_urls(album_url: str) -> list[dict]:
    """
    Fetch the Google Photos shared album page and extract photo URLs
    from the embedded JavaScript data.
    """
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
        "Accept-Language": "en-US,en;q=0.9",
    }

    print(f"Fetching album page: {album_url}")
    resp = requests.get(album_url, headers=headers, timeout=30)
    resp.raise_for_status()
    html = resp.text

    # Google Photos embeds image data in JS arrays within the page.
    # Image URLs look like: https://lh3.googleusercontent.com/...
    # They appear in patterns like: ["https://lh3.googleusercontent.com/...",<width>,<height>]
    pattern = r'\["(https://lh3\.googleusercontent\.com/[^"]+)",(\d+),(\d+)'
    matches = re.findall(pattern, html)

    if not matches:
        # Try alternate pattern — sometimes the data is in a different format
        pattern2 = r'"(https://lh3\.googleusercontent\.com/[a-zA-Z0-9_\-/]+)"'
        raw_urls = re.findall(pattern2, html)
        # Deduplicate while preserving order
        seen = set()
        photos = []
        for url in raw_urls:
            if url not in seen:
                seen.add(url)
                photos.append({"url": url, "width": None, "height": None})
        return photos

    # Deduplicate while preserving order
    seen = set()
    photos = []
    for url, width, height in matches:
        if url not in seen:
            seen.add(url)
            photos.append({
                "url": url,
                "width": int(width),
                "height": int(height),
            })

    return photos


def download_photo(url: str, output_path: Path, index: int, total: int) -> bool:
    """Download a single photo at full resolution."""
    # Append =d to get the original/full resolution download
    download_url = url + "=d"

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
    }

    try:
        resp = requests.get(download_url, headers=headers, timeout=60, stream=True)
        resp.raise_for_status()

        # Determine file extension from Content-Type
        content_type = resp.headers.get("Content-Type", "image/jpeg")
        ext_map = {
            "image/jpeg": ".jpg",
            "image/png": ".png",
            "image/gif": ".gif",
            "image/webp": ".webp",
            "image/heic": ".heic",
            "image/heif": ".heif",
            "video/mp4": ".mp4",
            "video/quicktime": ".mov",
        }
        ext = ext_map.get(content_type, ".jpg")

        # Try to get filename from Content-Disposition header
        cd = resp.headers.get("Content-Disposition", "")
        filename = None
        if "filename=" in cd:
            match = re.search(r'filename[*]?=["\']?(?:UTF-8\'\')?([^"\';\r\n]+)', cd)
            if match:
                filename = unquote(match.group(1)).strip()

        if not filename:
            filename = f"photo_{index:04d}{ext}"

        filepath = output_path / filename

        # Avoid overwriting — add suffix if file exists
        counter = 1
        original_stem = filepath.stem
        while filepath.exists():
            filepath = output_path / f"{original_stem}_{counter}{filepath.suffix}"
            counter += 1

        # Download with progress
        total_size = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        with open(filepath, "wb") as f:
            for chunk in resp.iter_content(chunk_size=8192):
                f.write(chunk)
                downloaded += len(chunk)

        size_mb = downloaded / (1024 * 1024)
        print(f"  [{index}/{total}] ✓ {filepath.name} ({size_mb:.1f} MB)")
        return True

    except requests.RequestException as e:
        print(f"  [{index}/{total}] ✗ Failed: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Download all photos from a Google Photos shared album."
    )
    parser.add_argument("url", help="Google Photos shared album URL")
    parser.add_argument(
        "output",
        nargs="?",
        default="./google_album_photos",
        help="Output directory (default: ./google_album_photos)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.5,
        help="Delay between downloads in seconds (default: 0.5)",
    )
    args = parser.parse_args()

    # Validate URL
    if "photos.google.com" not in args.url and "photos.app.goo.gl" not in args.url:
        print("⚠  Warning: URL doesn't look like a Google Photos link. Proceeding anyway...")

    # Create output directory
    output_path = Path(args.output)
    output_path.mkdir(parents=True, exist_ok=True)
    print(f"Output directory: {output_path.resolve()}")

    # Extract photo URLs
    photos = extract_photo_urls(args.url)

    if not photos:
        print("❌ No photos found. Possible reasons:")
        print("   - The album URL might be invalid or expired")
        print("   - The album might be private (not shared via link)")
        print("   - Google may have changed their page format")
        sys.exit(1)

    print(f"\n📷 Found {len(photos)} photo(s). Starting download...\n")

    # Download all photos
    success = 0
    failed = 0
    for i, photo in enumerate(photos, 1):
        if download_photo(photo["url"], output_path, i, len(photos)):
            success += 1
        else:
            failed += 1
        if i < len(photos):
            time.sleep(args.delay)

    # Summary
    print(f"\n{'='*50}")
    print(f"✅ Downloaded: {success}/{len(photos)}")
    if failed:
        print(f"❌ Failed:     {failed}/{len(photos)}")
    print(f"📁 Saved to:   {output_path.resolve()}")


if __name__ == "__main__":
    main()
