#!/usr/bin/env python3
"""
Google Photos Album Downloader & Renamer — GUI Edition

A tkinter GUI that downloads all photos from a Google Photos shared album
and optionally renames them based on EXIF metadata or filename timestamps.

Dependencies:
    pip install requests Pillow
"""

import hashlib
import os
import re
import sys
import threading
import time
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk
from urllib.parse import unquote

try:
    import requests
except ImportError:
    requests = None

try:
    from PIL import Image
    from PIL.ExifTags import Base as ExifBase
except ImportError:
    Image = None
    ExifBase = None


# ─── Constants ────────────────────────────────────────────────────────────────

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)

RENAME_PATTERNS = {
    "YYYYMMDD_HHMMSS": "%Y%m%d_%H%M%S",
    "YYYY-MM-DD_HH-MM-SS": "%Y-%m-%d_%H-%M-%S",
    "IMG_YYYYMMDD_HHMMSS": "IMG_%Y%m%d_%H%M%S",
}

TIMESTAMP_SOURCES = ["EXIF (recommended)", "Filename"]

EXT_MAP = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/gif": ".gif",
    "image/webp": ".webp",
    "image/heic": ".heic",
    "image/heif": ".heif",
    "video/mp4": ".mp4",
    "video/quicktime": ".mov",
}


# ─── Core Logic ───────────────────────────────────────────────────────────────


def extract_photo_urls(album_url: str) -> list[dict]:
    """Fetch a Google Photos shared album page and extract photo URLs."""
    headers = {"User-Agent": USER_AGENT, "Accept-Language": "en-US,en;q=0.9"}

    resp = requests.get(album_url, headers=headers, timeout=30)
    resp.raise_for_status()
    html = resp.text

    # Primary pattern: ["https://lh3.googleusercontent.com/...",<w>,<h>]
    pattern = r'\["(https://lh3\.googleusercontent\.com/[^"]+)",(\d+),(\d+)'
    matches = re.findall(pattern, html)

    if not matches:
        # Fallback pattern
        pattern2 = r'"(https://lh3\.googleusercontent\.com/[a-zA-Z0-9_\-/]+)"'
        raw_urls = re.findall(pattern2, html)
        seen = set()
        photos = []
        for url in raw_urls:
            if url not in seen:
                seen.add(url)
                photos.append({"url": url, "width": None, "height": None})
        return photos

    seen = set()
    photos = []
    for url, width, height in matches:
        if url not in seen:
            seen.add(url)
            photos.append({"url": url, "width": int(width), "height": int(height)})

    return photos


def download_single_photo(
    url: str, output_path: Path, index: int
) -> tuple[bool, str, Path | None]:
    """Download a single photo. Returns (success, message, filepath)."""
    download_url = url + "=d"
    headers = {"User-Agent": USER_AGENT}

    try:
        resp = requests.get(download_url, headers=headers, timeout=60, stream=True)
        resp.raise_for_status()

        content_type = resp.headers.get("Content-Type", "image/jpeg")
        ext = EXT_MAP.get(content_type, ".jpg")

        # Try Content-Disposition for filename
        cd = resp.headers.get("Content-Disposition", "")
        filename = None
        if "filename=" in cd:
            match = re.search(
                r"filename[*]?=[\"']?(?:UTF-8'')?([^\"';\\r\\n]+)", cd
            )
            if match:
                filename = unquote(match.group(1)).strip()

        if not filename:
            filename = f"photo_{index:04d}{ext}"

        filepath = output_path / filename

        # Avoid overwriting
        counter = 1
        original_stem = filepath.stem
        while filepath.exists():
            filepath = output_path / f"{original_stem}_{counter}{filepath.suffix}"
            counter += 1

        total_size = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        with open(filepath, "wb") as f:
            for chunk in resp.iter_content(chunk_size=8192):
                f.write(chunk)
                downloaded += len(chunk)

        size_mb = downloaded / (1024 * 1024)
        return True, f"✓ {filepath.name} ({size_mb:.1f} MB)", filepath

    except requests.RequestException as e:
        return False, f"✗ Failed: {e}", None


