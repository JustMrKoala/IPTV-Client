#!/usr/bin/env python3
import calendar
import functools
import gzip
import hmac
import io
import json
import os
import queue
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import unicodedata
import xml.etree.ElementTree as ET
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import apps

WEB = apps.WEB
WINDOWS = os.name == 'nt'
# Lounge.exe carries its own mpv; everywhere else mpv comes from the system
BUNDLED_MPV = os.path.join(apps.ROOT, 'mpv', 'mpv.exe')
MPV = BUNDLED_MPV if os.path.isfile(BUNDLED_MPV) else shutil.which('mpv')
# Printed lines show up at once, also when the output goes to a file or a service log. pythonw has no output at all.
if sys.stdout:
    sys.stdout.reconfigure(line_buffering=True)
CONFIG = os.path.join(os.environ['APPDATA'], 'tvhub') if WINDOWS else os.path.expanduser('~/.config/tvhub')
os.makedirs(CONFIG, mode=0o700, exist_ok=True)
STATE_FILE = os.path.join(CONFIG, 'state.json')
CACHE_DIR = os.path.join(CONFIG, 'cache')
os.makedirs(CACHE_DIR, mode=0o700, exist_ok=True)
INPUT_CONF = os.path.join(CONFIG, 'mpv-input.conf')
# mpv control sockets go in the private runtime dir, since socket paths are limited to 108 bytes
RUNTIME = os.environ.get('XDG_RUNTIME_DIR') or CONFIG
PORT = 8775
REMOTE_PORT = 8776
LOCK = threading.Lock()
PLAY_LOCK = threading.RLock()
DATA, DATA_LOCKS = {}, {}
PLAYER, REMOTE, SUBS = {'proc': None, 'url': '', 'sock': '', 'n': 0, 'started': False}, {'url': ''}, []
# Saved data is shown at once and refreshed in the background once it is older than these (seconds)
LIBRARY_TTL = EPG_TTL = 6 * 3600
ART_TTL, MISS_TTL, SERIES_TTL, WALL_TTL = 30 * 86400, 86400, 86400, 86400
VOLUME = {'volup': ('set-volume', '-l', '1.0', '@DEFAULT_AUDIO_SINK@', '5%+'), 'voldown': ('set-volume', '@DEFAULT_AUDIO_SINK@', '5%-'),
          'mute': ('set-mute', '@DEFAULT_AUDIO_SINK@', 'toggle')}
DIGITS = tuple('0123456789')

PREFIX = re.compile(r'^\s*(?:[\[(|]+\s*[A-Z]{2,4}\s*[|\])]+|[A-Z]{2,4}\s*\|)\s*')
TAGS = re.compile(r'[\[({][^\])}]*[\])}]')
JUNK = re.compile(r'\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|x26[45]|\d{3,4}p|MULTI|RAW|VOSTFR)\b', re.I)
SYMBOLS = re.compile(r"[^\w\s&'.:,!?+-]")
DOTS = re.compile(r'(?<=\w)\.(?=\w\w)')
EPISODE = re.compile(r'^(.*?)[\s._-]*S(\d{1,2})\s*E(\d{1,3})', re.I)
# Language and country codes that providers put in front of titles, such as "NL | ", "[NL]", "NL - " or "NL: "
LANGS = {'NL', 'BE', 'VL', 'EN', 'UK', 'GB', 'US', 'USA', 'CA', 'AU', 'IE', 'DE', 'AT', 'CH', 'FR', 'ES', 'LAT', 'IT', 'PT', 'BR',
         'PL', 'TR', 'AR', 'ARA', 'RU', 'UA', 'SE', 'SW', 'NO', 'DK', 'FI', 'IS', 'GR', 'RO', 'HU', 'CZ', 'SK', 'HR', 'RS', 'SI', 'BA',
         'MK', 'BG', 'AL', 'EXYU', 'IN', 'PK', 'IR', 'KU', 'KR', 'JP', 'CN', 'PH', 'TH', 'VN', 'ID', 'IL', 'MA', 'EG', 'ZA', 'MX', 'AF'}
LANG = re.compile(r'^\s*(?:[\[(|]+\s*([A-Z]{2,4})\s*[|\])]+|([A-Z]{2,4})\s*(?:[|:]|[-\u2013](?=\s)))\s*')
BARE_LANG = re.compile(r'^([A-Z]{2,4}) (?=\S)')
# A year after the title: "Title - 2009", "Title (2009)" or "Title [2009]", but not "Blade Runner 2049" or "1881 - 1919"
TITLE_YEAR = re.compile(r'^(.*?\S)(?<!\d{4})\s*(?:-\s*|[(\[])((?:19|20)\d\d)[)\]]?\s*$')

HEADERS = {
    'Content-Security-Policy': "default-src 'self'; img-src * data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
}


def clean(text):
    s = unicodedata.normalize('NFKC', text or '')
    s = PREFIX.sub('', s)
    s = TAGS.sub(' ', s)
    s = JUNK.sub(' ', s)
    s = SYMBOLS.sub(' ', s).replace('_', ' ')
    s = DOTS.sub(' ', s)
    return re.sub(r'\s+', ' ', s).strip(' -.:')


def split_lang(text):
    """Takes the language code off the front of a title and returns it separately, so it can still be filtered on."""
    s = unicodedata.normalize('NFKC', text or '')
    found = LANG.match(s)
    code = found and (found.group(1) or found.group(2))
    if code in LANGS:
        return code, s[found.end():]
    return '', s


