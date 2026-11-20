"""Publish the Markdown pages in this folder to Confluence Cloud (creates each page, or updates it if the title exists).

The API token is read from the environment only; it is never written to a file or printed.

    $env:CONFLUENCE_BASE_URL = "https://<your-site>.atlassian.net"
    $env:CONFLUENCE_EMAIL = "<the Atlassian account e-mail>"
    $env:CONFLUENCE_API_TOKEN = "<token from id.atlassian.com/manage-profile/security/api-tokens>"
    $env:CONFLUENCE_SPACE_KEY = "<space key, e.g. SW>"
    $env:CONFLUENCE_PARENT_ID = "<optional: id of the page to publish under>"
    python publish.py            # publish the pages listed in PAGES
    python publish.py --dry-run  # write the converted pages to out/ and send nothing

Confluence records the token's owner as the page creator; each page also opens with a date and author line.
"""

import html
import os
import re
import sys
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).parent
PAGES = [
    HERE / 'docs' / 'confluence' / '01-solution-design.md',
    HERE / 'docs' / 'confluence' / '02-capabilities-and-path-to-production.md',
]


def inline(text: str) -> str:
    """Bold, italics, inline code and links, after escaping; the only inline Markdown these pages use.
    Code spans are cut out first, so a '*' inside `GRAPH_*` can't start italics across two spans."""
    out = []
    for i, part in enumerate(re.split(r'`([^`]+)`', text)):
        part = html.escape(part, quote=False)
        if i % 2:  # inside backticks
            out.append(f'<code>{part}</code>')
            continue
        part = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', part)
        part = re.sub(r'\*([^*]+)\*', r'<em>\1</em>', part)
        out.append(re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', part))
    return ''.join(out)


def cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip('|').split('|')]


def to_storage(markdown: str) -> tuple[str, str]:
    """Markdown -> (title, Confluence storage-format XHTML). The first '# ' heading becomes the page title."""
    lines = markdown.splitlines()
    out: list[str] = []
    title = ''
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):  # code block -> Confluence code macro
            body = []
            i += 1
            while i < len(lines) and not lines[i].startswith('```'):
                body.append(lines[i])
                i += 1
            code = '\n'.join(body).replace(']]>', ']]]]><![CDATA[>')
            out.append('<ac:structured-macro ac:name="code"><ac:plain-text-body><![CDATA['
                       + code + ']]></ac:plain-text-body></ac:structured-macro>')
        elif m := re.match(r'(#{1,6}) (.*)', line):
            level = len(m.group(1))
            if level == 1 and not title:
                title = m.group(2).strip()
            else:
                out.append(f'<h{level - 1 or 1}>{inline(m.group(2))}</h{level - 1 or 1}>')
        elif line.startswith('|'):  # table: header, separator, rows
            rows = []
            while i < len(lines) and lines[i].startswith('|'):
                rows.append(cells(lines[i]))
                i += 1
            i -= 1
            head, body = rows[0], [r for r in rows[2:]]
            out.append('<table><tbody><tr>' + ''.join(f'<th>{inline(c)}</th>' for c in head) + '</tr>'
                       + ''.join('<tr>' + ''.join(f'<td>{inline(c).replace("&lt;br&gt;", "<br />")}</td>'
                                                  for c in r) + '</tr>' for r in body)
                       + '</tbody></table>')
        elif line.startswith('> '):  # quote -> info panel
            body = []
            while i < len(lines) and lines[i].startswith('>'):
                body.append(lines[i][1:].strip())
                i += 1
            i -= 1
            out.append('<ac:structured-macro ac:name="info"><ac:rich-text-body><p>'
                       + inline(' '.join(body)) + '</p></ac:rich-text-body></ac:structured-macro>')
        elif re.match(r'- \[[ x]\] ', line):  # checklist -> Confluence task list
            tasks = []
            while i < len(lines) and (re.match(r'- \[[ x]\] ', lines[i]) or lines[i].startswith('      ')):
                if lines[i].startswith('      '):
                    tasks[-1] = (tasks[-1][0], tasks[-1][1] + ' ' + lines[i].strip())
                else:
                    tasks.append(('complete' if lines[i][3] == 'x' else 'incomplete', lines[i][6:]))
                i += 1
            i -= 1
            out.append('<ac:task-list>' + ''.join(
                f'<ac:task><ac:task-status>{s}</ac:task-status><ac:task-body>{inline(t)}</ac:task-body></ac:task>'
                for s, t in tasks) + '</ac:task-list>')
        elif re.match(r'(- |\d+\. )', line):  # bullet or numbered list (one level; wrapped lines join the item)
            ordered = bool(re.match(r'\d+\. ', line))
            items: list[str] = []
            while i < len(lines) and (re.match(r'(- |\d+\. )', lines[i]) or (items and lines[i].startswith('  ')
                                                                            and lines[i].strip())):
                if re.match(r'(- |\d+\. )', lines[i]):
                    items.append(re.sub(r'^(- |\d+\. )', '', lines[i]))
                else:
                    items[-1] += ' ' + lines[i].strip()
                i += 1
            i -= 1
            tag = 'ol' if ordered else 'ul'
            out.append(f'<{tag}>' + ''.join(f'<li>{inline(t)}</li>' for t in items) + f'</{tag}>')
        elif m := re.fullmatch(r'(\w{3} \d{1,2}, \d{4}) · (.+)', line.strip()):  # byline: date chip and author
            when = datetime.strptime(m.group(1), '%b %d, %Y').date().isoformat()
            out.append(f'<p><time datetime="{when}" /> · <strong>{inline(m.group(2))}</strong></p>')
        elif line.strip() == '---':
            out.append('<hr />')
        elif line.strip():  # paragraph: consecutive text lines
            body = []
            while i < len(lines) and lines[i].strip() and not re.match(r'(#|\||>|```|- |\d+\. |---)', lines[i]):
                body.append(lines[i].strip())
                i += 1
            i -= 1
            out.append('<p>' + inline(' '.join(body)) + '</p>')
        i += 1
    return title, '\n'.join(out)


