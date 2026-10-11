#!/usr/bin/env python3
"""Destructive only to this lab's app data and synthetic fixture. Run on the named lab emulator."""
from pathlib import Path
from fresh_android_xml import read_ui_xml
from common_goods_ui import CommonGoodsUi
import signal
import datetime, hashlib, json, os, re, shutil, subprocess, time, urllib.request, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=Path(os.environ.get('CHAPTER15_EVIDENCE', ROOT/'evidence/host-journeys'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
OUT.mkdir(parents=True,exist_ok=False)
for helper_name in ('common_goods_ui.py', 'fresh_android_xml.py'):
    helper = Path(__file__).with_name(helper_name)
    if helper.is_file():
        (OUT / helper_name).write_bytes(helper.read_bytes())
ADB=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
JAVA=Path(os.environ['JAVA_HOME'])/'bin/java'
APP='staff.android.orders'
backend=None; logs=[]; events=[]; server_number=0
# Freeze inputs before installing/starting them, even if another build is prepared later.
SNAP_APK=OUT/'tested-app-debug.apk'
shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk', SNAP_APK)
shutil.copytree(ROOT/'server/build/install/server/lib', OUT/'tested-server-libs')
shutil.copy2(OUT/'tested-server-libs/server.jar', OUT/'tested-server.jar')
shutil.copy2(Path(__file__),OUT/'tested-host-journey.py')


def run(*args):
    p=subprocess.run([str(ADB),'-s','emulator-5554',*args],capture_output=True,check=True)
    return p.stdout

def xml():
    return read_ui_xml(run, '/sdcard/chapter15-window.xml', {APP})

ui = CommonGoodsUi(xml, run, OUT)
def capture(name, contains=(), absent=()):
    frames=ui.observe(name)
    content='\n'.join(value for _,value in frames)
    for text in contains: assert text in content, (name,'missing',text)
    for text in absent: assert text not in content, (name,'unexpected',text)
    events.append({'screen':name,'viewports':[label for label,_ in frames],
                   'asserted':list(contains),'absent':list(absent),
                   'scope':'Assertions span retained scrolling viewports; not one simultaneous screenshot'})

def tap(text):ui.tap(text)

def edit(text):
    field=ui.find(lambda n: n.get('class')=='android.widget.EditText')
    assert field is not None, 'No visible editor'
    x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')))
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.3)
    for _ in range(3):
        run('shell','input','keycombination','113','29')
        run('shell','input','keyevent','67')
        current=ET.fromstring(xml())
        value=next(n for n in current.iter('node') if n.get('class')=='android.widget.EditText').get('text','')
        if not value: break
    else: raise AssertionError('Could not clear draft field before typing')
    run('shell','input','text',text.replace(' ','%s'))
    run('shell','input','keyevent','4'); time.sleep(.3)

def control(name):
    req=urllib.request.Request('http://127.0.0.1:8790/control/'+name,data=b'',method='POST')
    with urllib.request.urlopen(req,timeout=5) as response: assert response.status==200

def start():
    global backend,server_number
    server_number+=1
    f=(OUT/f'server-{server_number}.log').open('w');logs.append(f)
    backend=subprocess.Popen([str(JAVA),'--enable-native-access=ALL-UNNAMED','-cp',str(OUT/'tested-server-libs/*'),
        'staff.android.orders.server.ServerMainKt', '8790', str(OUT/'server-orders.db')],stdout=f,stderr=subprocess.STDOUT)
    for _ in range(100):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8790/control/journal',timeout=.2): return
        except Exception: time.sleep(.1)
    raise RuntimeError('Server did not start')

def stop():
    global backend
    if backend is not None:
        backend.send_signal(signal.SIGCONT); backend.terminate(); backend.wait(timeout=10);backend=None

def launch():
    run('shell','am','start','-n',APP+'/.MainActivity');time.sleep(1)

def journal():
    with urllib.request.urlopen('http://127.0.0.1:8790/control/journal',timeout=5) as response:
        return json.loads(response.read())

def top():
    ui.top()

def count():
    with urllib.request.urlopen('http://127.0.0.1:8790/control/count',timeout=5) as response:
        return json.loads(response.read())['orders']

def wait_text(text, seconds=90):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if ui.find(lambda n: text in n.get('text','')) is not None: return
        time.sleep(.5)
    capture('timeout-'+re.sub('[^a-zA-Z0-9]','-',text))
    raise AssertionError('Timed out waiting for '+text)

def wait_wire(part):
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        if any(part in row for row in journal()): return
        time.sleep(.1)
    raise AssertionError('No wire event '+part)

def operation():
    node=ui.find(lambda n: n.get('text','').startswith('Saved operation: '))
    match=re.search(r'Saved operation: ([0-9a-f-]{36})', '' if node is None else node.get('text',''))
    assert match, 'No saved operation visible'
    return match.group(1)

def await_rejection(previous_operation):
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        found=ui.find(lambda n: n.get('text','')=='Outcome: Rejected' or
            n.get('text','').startswith('Action unavailable.'))
        if found is None:
            time.sleep(.5)
            continue
        if found.get('text')=='Outcome: Rejected': return
        # A failed admission must not silently become a new order. Retain the
        # failure, then exercise one explicit retry only from the unchanged
        # terminal operation with the original requested quantity still visible.
        capture('08-admission-action-unavailable',
            ('Action unavailable.','Outcome: Confirmed','Receipt: order-1',previous_operation),
            ('Submitted: Tea × 5',))
        (OUT/'wire-before-admission-retry.json').write_text(json.dumps(journal(),indent=2)+'\n')
        assert operation()==previous_operation
        field=ui.find(lambda n: n.get('class')=='android.widget.EditText')
        assert field is not None and field.get('text')=='5'
        submit=ui.find(lambda n: n.get('clickable')=='true' and
            any(c.get('text')=='Submit simulated order' for c in n.iter()))
        assert submit is not None and submit.get('enabled')=='true'
        assert count()==1
        events.append({'explicitAdmissionRetry':{
            'previous_operation':previous_operation,'quantity':5,'maximum_retries':1,
            'reason':'Visible action failure with unchanged confirmed operation',
            'cause':'Exception type unavailable; no source cause inferred'}})
        tap('Submit simulated order')
        wait_text('Outcome: Rejected',90)
        return
    capture('timeout-Outcome--Rejected')
    raise AssertionError('Timed out waiting for Outcome: Rejected')

def failure_witnesses():
    errors=[]
    # Read the controlled service while it is still alive; stop() follows this
    # function in finally. Diagnostic failures must not replace the UI failure.
    for name,read in [('failure-wire.json',journal),('failure-order-count.json',count)]:
        try:
            (OUT/name).write_text(json.dumps(read(),indent=2)+'\n')
        except Exception as error:
            errors.append({'artifact':name,'error':repr(error)})
    def diagnostic_adb(*args):
        return subprocess.run([str(ADB),'-s','emulator-5554',*args],
            capture_output=True,check=True,timeout=5).stdout
    # A system-owned dialog is itself evidence. Do not dismiss it to reach the app.
    try:
        content=read_ui_xml(diagnostic_adb,'/sdcard/chapter15-failure.xml',
            {APP,'android','com.android.systemui','com.android.permissioncontroller',
             'com.google.android.permissioncontroller'},attempts=2)
        (OUT/'failure-viewport.xml').write_text(content)
    except Exception as error:
        errors.append({'artifact':'failure-viewport.xml','error':repr(error)})
    for name,command in [
        ('failure-viewport.png',('exec-out','screencap','-p')),
        ('failure-logcat.txt',('logcat','-d','-t','1200')),
        ('failure-activities.txt',('shell','dumpsys','activity','activities')),
        ('failure-activity-top.txt',('shell','dumpsys','activity','top')),
    ]:
        try:
            (OUT/name).write_bytes(diagnostic_adb(*command))
        except Exception as error:
            errors.append({'artifact':name,'error':repr(error)})
    return errors

try:
    start()
    run('reverse','tcp:8790','tcp:8790')
    run('install','-r',str(SNAP_APK))
    run('shell','pm','clear',APP)
    launch();tap('Sign in A')
    capture('01-review-order',('Review order','Tea','Quantity','Submit simulated order'))
    control('lose-reply');tap('Submit simulated order');wait_wire('LOST_REPLY')
    control('offline');wait_text('Outcome: Unknown',15)
    original=operation()
    capture('02-unknown-after-accepted-write',('Outcome: Unknown','Submitted: Tea × 2',original),('Receipt:',))
    assert count()==1
    (OUT/'wire-before-server-crash.json').write_text(json.dumps(journal(),indent=2)+'\n')
    old_server_pid=backend.pid;backend.kill();backend.wait(timeout=5);backend=None
    old_app_pid=run('shell','pidof',APP).decode().strip()
    run('shell','input','keyevent','3')
    run('shell','run-as',APP,'kill','-9',old_app_pid)
    package=run('shell','dumpsys','package',APP).decode()
    assert 'stopped=false' in package
    (OUT/'package-after-abrupt-death.txt').write_text(package)
    run('reverse','--remove','tcp:8790')
    start();control('offline');assert count()==1
    run('reverse','tcp:8790','tcp:8790')
    launch();wait_text('Outcome: Unknown',15)
    new_app_pid=run('shell','pidof',APP).decode().strip()
    assert old_app_pid!=new_app_pid and old_server_pid!=backend.pid
    assert operation()==original
    capture('03-same-operation-after-both-restarts',('Outcome: Unknown',original),('Receipt:',))
    events.append({'abruptRestarts':{'oldAppPid':old_app_pid,'newAppPid':new_app_pid,
        'oldServerPid':old_server_pid,'newServerPid':backend.pid,'sameSqlitePath':True,'sameOperation':original}})
    control('online');control('expire-auth');tap('Check status')
    wait_text('Session expired.',90)
    capture('04-expired-session',('Session expired.','Sign in A'))
    control('renew-auth');tap('Sign in A');wait_text('Outcome: Confirmed',90)
    assert operation()==original and count()==1
    capture('05-recovered-original-receipt',('Outcome: Confirmed','Receipt: order-1',original))
    tap('Sign out');tap('Sign in B')
    capture('06-account-b-isolation',('Account B',),('Saved operation:','order-1',original))
    tap('Sign out');tap('Sign in A');wait_text('Outcome: Confirmed',15)
    assert operation()==original
    capture('07-account-a-retains-receipt',('Receipt: order-1',original))
    edit('5');tap('Submit simulated order');await_rejection(original)
    capture('08-recorded-business-rejection',('Outcome: Rejected','Only four packs available','Submitted: Tea × 5'))
    assert count()==1
    edit('2');control('lose-reply');tap('Submit simulated order');wait_wire('LOST_REPLY')
    control('offline');wait_text('Outcome: Unknown',15);second=operation()
    assert second!=original and count()==2
    control('expire-outcomes');control('online');tap('Check status');wait_text('Outcome: NeedsReview',90)
    capture('09-expired-outcome-preserved',('Outcome: NeedsReview',second,'support must reconcile'),('Receipt:',))
    assert ui.text('Submit simulated order') is not None
    tree=ET.fromstring(xml())
    submit=next(n for n in tree.iter('node') if n.get('text')=='Submit simulated order')
    # Compose may expose enabled on the clickable ancestor rather than the text node.
    buttons=[n for n in tree.iter('node') if n.get('clickable')=='true' and any(c.get('text')=='Submit simulated order' for c in n.iter())]
    assert buttons and buttons[0].get('enabled')=='false'
    assert count()==2
    (OUT/'wire-after-server-restart.json').write_text(json.dumps(journal(),indent=2)+'\n')
    (OUT/'jobs-final.txt').write_bytes(run('shell','dumpsys','jobscheduler'))
    (OUT/'package-final.txt').write_bytes(run('shell','dumpsys','package',APP))
    (OUT/'logcat.txt').write_bytes(run('logcat','-d','-t','1200'))
    apk=SNAP_APK;jar=OUT/'tested-server.jar'
    libs=OUT/'tested-server-libs'
    dependencies={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(libs.glob('*.jar'))}
    result={'server_distribution_jars':dependencies,'status':'PASS','events':events,'tested_apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
        'tested_server_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'final_order_count':count(),
        'limits':['API36 emulator/debug run-as SIGKILL; not low-memory or every-device evidence',
            'Server SIGKILL after observed durable commit, not power-loss storage testing',
            'No real payment or real FCM delivery; hint behavior has separate Android tests']}
    (OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'status':'PASS','output':str(OUT),'captures':len([e for e in events if 'screen' in e])}))
except Exception as error:
    diagnostics=failure_witnesses()
    (OUT/'failure.json').write_text(json.dumps({'status':'FAIL','error':str(error),
        'events':events,'diagnostic_errors':diagnostics},indent=2)+'\n')
    raise
finally:
    stop()
    for log in logs: log.close()
