import subprocess, sys, time, re, json, urllib.request
from pathlib import Path
from xml.etree import ElementTree as ET
ROOT = Path(__file__).parent
ADB = ['D:/Android/sdk/platform-tools/adb.exe', '-s', 'emulator-5554']
def adb(*args):
    return subprocess.check_output(ADB + list(args)).decode('utf8', 'replace')
def dump(label):
    adb('shell', 'uiautomator', 'dump', '/sdcard/qa.xml')
    xml = adb('shell', 'cat', '/sdcard/qa.xml')
    (ROOT / (label + '.xml')).write_text(xml, encoding='utf8')
    return ET.fromstring(xml)
def tap(root, text):
    for n in root.iter('node'):
        if n.get('text') == text:
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            print(adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)))
            return True
    return False
def capture(label):
    dump(label)
    (ROOT / (label + '.png')).write_bytes(subprocess.check_output(ADB + ['exec-out','screencap','-p']))
    logs = adb('logcat','-d','-s','PlayerFragment:D','MainActivity:D','AndroidRuntime:E')
    (ROOT / (label + '.log')).write_text(logs, encoding='utf8')
    print('\n'.join(logs.splitlines()[-16:]))

if sys.argv[1] == 'select':
    root = dump('menu-before')
    for attempt in range(3):
        if any(n.get('text') == sys.argv[2] for n in root.iter('node')): break
        adb('shell','input','keyevent','KEYCODE_MENU')
        time.sleep(.3)
        root = dump('menu-select')
    if not tap(root, sys.argv[2]):
        raise RuntimeError('Channel missing: ' + sys.argv[2])
elif sys.argv[1] == 'capture':
    capture(sys.argv[2])
elif sys.argv[1] == 'tap':
    if not tap(dump('before-tap'),sys.argv[2]): raise RuntimeError('Missing button')
