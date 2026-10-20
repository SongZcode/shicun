"""Convert selected ISC Lucide SVG assets into Android vector drawables."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SOURCE = Path('node_modules/lucide-static/icons')
DESTINATION = Path('app/src/main/res/drawable')
NAMES = ['layers-2', 'video', 'headphones', 'images', 'message-circle', 'download',
         'list-todo', 'folder-open', 'clipboard', 'x', 'chevron-right',
         'chevron-down', 'arrow-right', 'settings-2', 'search',
         'clock-3', 'rotate-ccw', 'plus', 'file-image', 'file-video', 'music-2', 'play', 'pause', 'trash-2']
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)

def data(node):
    tag = node.tag.rsplit('}', 1)[-1]
    a = node.attrib
    if tag == 'path':
        return a['d']
    if tag == 'line':
        return f"M{a['x1']},{a['y1']} L{a['x2']},{a['y2']}"
    if tag in ('polyline', 'polygon'):
        values = re.findall(r'-?\d+(?:\.\d+)?', a['points'])
        points = [','.join(values[i:i+2]) for i in range(0, len(values), 2)]
        return 'M' + ' L'.join(points) + (' Z' if tag == 'polygon' else '')
    if tag == 'circle':
        x, y, r = float(a['cx']), float(a['cy']), float(a['r'])
        return f'M{x-r},{y} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0'
    if tag == 'rect':
        x, y = float(a.get('x', 0)), float(a.get('y', 0))
        w, h = float(a['width']), float(a['height'])
        r = min(float(a.get('rx', 0)), w/2, h/2)
        return (f'M{x+r},{y} H{x+w-r} Q{x+w},{y} {x+w},{y+r} V{y+h-r} '
                f'Q{x+w},{y+h} {x+w-r},{y+h} H{x+r} Q{x},{y+h} {x},{y+h-r} '
                f'V{y+r} Q{x},{y} {x+r},{y} Z')
    raise ValueError(f'Unsupported Lucide geometry: {tag}')

DESTINATION.mkdir(parents=True, exist_ok=True)
for name in NAMES:
    svg = ET.parse(SOURCE / (name + '.svg')).getroot()
    vector = ET.Element('vector', {f'{{{ANDROID}}}width': '24dp', f'{{{ANDROID}}}height': '24dp',
                                  f'{{{ANDROID}}}viewportWidth': '24', f'{{{ANDROID}}}viewportHeight': '24'})
    for node in svg:
        ET.SubElement(vector, 'path', {f'{{{ANDROID}}}pathData': data(node),
            f'{{{ANDROID}}}fillColor': '@android:color/transparent',
            f'{{{ANDROID}}}strokeColor': '#000000', f'{{{ANDROID}}}strokeWidth': '1.8',
            f'{{{ANDROID}}}strokeLineCap': 'round', f'{{{ANDROID}}}strokeLineJoin': 'round'})
    ET.indent(vector, space='    ')
    ET.ElementTree(vector).write(DESTINATION / ('ic_' + name.replace('-', '_') + '.xml'), encoding='utf-8', xml_declaration=True)
print(f'Generated {len(NAMES)} Lucide drawables')

launcher = ET.Element('vector', {f'{{{ANDROID}}}width': '108dp', f'{{{ANDROID}}}height': '108dp',
                               f'{{{ANDROID}}}viewportWidth': '108', f'{{{ANDROID}}}viewportHeight': '108'})
group = ET.SubElement(launcher, 'group', {f'{{{ANDROID}}}translateX': '30', f'{{{ANDROID}}}translateY': '30',
                                       f'{{{ANDROID}}}scaleX': '2', f'{{{ANDROID}}}scaleY': '2'})
for node in ET.parse(DESTINATION / 'ic_layers_2.xml').getroot():
    node.set(f'{{{ANDROID}}}strokeColor', '#FFFFFF')
    group.append(node)
ET.indent(launcher, space='    ')
ET.ElementTree(launcher).write(DESTINATION / 'ic_launcher_foreground.xml', encoding='utf-8', xml_declaration=True)