def get_exif_datetime(filepath: Path) -> datetime | None:
    """Extract the DateTimeOriginal from EXIF data."""
    if Image is None:
        return None
    try:
        img = Image.open(filepath)
        exif_data = img.getexif()
        if not exif_data:
            return None

        # Try DateTimeOriginal (36867), then DateTimeDigitized (36868),
        # then DateTime (306)
        for tag_id in (36867, 36868, 306):
            value = exif_data.get(tag_id)
            if value:
                # EXIF datetime format: "YYYY:MM:DD HH:MM:SS"
                try:
                    return datetime.strptime(value, "%Y:%m:%d %H:%M:%S")
                except (ValueError, TypeError):
                    continue
        return None
    except Exception:
        return None


def get_filename_datetime(filepath: Path) -> datetime | None:
    """Extract datetime from PXL-style filenames like PXL_20260416_004927341."""
    stem = filepath.stem
    # Match PXL_YYYYMMDD_HHMMSSmmm or similar patterns
    match = re.search(r"(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})", stem)
    if match:
        try:
            return datetime(
                int(match.group(1)),
                int(match.group(2)),
                int(match.group(3)),
                int(match.group(4)),
                int(match.group(5)),
                int(match.group(6)),
            )
        except ValueError:
            return None
    return None


def short_hash(name: str, length: int = 4) -> str:
    """Return a short hex hash of the given string."""
    return hashlib.md5(name.encode()).hexdigest()[:length]


def compute_rename_plan(
    folder: Path,
    pattern_fmt: str,
    use_exif: bool,
) -> list[tuple[Path, Path, str]]:
    """
    Compute a rename plan: list of (old_path, new_path, status_message).
    Does not perform any actual renames.
    """
    image_exts = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif"}
    video_exts = {".mp4", ".mov"}
    allowed = image_exts | video_exts

    files = sorted(
        [f for f in folder.iterdir() if f.is_file() and f.suffix.lower() in allowed]
    )

    plan: list[tuple[Path, Path, str]] = []
    used_names: dict[str, int] = {}

    for fpath in files:
        dt = None
        source_label = ""

        if use_exif and fpath.suffix.lower() in image_exts:
            dt = get_exif_datetime(fpath)
            if dt:
                source_label = "EXIF"

        if dt is None:
            dt = get_filename_datetime(fpath)
            if dt:
                source_label = "filename" if use_exif else "filename"
            else:
                source_label = "skipped"

        if dt is None:
            plan.append((fpath, fpath, f"⚠ No timestamp found, skipping"))
            continue

        base_name = dt.strftime(pattern_fmt)
        ext = fpath.suffix.lower()

        # Collision handling with short hash
        if base_name in used_names:
            new_name = f"{base_name}_{short_hash(fpath.name)}{ext}"
        else:
            new_name = f"{base_name}{ext}"

        used_names[base_name] = used_names.get(base_name, 0) + 1
        new_path = fpath.parent / new_name

        if new_path == fpath:
            plan.append((fpath, new_path, f"— Already named correctly"))
        else:
            plan.append(
                (fpath, new_path, f"→ {new_name}  (from {source_label})")
            )

    return plan


def execute_rename(plan: list[tuple[Path, Path, str]]) -> tuple[int, int]:
    """Execute the rename plan. Returns (renamed_count, skipped_count)."""
    renamed = 0
    skipped = 0
    for old_path, new_path, _ in plan:
        if old_path == new_path or new_path == old_path:
            skipped += 1
            continue
        if "skipping" in _.lower():
            skipped += 1
            continue
        try:
            old_path.rename(new_path)
            renamed += 1
        except OSError:
            skipped += 1
    return renamed, skipped


# ─── GUI ──────────────────────────────────────────────────────────────────────


