#!/usr/bin/env python3
"""Offline regression gate for curated open-source reference URLs and reuse rules."""
from pathlib import Path
import re
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
doc = (root / 'docs/THIRD_PARTY_REFERENCES.md').read_text(encoding='utf-8')
urls = re.findall(r'\]\((https?://[^)]+)\)', doc)
assert len(urls) >= 5, 'Reference list unexpectedly empty'
assert len(urls) == len(set(urls)), 'Duplicate reference URL'
for url in urls:
    parsed = urlsplit(url)
    assert parsed.scheme == 'https' and parsed.hostname, f'Unsafe reference: {url}'
    assert not parsed.username and not parsed.password, 'Embedded credentials in reference URL'
for expected in ('google/jetpack-camera-app', 'android/nowinandroid',
                 'android/architecture-components-samples', 'VKCOM/vkid-android-sdk'):
    assert expected in doc, f'Missing reviewed source: {expected}'
assert 'Pin the exact commit or release' in doc
assert 'Read the license file' in doc
print(f'OK: {len(urls)} unique HTTPS references; reuse policy present')
