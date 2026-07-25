#!/usr/bin/env python3
"""
Discogs music video list generator.

Searches the Discogs database for music releases (one year at a time for
coverage), fetches release detail pages to find YouTube video links, and
writes the results into ``app/src/main/assets/music_videos_discogs.txt``.

Format: ``{videoId},{name},{year}``

Where ``videoId`` comes from the Discogs ``videos[].uri`` field, parsed
to extract the YouTube watch ID.
"""

import os
import re
import time
import requests

API_BASE = "https://api.discogs.com"
SEARCH_ENDPOINT = f"{API_BASE}/database/search"
ASSET_FILE = "app/src/main/assets/music_videos_discogs.txt"

USER_AGENT = os.getenv("USER_AGENT", "GuessTheSongYearDiscogsSync/1.0")

YEARS = list(range(2025, 1960, -1))
BATCH_SIZE = 3
SLEEP_BETWEEN_YEARS = 3.0  # search + 3 detail pages = 4 req per year

YOUTUBE_RE = re.compile(
    r"(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/)([a-zA-Z0-9_-]{11})"
)


def extract_youtube_id(uri: str) -> str | None:
    m = YOUTUBE_RE.search(uri)
    return m.group(1) if m else None


def parse_artist_title(title_raw: str) -> tuple[str, str]:
    if " - " in title_raw:
        parts = title_raw.split(" - ", 1)
        return parts[0].strip(), parts[1].strip()
    return "Various Artists", title_raw.strip()


def get_release_videos(release_id: str) -> list[str]:
    """Fetch a release detail page and extract YouTube video IDs."""
    try:
        resp = requests.get(
            f"{API_BASE}/releases/{release_id}",
            headers={"User-Agent": USER_AGENT},
            timeout=20,
        )
        if resp.status_code != 200:
            return []
        data = resp.json()
        video_ids = []
        for v in data.get("videos", []):
            uri = v.get("uri", "")
            vid = extract_youtube_id(uri)
            if vid and vid not in video_ids:
                video_ids.append(vid)
        return video_ids
    except Exception:
        return []


def fetch_releases(limit: int = 200) -> list[dict]:
    releases = []
    seen_ids = set()

    for year in YEARS:
        if len(releases) >= limit:
            break

        params = {
            "q": "music",
            "type": "release",
            "year": str(year),
            "per_page": BATCH_SIZE,
            "page": 1,
        }
        try:
            resp = requests.get(
                SEARCH_ENDPOINT,
                params=params,
                headers={"User-Agent": USER_AGENT},
                timeout=30,
            )
        except requests.RequestException as e:
            print(f"[ERR] {year} — {e}")
            time.sleep(SLEEP_BETWEEN_YEARS)
            continue

        if resp.status_code == 429:
            print(f"[RATE-LIMITED] Waiting 30 s...")
            time.sleep(30)
            resp = requests.get(
                SEARCH_ENDPOINT,
                params=params,
                headers={"User-Agent": USER_AGENT},
                timeout=30,
            )

        if resp.status_code != 200:
            print(f"[SKIP] {year} — HTTP {resp.status_code}")
            time.sleep(SLEEP_BETWEEN_YEARS)
            continue

        data = resp.json()
        results = data.get("results", [])

        for r in results:
            if len(releases) >= limit:
                break

            rid_raw = r.get("id")
            if not rid_raw:
                continue
            rid = int(rid_raw)
            rid_str = str(rid)
            if rid in seen_ids:
                continue
            seen_ids.add(rid)

            title_raw = (r.get("title") or "").strip()
            artist, title = parse_artist_title(title_raw)

            if title.lower() in ("music", ""):
                continue

            name_key = f"{artist} - {title}".replace(",", ";")[:60]

            # Fetch release detail to get YouTube video IDs
            video_ids = get_release_videos(rid_str)
            video_id = video_ids[0] if video_ids else rid_str

            releases.append({
                "id": video_id,
                "name_key": name_key,
                "year": int(r["year"]),
            })

        print(f"[YEAR] {year}: +{len(results)} → total={len(releases)}")
        time.sleep(SLEEP_BETWEEN_YEARS)

    return releases


def write_assets(releases: list[dict]):
    os.makedirs(os.path.dirname(ASSET_FILE), exist_ok=True)
    with open(ASSET_FILE, "w", encoding="utf-8") as fh:
        for rel in releases:
            fh.write(f"{rel['id']},{rel['name_key']},{rel['year']}\n")


if __name__ == "__main__":
    consumer_key = os.getenv("DISCOGS_CONSUMER_KEY")
    consumer_secret = os.getenv("DISCOGS_CONSUMER_SECRET")
    authenticated = bool(consumer_key and consumer_secret)

    if authenticated:
        print("[INFO] Discogs secrets found — higher rate limits available.")
    else:
        print("[WARN] Discogs secrets missing — using public endpoint (60 req/min).")

    releases = fetch_releases(limit=200)
    write_assets(releases)
    youtube_count = sum(1 for r in releases if extract_youtube_id(r["id"]))
    print(
        f"[DONE] Written {len(releases)} entries "
        f"({youtube_count} with real YouTube IDs) to {ASSET_FILE}"
    )