class AlbumDownloaderApp:
    """Main application window."""

    BG = "#1e1e2e"
    BG_FRAME = "#282840"
    BG_INPUT = "#313148"
    FG = "#cdd6f4"
    FG_DIM = "#a6adc8"
    ACCENT = "#89b4fa"
    ACCENT_HOVER = "#74c7ec"
    GREEN = "#a6e3a1"
    RED = "#f38ba8"
    YELLOW = "#f9e2af"
    BORDER = "#45475a"

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Google Photos Album Downloader")
        self.root.geometry("780x820")
        self.root.minsize(680, 720)
        self.root.configure(bg=self.BG)
        self._configure_styles()

        self._downloading = False
        self._downloaded_files: list[Path] = []

        self._build_ui()
        self._check_dependencies()

    # ── Styles ────────────────────────────────────────────────────────────

    def _configure_styles(self):
        style = ttk.Style()
        style.theme_use("clam")

        style.configure(".", background=self.BG, foreground=self.FG)
        style.configure(
            "TFrame", background=self.BG_FRAME, borderwidth=0
        )
        style.configure("Main.TFrame", background=self.BG)
        style.configure(
            "TLabel",
            background=self.BG_FRAME,
            foreground=self.FG,
            font=("Segoe UI", 10),
        )
        style.configure(
            "Header.TLabel",
            background=self.BG,
            foreground=self.ACCENT,
            font=("Segoe UI", 18, "bold"),
        )
        style.configure(
            "Sub.TLabel",
            background=self.BG,
            foreground=self.FG_DIM,
            font=("Segoe UI", 9),
        )
        style.configure(
            "Section.TLabel",
            background=self.BG_FRAME,
            foreground=self.ACCENT,
            font=("Segoe UI", 11, "bold"),
        )
        style.configure(
            "Accent.TButton",
            background=self.ACCENT,
            foreground="#1e1e2e",
            font=("Segoe UI", 10, "bold"),
            borderwidth=0,
            padding=(16, 8),
        )
        style.map(
            "Accent.TButton",
            background=[("active", self.ACCENT_HOVER), ("disabled", self.BORDER)],
            foreground=[("disabled", self.FG_DIM)],
        )
        style.configure(
            "Secondary.TButton",
            background=self.BG_INPUT,
            foreground=self.FG,
            font=("Segoe UI", 9),
            borderwidth=0,
            padding=(10, 6),
        )
        style.map(
            "Secondary.TButton",
            background=[("active", self.BORDER)],
        )
        style.configure(
            "TCombobox",
            fieldbackground=self.BG_INPUT,
            background=self.BG_INPUT,
            foreground=self.FG,
            arrowcolor=self.ACCENT,
            borderwidth=0,
        )
        style.configure(
            "Horizontal.TProgressbar",
            troughcolor=self.BG_INPUT,
            background=self.ACCENT,
            borderwidth=0,
            thickness=8,
        )
        style.configure(
            "TRadiobutton",
            background=self.BG_FRAME,
            foreground=self.FG,
            font=("Segoe UI", 10),
            indicatorcolor=self.BG_INPUT,
        )
        style.map(
            "TRadiobutton",
            indicatorcolor=[("selected", self.ACCENT)],
        )

    # ── UI Construction ───────────────────────────────────────────────────

    def _build_ui(self):
        main = ttk.Frame(self.root, style="Main.TFrame")
        main.pack(fill=tk.BOTH, expand=True, padx=20, pady=16)

        # Header
        ttk.Label(main, text="📷  Album Downloader & Renamer", style="Header.TLabel").pack(
            anchor="w"
        )
        ttk.Label(
            main,
            text="Download photos from Google Photos shared albums and rename them by timestamp.",
            style="Sub.TLabel",
        ).pack(anchor="w", pady=(0, 12))

        # ── Download Section ──────────────────────────────────────────────
        dl_frame = ttk.Frame(main)
        dl_frame.pack(fill=tk.X, pady=(0, 10), ipady=10)

        ttk.Label(dl_frame, text="Download", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(10, 6)
        )

        # URL
        url_row = ttk.Frame(dl_frame)
        url_row.pack(fill=tk.X, padx=14, pady=2)
        ttk.Label(url_row, text="Album URL").pack(anchor="w")
        self.url_var = tk.StringVar()
        self.url_entry = tk.Entry(
            url_row,
            textvariable=self.url_var,
            font=("Segoe UI", 10),
            bg=self.BG_INPUT,
            fg=self.FG,
            insertbackground=self.ACCENT,
            relief="flat",
            highlightthickness=1,
            highlightcolor=self.ACCENT,
            highlightbackground=self.BORDER,
        )
        self.url_entry.pack(fill=tk.X, ipady=5, pady=(2, 0))

        # Output folder
        folder_row = ttk.Frame(dl_frame)
        folder_row.pack(fill=tk.X, padx=14, pady=(8, 2))
        ttk.Label(folder_row, text="Output Folder").pack(anchor="w")

        folder_inner = ttk.Frame(folder_row)
        folder_inner.pack(fill=tk.X, pady=(2, 0))

        self.folder_var = tk.StringVar(
            value=str(Path("./google_album_photos").resolve())
        )
        self.folder_entry = tk.Entry(
            folder_inner,
            textvariable=self.folder_var,
            font=("Segoe UI", 10),
            bg=self.BG_INPUT,
            fg=self.FG,
            insertbackground=self.ACCENT,
            relief="flat",
            highlightthickness=1,
            highlightcolor=self.ACCENT,
            highlightbackground=self.BORDER,
        )
        self.folder_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=5)
        ttk.Button(
            folder_inner,
            text="Browse…",
            style="Secondary.TButton",
            command=self._browse_folder,
        ).pack(side=tk.RIGHT, padx=(6, 0))

        # Delay
        delay_row = ttk.Frame(dl_frame)
        delay_row.pack(fill=tk.X, padx=14, pady=(8, 2))
        ttk.Label(delay_row, text="Delay between downloads (seconds)").pack(
            side=tk.LEFT
        )
        self.delay_var = tk.StringVar(value="0.5")
        delay_spin = tk.Spinbox(
            delay_row,
            textvariable=self.delay_var,
            from_=0.0,
            to=10.0,
            increment=0.1,
            width=6,
            font=("Segoe UI", 10),
            bg=self.BG_INPUT,
            fg=self.FG,
            insertbackground=self.ACCENT,
            relief="flat",
            highlightthickness=1,
            highlightcolor=self.ACCENT,
            highlightbackground=self.BORDER,
            buttonbackground=self.BG_INPUT,
        )
        delay_spin.pack(side=tk.RIGHT)

        # ── Rename Section ────────────────────────────────────────────────
        rn_frame = ttk.Frame(main)
        rn_frame.pack(fill=tk.X, pady=(0, 10), ipady=10)

        ttk.Label(rn_frame, text="Rename Options", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(10, 6)
        )

        # Pattern
        pat_row = ttk.Frame(rn_frame)
        pat_row.pack(fill=tk.X, padx=14, pady=2)
        ttk.Label(pat_row, text="Naming Pattern").pack(anchor="w")
        self.pattern_var = tk.StringVar(value="YYYYMMDD_HHMMSS")
        pat_combo = ttk.Combobox(
            pat_row,
            textvariable=self.pattern_var,
            values=list(RENAME_PATTERNS.keys()),
            state="readonly",
            font=("Segoe UI", 10),
        )
        pat_combo.pack(fill=tk.X, pady=(2, 0), ipady=3)

        # Timestamp source
        ts_row = ttk.Frame(rn_frame)
        ts_row.pack(fill=tk.X, padx=14, pady=(8, 2))
        ttk.Label(ts_row, text="Timestamp Source").pack(anchor="w")
        self.ts_var = tk.StringVar(value=TIMESTAMP_SOURCES[0])
        for src in TIMESTAMP_SOURCES:
            ttk.Radiobutton(ts_row, text=src, variable=self.ts_var, value=src).pack(
                anchor="w", padx=(10, 0)
            )

        # ── Action Buttons ────────────────────────────────────────────────
        btn_row = ttk.Frame(main, style="Main.TFrame")
        btn_row.pack(fill=tk.X, pady=(0, 6))

        self.download_btn = ttk.Button(
            btn_row,
            text="⬇  Download Album",
            style="Accent.TButton",
            command=self._start_download,
        )
        self.download_btn.pack(side=tk.LEFT, padx=(0, 8))

        self.preview_btn = ttk.Button(
            btn_row,
            text="👁  Preview Rename",
            style="Secondary.TButton",
            command=self._preview_rename,
        )
        self.preview_btn.pack(side=tk.LEFT, padx=(0, 8))

        self.rename_btn = ttk.Button(
            btn_row,
            text="✏  Rename Files",
            style="Secondary.TButton",
            command=self._execute_rename,
        )
        self.rename_btn.pack(side=tk.LEFT)

        # ── Progress ──────────────────────────────────────────────────────
        self.progress_var = tk.DoubleVar(value=0)
        self.progress_bar = ttk.Progressbar(
            main,
            variable=self.progress_var,
            maximum=100,
            mode="determinate",
            style="Horizontal.TProgressbar",
        )
        self.progress_bar.pack(fill=tk.X, pady=(0, 4))

        self.status_var = tk.StringVar(value="Ready")
        ttk.Label(main, textvariable=self.status_var, style="Sub.TLabel").pack(
            anchor="w", pady=(0, 6)
        )

        # ── Log Area ──────────────────────────────────────────────────────
        self.log = scrolledtext.ScrolledText(
            main,
            height=12,
            font=("Consolas", 9),
            bg=self.BG_INPUT,
            fg=self.FG,
            insertbackground=self.FG,
            relief="flat",
            highlightthickness=1,
            highlightcolor=self.BORDER,
            highlightbackground=self.BORDER,
            state="disabled",
            wrap=tk.WORD,
        )
        self.log.pack(fill=tk.BOTH, expand=True)

        # Configure log tags for coloured output
        self.log.tag_configure("success", foreground=self.GREEN)
        self.log.tag_configure("error", foreground=self.RED)
        self.log.tag_configure("warn", foreground=self.YELLOW)
        self.log.tag_configure("info", foreground=self.ACCENT)

    # ── Helpers ───────────────────────────────────────────────────────────

    def _check_dependencies(self):
        missing = []
        if requests is None:
            missing.append("requests")
        if Image is None:
            missing.append("Pillow")
        if missing:
            self._log(
                f"⚠ Missing dependencies: {', '.join(missing)}\n"
                f"  Install with: pip install {' '.join(missing)}\n",
                "warn",
            )
            if requests is None:
                self.download_btn.configure(state="disabled")

    def _log(self, text: str, tag: str = ""):
        self.log.configure(state="normal")
        if tag:
            self.log.insert(tk.END, text + "\n", tag)
        else:
            self.log.insert(tk.END, text + "\n")
        self.log.see(tk.END)
        self.log.configure(state="disabled")

    def _clear_log(self):
        self.log.configure(state="normal")
        self.log.delete("1.0", tk.END)
        self.log.configure(state="disabled")

    def _set_status(self, text: str):
        self.status_var.set(text)

    def _browse_folder(self):
        folder = filedialog.askdirectory(
            title="Select Output Folder",
            initialdir=self.folder_var.get() or ".",
        )
        if folder:
            self.folder_var.set(folder)

    def _set_buttons_state(self, downloading: bool):
        state = "disabled" if downloading else "normal"
        self.download_btn.configure(state=state)
        self.preview_btn.configure(state=state)
        self.rename_btn.configure(state=state)

    # ── Download ──────────────────────────────────────────────────────────

    def _start_download(self):
        url = self.url_var.get().strip()
        if not url:
            messagebox.showwarning("Missing URL", "Please enter a Google Photos album URL.")
            return

        folder = self.folder_var.get().strip()
        if not folder:
            messagebox.showwarning("Missing Folder", "Please choose an output folder.")
            return

        try:
            delay = float(self.delay_var.get())
        except ValueError:
            delay = 0.5

        self._downloading = True
        self._downloaded_files.clear()
        self._set_buttons_state(True)
        self._clear_log()
        self.progress_var.set(0)

        thread = threading.Thread(
            target=self._download_thread,
            args=(url, Path(folder), delay),
            daemon=True,
        )
        thread.start()

    def _download_thread(self, album_url: str, output_path: Path, delay: float):
        try:
            self.root.after(0, self._set_status, "Fetching album page…")
            self.root.after(0, self._log, f"Fetching album: {album_url}", "info")

            photos = extract_photo_urls(album_url)

            if not photos:
                self.root.after(0, self._log, "❌ No photos found in album.", "error")
                self.root.after(
                    0,
                    self._log,
                    "  Possible reasons:\n"
                    "  • Invalid or expired URL\n"
                    "  • Private album (not shared via link)\n"
                    "  • Google changed their page format",
                    "warn",
                )
                self.root.after(0, self._set_status, "No photos found")
                self.root.after(0, self._set_buttons_state, False)
                return

            total = len(photos)
            self.root.after(
                0, self._log, f"📷 Found {total} photo(s). Downloading…\n", "info"
            )

            output_path.mkdir(parents=True, exist_ok=True)
            success_count = 0
            fail_count = 0

            for i, photo in enumerate(photos, 1):
                self.root.after(
                    0, self._set_status, f"Downloading {i}/{total}…"
                )
                ok, msg, filepath = download_single_photo(
                    photo["url"], output_path, i
                )
                if ok:
                    success_count += 1
                    self._downloaded_files.append(filepath)
                    self.root.after(0, self._log, f"  [{i}/{total}] {msg}", "success")
                else:
                    fail_count += 1
                    self.root.after(0, self._log, f"  [{i}/{total}] {msg}", "error")

                progress = (i / total) * 100
                self.root.after(0, self.progress_var.set, progress)

                if i < total:
                    time.sleep(delay)

            # Summary
            self.root.after(0, self._log, f"\n{'═' * 50}", "")
            self.root.after(
                0,
                self._log,
                f"✅ Downloaded: {success_count}/{total}",
                "success",
            )
            if fail_count:
                self.root.after(
                    0, self._log, f"❌ Failed: {fail_count}/{total}", "error"
                )
            self.root.after(
                0, self._log, f"📁 Saved to: {output_path.resolve()}", "info"
            )
            self.root.after(0, self._set_status, f"Done — {success_count} downloaded")

        except requests.RequestException as e:
            self.root.after(0, self._log, f"❌ Network error: {e}", "error")
            self.root.after(0, self._set_status, "Error")

        except Exception as e:
            self.root.after(0, self._log, f"❌ Unexpected error: {e}", "error")
            self.root.after(0, self._set_status, "Error")

        finally:
            self._downloading = False
            self.root.after(0, self._set_buttons_state, False)

    # ── Rename ────────────────────────────────────────────────────────────

    def _get_rename_settings(self) -> tuple[Path, str, bool] | None:
        folder = self.folder_var.get().strip()
        if not folder or not Path(folder).is_dir():
            messagebox.showwarning(
                "Invalid Folder",
                "The output folder does not exist. Download photos first or select a valid folder.",
            )
            return None

        pattern_key = self.pattern_var.get()
        pattern_fmt = RENAME_PATTERNS.get(pattern_key, "%Y%m%d_%H%M%S")

        use_exif = self.ts_var.get() == TIMESTAMP_SOURCES[0]

        if use_exif and Image is None:
            self._log(
                "⚠ Pillow not installed — falling back to filename timestamps.\n"
                "  Install with: pip install Pillow",
                "warn",
            )
            use_exif = False

        return Path(folder), pattern_fmt, use_exif

    def _preview_rename(self):
        settings = self._get_rename_settings()
        if settings is None:
            return

        folder, pattern_fmt, use_exif = settings

        self._clear_log()
        self._log("👁  Rename Preview (dry run — no files changed)\n", "info")

        plan = compute_rename_plan(folder, pattern_fmt, use_exif)

        if not plan:
            self._log("No image/video files found in the folder.", "warn")
            return

        for old_path, new_path, msg in plan:
            if "skipping" in msg.lower():
                self._log(f"  {old_path.name}  {msg}", "warn")
            elif old_path == new_path:
                self._log(f"  {old_path.name}  {msg}", "")
            else:
                self._log(f"  {old_path.name}  {msg}", "success")

        renames = sum(1 for o, n, _ in plan if o != n and "skipping" not in _.lower())
        self._log(f"\n{renames} file(s) would be renamed.", "info")
        self._set_status(f"Preview — {renames} rename(s) pending")

    def _execute_rename(self):
        settings = self._get_rename_settings()
        if settings is None:
            return

        folder, pattern_fmt, use_exif = settings

        plan = compute_rename_plan(folder, pattern_fmt, use_exif)
        renames = sum(1 for o, n, _ in plan if o != n and "skipping" not in _.lower())

        if renames == 0:
            messagebox.showinfo("Nothing to rename", "No files need renaming.")
            return

        if not messagebox.askyesno(
            "Confirm Rename",
            f"Rename {renames} file(s) in:\n{folder}\n\nThis cannot be undone.",
        ):
            return

        self._clear_log()
        self._log("✏  Renaming files…\n", "info")

        renamed, skipped = execute_rename(plan)

        for old_path, new_path, msg in plan:
            if old_path != new_path and "skipping" not in msg.lower():
                self._log(f"  {old_path.name}  {msg}", "success")
            elif "skipping" in msg.lower():
                self._log(f"  {old_path.name}  {msg}", "warn")

        self._log(f"\n{'═' * 50}", "")
        self._log(f"✅ Renamed: {renamed}", "success")
        if skipped:
            self._log(f"⏭  Skipped: {skipped}", "warn")
        self._set_status(f"Done — {renamed} file(s) renamed")


# ─── Entry Point ──────────────────────────────────────────────────────────────


def main():
    root = tk.Tk()

    # Set DPI awareness on Windows
    try:
        from ctypes import windll
        windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    app = AlbumDownloaderApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
