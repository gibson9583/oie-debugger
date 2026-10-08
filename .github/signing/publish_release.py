#!/usr/bin/env python3
"""Create one draft, upload both assets, and publish only that returned release ID."""

import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote


BODY = """The ZIP contains the complete JavaScript Debugger extension for OIE 4.6.0,
including its Web Administrator interface (plugin API 4.7 or newer).

Both plugin JARs are signed with SSL.com eSigner and verified before publication.
SHA256SUMS covers the complete signed ZIP, including its browser assets.
Installing or updating the extension requires an engine restart.
"""


def api(method, endpoint, *, payload=None, file=None, content_type=None):
    command = ['gh', 'api', '--method', method, endpoint]
    if file is not None:
        command += ['--input', str(file), '--header', 'Content-Type: ' + content_type]
    elif payload is not None:
        command += ['--input', '-']
    response = subprocess.check_output(command, input=json.dumps(payload) if payload is not None else None,
                                       text=True, timeout=300 if file is not None else 60)
    result = json.loads(response)
    if not isinstance(result, dict):
        raise ValueError('Unexpected GitHub API response')
    return result


def publish(directory=Path('out'), environ=None):
    environ = os.environ if environ is None else environ
    repository, tag = environ['GITHUB_REPOSITORY'], environ['GITHUB_REF_NAME']
    source_sha = environ['GITHUB_SHA']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository')
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', tag):
        raise ValueError('Invalid release tag')
    if not re.fullmatch(r'[0-9a-fA-F]{40}', source_sha):
        raise ValueError('Invalid workflow source commit')
    source_sha = source_sha.lower()
    assets = [(directory / f'oie-debugger-{tag[1:]}.zip', 'application/zip'),
              (directory / 'SHA256SUMS', 'text/plain')]
    for path, _ in assets:
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f'Missing or empty release asset: {path.name}')

    commit = api('GET', f'repos/{repository}/commits/{quote(tag, safe="")}')
    if commit.get('sha') != source_sha:
        raise ValueError('Release tag no longer points to the commit validated by this workflow')

    # POST is create-only: never adopt/update a release found by its tag. A
    # conflict or an ambiguous response leaves existing releases untouched.
    created = api('POST', f'repos/{repository}/releases', payload={
        'tag_name': tag, 'name': tag, 'draft': True,
        'prerelease': '-' in tag, 'body': BODY + f'\nSource commit: `{source_sha}`.\n',
        'target_commitish': source_sha,
    })
    release_id = created.get('id')
    if (type(release_id) is not int or release_id <= 0
            or created.get('tag_name') != tag or created.get('draft') is not True):
        raise ValueError('Unexpected draft creation response; no assets were uploaded')
    print(f'Created draft release ID {release_id}', flush=True)

    for path, content_type in assets:
        # gh authenticates uploads.github.com with GH_TOKEN. Derive this URL
        # from the validated repository and owned ID, not a response URL.
        endpoint = (f'https://uploads.github.com/repos/{repository}/releases/{release_id}/assets'
                    f'?name={quote(path.name, safe="")}')
        uploaded = api('POST', endpoint, file=path, content_type=content_type)
        if (uploaded.get('state') != 'uploaded' or uploaded.get('name') != path.name
                or uploaded.get('size') != path.stat().st_size):
            raise ValueError(f'Unexpected upload response for {path.name}; draft remains unpublished')

    result = api('PATCH', f'repos/{repository}/releases/{release_id}', payload={'draft': False})
    if (result.get('id') != release_id or result.get('tag_name') != tag
            or result.get('draft') is not False):
        raise ValueError(f'Publication response was not confirmed; inspect release ID {release_id} before retrying')
    print(f'Published release ID {release_id}', flush=True)


if __name__ == '__main__':
    try:
        publish()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None
