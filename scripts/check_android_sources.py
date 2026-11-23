#!/usr/bin/env python3
"""Dependency-free source/resource invariants; NOT a Kotlin compiler."""
from pathlib import Path
from collections import Counter
import re
import tomllib
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
src = root/'app/src/main/java'
files = list(src.rglob('*.kt'))
assert files, 'No Kotlin sources'
classes = []
for file in files:
    data = file.read_text()
    package = re.search(r'^package\s+([\w.]+)', data, re.M)
    assert package, f'Missing package: {file}'
    for decl in re.finditer(r'^(?:public |internal |private )?(?:data |sealed |enum |open |abstract )?(?:class|interface|object)\s+(\w+)', data, re.M):
        classes.append(package.group(1)+'.'+decl.group(1))
    assert not re.search(r'onClick\s*=\s*\{\s*\}', data), f'Empty onClick: {file}'
    assert 'TODO(' not in data and 'NotImplementedError(' not in data, f'Unimplemented code: {file}'
duplicates = [key for key,n in Counter(classes).items() if n>1]
assert not duplicates, f'Duplicate top-level types: {duplicates}'
manifest = ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
for node in manifest.findall('.//activity')+manifest.findall('.//service')+manifest.findall('.//receiver'):
    name = node.get(ns+'name', '')
    if name.startswith('.'):
        name = 'ru.momento.app'+name
    if name.startswith('ru.momento.app.'):
        assert name in classes, f'Manifest class missing: {name}'
resources = set()
for f in (root/'app/src/main/res').rglob('*'):
    if f.is_file():
        if f.suffix == '.xml' and f.parent.name.startswith('values'):
            tree=ET.parse(f)
            resources.update((node.tag,node.attrib.get('name')) for node in tree.getroot() if node.attrib.get('name'))
        else:
            resources.add((f.parent.name.split('-')[0], f.stem))
for f in files:
    for typ,name in re.findall(r'(?<!android\.)\bR\.(string|drawable|mipmap|color|style|raw)\.(\w+)', f.read_text()):
        assert (typ,name) in resources, f'Missing R.{typ}.{name} in {f}'
with (root/'gradle/libs.versions.toml').open('rb') as f:
    versions=tomllib.load(f)
assert versions['versions']['agp']=='9.1.1'
assert (root/'gradle/wrapper/gradle-wrapper.properties').is_file()
assert (root/'scripts/restore_gradle_wrapper.sh').is_file()
print(f'Android source audit OK: {len(files)} Kotlin files, {len(classes)} top-level types, {len(resources)} resources; no duplicate top-level types or missing referenced R resources.')
