#!/usr/bin/env python3
"""Freeze the verified Chapter 15 checkpoint. Refuses a mismatched host APK."""
from pathlib import Path
import hashlib, json, shutil, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
OUT = ROOT / 'evidence/2026-10-06'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
rel = lambda p: str(p.relative_to(REPO))

hosts = sorted((ROOT / 'evidence/host-journeys').iterdir())
final_host = hosts[-1]
result = json.loads((final_host / 'result.json').read_text())
assert result['status'] == 'PASS'
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
server = ROOT / 'server/build/libs/server.jar'
assert sha(apk) == result['tested_apk_sha256']
assert sha(server) == result['tested_server_sha256']
for name, expected in result['server_distribution_jars'].items():
    assert sha(ROOT/'server/build/install/server/lib'/name) == expected
    assert sha(final_host/'tested-server-libs'/name) == expected
assert sha(ROOT/'scripts/host-journey.py') == sha(final_host/'tested-host-journey.py')
for folder, count, failures in [('android-test-results',19,0),('protocol-tests',18,0),
                                ('http-tests',17,0),('learner-tests',18,14)]:
    reports = [ET.parse(p).getroot() for p in (OUT/'reference'/folder).rglob('TEST-*.xml')]
    assert sum(int(r.get('tests')) for r in reports) == count
    assert sum(int(r.get('failures')) for r in reports) == failures
    assert all(int(r.get('errors','0')) == 0 and int(r.get('skipped','0')) == 0 for r in reports)

shutil.copytree(final_host, OUT/'final-host-journey')
for earlier in hosts[:-1]:
    shutil.copytree(earlier, OUT/'earlier-host-journeys'/earlier.name)
artifacts = []
for file in [apk, ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
             ROOT/'protocol/build/libs/protocol.jar', server]:
    target = OUT/'artifacts'/file.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(file, target)
    artifacts.append({'path':rel(target),'sha256':sha(target),'built_from':rel(file)})
sources=[]
allowed_names={'.gitignore','README.md','verification.md','gradlew','gradlew.bat'}
for file in sorted(ROOT.rglob('*')):
    local=file.relative_to(ROOT)
    if not file.is_file() or any(p in local.parts for p in ['evidence','build','.gradle','.kotlin','__pycache__']):continue
    if file.name=='local.properties':continue
    if file.name not in allowed_names and file.suffix not in ['.kt','.kts','.toml','.properties','.xml','.json','.py','.jar']:continue
    target=OUT/'source-snapshot'/local
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
    assert sha(target)==sha(file)
    sources.append({'path':rel(file),'sha256':sha(file),'snapshot':rel(target)})
evidence=[]
for file in sorted(OUT.rglob('*')):
    if file.is_file() and 'source-snapshot' not in file.relative_to(OUT).parts and file.name!='verification.json':
        evidence.append({'path':rel(file),'sha256':sha(file)})
manifest={'status':'PASS','date':'2026-10-06','edition_cutoff':'2026-10-04',
    'runtime':'JDK27 / Gradle9.8 / Kotlin2.4.20 / JVM26 / AGP9.4.1 / API36 emulator',
    'reference_checks':{'android':19,'pure_report_planner':18,'actual_http':17,'total':54},
    'learner':{'cases':18,'intended_assertion_failures':14,'passes':4,'errors':0,'skipped':0},
    'final_build':{'scope':'Separate final server/protocol build and Android rerun; exact task execution counts are in copied logs','fresh_all_tasks_run':False},
    'lint':{'errors':0,'warnings':['MissingApplicationIcon']},
    'final_host':{'path':rel(OUT/'final-host-journey'),'result':result},
    'server_distribution_jars':result['server_distribution_jars'],
    'sources':sources,'artifacts':artifacts,'evidence_files':evidence,
    'limits':['API36 functional emulator evidence; no API26/37 device or physical timing',
              'App debug SIGKILL is an abrupt process simulation, not an observed OS low-memory kill',
              'Server SIGKILL follows a known committed write, not power-loss durability testing',
              'No real payment, external distributed transaction, production authentication or FCM delivery',
              'One unresolved operation per account; initial policy fetch requires connectivity; app data loss needs account-level recovery outside this fixture']}
(OUT/'verification.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'status':'PASS','sources':len(sources),'evidence_files':len(evidence),
                  'artifacts':len(artifacts),'host_screens':len(list((OUT/'final-host-journey').glob('*.png')))}))
