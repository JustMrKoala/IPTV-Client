"""Web apps such as YouTube and Netflix, each opened in its own fullscreen browser and driven by the remote.

The browser is controlled through --remote-debugging-pipe: commands travel over two private pipes instead of a
network port, so key presses from the phone remote reach the page on Wayland without any extra tools or root.
"""
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import quote
from urllib.request import urlopen

WINDOWS = os.name == 'nt'
if WINDOWS:
    import msvcrt
else:
    import fcntl

# The pages sit in web/ next to this folder, or inside the bundle when Lounge runs as Lounge.exe
ROOT = getattr(sys, '_MEIPASS', None) or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, 'web')
# YouTube only serves its TV interface to televisions, so the YouTube browser introduces itself as one
TV_AGENT = 'Mozilla/5.0 (Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36'
BROWSERS = {'chromium': ('chromium', 'chromium-browser'), 'chrome': ('google-chrome-stable', 'google-chrome')}
BROWSER_NAMES = {'chromium': 'Chromium', 'chrome': 'Google Chrome'}
if WINDOWS:
    # Windows browsers are not on the PATH. Edge comes with Windows and plays Netflix, so it stands in for both.
    _CHROME = [os.path.join(os.environ.get(v, ''), 'Google', 'Chrome', 'Application', 'chrome.exe')
               for v in ('PROGRAMFILES', 'PROGRAMFILES(X86)', 'LOCALAPPDATA')]
    _EDGE = [os.path.join(os.environ.get(v, ''), 'Microsoft', 'Edge', 'Application', 'msedge.exe')
             for v in ('PROGRAMFILES(X86)', 'PROGRAMFILES')]
    BROWSERS = {'chromium': (os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Chromium', 'Application', 'chrome.exe'),
                             *_CHROME, *_EDGE), 'chrome': (*_CHROME, *_EDGE)}
    BROWSER_NAMES = {'chromium': 'Chrome or Edge', 'chrome': 'Chrome or Edge'}
FLAGS = ['--kiosk', '--no-first-run', '--no-default-browser-check', '--noerrdialogs', '--disable-infobars', '--password-store=basic',
         '--autoplay-policy=no-user-gesture-required', '--hide-crash-restore-bubble', '--remote-debugging-pipe']
SCRIPTS = {'youtube': os.path.join(WEB, 'adblock', 'youtube.js')}
# A USB remote's Back button sends the browser's Back key and Escape at once. The page reports one Back per press
# along with its address at that moment, and the address of the first press, which is the app's first page.
# The browser's own handling of these keys is held back so Lounge decides what Back does, see back().
BACK_SCRIPT = """if (window === top) {
  let seen = false, last = 0;
  const send = what => typeof loungeBack === 'function' && loungeBack(JSON.stringify({what, url: location.href}));
  addEventListener('keydown', e => {
    const typing = e.target.closest?.('input, textarea, select, [contenteditable]');
    if (e.key === 'BrowserBack' || (!typing && (e.key === 'Escape' || e.key === 'Backspace'))) {
      // The page still sees the key, but the browser does not: its Escape would stop the page Back is opening
      e.preventDefault();
      if (e.timeStamp - last > 400) send('back');
      last = e.timeStamp;
    } else if (!seen) send('press');
    seen = true;
  }, true);
  addEventListener('mousedown', () => { if (!seen) send('press'); seen = true }, true);
}"""
# Browsers Lounge can install by itself the first time an app needs one
INSTALLERS = {} if WINDOWS else {
    'chrome': ('https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb', 'google-chrome-stable')}
ASKPASS = ('ksshaskpass', 'ssh-askpass', 'lxqt-openssh-askpass')

