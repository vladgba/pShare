"""Validate this project's local publishing files without building the app."""
from pathlib import Path
import json, re
ROOT=Path(__file__).resolve().parents[1]
info=json.loads((ROOT/'docs/publication-inventory.json').read_text())
for app in info['apps']:
    paths=app['publication_files']
    store=ROOT/paths['store']
    assert app['package'], 'Missing package ID'
    for name,limit in [('title',30),('short_description',80),('full_description',4000)]:
        text=(store/'en-US'/(name+'.txt')).read_text().strip()
        assert 0<len(text)<=limit, (name,len(text),limit)
    for name in ['review.md','assets.md','data-safety.md','release-notes-draft.txt']:
        assert (store/name).is_file(), name
    assert (ROOT/paths['privacy']).is_file(), paths['privacy']
    for p in [ROOT/paths['site'],ROOT/'docs/PUBLISHING.md']:
        for href in re.findall(r'\]\(([^)]+)\)',p.read_text()):
            if href.startswith(('https://','http://','mailto:','#')):continue
            dest=p.parent/href
            assert dest.exists() or dest.with_suffix('.md').exists(), (str(p),href)
    print('PASS:',app['name'],'listing limits, required files and local links')
