"""Saves movies and episodes to disk, one at a time, so they can be watched without the provider.

Names and folders follow KoalaIPTV: "Movie (2009).mp4" in the download folder, and episodes in
"Show/Season 01/Show S01E02 - Title.mkv". Movies and episodes are plain files on Xtream servers, so they are fetched
directly and nothing has to be installed. A broken connection carries on from where it stopped.
"""
import errno
import http.client
import json
import os
import re
import secrets
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

# Some providers refuse anything that does not look like a media player
AGENT = 'VLC/3.0.9 LibVLC/3.0.9'
EXTENSIONS = ('mp4', 'mkv', 'avi', 'mov', 'm4v', 'webm', 'ts', 'mpg', 'mpeg', 'wmv', 'flv')
RESERVED = {'CON', 'PRN', 'AUX', 'NUL', *(f'{p}{n}' for p in ('COM', 'LPT') for n in range(1, 10))}
CHUNK = 1 << 18
RETRIES, RETRY_WAIT, TIMEOUT = 20, 3, 30
# A provider that has this file gone or the login refused will not change its mind on a retry. 403 is not one of them:
# many providers send it when the connection limit is reached, for example while something else is playing.
FINAL_CODES = (400, 401, 404, 410)
ACTIVE = ('queued', 'downloading')

CONFIG = {'path': '', 'failure': lambda e: 'other'}
LOCK = threading.Lock()
WAKE = threading.Event()
SAVED = {'dir': '', 'jobs': []}


class Cancelled(Exception):
    pass


class NotAFile(Exception):
    """The address is a live or segmented stream, not a file that can be saved."""


def setup(config_dir, failure):
    CONFIG.update(path=os.path.join(config_dir, 'downloads.json'), failure=failure)
    try:
        with open(CONFIG['path'], encoding='utf-8') as f:
            SAVED.update(json.load(f))
    except (OSError, ValueError):
        pass
    SAVED['dir'] = SAVED['dir'] or os.path.join(os.path.expanduser('~'), 'Videos', 'Lounge')
    # Whatever was busy when Lounge stopped carries on from its .part file
    for job in SAVED['jobs']:
        if job['state'] == 'downloading':
            job['state'] = 'queued'
    threading.Thread(target=work, daemon=True).start()
    WAKE.set()


def save():
    """Called with LOCK held. The file holds the stream addresses, and so the login, so only this user can read it."""
    tmp = CONFIG['path'] + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump({'dir': SAVED['dir'], 'jobs': [{k: v for k, v in j.items() if k != 'stop'} for j in SAVED['jobs']]}, f)
    os.chmod(tmp, 0o600)
    os.replace(tmp, CONFIG['path'])


def sanitize(name):
    name = re.sub(r'[\\/*?:"<>|\x00-\x1f]', '_', str(name)).strip('_. ')[:150].strip('_. ')
    return f'_{name}' if name.split('.')[0].upper() in RESERVED else name or 'Untitled'


def extension(url):
    found = os.path.splitext(urlparse(url).path)[1].lstrip('.').lower()
    return found if found in EXTENSIONS else 'mp4'


def target(item, url):
    """Where a title goes, relative to the download folder."""
    ext = extension(url)
    if item.get('kind') != 'ep':
        year = f" ({item['year']})" if item.get('year') else ''
        return f"{sanitize(str(item['name']) + year)}.{ext}"
    show, title, season, ep = sanitize(item['series']), str(item.get('title') or ''), str(item['season']), item.get('ep') or 0
    folder = f'Season {int(season):02d}' if season.isdigit() else f'Season {sanitize(season)}'
    code = f'S{int(season):02d}E{int(ep):02d}' if season.isdigit() else f'E{int(ep):02d}'
    # Providers often write the whole "Show S01E02 Title" in the episode's own title already
    name = title if re.search(r'S\d+\s*E\d+', title, re.I) else f'{show} {code} - {title}' if title else f'{show} {code}'
    return os.path.join(show, folder, f'{sanitize(name)}.{ext}')


def add(items):
    """items are (address, details) pairs. A title that is already saved or on its way is left alone."""
    with LOCK:
        for url, item in items:
            old = next((j for j in SAVED['jobs'] if j['key'] == item['key']), None)
            if old and old['state'] in (*ACTIVE, 'done'):
                continue
            if old:
                SAVED['jobs'].remove(old)
            SAVED['jobs'].append({'id': secrets.token_hex(4), 'key': item['key'], 'name': item['label'], 'url': url,
                                  'path': target(item, url), 'state': 'queued', 'got': 0, 'size': 0, 'error': ''})
        save()
    WAKE.set()