DEFAULT_TILES = [
    {'id': 'tvhub', 'name': 'Tuner', 'type': 'tvhub', 'color': '#26262c', 'icon': 'tvhub'},
    {'id': 'youtube', 'name': 'YouTube', 'type': 'web', 'url': 'https://www.youtube.com/tv', 'browser': 'chromium',
     'user_agent': TV_AGENT, 'adblock': 'youtube', 'color': '#ff0033', 'icon': 'youtube',
     'search_url': 'https://www.youtube.com/tv#/search?q={q}'},
    {'id': 'netflix', 'name': 'Netflix', 'type': 'web', 'url': 'https://www.netflix.com/browse', 'browser': 'chrome',
     'spatial_nav': True, 'color': '#b20710', 'icon': 'netflix', 'keys': {'back': 'history.back', 'playpause': ' '},
     'search_url': 'https://www.netflix.com/search?q={q}'},
    {'id': 'twitch', 'name': 'Twitch', 'type': 'web', 'url': 'https://www.twitch.tv/', 'browser': 'chromium',
     'spatial_nav': True, 'color': '#9146ff', 'icon': 'twitch', 'keys': {'back': 'history.back', 'playpause': ' '},
     'search_url': 'https://www.twitch.tv/search?term={q}'},
]

# Remote button -> key sent to the page. A tile can override any of these with its own "keys".
REMOTE_KEYS = ('up', 'down', 'left', 'right', 'ok', 'back', 'playpause', 'seek_back', 'seek_fwd') + tuple('0123456789')
DEFAULT_KEYS = {'up': 'ArrowUp', 'down': 'ArrowDown', 'left': 'ArrowLeft', 'right': 'ArrowRight', 'ok': 'Enter', 'back': 'Escape',
                'playpause': 'MediaPlayPause', 'seek_back': 'ArrowLeft', 'seek_fwd': 'ArrowRight', **{d: d for d in '0123456789'}}
KEYS = {'ArrowUp': ('ArrowUp', 38), 'ArrowDown': ('ArrowDown', 40), 'ArrowLeft': ('ArrowLeft', 37), 'ArrowRight': ('ArrowRight', 39),
        'Enter': ('Enter', 13), 'Escape': ('Escape', 27), 'Backspace': ('Backspace', 8), ' ': ('Space', 32), 'Tab': ('Tab', 9),
        'MediaPlayPause': ('MediaPlayPause', 179), 'MediaRewind': ('MediaRewind', 227), 'MediaFastForward': ('MediaFastForward', 228),
        **{d: ('Digit' + d, 48 + int(d)) for d in '0123456789'},
        **{c: ('Key' + c.upper(), ord(c.upper())) for c in 'abcdefghijklmnopqrstuvwxyz'}}
ACTIONS = ('history.back',)

CONFIG = {'dir': '', 'notify': lambda event: None}
LOCK = threading.RLock()
CURRENT = {'app': None}
INSTALLING = {}


def setup(config_dir, notify):
    CONFIG.update(dir=config_dir, notify=notify)


def tiles_file():
    return os.path.join(CONFIG['dir'], 'dashboard.json')


def browser(name):
    if WINDOWS:
        return next((p for p in BROWSERS.get(name, ()) if os.path.isfile(p)), None)
    return next(filter(None, map(shutil.which, BROWSERS.get(name, ()))), None)


def load_tiles():
    try:
        with open(tiles_file(), encoding='utf-8') as f:
            saved = json.load(f)
    except FileNotFoundError:
        # Written out once, so the dashboard can also be edited by hand
        write_tiles(DEFAULT_TILES)
        return [clean_tile(t) for t in DEFAULT_TILES]
    # Built-in apps added in a newer version show up once; removing one afterwards keeps it removed
    known = saved.get('builtin', ['tvhub', 'youtube', 'netflix'])
    added = [t for t in DEFAULT_TILES if t['id'] not in known]
    if added:
        saved['tiles'] += added
        write_tiles(saved['tiles'])
    # Built-in tiles pick up settings added in newer versions, unless they were pointed somewhere else
    defaults = {t['id']: t for t in DEFAULT_TILES}
    for t in saved['tiles']:
        # The IPTV app was called TVHub before it became Tuner
        if t.get('type') == 'tvhub' and t.get('name') == 'TVHub':
            t['name'] = 'Tuner'
    return [clean_tile({**defaults[t.get('id')], **t} if defaults.get(t.get('id'), {}).get('url') == t.get('url') else t)
            for t in saved['tiles']]


