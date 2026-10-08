"""Exercise the release preflight without contacting GitHub or a signing service."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


class ReleasePreflightTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workflow = (Path(__file__).parent.parent / 'workflows/release.yml').read_text()
        cls.workflow = workflow
        # Execute the exact script shipped in the workflow, including its error handling.
        cls.script = textwrap.dedent(workflow.split("python3 - <<'PYTHON'\n", 1)[1]
                                     .split('\n          PYTHON', 1)[0])

    def run_preflight(self, *, pages=None, failure=None, tag='v1.0.0', raw=None):
        response = json.dumps([[]] if pages is None else pages) if raw is None else raw
        with patch.dict(os.environ, {
            'GITHUB_REF_NAME': tag, 'GITHUB_REPOSITORY': 'fixture/debugger',
            'GH_TOKEN': 'fixture-token',
        }), patch('subprocess.check_output', return_value=response,
                  side_effect=failure) as command:
            exec(self.script, {})
            return command.call_args

    def test_absent_release_allows_signing(self):
        call = self.run_preflight(pages=[[{'tag_name': 'v0.9.0'}], []])
        self.assertEqual(call.kwargs['timeout'], 60)
        self.assertEqual(call.args[0], ['gh', 'api', '--paginate', '--slurp',
                                      'repos/fixture/debugger/releases?per_page=100'])

    def test_existing_published_release_blocks_signing(self):
        with self.assertRaisesRegex(SystemExit, 'A release already exists'):
            self.run_preflight(pages=[[{'tag_name': 'v1.0.0', 'draft': False}]])

    def test_existing_draft_on_later_page_blocks_signing(self):
        with self.assertRaisesRegex(SystemExit, 'A release already exists'):
            self.run_preflight(pages=[[{'tag_name': 'v0.9.0'}],
                                      [{'tag_name': 'v1.0.0', 'draft': True}]])

    def test_api_errors_and_timeout_are_not_mistaken_for_absence(self):
        for failure in (subprocess.CalledProcessError(1, ['gh']),
                        subprocess.TimeoutExpired(['gh'], 60)):
            with self.subTest(failure=type(failure)), self.assertRaises(type(failure)):
                self.run_preflight(failure=failure)

    def test_malformed_api_response_blocks_signing(self):
        with self.assertRaises(json.JSONDecodeError):
            self.run_preflight(raw='invalid response')

    def test_tag_matching_is_exact_and_never_shell_interpolated(self):
        tag = 'v1.0.0/extra?query#fragment'
        call = self.run_preflight(pages=[[{'tag_name': 'v1.0.0'}]], tag=tag)
        self.assertNotIn(tag, call.args[0])
        with self.assertRaisesRegex(SystemExit, 'A release already exists'):
            self.run_preflight(pages=[[{'tag_name': tag}]], tag=tag)

    def test_debugger_signing_selects_both_jars_only(self):
        from io import BytesIO
        import zipfile
        import sign_release

        config = json.loads((Path(__file__).parent / 'config.json').read_text())
        self.assertEqual(config['bundle'], 'package/target/oie-debugger-*.zip')
        expected = ['oie-debugger/libs/oie-debugger-shared-1.0.0.jar',
                    'oie-debugger/libs/oie-debugger-server-1.0.0.jar']
        data = BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            for name in expected + ['oie-debugger/webadmin/web/plugin.js',
                                    'oie-debugger/plugin.xml', 'oie-debugger/libs/unrelated.jar']:
                archive.writestr(name, 'fixture')
        with zipfile.ZipFile(data) as archive:
            self.assertEqual(sign_release.select_jars(archive, config['jars']), expected)


class ReleasePublicationTests(unittest.TestCase):
    def setUp(self):
        import publish_release
        self.publisher = publish_release
        temp = tempfile.TemporaryDirectory(prefix='debugger-release-test-')
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.environment = {'GITHUB_REPOSITORY': 'fixture/debugger', 'GITHUB_REF_NAME': 'v1.0.0',
                            'GITHUB_SHA': 'a' * 40}
        for name in ('oie-debugger-1.0.0.zip', 'SHA256SUMS'):
            (self.directory / name).write_bytes(b'fixture asset bytes')
        self.existing = {'id': 99, 'tag_name': 'v1.0.0', 'draft': False,
                         'body': 'existing release', 'assets': {'SHA256SUMS': 'existing checksum'}}
        self.owned = {'id': 101, 'tag_name': 'v1.0.0', 'draft': True, 'assets': {}}
        self.calls = []

    def run_publication(self, *, fail_at=None, responses=None):
        def request(command, **kwargs):
            self.calls.append(command)
            index = len(self.calls) - 1
            if index == fail_at:
                raise subprocess.CalledProcessError(1, command)
            method, endpoint = command[3:5]
            if responses and index in responses:
                return json.dumps(responses[index])
            if index == 0:
                self.assertEqual((method, endpoint), ('GET', 'repos/fixture/debugger/commits/v1.0.0'))
                self.assertNotIn('--input', command, 'GET must not send a JSON request body')
                self.assertIsNone(kwargs['input'])
                return json.dumps({'sha': self.environment['GITHUB_SHA']})
            if index == 1:
                self.assertEqual((method, endpoint), ('POST', 'repos/fixture/debugger/releases'))
                body = json.loads(kwargs['input'])
                self.assertTrue(body['draft'])
                self.assertEqual(body['tag_name'], self.environment['GITHUB_REF_NAME'])
                self.assertEqual(body['target_commitish'], self.environment['GITHUB_SHA'])
                self.assertIn('Source commit: `' + self.environment['GITHUB_SHA'] + '`.', body['body'])
                return json.dumps(self.owned)
            if method == 'POST':
                self.assertTrue(endpoint.startswith('https://uploads.github.com/repos/fixture/debugger/releases/101/assets?name='))
                path = Path(command[command.index('--input') + 1])
                self.assertIsNone(kwargs['input'], 'gh must read the file as raw bytes')
                self.owned['assets'][path.name] = path.read_bytes().hex()
                return json.dumps({'name': path.name, 'state': 'uploaded', 'size': path.stat().st_size})
            self.assertEqual((method, endpoint), ('PATCH', 'repos/fixture/debugger/releases/101'))
            self.assertEqual(json.loads(kwargs['input']), {'draft': False})
            self.assertEqual(set(self.owned['assets']), {'oie-debugger-1.0.0.zip', 'SHA256SUMS'})
            self.owned['draft'] = False
            return json.dumps(self.owned)

        with patch.object(self.publisher.subprocess, 'check_output', side_effect=request):
            self.publisher.publish(self.directory, self.environment)

    def test_concurrent_same_tag_release_is_never_adopted_or_mutated(self):
        original = json.dumps(self.existing, sort_keys=True)
        # Model a release arriving after preflight, with an API that permits a
        # second draft for that tag. Every mutation must target the new ID.
        self.run_publication()
        self.assertEqual(json.dumps(self.existing, sort_keys=True), original)
        self.assertFalse(self.owned['draft'])
        self.assertEqual([call[3] for call in self.calls], ['GET', 'POST', 'POST', 'POST', 'PATCH'])

    def test_create_conflict_stops_without_lookup_or_mutation(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_publication(fail_at=1)
        self.assertEqual(len(self.calls), 2)
        self.assertTrue(self.owned['draft'])

    def test_ambiguous_creation_response_stops_before_upload(self):
        for response in (None, {}, {'id': True}, {'id': -1},
                         {**self.owned, 'id': '101'}, {**self.owned, 'draft': False},
                         {**self.owned, 'tag_name': 'v0.9.0'}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                self.calls.clear()
                self.run_publication(responses={1: response})
            self.assertEqual(len(self.calls), 2)

    def test_partial_upload_failure_leaves_draft_unpublished(self):
        for fail_at in (2, 3):
            with self.subTest(fail_at=fail_at), self.assertRaises(subprocess.CalledProcessError):
                self.calls.clear()
                self.owned['assets'].clear()
                self.run_publication(fail_at=fail_at)
            self.assertTrue(self.owned['draft'])
            self.assertNotIn('PATCH', [call[3] for call in self.calls])

    def test_success_exit_with_unconfirmed_asset_does_not_publish(self):
        for response in ({}, {'name': 'SHA256SUMS', 'state': 'uploaded', 'size': 19},
                         {'name': 'oie-debugger-1.0.0.zip', 'state': 'new', 'size': 19},
                         {'name': 'oie-debugger-1.0.0.zip', 'state': 'uploaded', 'size': 0}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                self.calls.clear()
                self.run_publication(responses={2: response})
            self.assertTrue(self.owned['draft'])
            self.assertEqual(len(self.calls), 3)

    def test_publication_failure_never_retries_or_falls_back_to_tag(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_publication(fail_at=4)
        self.assertEqual(len(self.calls), 5)
        self.assertEqual(self.calls[-1][4], 'repos/fixture/debugger/releases/101')

    def test_unconfirmed_publication_response_fails(self):
        with self.assertRaisesRegex(ValueError, 'Publication response was not confirmed'):
            self.run_publication(responses={4: {**self.owned, 'id': 99, 'draft': False}})
        self.assertEqual(len(self.calls), 5)

    def test_moved_tag_stops_before_draft_creation(self):
        with self.assertRaisesRegex(ValueError, 'tag no longer points'):
            self.run_publication(responses={0: {'sha': 'b' * 40}})
        self.assertEqual([call[3] for call in self.calls], ['GET'])

    def test_deleted_tag_stops_before_draft_creation(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_publication(fail_at=0)
        self.assertEqual([call[3] for call in self.calls], ['GET'])

    def test_invalid_source_commit_stops_before_api_requests(self):
        self.environment['GITHUB_SHA'] = 'main'
        with self.assertRaisesRegex(ValueError, 'Invalid workflow source commit'):
            self.run_publication()
        self.assertEqual(self.calls, [])

    def test_missing_asset_fails_before_creating_a_draft(self):
        (self.directory / 'SHA256SUMS').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing or empty release asset'):
            self.run_publication()
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
