# Beta Android: implementação e critérios de liberação

O canal aprovado é **beta público**, não uma certificação de segurança ou aprovação da versão estável. O estado verificável fica em `distribution/beta-readiness.json`. `scripts/package-beta.py --publishable` exige aprovação do beta e evidência para todos os critérios técnicos obrigatórios de `scripts/beta_policy.py`. Auditoria independente e transferência pública do USDT da testnet permanecem pendentes, explicitamente não obrigatórias para este beta solicitado pelo responsável. Nenhum desses itens foi marcado como executado; auditoria independente segue exigida antes da versão estável. Os testes da política impedem remover ou tornar opcionais os critérios técnicos obrigatórios.

## 1. Envio

`TransferRepository` prepara BNB e USDT nas redes BSC 56/97: verifica chain ID, contrato/decimais, saldo, nonce pending, estimativa e simulação. Quantidades são inteiros de 256 bits com 18 decimais, sem Double. O limite de gas e o preço máximos aparecem na revisão. Alterar parâmetros invalida o digest que vincula a autenticação. A cotação vence em 90 segundos e é revalidada antes da assinatura.

PIN/biometria autorizam uma única operação vinculada ao digest. Trust Wallet Core assina localmente a transação legacy/EIP-155; chaves/frase não saem do cofre. O hash é calculado sobre os bytes assinados antes da transmissão. Não há approvals ou chamadas arbitrárias de contratos.

## 2. Acompanhamento e retomada

O outbox é cifrado com AES-GCM/Keystore, gravado atomicamente **antes** de enviar. Falha de rede não se transforma em falha financeira nem em nova transação: retomada usa os mesmos bytes/hash/nonce. Uma conta/rede tem um envio em acompanhamento por vez. Nenhum arquivo ilegível é automaticamente apagado ou interpretado como histórico vazio.

Atividade mostra envios criados neste dispositivo e retoma a consulta após reabrir. Após 15 confirmações, verifica o bloco canônico e o status do recibo; para token, exige também o evento Transfer do contrato/destinatário/valor esperados. Após finalizar remove os bytes assinados do registro. Mantém até 500 registros, removendo finalizados antigos quando necessário; nunca descarta um envelope pendente para abrir espaço. Taxa efetiva vem do recibo.

Limites atuais: não é um indexador de todas as entradas ou de transações feitas em outras wallets. Substituição de nonce por outro dispositivo, transação descartada sem recibo, reorganização além da janela escolhida e histórico completo exigem validação e tratamento adicionais antes de prometer suporte irrestrito. Não há P&L diário inferido de depósitos.

## 3. Tokens