def write_tiles(tiles):
    tmp = tiles_file() + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump({'tiles': tiles, 'builtin': [t['id'] for t in DEFAULT_TILES]}, f, indent=2)
    os.replace(tmp, tiles_file())


def clean_tile(t):
    kind = t.get('type')
    if kind not in ('tvhub', 'web'):
        raise ValueError('unknown tile type')
    out = {'id': re.sub(r'[^a-z0-9-]', '', str(t.get('id') or '').lower())[:30] or secrets.token_hex(4),
           'name': str(t.get('name') or '').strip()[:40] or 'App', 'type': kind,
           'color': t['color'] if re.fullmatch(r'#[0-9a-fA-F]{6}', str(t.get('color', ''))) else '#333333',
           'icon': re.sub(r'[^a-z]', '', str(t.get('icon') or ''))[:20]}
    if kind == 'web':
        url = str(t.get('url') or '').strip()
        if not re.match(r'https?://[^\s]+$', url):
            raise ValueError('web tiles need an http or https address')
        out.update(url=url[:500], browser=t.get('browser') if t.get('browser') in BROWSERS else 'chromium')
        if t.get('user_agent'):
            out['user_agent'] = str(t['user_agent'])[:300]
        if t.get('adblock') in SCRIPTS:
            out['adblock'] = t['adblock']
        if t.get('spatial_nav'):
            out['spatial_nav'] = True
        search = str(t.get('search_url') or '')
        if re.match(r'https?://[^\s]+$', search) and '{q}' in search:
            out['search_url'] = search[:500]
        keys = {k: v for k, v in (t.get('keys') or {}).items() if k in REMOTE_KEYS and (v in KEYS or v in ACTIONS)}
        if keys:
            out['keys'] = keys
    return out


def tiles():
    out = []
    for t in load_tiles():
        ready = t['type'] == 'tvhub' or bool(browser(t['browser']))
        out.append({**t, 'ready': ready, 'needs': '' if ready else BROWSER_NAMES[t['browser']],
                    'installing': not ready and t['browser'] in INSTALLING})
    return out


def as_root(cmd):
    """Runs a command as root, asking for the password in a desktop dialog when one is needed."""
    if subprocess.run(['sudo', '-n', 'true'], capture_output=True).returncode == 0:
        return subprocess.run(['sudo', '-n', *cmd], capture_output=True, text=True)
    if shutil.which('pkexec'):
        return subprocess.run(['pkexec', *cmd], capture_output=True, text=True)
    askpass = next(filter(None, map(shutil.which, ASKPASS)), None)
    if not askpass:
        raise RuntimeError('no way to ask for the password: install pkexec or ksshaskpass')
    return subprocess.run(['sudo', '-A', *cmd], capture_output=True, text=True, env={**os.environ, 'SUDO_ASKPASS': askpass})


def install(name):
    """Downloads a browser such as Google Chrome and installs it with apt, which also adds its package source so it
    updates with the rest of the system. Runs in the background and reports back through notify."""
    with LOCK:
        if name not in INSTALLERS or name in INSTALLING or browser(name):
            return
        INSTALLING[name] = True
    threading.Thread(target=run_install, args=(name,), daemon=True).start()


