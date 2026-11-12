# Beta Android: implementação e critérios de liberação

Não é uma declaração de aprovação para produção. O estado verificável fica em `distribution/beta-readiness.json`; `scripts/package-beta.py --publishable` recusa empacotar um beta enquanto houver critério pendente ou sem evidência.

## 1. Envio

`TransferRepository` prepara BNB e USDT nas redes BSC 56/97: verifica chain ID, contrato/decimais, saldo, nonce pending, estimativa e simulação. Quantidades são inteiros de 256 bits com 18 decimais, sem Double. O limite de gas e o preço máximos aparecem na revisão. Alterar parâmetros invalida o digest que vincula a autenticação. A cotação vence em 90 segundos e é revalidada antes da assinatura.

PIN/biometria autorizam uma única operação vinculada ao digest. Trust Wallet Core assina localmente a transação legacy/EIP-155; chaves/frase não saem do cofre. O hash é calculado sobre os bytes assinados antes da transmissão. Não há approvals ou chamadas arbitrárias de contratos.

## 2. Acompanhamento e retomada

O outbox é cifrado com AES-GCM/Keystore, gravado atomicamente **antes** de enviar. Falha de rede não se transforma em falha financeira nem em nova transação: retomada usa os mesmos bytes/hash/nonce. Uma conta/rede tem um envio em acompanhamento por vez. Nenhum arquivo ilegível é automaticamente apagado ou interpretado como histórico vazio.

Atividade mostra envios criados neste dispositivo e retoma a consulta após reabrir. Após 15 confirmações, verifica o bloco canônico e o status do recibo; para token, exige também o evento Transfer do contrato/destinatário/valor esperados. Após finalizar remove os bytes assinados do registro. Taxa efetiva vem do recibo.

Limites atuais: não é um indexador de todas as entradas ou de transações feitas em outras wallets. Substituição de nonce por outro dispositivo, transação descartada sem recibo, reorganização além da janela escolhida e histórico completo exigem validação e tratamento adicionais antes de prometer suporte irrestrito. Não há P&L diário inferido de depósitos.

## 3. Tokens

Catálogo fechado inicial: BNB nativo e USDT BEP-20 em cada rede. Mainnet: `0x55d398326f99059fF775485246999027B3197955`. Testnet: `0x337610d27c682E347C9cD60BD4b3b107C9d34dDd`. Ambos responderam `decimals=18` e `symbol=USDT` na verificação RPC; os contratos são distintos. O segundo aparece como USDT Token e é citado na [documentação de tokens de teste da Immersve](https://docs.immersve.com/guides/obtaining-test-tokens/). Metadados on-chain não comprovam emissor; revisar a representação/custódia do token escolhido na documentação do emissor antes da publicação.

Não há adicionar contratos arbitrários, tokens com taxa de transferência, outros decimais ou moedas de outras redes nesta versão. Testnet mantém valor fiduciário zero. QR usa o contrato e chain ID correspondentes.

## 4. Infraestrutura

Transporte HTTPS usa endpoints permitidos, verificação de chain ID, limites de resposta, timeouts, limite global por operação e contingência entre endpoints. Não inclui dados secretos ou mensagens brutas do provedor nos erros. Consultas de saldo/cotações continuam automáticas. Os endpoints públicos e a API de preços não têm SLA contratado: medir disponibilidade/limites e configurar infraestrutura compatível com o tamanho da coorte beta ainda é um critério de liberação.

## 5. Segurança e testes

O app bloqueia screenshots e overlays no fluxo principal; autenticação usa criptografia vinculada à ação. O APK release é não depurável, preserva entradas JNI sob R8 e tem assinatura própria. O relatório de testes precisa distinguir simulação local de transferências públicas.

`qa/run-evm-tests.sh` cria dois Anvil locais com chain IDs 56/97 e token de fixture. Encaminha somente os RPCs locais para a instalação Android isolada; não muda a lista HTTPS da aplicação. Cobre assinatura real no A57, transferências nativas/ERC-20, inspeção de destinatário/valor/calldata/nonce, recibos, reinício do journal, perda da resposta e tentativa de mudar os dados após autenticar. O token de fixture nunca é implantado pelo app nem apresentado como USDT real. Executar antes do relatório de regressão normal, pois os resultados instrumentados são regenerados por execução.

São necessários ainda testes manuais nas redes públicas, biometria real, atualização do APK release, recuperação em outro aparelho, testes de indisponibilidade/reorganização e revisão independente do novo código de transações. Testes automatizados não equivalem a uma auditoria independente.

## 6. Distribuição Android

Chave RSA de 4096 bits criada fora do repositório em `~/.local/share/caedralpay/android-signing`, com permissões restritas. O script não sobrescreve chaves existentes nem imprime senhas. Faça backup seguro fora desta máquina antes da primeira publicação. Não use a chave debug para beta público. Uma assinatura diferente não atualiza a instalação debug atual: testar a migração/recuperação sem desinstalar inadvertidamente a carteira pessoal.

Build: `CAEDRAL_SIGNING_PROPERTIES="$HOME/.local/share/caedralpay/android-signing/signing.properties" ./gradlew --no-configuration-cache :app:assembleRelease`.

`scripts/package-beta.py` verifica assinatura, applicationId, versão e ausência de depuração e gera candidato com SHA-256, certificado e manifesto. Sem `--publishable`, o manifesto permanece bloqueado para download público. O caminho de produção deve ser verificado no relatório de assinatura, não copiado de uma execução do pacote isolado.

Servidor de download preparado em `distribution/`, com Node, Nginx e systemd. Turnstile é validado no servidor, incluindo hostname/action, cache antirreplay, limite de tentativas e links HMAC de 120 segundos vinculados ao IP. Configurar sitekey/secret reais em arquivo protegido no servidor, não no app ou Git. O backend recusa candidato não aprovado. Nginx não expõe uma pasta de APKs que contorne o desafio. A origem deve ser restrita à Cloudflare e HTTPS em Full (strict).

Destino proposto conforme o domínio encontrado no projeto: `download.caedral.com`. O DNS não existia na checagem; nenhuma publicação foi feita. Suporte: https://discord.com/invite/ujR9huP7qK. A página de privacidade técnica está preparada para revisão do responsável antes da publicação.

## Evidência exigida para liberar

Para cada critério do JSON, registrar resultado e evidência: relatório automatizado, hashes públicos das transferências, versão/fingerprint do APK, resultado da recuperação/atualização e da revisão de segurança, confirmação de backup, URL HTTPS funcionando e validação real do Turnstile. Não marcar como aprovado por conveniência para destravar o empacotamento.
