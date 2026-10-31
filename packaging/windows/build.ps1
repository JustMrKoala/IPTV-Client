# Builds dist\windows\Lounge.exe from lounge\ and web\, with Python and mpv inside, so the PC it runs on only needs
# Chrome or Edge (Edge comes with Windows). Needs Python 3.10 or newer and PyInstaller:
#   python -m pip install pyinstaller
#   powershell -ExecutionPolicy Bypass -File packaging\windows\build.ps1
# mpv is downloaded once from the mpv project's own releases and checked against the hash below. To bundle another
# copy instead, pass its folder: build.ps1 -Mpv C:\path\to\mpv
param([string]$Mpv = '')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$MpvVersion = '0.41.0'
$MpvHash = '4e197f729f5071c6772f35fffd96e0f36e3e8a044bd9479b136bb09b7c6a80ff'
$MpvUrl = "https://github.com/mpv-player/mpv/releases/download/v$MpvVersion/mpv-v$MpvVersion-x86_64-pc-windows-msvc.zip"

$here = $PSScriptRoot
$root = Resolve-Path (Join-Path $here '..\..')
$dist = Join-Path $root 'dist\windows'
$work = Join-Path $env:TEMP 'lounge-exe-build'
$cache = Join-Path $env:LOCALAPPDATA 'lounge-build'

python -c "import sys; sys.exit(sys.version_info < (3, 10))"
if ($LASTEXITCODE -ne 0) { throw 'Python 3.10 or newer is needed. Install it from python.org and open a new terminal.' }
python -m PyInstaller --version *> $null
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller is missing: python -m pip install pyinstaller' }

if (-not $Mpv) {
    $Mpv = Join-Path $cache "mpv-$MpvVersion"
    if (-not (Test-Path (Join-Path $Mpv 'mpv.exe'))) {
        New-Item -ItemType Directory -Force $cache | Out-Null
        $zip = Join-Path $cache "mpv-$MpvVersion.zip"
        Write-Host "Downloading mpv $MpvVersion"
        Invoke-WebRequest $MpvUrl -OutFile $zip -UseBasicParsing
        if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $MpvHash) {
            Remove-Item $zip
            throw 'The mpv download does not match the expected hash, so it was not used.'
        }
        Expand-Archive $zip -DestinationPath $Mpv -Force
        Remove-Item $zip
    }
}
if (-not (Test-Path (Join-Path $Mpv 'mpv.exe'))) { throw "No mpv.exe in $Mpv" }

# mpv.exe and the DLLs next to it; the debug symbols (mpv.pdb) and the console wrapper mpv.com are left out
$binaries = Get-ChildItem $Mpv -File | Where-Object { $_.Name -eq 'mpv.exe' -or $_.Extension -eq '.dll' } |
    ForEach-Object { '--add-binary', "$($_.FullName);mpv" }

python -m PyInstaller --noconfirm --clean --onefile --name Lounge --icon "$here\lounge.ico" `
    --distpath $dist --workpath $work --specpath $work --paths "$root\lounge" `
    --add-data "$root\web;web" @binaries "$root\lounge\server.py"
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed, see the messages above.' }
Write-Host "Built $(Join-Path $dist 'Lounge.exe')"
