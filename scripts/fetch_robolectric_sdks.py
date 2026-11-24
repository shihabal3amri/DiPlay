#!/usr/bin/env python3
"""Put the Android jars that Robolectric needs into the local Maven repository before the tests run.

Robolectric makes one attempt per file, so one HTTP 429 from Maven Central fails a test class. A jar
that is already in the local repository is used as is. Robolectric does not check it, so this script
does, against gradle/robolectric-sdks.sha512.
"""
import argparse
import hashlib
import http.client
import os
from pathlib import Path
import random
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'gradle' / 'robolectric-sdks.sha512'
# The second host is Google's mirror of Maven Central. It has its own rate limit.
HOSTS = [
    'https://repo1.maven.org/maven2',
    'https://maven-central.storage-download.googleapis.com/maven2',
]
ROUNDS = 6
MAX_DELAY_SECONDS = 60
SOCKET_TIMEOUT_SECONDS = 60
CHUNK = 1 << 20


def read_manifest(path=MANIFEST):
    """Return {repository path: SHA-512} from a file in sha512sum format."""
    entries = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.startswith('#'):
            digest, name = line.split()
            entries[name] = digest
    return entries


def sha512(path):
    digest = hashlib.sha512()
    with path.open('rb') as file:
        while chunk := file.read(CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, target, expected):
    """Download to a temporary file, and move it into place only when the checksum is right."""
    part = target.with_name(target.name + '.part')
    digest = hashlib.sha512()
    try:
        with urllib.request.urlopen(url, timeout=SOCKET_TIMEOUT_SECONDS) as response, part.open('wb') as file:
            while chunk := response.read(CHUNK):
                digest.update(chunk)
                file.write(chunk)
        if digest.hexdigest() != expected:
            raise OSError('checksum does not match')
        os.replace(part, target)
    finally:
        part.unlink(missing_ok=True)


def retry_after(error):
    value = error.headers.get('Retry-After', '') if isinstance(error, urllib.error.HTTPError) else ''
    return int(value) if value.isdigit() else 0


def fetch(name, expected, repository):
    target = repository / name
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(ROUNDS):
        wait = 0
        for host in HOSTS:
            try:
                download(f'{host}/{name}', target, expected)
                print(f'Downloaded {target.name} from {host}', flush=True)
                return
            except (OSError, http.client.HTTPException) as error:
                wait = max(wait, retry_after(error))
                print(f'{host}: {target.name}: {error}', flush=True)
        if attempt < ROUNDS - 1:
            delay = min(MAX_DELAY_SECONDS, max(wait, 5 * 2 ** attempt)) + random.uniform(0, 2)
            print(f'Retrying {target.name} in {delay:.0f} s', flush=True)
            time.sleep(delay)
    raise SystemExit(f'Could not download {target.name} from any host.')


def fetch_all(entries, repository):
    ready = 0
    for name, expected in entries.items():
        target = repository / name
        if target.is_file() and sha512(target) == expected:
            ready += 1
        else:
            fetch(name, expected, repository)
    print(f'{len(entries)} Robolectric jars are in {repository} ({ready} were already there).')


def report_unlisted(entries, repository):
    """Warn about jars that Robolectric had to download itself, with the line that the list needs."""
    for jar in sorted((repository / 'org' / 'robolectric').glob('android-all*/*/*.jar')):
        name = jar.relative_to(repository).as_posix()
        if name not in entries:
            print(f'::warning file=gradle/{MANIFEST.name}::Robolectric downloaded {jar.name} during the tests. '
                  f'Add this line to gradle/{MANIFEST.name}: {sha512(jar)}  {name}')


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2' / 'repository')
    parser.add_argument('--report-unlisted', action='store_true',
                        help='after the tests: warn about jars that are not in the list')
    options = parser.parse_args(arguments)
    entries = read_manifest()
    (report_unlisted if options.report_unlisted else fetch_all)(entries, options.repository)


if __name__ == '__main__':
    sys.exit(main())
