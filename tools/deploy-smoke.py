#!/usr/bin/env python3
"""Device smoke tests. --load-fixture exercises the real confirmation and scoped bridge.

Requires debug APKs, adb and /sdcard/deploy-tests/libdeploy_fixture.so. Credentials
are read into memory only; artifacts contain tool results, never bearer tokens.
"""
import argparse
import json
import subprocess
import re
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='3972b041')
parser.add_argument('--output', required=True)
parser.add_argument('--load-fixture', action='store_true')
parser.add_argument('--isolation', action='store_true', help='Temporarily disable each app, then restore both')
options = parser.parse_args()


def adb(*args):
    return subprocess.check_output(['adb', '-s', options.serial, *args])


class Client:
    def __init__(self, package, prefs, key, port, remote):
        adb('forward', f'tcp:{port}', f'tcp:{remote}')
        self.port = port
        xml = adb('shell', 'run-as', package, 'cat', f'shared_prefs/{prefs}.xml')
        self.token = ET.fromstring(xml).find(f"string[@name='{key}']").text

    def rpc(self, method, params):
        body = json.dumps(dict(jsonrpc='2.0', id=1, method=method, params=params)).encode()
        request = urllib.request.Request(f'http://127.0.0.1:{self.port}/mcp', data=body,
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=145) as response:
            return json.load(response)

    def call(self, tool, **args):
        response = self.rpc('tools/call', dict(name=tool, arguments=args))
        if 'error' in response:
            return {'error': response['error']}
        result = response['result']
        text = result['content'][0]['text']
        try:
            value = json.loads(text)
        except json.JSONDecodeError:
            value = {'error': text}
        if result.get('isError') and 'error' not in value:
            value['error'] = text
        return value


posed = Client('dev.posedmcp', 'posedmcp', 'mcp_token', 18765, 8765)
deploy = Client('dev.deploy', 'deploy', 'token', 14177, 4177)
results = {}


def record(name, value, valid):
    results[name] = value
    Path(options.output).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    assert valid, f'{name} failed: {value}'
    print('PASS', name, flush=True)


fixture = '/sdcard/deploy-tests/libdeploy_fixture.so'
tools = deploy.rpc('tools/list', {})['result']['tools']
record('deploy_tools', tools, {t['name'] for t in tools} == {'deploy_status', 'deploy_analyze'})
properties = next(t for t in tools if t['name'] == 'deploy_analyze')['inputSchema']['properties']
record('schema', properties, {'name', 'address', 'max', 'bytes', 'mode'} <= properties.keys())
for endpoint, client in [('direct', deploy), ('aidl', posed)]:
    status = client.call('deploy_status')
    record(endpoint + '_status', status, 'disasm' in status.get('ops', []))
    resolved = client.call('deploy_analyze', path=fixture, op='resolve', name='deploy_fixture_answer')
    record(endpoint + '_resolve', resolved, resolved.get('found') is True)
    asm = client.call('deploy_analyze', path=fixture, op='disasm', name='deploy_fixture_answer', max=16)
    instructions = asm.get('instructions', [])
    record(endpoint + '_disasm', asm, any(i['mnemonic'] == 'ret' for i in instructions)
           and any('#0x2a' in i['operands'] or '#42' in i['operands'] for i in instructions))
    addressed = client.call('deploy_analyze', path=fixture, op='disasm', address=resolved['value'], bytes=8, max=2)
    record(endpoint + '_address', addressed, addressed.get('instructions') == instructions[:2])
    limited = client.call('deploy_analyze', path=fixture, op='disasm', name='deploy_fixture_answer', max=1)
    record(endpoint + '_limit', limited, limited.get('count') == 1 and limited.get('stopReason') == 'instruction_limit')
    libc = client.call('deploy_analyze', path='/sdcard/libc.so', op='disasm', name='strlen', max=8)
    record(endpoint + '_libc', libc, libc.get('count', 0) > 0)
    for label, arguments in [
        ('missing_symbol', dict(op='disasm', name='symbol_that_does_not_exist')),
        ('unmapped_address', dict(op='disasm', address='0xfffffffffffffff0')),
        ('misaligned', dict(op='disasm', address=hex(int(resolved['value'], 16) + 1))),
        ('ambiguous', dict(op='disasm', address=resolved['value'], name='deploy_fixture_answer')),
        ('bad_limit', dict(op='disasm', name='deploy_fixture_answer', max=513)),
        ('negative_limit', dict(op='disasm', name='deploy_fixture_answer', max=-1)),
        ('no_decompiler', dict(op='decompile')),
    ]:
        response = client.call('deploy_analyze', path=fixture, **arguments)
        record(endpoint + '_' + label, response, 'error' in response)