Catálogo fechado inicial: BNB nativo e USDT BEP-20 em cada rede. Mainnet: `0x55d398326f99059fF775485246999027B3197955`. Testnet: `0x337610d27c682E347C9cD60BD4b3b107C9d34dDd`. Ambos responderam `decimals=18` e `symbol=USDT` na verificação RPC; os contratos são distintos. O segundo aparece como USDT Token e é citado na [documentação de tokens de teste da Immersve](https://docs.immersve.com/guides/obtaining-test-tokens/). Metadados on-chain não comprovam emissor; revisar a representação/custódia do token escolhido na documentação do emissor antes da publicação.

Não há adicionar contratos arbitrários, tokens com taxa de transferência, outros decimais ou moedas de outras redes nesta versão. Testnet mantém valor fiduciário zero. QR usa o contrato e chain ID correspondentes.

## 4. Infraestrutura

Transporte HTTPS usa endpoints permitidos, verificação de chain ID, limites de resposta, timeouts, limite global por operação e contingência entre endpoints. Não inclui dados secretos ou mensagens brutas do provedor nos erros. Consultas de saldo/cotações continuam automáticas. Os endpoints públicos e a API de preços não têm SLA contratado: medir disponibilidade/limites e configurar infraestrutura compatível com o tamanho da coorte beta ainda é um critério de liberação.

## 5. Segurança e testes

O app bloqueia screenshots e overlays no fluxo principal; autenticação usa criptografia vinculada à ação. O APK release é não depurável, preserva entradas JNI sob R8 e tem assinatura própria. A regra `-dontrepackage` preserva os limites de acesso entre pacotes: o teste release detectou `IllegalAccessError` no inicializador AndroidX com o repackaging automático. A configuração considera o [repackaging padrão do AGP e as regras R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization). O relatório de testes precisa distinguir simulação local de transferências públicas.

`qa/run-evm-tests.sh` cria dois Anvil locais com chain IDs 56/97 e token de fixture. Encaminha somente os RPCs locais para a instalação Android isolada; não muda a lista HTTPS da aplicação. Cobre assinatura real no A57, transferências nativas/ERC-20, inspeção de destinatário/valor/calldata/nonce, recibos, reinício do journal, perda da resposta e tentativa de mudar os dados após autenticar. O token de fixture nunca é implantado pelo app nem apresentado como USDT real. Executar antes do relatório de regressão normal, pois os resultados instrumentados são regenerados por execução. `CAEDRAL_EVM_RELEASE=1 bash qa/run-evm-tests.sh` usa o release assinado em pacote isolado; a Activity auxiliar só é incluída nessa configuração de teste. Ela também preserva APIs compartilhadas AndroidX/Kotlin para compatibilidade com o APK separado de instrumentação. Essas regras não entram no APK distribuível: a suíte não comprova sozinha a correção de todas as otimizações do APK normal, cuja inicialização precisa de verificação separada. O script confere os XMLs e falha se houver falhas, testes ignorados ou ausência da suíte esperada, mesmo se o Gradle encerrar com código zero.

Foram executadas seis transferências públicas em contas QA novas no A57: ida/volta tBNB testnet e BNB/USDT mainnet. Atualização assinada e recuperação em novo cofre passaram no mesmo aparelho. Permanecem ampliação da matriz física/biometria, USDT público da testnet sem saldo de faucet, cenários adicionais de reorganização/nonce externo e revisão independente do novo código de transações. Testes automatizados não equivalem a uma auditoria independente.

## 6. Distribuição Android

Chave RSA de 4096 bits criada fora do repositório em `~/.local/share/caedralpay/android-signing`, com permissões restritas. O script não sobrescreve chaves existentes nem imprime senhas. Faça [backup criptografado](BACKUP_ASSINATURA.md) seguro fora desta máquina antes da primeira publicação. Não use a chave debug para beta público. Uma assinatura diferente não atualiza a instalação debug atual: testar a migração/recuperação sem desinstalar inadvertidamente a carteira pessoal.

Build: `CAEDRAL_SIGNING_PROPERTIES="$HOME/.local/share/caedralpay/android-signing/signing.properties" ./gradlew --no-configuration-cache :app:assembleRelease`.

`scripts/package-beta.py` verifica assinatura, applicationId, versão e ausência de depuração e gera candidato com SHA-256, certificado e manifesto. Sem `--publishable`, o manifesto permanece bloqueado para download público. O caminho de produção deve ser verificado no relatório de assinatura, não copiado de uma execução do pacote isolado.

Servidor de download preparado em `distribution/`, com Node, Nginx e systemd. Turnstile é validado no servidor, incluindo hostname/action, cache antirreplay, limite de tentativas e links HMAC de 120 segundos vinculados ao IP. Configurar sitekey/secret reais em arquivo protegido no servidor, não no app ou Git. O backend recusa candidato não aprovado. Nginx não expõe uma pasta de APKs que contorne o desafio. A origem deve ser restrita à Cloudflare e HTTPS em Full (strict).

A página https://download.caedral.com está publicada com certificado válido, proxy Cloudflare e Full (strict). O responsável confirmou a validação humana do Turnstile. O acesso direto ao IP da origem recebe 403; o acesso pela Cloudflare recebe 200. O manifesto do lançamento passa a `betaApproved=true` para o beta solicitado, após os critérios técnicos obrigatórios e checks da main. A página exige Turnstile antes de emitir link HMAC temporário. A [privacidade](https://download.caedral.com/privacy.html) foi publicada. Suporte: https://discord.com/invite/ujR9huP7qK.

Evidências registradas: 40 testes JVM, 42 testes instrumentados de regressão (incluindo retenção/adulteração do journal) e 3 testes EVM locais em debug; mais 39 testes de regressão e 3 EVM no release isolado assinado, com as regras auxiliares de instrumentação descritas acima; 9 testes do serviço de download. O PR #4 passou pelos checks Android e distribuição; ambos são obrigatórios na proteção da main. O smoke separado sem as regras auxiliares registrou criação da Activity e processo ativo; o aparelho estava bloqueado na inspeção, portanto isso não comprova interação visual completa (`qa/results/release-startup.json`). As transferências públicas posteriores estão em `qa/results/public-chain-proof.json`: seis recibos confirmados e conferidos também no segundo RPC configurado, com bloco canônico e 15+ confirmações. As taxas mainnet foram 0,00000677853 BNB. Esses resultados não substituem revisão independente.

A configuração da origem é aplicada por `distribution/restrict-origin.py`, após a instalação TLS. Ela limita somente o virtual host gerenciado às redes oficiais da Cloudflare e loopback, verifica `nginx -t` e confirma o reload pelo marcador de política. Não executar novamente o instalador TLS sem reaplicar e verificar essa restrição.

## Evidência exigida para liberar

Para cada critério obrigatório do beta, registrar resultado e evidência: relatório automatizado, hashes públicos das transferências, versão/fingerprint do APK, resultado da recuperação/atualização e da revisão de segurança, confirmação de backup, URL HTTPS funcionando e validação real do Turnstile. Não marcar como aprovado por conveniência para destravar o empacotamento.


## Roteiro de validação pública pelo responsável

1. Selecionar BSC testnet no aparelho e conferir conta/endereço. Receber tBNB e o token de teste configurado; registrar hashes e saldos.
2. Enviar uma quantidade pequena para uma segunda conta controlada. Conferir destinatário, moeda, quantidade e taxa na revisão, autenticar no aparelho e comparar o hash/recibo no explorador. Repetir para nativo e token.
3. Reabrir o app e conferir a retomada do mesmo envio e sua conclusão. Uma falha de conexão não deve criar um segundo pagamento.
4. Repetir em BSC mainnet com valores mínimos escolhidos pelo proprietário e autenticação dele. Registrar versão, conta pública, hash, quantidade, taxa e resultado, sem registrar PIN, frase ou chave.
5. Em outro aparelho de teste, recuperar uma frase de fixture sem valor e conferir os mesmos endereços. Validar PIN, habilitação/cancelamento de biometria e retorno do segundo plano.
6. Instalar uma versão assinada com a mesma chave sobre a versão anterior de teste e verificar a preservação de contas, cofre, preferências e histórico. Nunca desinstalar a única cópia de uma carteira para tentar contornar assinaturas incompatíveis.

A revisão independente deve cobrir geração/derivação, cofre e Keystore, autenticação por ação, preparação/revisão/assinatura, persistência antes do broadcast e confirmação/retomada. A equipe precisa registrar responsável, escopo, achados e correções; testes internos não preenchem esse critério.


## Validação pública e revisão interna

O procedimento executável, fontes oficiais de tokens e limites dos testes ficam em [VALIDACAO_REDES_PUBLICAS.md](VALIDACAO_REDES_PUBLICAS.md). A [revisão interna](REVISAO_INTERNA_BETA.md) registra escopo, achados corrigidos e limitações; não representa auditoria independente. O responsável confirmou a cópia/exportação do backup da assinatura nesta sessão.


## Resultado das transferências públicas

`qa/results/public-network.json` foi produzido pelos testes instrumentados usando `TransferRepository`, cofre e signer do app. `scripts/verify-public-receipts.py` conferiu os seis hashes por outro endpoint HTTPS, incluindo recibo, bloco canônico, campos da transação, evento do token e saldos finais. Nenhuma chave/PIN da carteira pessoal foi acessada.

| Rede | Ativo | Ida | Volta |
|---|---|---|---|
| BSC mainnet | BNB | [recibo](https://bscscan.com/tx/0x79a7f5ef1e8f037939f819b7f5c50818d5484b95fcec47d901e76e18d6c7089f) | [recibo](https://bscscan.com/tx/0xceb08ca51b6d588e16719fc859f0146f1506d0388f4edb2b38d5a86a7ed7a107) |
| BSC mainnet | USDT | [recibo](https://bscscan.com/tx/0xc0aee2a1cc2677fb4b114a5b56bfc9b997c1681f9883f60e0d5049a4b7a75282) | [recibo](https://bscscan.com/tx/0x86c9d2174a9044e3621433bbf41fda71e288f4f9dcaf95ab230ecd95f6e7b574) |
| BSC testnet | tBNB | [recibo](https://testnet.bscscan.com/tx/0xaa7d2c95f04112903756bcddd863cda71c820bcd94adc5d7209d5188f4481628) | [recibo](https://testnet.bscscan.com/tx/0x4d7b795da8ff3b4a77d0643e6d84262e8567e7f7b4ce2f07dc106a99e8a7df4c) |

Não há recibo de USDT na testnet: o faucet não forneceu esse saldo. Essa lacuna é mantida no manifesto como `publicTestnetTokenRoundTrip=pending`. A transferência ERC-20 foi exercitada nas duas chains locais e com USDT real na BSC mainnet. O APK beta não é anunciado como auditado.
