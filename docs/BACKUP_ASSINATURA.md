# Backup da assinatura Android

A chave de assinatura permite publicar atualizações compatíveis com o APK instalado. Ela não é a frase secreta de uma carteira. Não enviar os arquivos ou senhas ao chat, GitHub ou Discord.

## Criar um arquivo criptografado

Execute no seu terminal. O GnuPG solicita uma senha escolhida por você; guarde-a separadamente do arquivo. O comando inclui toda a pasta, inclusive as propriedades privadas de assinatura, sem gerar um arquivo intermediário sem criptografia.

```bash
set -o pipefail
umask 077
tar -C "$HOME/.local/share/caedralpay" -czf - android-signing |
  gpg --symmetric --cipher-algo AES256 \
    --output "$HOME/caedralpay-assinatura-backup.tar.gz.gpg"
```

Confira a senha e a integridade antes de copiar. Este comando exibe somente os nomes dos arquivos contidos no backup:

```bash
set -o pipefail
gpg --decrypt "$HOME/caedralpay-assinatura-backup.tar.gz.gpg" | tar -tzf -
```

A lista deve incluir `android-signing/caedralpay-release.p12` e `android-signing/signing.properties`. Se houver erro, não considere o backup válido.

Copie o `.gpg` para um pendrive ou armazenamento separado deste computador. Confirme a integridade da cópia com `sha256sum` nos dois caminhos: os resultados precisam ser iguais. Mantenha a senha em local separado e seguro. Guardar somente no mesmo computador não conclui o backup.

## Recuperar em caso de necessidade

Descriptografe em uma pasta privada vazia com permissão 700 e extraia o arquivo. O `storeFile` de `signing.properties` contém um caminho absoluto da máquina original: ajuste-o localmente se recuperar em outro caminho. Não gere outra chave para substituir a existente. Valide o fingerprint do certificado contra o manifesto do release antes de assinar uma atualização.

O critério `signingKeyBackup` só será concluído após o responsável confirmar a cópia externa verificada e a disponibilidade da senha.
