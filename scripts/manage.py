#!/usr/bin/env python3
"""Project-owned local processes only. No global Maven, Docker, or PostgreSQL needed."""
import json
import os
from pathlib import Path
import secrets
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
JAR = ROOT / 'target/voucher-issuance-service-1.0.0-SNAPSHOT.jar'
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'


def command(*args):
    subprocess.run(list(args), cwd=ROOT, check=True)


def setup():
    version = subprocess.run([JAVA, '-version'], check=True, capture_output=True, text=True)
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    if not match or int(match.group(1)) < 17:
        raise RuntimeError('JDK 17 or newer is required; select Java 17 in your shell and IntelliJ.')
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    config_file = LOCAL / 'settings.json'
    if config_file.exists():
        settings = json.loads(config_file.read_text())
    else:
        settings = {'port': int(os.environ.get('PORT', '8080')), 'dbPort': int(os.environ.get('DB_PORT', '55432')),
                    'adminUsername': 'admin', 'adminPassword': secrets.token_urlsafe(24)}
        config_file.write_text(json.dumps(settings, indent=2) + '\n')
        config_file.chmod(0o600)
        properties = LOCAL / 'application-local.properties'
        properties.write_text(
            f"spring.datasource.url=jdbc:postgresql://127.0.0.1:{settings['dbPort']}/vouchers?reWriteBatchedInserts=true\n"
            'spring.datasource.username=postgres\nspring.datasource.password=postgres\n'
            f"voucher.admin.username={settings['adminUsername']}\n"
            f"voucher.admin.password={settings['adminPassword']}\n")
        properties.chmod(0o600)
        private_http = ROOT / 'http-client.private.env.json'
        if not private_http.exists():
            private_http.write_text(json.dumps({'local': {'adminPassword': settings['adminPassword']}}, indent=2) + '\n')
            private_http.chmod(0o600)
    print('Local configuration ready. Credentials remain in ignored .local/ files.')
    return settings


def metadata(name):
    path = LOCAL / f'{name}.json'
    return json.loads(path.read_text()) if path.exists() else None


def running(name):
    info = metadata(name)
    if not info:
        return False
    process = subprocess.run(['ps', '-p', str(info['pid']), '-o', 'command='], capture_output=True, text=True)
    return process.returncode == 0 and info['marker'] in process.stdout


def start(name, args, marker):
    with (LOCAL / f'{name}.log').open('ab') as log:
        process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (LOCAL / f'{name}.json').write_text(json.dumps({'pid': process.pid, 'marker': marker}))
    return process


def stop(name):
    if not running(name):
        return
    info = metadata(name)
    os.kill(info['pid'], signal.SIGTERM)
    for _ in range(150):
        if not running(name):
            print(f'{name} stopped.')
            return
        time.sleep(0.1)
    raise RuntimeError(f'{name} did not stop in 15 seconds; inspect .local/{name}.log')


def db_up(settings):
    if running('db'):
        print('PostgreSQL already running.')
        return
    command('./mvnw', '-B', '-q', '-DskipTests', 'test-compile', 'dependency:build-classpath',
            '-Dmdep.outputFile=target/test-classpath.txt', '-DincludeScope=test')
    classpath = os.pathsep.join([str(ROOT / 'target/test-classes'), str(ROOT / 'target/classes'),
                                (ROOT / 'target/test-classpath.txt').read_text().strip()])
    ready = LOCAL / 'db-ready'
    ready.unlink(missing_ok=True)
    process = start('db', [JAVA, '-cp', classpath, 'com.aepsy.voucher.LocalPostgres',
                           str(settings['dbPort']), str(LOCAL)], str(LOCAL))
    for _ in range(600):
        if ready.exists():
            print(f"PostgreSQL 17 ready on 127.0.0.1:{settings['dbPort']} (data preserved in .local/postgres).")
            return
        if process.poll() is not None:
            raise RuntimeError('PostgreSQL failed to start. See .local/db.log; check DB_PORT availability.')
        time.sleep(0.1)
    stop('db')
    raise RuntimeError('PostgreSQL startup timed out. See .local/db.log.')


def app_up(settings, foreground=False):
    if running('app'):
        print(f"API already running at http://127.0.0.1:{settings['port']}")
        return
    command('./mvnw', '-B', '-q', '-DskipTests', 'package')
    args = [JAVA, '-jar', str(JAR), f"--server.port={settings['port']}"]
    if foreground:
        (LOCAL / 'app.json').write_text(json.dumps({'pid': os.getpid(), 'marker': str(JAR)}))
        os.execvp(JAVA, args)
    process = start('app', args, str(JAR))
    url = f"http://127.0.0.1:{settings['port']}/actuator/health/readiness"
    for _ in range(900):
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if json.load(response).get('status') == 'UP':
                    print(f"API ready at http://127.0.0.1:{settings['port']}")
                    return
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        if process.poll() is not None:
            raise RuntimeError('API failed to start. See .local/app.log; check PORT availability.')
        time.sleep(0.1)
    stop('app')
    raise RuntimeError('API readiness timed out. See .local/app.log.')


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else 'status'
    if action == 'logs':
        for name in ['db', 'app']:
            path = LOCAL / f'{name}.log'
            print(f'\n{name} log:')
            if path.exists():
                print('\n'.join(path.read_text(errors='replace').splitlines()[-35:]))
        return
    if action == 'status':
        for name in ['db', 'app']:
            print(f'{name}: {"running" if running(name) else "stopped"}')
        return
    if action in ['down', 'db-down']:
        # Stop the API first so it cannot write while its owned database shuts down.
        stop('app')
        stop('db')
        return
    settings = setup()
    if action == 'setup':
        command('./mvnw', '-B', '-q', '-DskipTests', 'test-compile', 'dependency:build-classpath',
                '-Dmdep.outputFile=target/test-classpath.txt', '-DincludeScope=test')
    elif action in ['db-up', 'up', 'run']:
        db_up(settings)
        if action != 'db-up':
            app_up(settings, foreground=action == 'run')
    else:
        raise ValueError(f'Unknown action: {action}')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError, FileNotFoundError) as failure:
        print(f'ERROR: {failure}', file=sys.stderr)
        sys.exit(1)
