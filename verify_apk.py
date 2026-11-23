#!/usr/bin/env python3
"""校验构建产物 APK：签名/清单/条目/xposed_init/关键类。"""
import sys, zipfile, subprocess, os, re

apk = sys.argv[1]
sdk = os.environ.get('ANDROID_HOME') or os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk')
bt = os.path.join(sdk, 'build-tools', '35.0.0')
ok = True

def check(cond, msg):
    global ok
    print(('PASS  ' if cond else 'FAIL  ') + msg)
    if not cond:
        ok = False

with zipfile.ZipFile(apk) as z:
    names = z.namelist()
    check('AndroidManifest.xml' in names, 'AndroidManifest.xml present')
    check('classes.dex' in names, 'classes.dex present')
    check('assets/xposed_init' in names, 'assets/xposed_init present')
    check(not any(n.startswith('de/robv/') for n in names), 'no bundled Xposed API classes')
    init = z.read('assets/xposed_init').decode('utf-8').strip()
    check(init == 'com.colorinversion.fix.ColorInversionHook', 'xposed_init -> ' + init)
    dex = z.read('classes.dex')

for key in (b'accessibility_display_inversion_enabled', b'DisplayTransformManager',
            b'ColorDisplayService', b'SurfaceFlinger', b'com/colorinversion/fix/ColorInversionHook'):
    check(key in dex, 'dex contains ' + key.decode())

manifest = subprocess.run([os.path.join(bt, 'aapt2.exe'), 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'],
                          capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
for meta in ('xposedmodule', 'xposeddescription', 'xposedminversion', 'xposedscope'):
    check(meta in manifest, 'manifest meta-data ' + meta)
check('com.colorinversion.fix' in manifest, 'manifest package com.colorinversion.fix')

badging = subprocess.run([os.path.join(bt, 'aapt2.exe'), 'dump', 'badging', apk],
                         capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
check("minSdkVersion:'29'" in badging, 'minSdk = 29')
check('launchable-activity' in badging, 'launchable activity present')

# 解出 dex 后列出类（dexdump）
tmp = os.path.join(os.environ['TEMP'], 'color_fix_verify')
os.makedirs(tmp, exist_ok=True)
open(os.path.join(tmp, 'classes.dex'), 'wb').write(dex)
dump = subprocess.run([os.path.join(bt, 'dexdump.exe'), 'classes.dex'], cwd=tmp,
                      capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
check("Class descriptor  : 'Lcom/colorinversion/fix/ColorInversionHook;'" in dump, 'ColorInversionHook defined in dex')
check("Class descriptor  : 'Lcom/colorinversion/fix/MainActivity;'" in dump, 'MainActivity defined in dex')
check("Class descriptor  : 'Lde/robv/" not in dump, 'no de.robv class definitions in dex')

print('\nRESULT:', 'OK' if ok else 'FAILED')
sys.exit(0 if ok else 1)
