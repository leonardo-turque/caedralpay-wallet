#!/usr/bin/env python3
"""Create the private signing identity outside git. Never print credentials."""
from pathlib import Path
import os
import secrets
import subprocess

directory = Path.home() / '.local/share/caedralpay/android-signing'
directory.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(directory, 0o700)
keystore = directory / 'caedralpay-release.p12'
config = directory / 'signing.properties'
if keystore.exists() or config.exists():
    raise SystemExit('Assinatura já existente: os arquivos não foram substituídos.')
password = secrets.token_hex(32)
password_file = directory / '.password.tmp'
try:
    fd = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream: stream.write(password)
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
                    '-alias', 'caedralpay-release', '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000',
                    '-dname', 'CN=CaedralPay Wallet', '-storepass:file', str(password_file),
                    '-keypass:file', str(password_file)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(keystore, 0o600)
    fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(f'storeFile={keystore}\nstorePassword={password}\nkeyAlias=caedralpay-release\nkeyPassword={password}\n')
    print('Assinatura privada criada em ' + str(directory))
    print('Backup seguro fora desta máquina é necessário antes da distribuição.')
finally:
    password_file.unlink(missing_ok=True)