def run_install(name):
    url, package = INSTALLERS[name]
    CONFIG['notify']('installing')
    folder = tempfile.mkdtemp(prefix='tvhub-')
    try:
        # apt reads the file as its own unprivileged user, so the folder must be readable by others
        os.chmod(folder, 0o755)
        deb = os.path.join(folder, package + '.deb')
        with urlopen(url, timeout=60) as r, open(deb, 'wb') as f:
            shutil.copyfileobj(r, f)
        os.chmod(deb, 0o644)
        found = subprocess.run(['dpkg-deb', '-f', deb, 'Package'], capture_output=True, text=True).stdout.strip()
        if found != package:
            raise RuntimeError(f'downloaded file is not {package}')
        result = as_root(['apt-get', 'install', '-y', deb])
        if result.returncode != 0 or not browser(name):
            raise RuntimeError(((result.stderr or '').strip().splitlines() or ['apt-get failed'])[-1])
        event = 'installed'
    except Exception as e:
        print('install failed', name, type(e).__name__, e)
        event = 'installfailed'
    shutil.rmtree(folder, ignore_errors=True)
    INSTALLING.pop(name, None)
    CONFIG['notify'](event)


def save_tiles(new):
    cleaned, seen = [], set()
    for t in new[:40]:
        t = clean_tile(t)
        if t['id'] in seen:
            t['id'] = secrets.token_hex(4)
        seen.add(t['id'])
        cleaned.append(t)
    if not any(t['type'] == 'tvhub' for t in cleaned):
        raise ValueError('the Tuner tile cannot be removed')
    write_tiles(cleaned)


class Pipe:
    """Chrome DevTools protocol over the browser's debugging pipe: JSON messages separated by a null byte."""

    def __init__(self, write_fd, read_fd, on_event):
        self.out = os.fdopen(write_fd, 'wb', buffering=0)
        self.inp = os.fdopen(read_fd, 'rb', buffering=0)
        self.n, self.waiting, self.lock, self.on_event = 0, {}, threading.Lock(), on_event
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        # Replies such as a page's history can be large and arrive in many pieces; a bytearray grows in place
        buf = bytearray()
        try:
            while chunk := self.inp.read(65536):
                start = len(buf)
                buf += chunk
                while (end := buf.find(b'\0', start)) >= 0:
                    reply = json.loads(buf[:end])
                    del buf[:end + 1]
                    start = 0
                    if 'method' in reply:
                        self.on_event(reply)
                        continue
                    slot = self.waiting.pop(reply.get('id'), None)
                    if slot:
                        slot[1] = reply
                        slot[0].set()
        except (OSError, ValueError):
            pass
        for slot in list(self.waiting.values()):
            slot[0].set()

    def send(self, method, params=None, session=None):
        with self.lock:
            self.n += 1
            msg = {'id': self.n, 'method': method, 'params': params or {}}
            if session:
                msg['sessionId'] = session
            slot = self.waiting[self.n] = [threading.Event(), None]
            self.out.write(json.dumps(msg).encode() + b'\0')
        return self.n, slot

    def call(self, method, params=None, session=None, timeout=10):
        n, slot = self.send(method, params, session)
        if not slot[0].wait(timeout) or slot[1] is None:
            self.waiting.pop(n, None)
            raise TimeoutError(method)
        if 'error' in slot[1]:
            raise RuntimeError(slot[1]['error'].get('message', method))
        return slot[1].get('result', {})

    def close(self):
        for f in (self.out, self.inp):
            try:
                f.close()
            except OSError:
                pass


class App:
    def __init__(self, tile, proc, pipe):
        self.tile, self.proc, self.pipe, self.session = tile, proc, pipe, None
        # Address of the page the first press was on, see BACK_SCRIPT
        self.first = None


def running():
    app = CURRENT['app']
    return app is not None and app.proc.poll() is None


def high_fd(fd):
    # Moves a pipe end above 10 so the shell redirections onto fd 3 and 4 never clash with it
    moved = fcntl.fcntl(fd, fcntl.F_DUPFD, 10)
    os.close(fd)
    return moved


