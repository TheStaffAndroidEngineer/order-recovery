"""Read fresh Android UI evidence; never consume a prior dump after a null root."""
import time
import re
import xml.etree.ElementTree as ET


def read_ui_xml(adb, remote_path, expected_packages, attempts=5):
    if not re.fullmatch(r"/sdcard/[A-Za-z0-9._-]+\.xml", remote_path):
        raise ValueError("Unexpected UI evidence path")
    failure = None
    for _ in range(attempts):
        try:
            adb('shell', 'rm', '-f', remote_path)
            output = adb('shell', 'uiautomator dump ' + remote_path + ' 2>&1')
            if isinstance(output, bytes):
                output = output.decode()
            if 'ERROR' in output.upper():
                raise RuntimeError('UI dump rejected: ' + output.strip())
            content = adb('exec-out', 'cat', remote_path)
            if isinstance(content, bytes):
                content = content.decode()
            root = ET.fromstring(content)
            if root.tag != 'hierarchy':
                raise RuntimeError('Unexpected UI XML root: ' + root.tag)
            packages = {node.get('package') for node in root.iter('node') if node.get('package')}
            if not packages.intersection(expected_packages):
                raise RuntimeError('Wrong visible UI packages: ' + repr(sorted(packages)))
            if not any(node.get('bounds') for node in root.iter('node')):
                raise RuntimeError('No bounded UI nodes in fresh hierarchy')
            return content
        except Exception as error:
            failure = error
            time.sleep(.3)
    raise RuntimeError('Could not obtain fresh UI XML for ' + repr(sorted(expected_packages))) from failure