if options.load_fixture:
    status = posed.call('module_status')
    peers = [p for p in status.get('bridgePeers', []) if p.startswith('app:dev.deploy.fixture:')]
    record('fixture_peer', peers, len(peers) == 1)
    peer = peers[0]
    result = posed.call('deploy_load_native', package='dev.deploy.fixture', peer=peer, path=fixture,
        entry='deploy_fixture_probe', reason='Load our test library: constructor captures its process PID; entry returns that PID. No hooks or file changes.')
    record('runtime_load', result, result.get('loaded') is True
        and result.get('entryResult') == peer.rsplit(':', 1)[1]
        and result.get('pid') == int(peer.rsplit(':', 1)[1]) and result.get('uid', 0) != 0)
    again = posed.call('deploy_load_native', package='dev.deploy.fixture', peer=peer, path=fixture,
        entry='deploy_fixture_answer', reason='Reuse the same test library and call its int64_t(void) function returning 42.')
    record('runtime_reuse', again, again.get('alreadyLoaded') is True and again.get('entryResult') == '42'
        and again.get('handleId') == result.get('handleId'))
    missing = posed.call('deploy_load_native', package='dev.deploy.fixture', peer=peer, path=fixture,
        entry='deploy_fixture_absent', reason='Verify a missing entry is reported explicitly after the test library has been loaded; no function will be invoked.')
    record('runtime_missing_entry', missing, 'error' in missing and missing.get('loaded') is True)
    offline = posed.call('deploy_load_native', package='dev.deploy.fixture', peer='app:dev.deploy.fixture:0',
        path=fixture, reason='Verify an offline target is rejected before any code is loaded.')
    record('runtime_offline', offline, 'error' in offline)

if options.isolation:
    def available(port):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=3) as response:
                return json.load(response).get('status') == 'ok'
        except Exception:
            return False

    original = {}
    for package in ['dev.deploy', 'dev.posedmcp']:
        dump = adb('shell', 'dumpsys', 'package', package).decode()
        state = re.search(r'User 0:.*?enabled=(\d+)', dump).group(1)
        assert state in ['0', '1'], f'{package} must initially be enabled'
        original[package] = 'default-state' if state == '0' else 'enable'

    def restore(package):
        adb('shell', 'pm', original[package], '--user', '0', package)

    def start(package, service):
        # Launch clears the stopped-package state after disable/enable on this ROM;
        # starting an unexported service directly can otherwise report "not found".
        activity = '.DeployActivity' if package == 'dev.deploy' else '.MainActivity'
        adb('shell', 'am', 'start', '--user', '0', '-n', package + '/' + activity)
        adb('shell', 'am', 'start-foreground-service', '--user', '0', '-n', package + '/' + service)

    try:
        adb('shell', 'pm', 'disable-user', '--user', '0', 'dev.deploy')
        time.sleep(1)
        missing = posed.call('deploy_status')
        record('isolated_posed_missing_deploy', missing, 'error' in missing)
        names = [t['name'] for t in posed.rpc('tools/list', {})['result']['tools']]
        record('isolated_posed_tools', names, 'root_shell_exec' in names and available(18765))
        device = posed.call('device_info')
        record('isolated_posed_device_info', {'ok': 'error' not in device}, 'error' not in device)
        record('isolated_deploy_endpoint_off', {'available': available(14177)}, not available(14177))
        restore('dev.deploy')
        start('dev.deploy', '.DeployMcpService')
        adb('shell', 'pm', 'disable-user', '--user', '0', 'dev.posedmcp')
        time.sleep(1)
        record('isolated_posed_endpoint_off', {'available': available(18765)}, not available(18765))
        names = [t['name'] for t in deploy.rpc('tools/list', {})['result']['tools']]
        record('isolated_deploy_tools', names, set(names) == {'deploy_status', 'deploy_analyze'})
        value = deploy.call('deploy_analyze', path=fixture, op='disasm', name='deploy_fixture_answer')
        record('isolated_deploy_disasm', value, value.get('count', 0) > 0)
    finally:
        restore('dev.deploy')
        restore('dev.posedmcp')
        start('dev.deploy', '.DeployMcpService')
        start('dev.posedmcp', '.McpService')
    time.sleep(1)
    status = posed.call('deploy_status')
    record('restored_aidl', status, status.get('version') == '0.3.0' and available(18765) and available(14177))