def bare_langs(items):
    """Some providers write "NL Title" with only a space. That is only treated as a language code when a good share
    of the list does it, so a film like "IT Chapter Two" keeps its name in a list that does not."""
    hits = [(i, m.group(1)) for i in items if not i.get('lang') and (m := BARE_LANG.match(i['name'])) and m.group(1) in LANGS]
    if len(hits) >= max(3, len(items) // 5):
        for i, code in hits:
            i['lang'], i['name'] = code, i['name'][len(code):].strip()
    return items


def fetch(url, timeout=40):
    with urlopen(Request(url, headers={'User-Agent': 'Lounge/0.1'}), timeout=timeout) as r:
        return r.read().decode('utf-8', 'replace')


def load_state():
    try:
        with open(STATE_FILE, encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {'sources': [], 'fav': {}, 'progress': {}, 'tmdb': ''}


STATE = load_state()


def write_text(path, text):
    tmp = f'{path}.{threading.get_ident()}.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def save_state():
    with LOCK:
        write_text(STATE_FILE, json.dumps(STATE))


def public_state():
    sources = [{'id': s['id'], 'name': s['name'], 'type': s['type']} for s in STATE['sources']]
    return {'sources': sources, 'fav': STATE['fav'], 'progress': STATE['progress'], 'tmdb': bool(STATE['tmdb']), 'remote': REMOTE['url'],
            'playing': playing(), 'recent': STATE.get('recent', []), 'lang': STATE.get('lang', '')}


class Store:
    """Small lookups saved to one JSON file. Writes are batched so a burst of lookups saves once."""

    def __init__(self, name):
        self.path = os.path.join(CACHE_DIR, name + '.json')
        self.lock = threading.Lock()
        self.timer = None
        try:
            with open(self.path, encoding='utf-8') as f:
                self.data = json.load(f)
        except Exception:
            self.data = {}

    def get(self, key, ttl):
        hit = self.data.get(key)
        return hit if hit and time.time() - hit['t'] < ttl else None

    def put(self, key, value):
        with self.lock:
            self.data[key] = {'t': time.time(), 'v': value}
            if not self.timer:
                self.timer = threading.Timer(15, self.flush)
                self.timer.daemon = True
                self.timer.start()

    def clear(self):
        with self.lock:
            self.data = {}
        self.flush()

    def flush(self):
        with self.lock:
            self.timer = None
            # Old entries are dropped so the file does not grow forever
            now = time.time()
            self.data = {k: v for k, v in self.data.items() if now - v['t'] < 2 * ART_TTL}
            text = json.dumps(self.data)
        write_text(self.path, text)


ART_STORE, SERIES_STORE = Store('art'), Store('series')


# How far each source's library is, for Settings and the loading screens: a big provider takes minutes
PROGRESS = {}


def step(sid, name, n=0):
    PROGRESS[sid] = {'state': 'loading', 'step': name, 'n': n}


def ready(sid, value):
    PROGRESS[sid] = {'state': 'ready', 'counts': {k: len(value[k]) for k in ('live', 'movies', 'series')}}


def failure(e):
    """A reason the viewer can act on. Exception messages are not shown, since they can hold the login."""
    reason = getattr(e, 'reason', e)
    if isinstance(e, HTTPError):
        return f'http:{e.code}'
    if isinstance(reason, socket.gaierror):
        return 'notfound'
    if isinstance(reason, TimeoutError) or 'timed out' in str(reason):
        return 'timeout'
    if isinstance(reason, ConnectionRefusedError):
        return 'refused'
    if isinstance(e, (ValueError, KeyError, TypeError)):
        return 'badreply'
    return 'other'


def disk_path(kind, sid):
    return os.path.join(CACHE_DIR, f'{kind}-{sid}.json')


def compact(kind, value):
    """Category and language names repeat across tens of thousands of titles; one shared copy of each saves memory."""
    if kind == 'library':
        for items in (value['live'], value['movies'], value['series']):
            for i in items:
                i['group'], i['lang'] = sys.intern(i['group']), sys.intern(i['lang'])
    return value


def keep(kind, sid, value):
    if kind == 'library':
        step(sid, 'saving')
    hit = DATA[(kind, sid)] = (time.time(), compact(kind, value))
    write_text(disk_path(kind, sid), json.dumps({'t': hit[0], 'v': value}, ensure_ascii=False, separators=(',', ':')))
    if kind == 'library':
        ready(sid, value)
    return hit


def read_disk(kind, sid):
    try:
        with open(disk_path(kind, sid), encoding='utf-8') as f:
            saved = json.load(f)
    except Exception:
        return None
    hit = DATA[(kind, sid)] = (saved['t'], compact(kind, saved['v']))
    if kind == 'library':
        ready(sid, hit[1])
    return hit


def refresh(kind, sid, loader):
    lock = DATA_LOCKS.setdefault((kind, sid), threading.Lock())
    if not lock.acquire(blocking=False):
        return
    try:
        keep(kind, sid, loader(sid))
        broadcast(kind)
    except Exception as e:
        print(kind, 'refresh failed', type(e).__name__, file=sys.stderr)
    finally:
        lock.release()


def cached(kind, sid, ttl, loader, force=False, wait=True):
    """Returns the saved copy at once and refreshes it in the background once it is older than ttl.
    With nothing saved it downloads and waits, or with wait=False starts the download and returns None."""
    key = (kind, sid)
    hit = None if force else DATA.get(key) or read_disk(kind, sid)
    if hit is None:
        if not wait:
            threading.Thread(target=refresh, args=(kind, sid, loader), daemon=True).start()
            return None
        with DATA_LOCKS.setdefault(key, threading.Lock()):
            hit = None if force else DATA.get(key)
            if hit is None:
                hit = keep(kind, sid, loader(sid))
        return hit[1]
    if time.time() - hit[0] > ttl:
        threading.Thread(target=refresh, args=(kind, sid, loader), daemon=True).start()
    return hit[1]


def forget(sid):
    PROGRESS.pop(sid, None)
    for kind in ('library', 'epg'):
        DATA.pop((kind, sid), None)
        try:
            os.remove(disk_path(kind, sid))
        except OSError:
            pass


def source(sid):
    return next(s for s in STATE['sources'] if s['id'] == sid)


def item(ident, name, logo, group, url, **extra):
    lang, name = split_lang(name)
    group_lang, group = split_lang(group)
    return {'id': str(ident), 'name': clean(name), 'lang': lang or group_lang, 'logo': logo or '', 'group': clean(group) or 'Other',
            'url': url, **extra}


def years(items):
    """Moves a year written after a title into its own field, so "Heat - 1995" shows as Heat and 1995 becomes a filter."""
    for i in items:
        found = TITLE_YEAR.match(i['name'])
        if found:
            i['name'], i['year'] = found.group(1), int(found.group(2))
    return items


def build_m3u(text):
    live, movies, series = [], [], {}
    meta, guide = None, ''
    for n, line in enumerate(text.splitlines()):
        line = line.strip()
        if line.startswith('#EXTM3U'):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            guide = (attrs.get('url-tvg') or attrs.get('x-tvg-url') or '').split(',')[0].strip()
        elif line.startswith('#EXTINF'):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            meta = (line.rsplit(',', 1)[-1], attrs.get('tvg-logo', ''), attrs.get('group-title', ''), attrs)
        elif line and not line.startswith('#') and meta:
            entry = item(n, meta[0], meta[1], meta[2], line)
            found = EPISODE.match(entry['name'])
            if found and clean(found.group(1)):
                title = clean(found.group(1))
                show = series.setdefault(title, {'id': 'm3u:' + title, 'name': title, 'lang': entry['lang'], 'logo': entry['logo'],
                                                 'group': entry['group'], 'seasons': {}})
                show['seasons'].setdefault(str(int(found.group(2))), []).append({**entry, 'ep': int(found.group(3))})
            elif '/movie/' in line:
                movies.append(entry)
            else:
                chno = meta[3].get('tvg-chno', '')
                live.append({**entry, 'num': int(chno) if chno.isdigit() else len(live) + 1, 'epg': meta[3].get('tvg-id', '')})
            meta = None
    return {'live': bare_langs(live), 'movies': years(bare_langs(movies)), 'series': years(bare_langs(list(series.values()))),
            'epg_url': guide if re.match(r'https?://', guide) else ''}


def xtream(src, action, **kw):
    extra = ''.join(f'&{k}={quote(str(v))}' for k, v in kw.items())
    url = f"{src['url'].rstrip('/')}/player_api.php?username={quote(src['user'])}&password={quote(src['pass'])}&action={action}{extra}"
    return json.loads(fetch(url, 120))


def build_xtream(src, progress=lambda name, n=0: None):
    base, u, p = src['url'].rstrip('/'), quote(src['user']), quote(src['pass'])

    def categories(action):
        return {str(c['category_id']): c['category_name'] for c in xtream(src, action) or []}

    def listing(kind, action):
        found = xtream(src, action) or []
        progress(kind, len(found))
        return found

    progress('categories')
    live_cats, vod_cats, series_cats = categories('get_live_categories'), categories('get_vod_categories'), categories('get_series_categories')
    live = [item(c['stream_id'], c['name'], c.get('stream_icon'), live_cats.get(str(c.get('category_id')), ''),
                 f"{base}/live/{u}/{p}/{c['stream_id']}.ts", num=int(c.get('num') or n + 1), epg=c.get('epg_channel_id') or '')
            for n, c in enumerate(listing('live', 'get_live_streams'))]
    # The TMDB id, when the server has one, finds the exact film for posters and translated plots
    movies = [item(c['stream_id'], c['name'], c.get('stream_icon'), vod_cats.get(str(c.get('category_id')), ''),
                   f"{base}/movie/{u}/{p}/{c['stream_id']}.{c.get('container_extension') or 'mp4'}",
                   **({'tmdb': str(c['tmdb'])} if str(c.get('tmdb') or '').isdigit() else {})) for c in listing('movies', 'get_vod_streams')]
    series = [{k: v for k, v in item(c['series_id'], c['name'], c.get('cover'), series_cats.get(str(c.get('category_id')), ''), '',
                                      plot=c.get('plot') or '').items() if k != 'url'} for c in listing('series', 'get_series')]
    return {'live': bare_langs(live), 'movies': years(bare_langs(movies)), 'series': years(bare_langs(series))}


def series_info(sid, series_id):
    key = f'{sid}|{series_id}'
    hit = SERIES_STORE.get(key, SERIES_TTL)
    if hit:
        return hit['v']
    result = load_series(sid, series_id)
    SERIES_STORE.put(key, result)
    return result


def load_series(sid, series_id):
    src = source(sid)
    base, u, p = src['url'].rstrip('/'), quote(src['user']), quote(src['pass'])
    data = xtream(src, 'get_series_info', series_id=series_id)
    episodes = data.get('episodes') or {}
    if isinstance(episodes, list):
        episodes = {str(i + 1): v for i, v in enumerate(episodes)}
    seasons = {}
    for number, eps in episodes.items():
        seasons[str(number)] = [{
            'id': str(e['id']),
            'ep': int(e.get('episode_num') or 0),
            'name': clean(e.get('title') or '') or f"Episode {e.get('episode_num')}",
            'logo': (e.get('info') or {}).get('movie_image') or '',
            'url': f"{base}/series/{u}/{p}/{e['id']}.{e.get('container_extension') or 'mkv'}",
        } for e in eps]
    return {'seasons': seasons, 'plot': (data.get('info') or {}).get('plot') or ''}


def load_library(sid):
    src = source(sid)
    step(sid, 'connecting')
    try:
        if src['type'] == 'xtream':
            return build_xtream(src, lambda name, n=0: step(sid, name, n))
        text = fetch(src['url'], 180)
        step(sid, 'playlist', text.count('#EXTINF'))
        return build_m3u(text)
    except Exception as e:
        PROGRESS[sid] = {'state': 'failed', 'error': failure(e)}
        raise


def library(sid, force=False):
    return cached('library', sid, LIBRARY_TTL, load_library, force)


# What Tuner receives per list: no stream addresses, which hold the login and are looked up on Play, and no series
# plots, which the series page loads itself. Each list is encoded and compressed once per download of the library.
LEAVE_OUT = {'live': ('url',), 'movies': ('url',), 'series': ('plot',)}
SENT = {}


def library_list(sid, kind, force=False):
    data = library(sid, force)
    hit = SENT.get((sid, kind))
    if not hit or hit[0] is not data[kind]:
        items = [{k: v for k, v in i.items() if k not in LEAVE_OUT[kind]} for i in data[kind]]
        hit = SENT[(sid, kind)] = (data[kind], gzip.compress(json.dumps(items, ensure_ascii=False, separators=(',', ':')).encode(), 5))
    return hit[1]


def epg_reply(sid):
    """The guide as Tuner receives it, encoded and compressed once per download of the guide."""
    guide = epg(sid)
    hit = SENT.get((sid, 'epg'))
    if not hit or hit[0] is not guide:
        body = json.dumps({'ready': guide is not None, 'guide': guide or {}}, ensure_ascii=False, separators=(',', ':'))
        hit = SENT[(sid, 'epg')] = (guide, gzip.compress(body.encode(), 5))
    return hit[1]


PAGE_CACHE = {}


def page(name, gzipped=False):
    """A file from web/, read once and kept, compressed or not. A changed file is read again, which helps while editing."""
    path = os.path.join(WEB, name)
    stamp = os.stat(path).st_mtime_ns
    hit = PAGE_CACHE.get(name)
    if not hit or hit[0] != stamp:
        with open(path, 'rb') as f:
            data = f.read()
        hit = PAGE_CACHE[name] = (stamp, data, gzip.compress(data, 9))
    return hit[2] if gzipped else hit[1]


def stream_url(body):
    """Episodes send their address; movies and channels are found in the library by id."""
    if body.get('url'):
        return body['url']
    if body.get('kind') not in ('live', 'movies'):
        raise ValueError('unknown kind')
    return next(i['url'] for i in library(body['src'])[body['kind']] if i['id'] == str(body['id']))


def movie_info(sid, movie_id):
    """What a movie is about, for its page before it plays. Only Xtream servers have this; M3U playlists do not."""
    src = source(sid)
    if src['type'] != 'xtream':
        return {}
    # Some servers now and then answer with empty details, so they are asked a second time
    for attempt in range(2):
        info = xtream(src, 'get_vod_info', vod_id=movie_id).get('info') or {}
        if info:
            break
    # Some servers send the backdrop as a list, or as a list written out as text
    backdrop = info.get('backdrop_path') or ''
    backdrop = re.search(r'https?://[^\s\'"\],]+', backdrop if isinstance(backdrop, str) else ' '.join(backdrop))
    date = str(info.get('releasedate') or '')
    # "01:50:00" reads as 1 h 50 min; servers that do not know the length send zeros
    h, m = (re.match(r'(\d+):(\d+)', str(info.get('duration') or '')) or re.match(r'()()', '')).groups()
    length = ' '.join(filter(None, [f'{int(h)} h' if h and int(h) else '', f'{int(m)} min' if m and int(m) else '']))
    return {'plot': info.get('plot') or info.get('description') or '', 'year': int(date[:4]) if date[:4].isdigit() else 0,
            'genre': info.get('genre') or '', 'duration': length, 'cast': info.get('cast') or info.get('actors') or '',
            'backdrop': backdrop.group(0) if backdrop else '', 'tmdb': str(info.get('tmdb_id') or '')}


def xmltv_time(text):
    # XMLTV times look like 20261020203000 +0200
    stamp = calendar.timegm(time.strptime(text[:14], '%Y%m%d%H%M%S'))
    zone = re.search(r'([+-])(\d\d)(\d\d)\s*$', text)
    if zone:
        stamp -= (1 if zone.group(1) == '+' else -1) * (int(zone.group(2)) * 3600 + int(zone.group(3)) * 60)
    return stamp


def load_epg(sid):
    src = source(sid)
    if src['type'] == 'xtream':
        url = f"{src['url'].rstrip('/')}/xmltv.php?username={quote(src['user'])}&password={quote(src['pass'])}"
    else:
        url = library(sid).get('epg_url', '')
    guide = {}
    if not url:
        return guide
    wanted = {i['epg'] for i in library(sid)['live'] if i.get('epg')}
    now = time.time()
    # Guides can be hundreds of megabytes, so they are read as a stream and only the next 12 hours are kept
    with urlopen(Request(url, headers={'User-Agent': 'Lounge/0.1'}), timeout=300) as r:
        stream = io.BufferedReader(r)
        if stream.peek(2)[:2] == b'\x1f\x8b':
            stream = gzip.GzipFile(fileobj=stream)
        for _, el in ET.iterparse(stream):
            if el.tag == 'programme' and el.get('channel') in wanted:
                try:
                    start, stop = xmltv_time(el.get('start', '')), xmltv_time(el.get('stop', ''))
                except ValueError:
                    start = stop = 0
                if stop > now and start < now + 12 * 3600:
                    guide.setdefault(el.get('channel'), []).append([start, stop, clean(el.findtext('title') or '')])
            if el.tag in ('programme', 'channel'):
                el.clear()
    for shows in guide.values():
        shows.sort()
        del shows[6:]
    return guide


def epg(sid):
    return cached('epg', sid, EPG_TTL, load_epg, wait=False)


def art(title, kind, year='', lang='', tmdb_id=''):
    """Poster, backdrop and plot. lang asks TMDB for the plot in that language; tmdb_id, which Xtream servers give for
    their movies, finds the exact film instead of searching by name."""
    key = f'{kind}|{title}|{year}|{lang}|{tmdb_id}'
    hit = ART_STORE.get(key, ART_TTL)
    if hit and (hit['v'] or time.time() - hit['t'] < MISS_TTL):
        return hit['v']
    result = {}
    try:
        if STATE['tmdb']:
            path = 'tv' if kind == 'tv' else 'movie'
            auth = f"api_key={quote(STATE['tmdb'])}&language={quote(lang or 'en')}"
            if tmdb_id.isdigit():
                hit = json.loads(fetch(f"https://api.themoviedb.org/3/{path}/{tmdb_id}?{auth}"))
            else:
                hits = json.loads(fetch(f"https://api.themoviedb.org/3/search/{path}?{auth}&query={quote(title)}"))['results']
                date = 'first_air_date' if kind == 'tv' else 'release_date'
                hit = next((h for h in hits if year and (h.get(date) or '').startswith(str(year))), None) or hits[0]
                # Search results have no genre names, so the match is looked up once more in full
                hit = json.loads(fetch(f"https://api.themoviedb.org/3/{path}/{hit['id']}?{auth}"))
            if not hit.get('overview') and lang not in ('', 'en'):
                # No translation of the plot: the English one is better than none
                english = json.loads(fetch(f"https://api.themoviedb.org/3/{path}/{hit['id']}?api_key={quote(STATE['tmdb'])}&language=en"))
                hit['overview'] = english.get('overview') or ''
            result = {
                'poster': 'https://image.tmdb.org/t/p/w500' + hit['poster_path'] if hit.get('poster_path') else '',
                'backdrop': 'https://image.tmdb.org/t/p/w1280' + hit['backdrop_path'] if hit.get('backdrop_path') else '',
                # Empty when TMDB has no translation in that language, so the page falls back to the provider's plot
                'plot': hit.get('overview') or '',
                'genre': ', '.join(g['name'] for g in hit.get('genres') or []),
            }
        elif kind == 'tv':
            hit = json.loads(fetch('https://api.tvmaze.com/singlesearch/shows?q=' + quote(title)))
            result = {'poster': (hit.get('image') or {}).get('original', ''), 'backdrop': '', 'plot': re.sub(r'<[^>]+>', '', hit.get('summary') or '')}
    except Exception as e:
        # Network trouble is not saved, so the next request tries again
        print('artwork lookup failed', type(e).__name__, file=sys.stderr)
        return result
    ART_STORE.put(key, result)
    return result


def wallpapers():
    if not STATE['tmdb']:
        return []
    hit = ART_STORE.get('wall', WALL_TTL)
    if hit:
        return hit['v']
    data = json.loads(fetch(f"https://api.themoviedb.org/3/trending/all/week?api_key={quote(STATE['tmdb'])}"))
    wall = ['https://image.tmdb.org/t/p/w1280' + r['backdrop_path'] for r in data['results'] if r.get('backdrop_path')][:12]
    ART_STORE.put('wall', wall)
    return wall


def stop_player():
    with PLAY_LOCK:
        proc = PLAYER['proc']
        if proc and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        # Clearing proc keeps the old watch thread from reporting a failed start
        PLAYER.update(proc=None, url='')


def playing():
    proc = PLAYER['proc']
    return proc is not None and proc.poll() is None


def mpv_request(sock, args, reply=True):
    if WINDOWS:
        # On Windows mpv listens on a named pipe, which opens like a file
        with open(sock, 'r+b', buffering=0) as conn:
            conn.write((json.dumps({'command': list(args)}) + '\n').encode())
            while reply:
                line = conn.readline()
                if not line:
                    return None
                if b'"error"' in line:
                    return json.loads(line).get('data')
        return None
    conn = socket.socket(socket.AF_UNIX)
    conn.settimeout(2)
    buf = b''
    try:
        conn.connect(sock)
        conn.sendall((json.dumps({'command': list(args)}) + '\n').encode())
        while reply and b'"error"' not in buf:
            chunk = conn.recv(4096)
            if not chunk:
                break
            buf += chunk
    finally:
        conn.close()
    for line in buf.split(b'\n'):
        if b'"error"' in line:
            return json.loads(line).get('data')


def mpv_get(prop, sock=None):
    return mpv_request(sock or PLAYER['sock'], ['get_property', prop])


def watch(proc, sock, key, meta):
    # Tells the TV interface when the video really starts or stops, and saves resume points
    started, ticks = False, 0
    while proc.poll() is None:
        time.sleep(1)
        ticks += 1
        try:
            pos = mpv_get('time-pos', sock)
            if pos is None:
                continue
            if not started:
                started = True
                if PLAYER['proc'] is proc:
                    PLAYER['started'] = True
                broadcast('started')
            if key and ticks % 5 == 0:
                dur = mpv_get('duration', sock)
                if pos and dur and dur > 300:
                    STATE['progress'][key] = {**meta, 'pos': pos, 'dur': dur, 'ts': time.time()}
                    save_state()
        except Exception:
            continue
    try:
        os.remove(sock)
    except OSError:
        pass
    if PLAYER['proc'] is proc:
        broadcast('stopped' if started else 'failed')


def write_input_conf():
    # Keys for a USB or keyboard remote while the mpv window has focus
    with open(INPUT_CONF, 'w') as f:
        f.write('ESC quit\nBS quit\nGO_BACK quit\nHOME quit\nENTER cycle pause\nKP_ENTER cycle pause\n'
                'LEFT seek -10\nRIGHT seek 10\nUP add volume 5\nDOWN add volume -5\n')


def play(url, key, pos, meta):
    if not re.match(r'https?://', url):
        raise ValueError('unsupported address')
    with PLAY_LOCK:
        # A second press on the same title must not start a second mpv
        if playing() and PLAYER['url'] == url:
            return False
        stop_player()
        PLAYER['n'] += 1
        name = f"tvhub-mpv-{os.getpid()}-{PLAYER['n']}"
        sock = rf'\\.\pipe\{name}' if WINDOWS else os.path.join(RUNTIME, name + '.sock')
        if not MPV:
            raise FileNotFoundError('mpv is not installed')
        cmd = [MPV, '--fullscreen', '--ontop', '--hwdec=auto-safe', '--force-window=immediate', '--ytdl=no',
               '--cache=yes', '--demuxer-lavf-analyzeduration=1', '--network-timeout=20', '--input-terminal=no',
               f'--input-conf={INPUT_CONF}', f'--input-ipc-server={sock}']
        if pos > 30:
            cmd.append(f'--start={int(pos)}')
        # Only errors reach Lounge's console; mpv's progress line would otherwise fill it several times a second
        cmd += ['--quiet', '--msg-level=all=error']
        proc = subprocess.Popen(cmd + ['--', url], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        PLAYER.update(proc=proc, url=url, sock=sock, started=False)
        threading.Thread(target=watch, args=(proc, sock, key, meta), daemon=True).start()
        return True


def add_source(body):
    if body['type'] not in ('m3u', 'xtream') or not re.match(r'https?://', body['url']):
        raise ValueError('invalid source')
    if body['type'] == 'xtream' and not (body.get('user') and body.get('pass')):
        raise ValueError('login required')
    sid = str(int(time.time() * 1000))
    STATE['sources'].append({'id': sid, 'name': body['name'][:60], 'type': body['type'], 'url': body['url'].strip(),
                             'user': body.get('user', ''), 'pass': body.get('pass', '')})
    save_state()
    return {'id': sid}


MPV_KEYS = {
    'ok': ('cycle', 'pause'), 'playpause': ('cycle', 'pause'),
    'left': ('seek', -10), 'seek_back': ('seek', -10),
    'right': ('seek', 10), 'seek_fwd': ('seek', 10),
    'up': ('add', 'volume', 5), 'volup': ('add', 'volume', 5),
    'down': ('add', 'volume', -5), 'voldown': ('add', 'volume', -5),
    'mute': ('cycle', 'mute'),
}
UI_KEYS = ('up', 'down', 'left', 'right', 'ok', 'back', 'home') + DIGITS


def mpv_cmd(*args):
    # mpv can take a moment to open its control socket right after launch
    for attempt in range(6):
        try:
            return mpv_request(PLAYER['sock'], args, reply=False)
        except OSError:
            if attempt == 5 or not playing():
                raise
            time.sleep(0.25)


def broadcast(cmd):
    for q in list(SUBS):
        q.put(cmd)


def system_volume(cmd):
    if WINDOWS:
        # Presses the keyboard's volume keys, which Windows handles itself (2% per press)
        import ctypes
        vk = {'volup': 0xAF, 'voldown': 0xAE, 'mute': 0xAD}[cmd]
        for _ in range(1 if cmd == 'mute' else 3):
            ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
            ctypes.windll.user32.keybd_event(vk, 0, 2, 0)
        return
    subprocess.run(['wpctl', *VOLUME[cmd]], timeout=3, check=False)


def run_command(cmd):
    if playing():
        if cmd in ('back', 'home', 'stop'):
            stop_player()
            broadcast('stopped')
            if cmd == 'home':
                broadcast('home')
        elif cmd in DIGITS:
            # The TV interface collects channel numbers and switches the channel
            broadcast(cmd)
        elif cmd in MPV_KEYS and PLAYER['started']:
            # Presses while the video is still opening are ignored, so a double tap cannot pause it
            mpv_cmd(*MPV_KEYS[cmd])
    elif apps.running():
        if cmd in ('home', 'stop'):
            apps.home()
        elif cmd in VOLUME:
            system_volume(cmd)
        else:
            apps.press(cmd)
    elif cmd in VOLUME:
        system_volume(cmd)
    elif cmd in UI_KEYS:
        broadcast(cmd)


def send_text(text):
    # Line breaks would split the event stream message, so control characters become spaces
    text = re.sub(r'[\x00-\x1f\x7f]', ' ', str(text))[:100]
    if playing():
        return
    if apps.running():
        apps.type_text(text)
    else:
        broadcast('text:' + text)


def remember_channel(entry):
    entry = {k: entry[k] for k in ('key', 'id', 'src', 'name', 'logo', 'url', 'group', 'num', 'epg') if k in entry}
    STATE['recent'] = [entry] + [r for r in STATE.get('recent', []) if r.get('key') != entry.get('key')][:19]
    save_state()


def dashboard():
    return {'tiles': apps.tiles(), 'remote': REMOTE['url'], 'lang': STATE.get('lang', '')}


def hide_idle_cursor():
    # KDE's Hide Cursor effect hides the pointer of an air mouse remote after a few seconds of rest, in every window
    if not shutil.which('kwriteconfig6'):
        return
    for group, key, value in (('Plugins', 'hidecursorEnabled', 'true'), ('Effect-hidecursor', 'InactivityDuration', '3')):
        subprocess.run(['kwriteconfig6', '--file', 'kwinrc', '--group', group, '--key', key, value], check=False)
    # The setting above keeps it on after the next login; this turns it on right away
    subprocess.run(['dbus-send', '--session', '--dest=org.kde.KWin', '--type=method_call', '/Effects',
                    'org.kde.kwin.Effects.loadEffect', 'string:hidecursor'], capture_output=True, check=False)


def install_autostart(enable):
    if WINDOWS:
        return install_autostart_windows(enable)
    path = os.path.expanduser('~/.config/autostart/tvhub.desktop')
    if not enable:
        if os.path.exists(path):
            os.remove(path)
        print('Lounge will no longer start when you log in.')
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    quoted = lambda p: '"' + p.replace('\\', '\\\\').replace('"', '\\"') + '"'
    # Run from the AppImage it points at itself, since the folder it unpacks to changes every time
    command = quoted(os.environ['APPIMAGE']) if os.environ.get('APPIMAGE') else 'python3 ' + quoted(os.path.abspath(__file__))
    with open(path, 'w') as f:
        f.write(f'[Desktop Entry]\nType=Application\nName=Lounge\nComment=Living room TV interface\n'
                f'Exec={command} --kiosk --remote\nX-GNOME-Autostart-enabled=true\n')
    print('Lounge will start fullscreen when you log in. Turn on automatic login in System Settings to start it at boot.')


def install_autostart_windows(enable):
    path = os.path.join(os.environ['APPDATA'], 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Startup', 'Lounge.cmd')
    if not enable:
        if os.path.exists(path):
            os.remove(path)
        print('Lounge will no longer start when you log in.')
        return
    if getattr(sys, 'frozen', False):
        # Lounge.exe, which carries its own Python
        command = f'"{sys.executable}"'
    else:
        # pythonw runs the server without a console window
        pythonw = os.path.join(os.path.dirname(sys.executable), 'pythonw.exe')
        command = f'"{pythonw if os.path.isfile(pythonw) else sys.executable}" "{os.path.abspath(__file__)}"'
    with open(path, 'w') as f:
        f.write(f'@start "" {command} --kiosk --remote\r\n')
    print('Lounge will start fullscreen when you log in.')


def lan_ip():
    conn = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        conn.connect(('10.255.255.255', 1))
        return conn.getsockname()[0]
    except OSError:
        return '127.0.0.1'
    finally:
        conn.close()


@functools.lru_cache(maxsize=4)
def icon_png(size):
    ink, gold = b'\x14\x14\x14', b'\xe5\x09\x14'
    mid, radius = size / 2, size * 0.3
    rows = b''
    for y in range(size):
        gap = radius * radius - (y + 0.5 - mid) ** 2
        half = int(gap ** 0.5) if gap > 0 else 0
        left = int(mid - half)
        rows += b'\x00' + ink * left + gold * (2 * half) + ink * (size - left - 2 * half)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    header = struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


class RemoteHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_body(self, data, ctype, code=200, extra=None):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Security-Policy', "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def target(self):
        path = urlparse(self.path).path
        token, slash, rest = path.lstrip('/').partition('/')
        if not hmac.compare_digest(token.encode(), STATE['remote_token'].encode()):
            return None
        return rest if slash else ''

    def do_GET(self):
        sub = self.target()
        if sub is None:
            return self.send_body(b'Not found', 'text/plain', 404)
        path = urlparse(self.path).path
        if sub == '' and not path.endswith('/'):
            return self.send_body(b'', 'text/plain', 302, {'Location': path + '/'})
        if sub == '':
            return self.send_body(page('remote.html'), 'text/html; charset=utf-8')
        if sub == 'manifest.json':
            manifest = {'name': 'Lounge Remote', 'short_name': 'Remote', 'start_url': './', 'scope': './', 'display': 'standalone',
                        'background_color': '#141414', 'theme_color': '#141414',
                        'icons': [{'src': f'icon-{s}.png', 'sizes': f'{s}x{s}', 'type': 'image/png'} for s in (192, 512)]}
            return self.send_body(json.dumps(manifest).encode(), 'application/manifest+json')
        found = re.fullmatch(r'icon-(\d{2,3})\.png', sub)
        if found:
            return self.send_body(icon_png(min(int(found.group(1)), 512)), 'image/png')
        self.send_body(b'Not found', 'text/plain', 404)

    def do_POST(self):
        if self.target() != 'cmd':
            return self.send_body(b'Not found', 'text/plain', 404)
        try:
            size = min(int(self.headers.get('Content-Length', 0)), 1024)
            body = json.loads(self.rfile.read(size) or b'{}')
            if body['cmd'] == 'text':
                send_text(body.get('text', ''))
            else:
                run_command(str(body['cmd']))
            self.send_body(b'{"ok":true}', 'application/json')
        except Exception as e:
            print('remote command failed', type(e).__name__, file=sys.stderr)
            self.send_body(b'{"error":"Request failed"}', 'application/json', 500)


def start_remote():
    if '--remote-reset' in sys.argv or not STATE.get('remote_token'):
        STATE['remote_token'] = secrets.token_hex(4)
        save_state()
    server = Server(('0.0.0.0', REMOTE_PORT), RemoteHandler)
    REMOTE['url'] = f"http://{lan_ip()}:{REMOTE_PORT}/{STATE['remote_token']}/"
    print('Phone remote:', REMOTE['url'])
    threading.Thread(target=server.serve_forever, daemon=True).start()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, body, code=200, ctype='application/json', gzipped=False):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        if gzipped and 'gzip' not in self.headers.get('Accept-Encoding', ''):
            data, gzipped = gzip.decompress(data), False
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        if gzipped:
            self.send_header('Content-Encoding', 'gzip')
        self.send_header('Content-Length', str(len(data)))
        for k, v in HEADERS.items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def stream(self):
        q = queue.Queue()
        SUBS.append(q)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        for k, v in HEADERS.items():
            self.send_header(k, v)
        self.end_headers()
        try:
            while True:
                try:
                    self.wfile.write(f'data: {q.get(timeout=20)}\n\n'.encode())
                except queue.Empty:
                    self.wfile.write(b': ping\n\n')
                self.wfile.flush()
        except OSError:
            pass
        finally:
            SUBS.remove(q)

    def local(self):
        return self.headers.get('Host', '').split(':')[0] in ('127.0.0.1', 'localhost')

    def do_GET(self):
        if not self.local():
            return self.reply({'error': 'Forbidden'}, 403)
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        try:
            if url.path in PAGES:
                return self.reply(page(PAGES[url.path], True), ctype='text/html; charset=utf-8', gzipped=True)
            if url.path == '/i18n.js':
                return self.reply(page('i18n.js', True), ctype='text/javascript; charset=utf-8', gzipped=True)
            if url.path == '/api/status':
                return self.reply(dict(PROGRESS))
            if url.path == '/api/events':
                return self.stream()
            if url.path == '/api/state':
                return self.reply(public_state())
            if url.path == '/api/library':
                return self.reply(library_list(q['src'], q['kind'], q.get('refresh') == '1'), gzipped=True)
            if url.path == '/api/series':
                return self.reply(series_info(q['src'], q['id']))
            if url.path == '/api/poster':
                # Cards point their picture here when the provider has none; it sends the browser on to the TMDB poster
                poster = art(q['q'], q.get('kind', 'movie'), q.get('year', ''), q.get('lang', ''), q.get('tmdb', '')).get('poster')
                if not poster:
                    return self.reply({'error': 'Not found'}, 404)
                self.send_response(302)
                self.send_header('Location', poster)
                self.send_header('Cache-Control', 'max-age=86400')
                self.end_headers()
                return
            if url.path == '/api/movie':
                return self.reply(movie_info(q['src'], q['id']))
            if url.path == '/api/art':
                return self.reply(art(q['q'], q.get('kind', 'movie'), q.get('year', ''), q.get('lang', ''), q.get('tmdb', '')))
            if url.path == '/api/wallpapers':
                return self.reply(wallpapers())
            if url.path == '/api/epg':
                return self.reply(epg_reply(q['src']), gzipped=True)
            if url.path == '/api/dashboard':
                return self.reply(dashboard())
            self.reply({'error': 'Not found'}, 404)
        except ConnectionError:
            pass
        except Exception as e:
            print('request failed', url.path, type(e).__name__, file=sys.stderr)
            self.reply({'error': 'Request failed'}, 500)

    def do_POST(self):
        if not self.local() or self.headers.get('X-TVHub') != '1':
            return self.reply({'error': 'Forbidden'}, 403)
        path = urlparse(self.path).path
        try:
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
            result = {'ok': True}
            if path == '/api/play':
                apps.close()
                result['started'] = play(stream_url(body), body.get('key', ''), float(body.get('pos', 0)), body.get('meta', {}))
                if body.get('recent'):
                    remember_channel(body['recent'])
            elif path == '/api/key':
                if body['cmd'] in UI_KEYS:
                    run_command(body['cmd'])
            elif path == '/api/stop':
                stop_player()
                broadcast('stopped')
            elif path == '/api/osd':
                if playing() and PLAYER['started']:
                    mpv_cmd('show-text', str(body.get('text', ''))[:60], 1600)
            elif path == '/api/launch':
                stop_player()
                apps.launch(str(body['id']))
            elif path == '/api/install':
                apps.install(str(body['browser']))
            elif path == '/api/close':
                apps.close()
            elif path == '/api/dashboard':
                apps.save_tiles(body['tiles'])
                result = dashboard()
            elif path == '/api/fav':
                if body.get('item'):
                    STATE['fav'][body['key']] = body['item']
                else:
                    STATE['fav'].pop(body['key'], None)
                save_state()
            elif path == '/api/source':
                result = add_source(body)
            elif path == '/api/source/delete':
                STATE['sources'] = [s for s in STATE['sources'] if s['id'] != body['id']]
                forget(body['id'])
                save_state()
            elif path == '/api/lang':
                if not re.fullmatch(r'[a-z]{2}', str(body.get('lang', ''))):
                    raise ValueError('unknown language')
                STATE['lang'] = body['lang']
                save_state()
            elif path == '/api/settings':
                STATE['tmdb'] = body.get('tmdb', '').strip()
                ART_STORE.clear()
                save_state()
            else:
                return self.reply({'error': 'Not found'}, 404)
            self.reply(result)
        except Exception as e:
            print('request failed', path, type(e).__name__, file=sys.stderr)
            self.reply({'error': 'Request failed'}, 500)


# /tvhub is the old address of Tuner, kept so bookmarks still work
PAGES = {'/': 'dashboard.html', '/tuner': 'index.html', '/tvhub': 'index.html'}


class Server(ThreadingHTTPServer):
    # On Windows the address reuse that Python turns on lets a second Lounge listen on the same port without an error,
    # and requests then go to either of them. There the port is taken exclusively instead.
    allow_reuse_address = not WINDOWS
    daemon_threads = True

    def server_bind(self):
        if WINDOWS:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def handle_error(self, request, client_address):
        # The browser drops requests it no longer needs, such as posters scrolled past; that is not worth a traceback
        if not isinstance(sys.exc_info()[1], ConnectionError):
            super().handle_error(request, client_address)


def open_screen(kiosk):
    """Opens Lounge in its own browser profile: fullscreen with kiosk, otherwise as a maximised app window."""
    exe = apps.browser('chromium') or 'chromium'
    mode = ['--kiosk', f'http://127.0.0.1:{PORT}'] if kiosk else ['--start-maximized', f'--app=http://127.0.0.1:{PORT}']
    return subprocess.Popen([exe, '--no-first-run', '--no-default-browser-check', '--noerrdialogs', '--disable-infobars', '--password-store=basic',
                             '--autoplay-policy=no-user-gesture-required', f'--user-data-dir={os.path.join(CONFIG, "chromium")}', *mode])


def main():
    if '--install-autostart' in sys.argv or '--remove-autostart' in sys.argv:
        install_autostart('--install-autostart' in sys.argv)
        return
    # Lounge.exe opened with a double click has no options; it then opens its own window and stops when that closes
    window = WINDOWS and getattr(sys, 'frozen', False) and len(sys.argv) == 1
    try:
        server = Server(('127.0.0.1', PORT), Handler)
    except OSError:
        print(f'Lounge is already running at http://127.0.0.1:{PORT}, or another program uses port {PORT}.', file=sys.stderr)
        if window:
            open_screen(False)
        sys.exit(1)
    write_input_conf()
    apps.setup(CONFIG, broadcast)
    if not MPV:
        print('mpv was not found, so Tuner cannot play videos. Install it and make sure it is on the PATH.', file=sys.stderr)
    if '--kiosk' in sys.argv:
        hide_idle_cursor()
        threading.Timer(1, open_screen, args=(True,)).start()
    elif window:
        def run_window():
            opened = time.time()
            open_screen(False).wait()
            # A browser that hands the window to one already open on this profile exits at once; Lounge stays then
            if time.time() - opened < 5:
                return
            stop_player()
            apps.close()
            server.shutdown()
        threading.Thread(target=run_window, daemon=True).start()
    if '--remote' in sys.argv:
        start_remote()
    print(f'Lounge running at http://127.0.0.1:{PORT}')
    server.serve_forever()


if __name__ == '__main__':
    main()