def publish(title: str, body: str) -> str:
    import requests  # only needed when actually publishing

    base = os.environ['CONFLUENCE_BASE_URL'].rstrip('/') + '/wiki/rest/api/content'
    auth = (os.environ['CONFLUENCE_EMAIL'], os.environ['CONFLUENCE_API_TOKEN'])
    space = os.environ['CONFLUENCE_SPACE_KEY']
    parent = os.environ.get('CONFLUENCE_PARENT_ID')
    found = requests.get(base, auth=auth, timeout=30,
                         params={'spaceKey': space, 'title': title, 'expand': 'version'})
    found.raise_for_status()
    page = {'type': 'page', 'title': title, 'space': {'key': space},
            'body': {'storage': {'value': body, 'representation': 'storage'}}}
    if parent:
        page['ancestors'] = [{'id': parent}]
    existing = found.json().get('results', [])
    if existing:
        page['version'] = {'number': existing[0]['version']['number'] + 1}
        r = requests.put(f"{base}/{existing[0]['id']}", json=page, auth=auth, timeout=30)
    else:
        r = requests.post(base, json=page, auth=auth, timeout=30)
    if not r.ok:
        sys.exit(f'{title}: Confluence said {r.status_code}: {r.text[:500]}')
    links = r.json()['_links']
    return ('Updated ' if existing else 'Created ') + links['base'] + links['webui']


if __name__ == '__main__':
    dry = '--dry-run' in sys.argv
    if not dry:
        missing = [k for k in ('CONFLUENCE_BASE_URL', 'CONFLUENCE_EMAIL', 'CONFLUENCE_API_TOKEN',
                               'CONFLUENCE_SPACE_KEY') if not os.environ.get(k)]
        if missing:
            sys.exit('Set these first: ' + ', '.join(missing))
    for path in PAGES:
        title, body = to_storage(path.read_text(encoding='utf-8'))
        if dry:
            target = HERE / 'out' / (path.stem + '.xhtml')
            target.parent.mkdir(exist_ok=True)
            target.write_text(body, encoding='utf-8')
            print(f'{title}: {len(body)} characters -> {target}')
        else:
            print(publish(title, body))