def launch(tile_id):
    tile = next(t for t in load_tiles() if t['id'] == tile_id and t['type'] == 'web')
    exe = browser(tile['browser'])
    if not exe:
        raise LookupError(f"{BROWSER_NAMES[tile['browser']]} is not installed")
    with LOCK:
        # A second press on the tile that is already open does nothing
        if running() and CURRENT['app'].tile['id'] == tile_id:
            return
        close()
        profile = os.path.join(CONFIG['dir'], 'apps', tile['id'])
        os.makedirs(profile, mode=0o700, exist_ok=True)
        args = [exe, *FLAGS, f'--user-data-dir={profile}']
        if tile.get('user_agent'):
            args.append('--user-agent=' + tile['user_agent'])
        if tile.get('spatial_nav'):
            args.append('--enable-spatial-navigation')
        cmd_read, cmd_write = os.pipe()
        reply_read, reply_write = os.pipe()
        if WINDOWS:
            # Windows has no fd 3 and 4 to hand over, so the browser is told the pipe handles it inherits instead
            handles = [msvcrt.get_osfhandle(cmd_read), msvcrt.get_osfhandle(reply_write)]
            for h in handles:
                os.set_handle_inheritable(h, True)
            info = subprocess.STARTUPINFO()
            info.lpAttributeList = {'handle_list': handles}
            args += [f'--remote-debugging-io-pipes={handles[0]},{handles[1]}', 'about:blank']
            proc = subprocess.Popen(args, startupinfo=info, close_fds=True,
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            args.append('about:blank')
            cmd_read, reply_write = high_fd(cmd_read), high_fd(reply_write)
            # The browser reads commands on fd 3 and writes replies on fd 4. bash, unlike dash, accepts fds above 9.
            shell = f'exec "$@" 3<&{cmd_read} 4>&{reply_write} {cmd_read}<&- {reply_write}>&-'
            proc = subprocess.Popen(['bash', '-c', shell, 'bash', *args], pass_fds=(cmd_read, reply_write),
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.close(cmd_read)
        os.close(reply_write)
        app = CURRENT['app'] = App(tile, proc, None)
        app.pipe = Pipe(cmd_write, reply_read, lambda event: on_event(app, event))
    threading.Thread(target=start, args=(app,), daemon=True).start()


def start(app):
    try:
        deadline = time.time() + 20
        while True:
            pages = [t for t in app.pipe.call('Target.getTargets', timeout=20)['targetInfos'] if t['type'] == 'page']
            if pages or time.time() > deadline:
                break
            time.sleep(0.2)
        session = app.pipe.call('Target.attachToTarget', {'targetId': pages[0]['targetId'], 'flatten': True})['sessionId']
        # Scripts added before navigation only run once the Page domain is enabled
        app.pipe.call('Page.enable', session=session)
        # Without the Runtime domain the binding is lost as soon as the page moves to the app's site
        app.pipe.call('Runtime.enable', session=session)
        app.pipe.call('Runtime.addBinding', {'name': 'loungeBack'}, session)
        scripts = [BACK_SCRIPT]
        if app.tile.get('adblock') in SCRIPTS:
            with open(SCRIPTS[app.tile['adblock']], encoding='utf-8') as f:
                scripts.append(f.read())
        for source in scripts:
            app.pipe.call('Page.addScriptToEvaluateOnNewDocument', {'source': source}, session)
        app.pipe.call('Page.navigate', {'url': app.tile['url']}, session)
        forget_blank(app.pipe, session)
        # The browser puts the keyboard in its hidden address bar while the blank page shows, and leaves it there when
        # the app loads late, so the remote's keys would go nowhere. This hands the keyboard to the page.
        app.pipe.call('Page.bringToFront', session=session)
        app.session = session
        CONFIG['notify']('appopened')
    except Exception as e:
        print('app failed to open', type(e).__name__, e)
        if CURRENT['app'] is app:
            close()
            CONFIG['notify']('appfailed')
        return
    app.proc.wait()
    app.pipe.close()
    with LOCK:
        if CURRENT['app'] is app:
            CURRENT['app'] = None
            CONFIG['notify']('appclosed')


def forget_blank(pipe, session):
    """Drops the blank page the browser started on from the history once the app has replaced it. Netflix goes back
    a page by itself right after its login page loads, which would otherwise leave a white screen."""
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            if pipe.call('Page.getNavigationHistory', session=session)['currentIndex'] > 0:
                pipe.call('Page.resetNavigationHistory', session=session)
                return
        except RuntimeError:
            # The page cannot be reached for a moment while the browser swaps it for the app's site
            pass
        time.sleep(0.1)


def close():
    with LOCK:
        app, CURRENT['app'] = CURRENT['app'], None
    if not app:
        return
    if app.proc.poll() is None:
        try:
            # Closing politely keeps the profile from being marked as crashed
            app.pipe.send('Browser.close')
            app.proc.wait(4)
        except (OSError, subprocess.TimeoutExpired):
            app.proc.terminate()
            try:
                app.proc.wait(3)
            except subprocess.TimeoutExpired:
                app.proc.kill()
    app.pipe.close()


def on_event(app, event):
    if event['method'] != 'Runtime.bindingCalled' or event['params']['name'] != 'loungeBack':
        return
    data = json.loads(event['params']['payload'])
    if app.first is None:
        app.first = data['url']
    if data['what'] == 'back':
        # Runs on its own thread, since it waits for replies that arrive on the thread that reads events
        threading.Thread(target=back, args=(app, data['url']), daemon=True).start()


def back(app, url=None):
    """Back on the app's first page goes home. Elsewhere, apps whose back key is history.back go back a page, and
    the others (YouTube) handle Back themselves through the Escape key."""
    if CURRENT['app'] is not app or not app.session:
        return
    history = app.pipe.call('Page.getNavigationHistory', session=app.session)
    i = history['currentIndex']
    if (url or history['entries'][i]['url']) == app.first:
        return home()
    if {**DEFAULT_KEYS, **app.tile.get('keys', {})}.get('back') != 'history.back':
        return
    # Never back to the blank page the browser started on
    if i > 0 and history['entries'][i - 1]['url'] != 'about:blank':
        app.pipe.call('Page.navigateToHistoryEntry', {'entryId': history['entries'][i - 1]['id']}, app.session)
    else:
        home()


def home():
    close()
    CONFIG['notify']('home')


def press(cmd):
    app = CURRENT['app']
    if not app or not app.session:
        return
    key = {**DEFAULT_KEYS, **app.tile.get('keys', {})}.get(cmd)
    if key == 'history.back':
        return back(app)
    if key not in KEYS:
        return
    code, vk = KEYS[key]
    base = {'key': key, 'code': code, 'windowsVirtualKeyCode': vk, 'nativeVirtualKeyCode': vk}
    text = '\r' if key == 'Enter' else key if len(key) == 1 else ''
    app.pipe.call('Input.dispatchKeyEvent', {**base, 'type': 'keyDown', 'text': text} if text else {**base, 'type': 'rawKeyDown'}, app.session)
    app.pipe.call('Input.dispatchKeyEvent', {**base, 'type': 'keyUp'}, app.session)


def type_text(text):
    """Text typed on the phone. Apps with a search address open their search page for it, since YouTube's
    TV interface draws its own keyboard and has no text box. Other apps get the text in the focused box."""
    app = CURRENT['app']
    if not app or not app.session:
        return
    if app.tile.get('search_url'):
        if text.strip():
            app.pipe.call('Page.navigate', {'url': app.tile['search_url'].replace('{q}', quote(text.strip()))}, app.session)
        return
    app.pipe.call('Runtime.evaluate', {'expression': "document.activeElement?.select?.()"}, app.session)
    app.pipe.call('Input.insertText', {'text': text}, app.session)
