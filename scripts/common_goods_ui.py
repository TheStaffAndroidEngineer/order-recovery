"""Observe a scrollable chapter screen without conflating separate viewports.

The driver only opens Lab controls and scrolls. Each retained XML/PNG pair is an
actual viewport; callers keep their existing assertions over the observed set.
"""
import re
import time
import xml.etree.ElementTree as ET

class CommonGoodsUi:
    def __init__(self, read_xml, adb, output):
        self.read_xml, self.adb, self.output = read_xml, adb, output
        self.last_root = None

    def root(self):
        value = self.read_xml()
        self.last_root = ET.fromstring(value)
        return self.last_root, value

    def bounds(self, node):
        return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def swipe(self, forward, root=None):
        if root is None:
            root, _ = self.root()
        region = next((n for n in root.iter('node') if n.get('scrollable') == 'true'), None)
        if region is None:
            return False
        x1, y1, x2, y2 = self.bounds(region)
        top, bottom = y1 + (y2-y1)//5, y1 + (y2-y1)*4//5
        self.adb('shell', 'input', 'swipe', str((x1+x2)//2), str(bottom if forward else top),
                 str((x1+x2)//2), str(top if forward else bottom), '180')
        return True

    def top(self, snapshot=None):
        snapshot = self.root() if snapshot is None else snapshot
        previous = None
        for _ in range(10):
            root, value = snapshot
            if value == previous:
                break
            previous = value
            if not self.swipe(False, root):
                break
            snapshot = self.root()
        return snapshot

    def click_node(self, node):
        parents = {child: parent for parent in self.last_root.iter() for child in parent}
        target = node
        while target in parents and target.get('clickable') != 'true':
            target = parents[target]
        values = self.bounds(target)
        if len(values) != 4 or target.get('enabled', 'true') != 'true':
            return False
        x1, y1, x2, y2 = values
        if x2 <= x1 or y2 <= y1:
            return False
        self.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.3)
        return True

    def expand_visible(self, snapshot=None):
        snapshot = self.root() if snapshot is None else snapshot
        for _ in range(4):
            root, _ = snapshot
            closed = next((n for n in root.iter('node') if n.get('text') in ('Lab controls', 'Cart details')), None)
            if closed is None or not self.click_node(closed):
                break
            snapshot = self.root()
        return snapshot

    def find(self, predicate):
        snapshot = self.root()
        root, _ = snapshot
        found = next((n for n in root.iter('node') if predicate(n)), None)
        if found is not None:
            return found
        snapshot = self.top(snapshot)
        previous = None
        for _ in range(14):
            snapshot = self.expand_visible(snapshot)
            root, value = snapshot
            found = next((n for n in root.iter('node') if predicate(n)), None)
            if found is not None:
                return found
            if value == previous:
                break
            previous = value
            if not self.swipe(True, root):
                break
            snapshot = self.root()
        return None

    def text(self, text):
        return self.find(lambda n: n.get('text') == text or n.get('content-desc') == text)

    def tap(self, text):
        for _ in range(3):
            node = self.text(text)
            if node is not None and self.click_node(node):
                return
            time.sleep(.3)
        raise AssertionError('Enabled visible control not found: ' + text)

    def observe(self, name):
        snapshot = self.top()
        observations = []
        previous = None
        for index in range(14):
            snapshot = self.expand_visible(snapshot)
            root, value = snapshot
            if value == previous:
                break
            label = name if index == 0 else f'{name}-viewport-{index:02}'
            (self.output / f'{label}.xml').write_text(value)
            (self.output / f'{label}.png').write_bytes(self.adb('exec-out', 'screencap', '-p'))
            observations.append((label, value))
            previous = value
            if not self.swipe(True, root):
                break
            snapshot = self.root()
        return observations
