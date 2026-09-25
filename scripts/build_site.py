#!/usr/bin/env python3
"""Build the public status pages. No APK download URL is published."""
from pathlib import Path
from html import escape as e
import json
ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
BASE = 'https://shihabal3amri.github.io/DiPlay/'
REPO = 'https://github.com/shihabal3amri/DiPlay'
data = json.loads((SITE / 'content.json').read_text())
for lang, d in data.items():
    folder = SITE if lang == 'en' else SITE / lang
    folder.mkdir(exist_ok=True)
    prefix = './' if lang == 'en' else '../'
    url = BASE + ('' if lang == 'en' else lang + '/')
    nav = ''.join(f'<a href="{prefix}{"" if code == "en" else code + "/"}" lang="{code}" hreflang="{code}" dir="auto"'+(' aria-current="page"' if code == lang else '')+f'>{e(v["name"])}</a>' for code,v in data.items())
    (folder/'index.html').write_text(f'''<!doctype html>
<html lang="{lang}" dir="{d['dir']}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiPlay · {e(d['badge'])}</title><meta name="description" content="{e(d['body'])}"><meta name="theme-color" content="#0c121c">
<link rel="icon" href="{prefix}assets/icon.png"><link rel="stylesheet" href="{prefix}assets/site.css"><link rel="canonical" href="{url}">
</head><body><main><header><a class="brand" href="{prefix}"><img src="{prefix}assets/icon.png" width="56" height="56" alt=""><strong>DiPlay</strong></a><nav class="languages" aria-label="Language">{nav}</nav></header>
<section class="hero"><span class="badge">{e(d['badge'])}</span><h1>{e(d['title']).replace(chr(10),'<br>')}</h1><p class="intro">{e(d['body'])}</p><p class="note">{e(d['detail'])}</p><div class="actions"><a class="button secondary" href="{REPO}/blob/main/SECURITY.md">{e(d['security'])}</a><a class="button secondary" href="{REPO}">{e(d['source'])}</a></div></section>
<footer><p>{e(d['footer'])}</p></footer></main></body></html>''')
print('Generated', len(data), 'status pages')