def find(job_id):
    return next(j for j in SAVED['jobs'] if j['id'] == job_id)


def retry(job_id):
    with LOCK:
        job = find(job_id)
        if job['state'] == 'failed':
            job.update(state='queued', error='')
        save()
    WAKE.set()


def remove(job_id):
    """Takes a title off the list. One that is not finished is stopped and its partial file deleted; a finished file stays."""
    with LOCK:
        job = find(job_id)
        SAVED['jobs'].remove(job)
        job['stop'] = True
        path = None if job['state'] in ('done', 'downloading') else part_file(job)
        save()
    if path and os.path.exists(path):
        os.remove(path)


def set_dir(path):
    path = os.path.expanduser(path.strip())
    if not os.path.isabs(path):
        raise ValueError('the download folder must be a full path')
    os.makedirs(path, exist_ok=True)
    with LOCK:
        SAVED['dir'] = path
        save()


def folder():
    return SAVED['dir']


def status():
    """What Tuner shows. The addresses hold the login, so they stay here."""
    keep = ('id', 'key', 'name', 'state', 'got', 'size', 'error')
    with LOCK:
        return {'dir': SAVED['dir'], 'jobs': [{k: j[k] for k in keep} for j in SAVED['jobs']]}


def final_file(job):
    return os.path.join(SAVED['dir'], job['path'])


def part_file(job):
    return final_file(job) + '.part'


def work():
    while True:
        WAKE.wait()
        with LOCK:
            job = next((j for j in SAVED['jobs'] if j['state'] == 'queued'), None)
            if not job:
                WAKE.clear()
                continue
            job['state'] = 'downloading'
        run(job)


def run(job):
    try:
        if not os.path.exists(final_file(job)):
            fetch(job)
        state, error = 'done', ''
    except Cancelled:
        try:
            os.remove(part_file(job))
        except OSError:
            pass
        return
    except NotAFile:
        state, error = 'failed', 'stream'
    except OSError as e:
        code = 'disk' if isinstance(e, PermissionError) or e.errno == errno.ENOSPC else CONFIG['failure'](e)
        state, error = 'failed', code
    except Exception as e:
        state, error = 'failed', CONFIG['failure'](e)
    # The message says what went wrong without the address, which holds the login
    if error:
        print('download failed', error)
    with LOCK:
        job.update(state=state, error=error)
        if job in SAVED['jobs']:
            save()


def fetch(job):
    part = part_file(job)
    os.makedirs(os.path.dirname(part), exist_ok=True)
    tries = best = 0
    while True:
        try:
            if receive(job, part):
                break
            if tries >= RETRIES:
                raise ConnectionError('the server keeps stopping early')
        except HTTPError as e:
            if e.code == 416 and os.path.exists(part):
                # Nothing left past the end of the partial file, so it was complete
                break
            if e.code in FINAL_CODES or tries >= RETRIES:
                raise
        except (URLError, TimeoutError, ConnectionError, http.client.HTTPException):
            if tries >= RETRIES:
                raise
        # Only a connection that brought nothing new counts against the limit, so long files survive many short drops
        have = os.path.getsize(part) if os.path.exists(part) else 0
        tries = 0 if have > best else tries + 1
        best = max(best, have)
        for _ in range(RETRY_WAIT * 10):
            time.sleep(0.1)
            if job.get('stop'):
                raise Cancelled
    os.replace(part, final_file(job))


def receive(job, part):
    """Fetches what is still missing and returns True when the file is complete. False means the server stopped early."""
    have = os.path.getsize(part) if os.path.exists(part) else 0
    headers = {'User-Agent': AGENT, **({'Range': f'bytes={have}-'} if have else {})}
    with urlopen(Request(job['url'], headers=headers), timeout=TIMEOUT) as r:
        kind = r.headers.get('Content-Type', '').lower()
        if 'mpegurl' in kind or kind.startswith('text/') or job['url'].lower().split('?')[0].endswith('.m3u8'):
            raise NotAFile
        # A server that ignores Range sends the whole file again, so the partial file starts over
        if r.status != 206:
            have = 0
        length = int(r.headers.get('Content-Length') or 0)
        job.update(got=have, size=have + length if length else 0)
        with open(part, 'ab' if have else 'wb') as f:
            while chunk := r.read(CHUNK):
                if job.get('stop'):
                    raise Cancelled
                f.write(chunk)
                job['got'] += len(chunk)
    return not job['size'] or job['got'] >= job['size']
