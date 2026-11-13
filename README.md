<div align="center">

# CaedralPay Wallet

**Autocustódia. Clareza. Precisão.**

Uma carteira Android nativa, com identidade visual sóbria e proteção local das credenciais.

![Android API 37+](https://img.shields.io/badge/Android-API%2037%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![Status](https://img.shields.io/badge/Status-Beta%20candidate-DDD6CD)

</div>

**Beta Android: `0.1.0-beta.1`.** Download oficial protegido em [download.caedral.com](https://download.caedral.com). O escopo validado e os critérios do beta estão registrados em [validação do beta](docs/BETA_VALIDACAO.md) e [beta-readiness.json](distribution/beta-readiness.json).

A [página oficial de download](https://download.caedral.com) usa HTTPS, Turnstile e links temporários. O APK é distribuído pelo domínio oficial; os assets APK dos candidatos no GitHub permanecem em rascunho. [Privacidade](https://download.caedral.com/privacy.html).

## Visão do produto

A CaedralPay Wallet está sendo construída para dar ao usuário controle das próprias chaves, com uma experiência simples de criar, recuperar e acessar sua carteira. A identidade combina temas claro/escuro, tons minerais, tipografia Manrope e movimento discreto.

A primeira rede integrada é a BNB Smart Chain, em mainnet e testnet, com BNB e USDT em contratos separados. O aplicativo cobre onboarding, cofre, carteiras/contas, QR Code, saldos e total em US$, temas claro/escuro e ações sensíveis autenticadas. O envio prepara e revisa taxas, autentica, assina localmente e transmite; um outbox cifrado preserva hash/nonce para acompanhamento e retomada. Trocar permanece desativado; a ação Comprar foi substituída por Maquininha. Histórico completo de entradas, P&L diário real, outros tokens e auditoria independente continuam como trabalho posterior explicitamente documentado.

## Estado atual

| Funcionalidade | Situação |
| --- | --- |
| Splash e identidade CaedralPay | Implementadas |
| Nome local da carteira | Implementado |
| Geração de 12 palavras com Wallet Core | Implementada |
| Confirmação de quatro palavras, uma por fileira | Implementada |
| PIN de seis dígitos e confirmação | Implementados |
| Biometria forte opcional, com PIN alternativo | Implementada |
| Cofre local cifrado e gravação atômica | Implementados |
| Bloqueio ao sair do aplicativo | Implementado para a sessão autenticada |
| Importação de frase de 12 palavras | Implementada com validação BIP39 local |
| Interface principal | Temas Sistema/Claro/Escuro, Manrope, componentes Compose e carregamento de ativos |
| Carteiras e contas | Seleção/criação/importação, renomeação, remoção local com índices preservados e ações sensíveis autenticadas |
| Endereços e recebimento | Conta EVM derivada, QR com quantidade opcional e cópia do endereço |
| Saldos BNB/USDT | Consulta HTTPS real, validação de chain ID/decimais, atualização por conta |
| Histórico | Envios deste dispositivo, recibos, taxa efetiva e retomada do mesmo hash; indexador completo pendente |
| Cotação | USD público com validade; BRL e P&L real pendentes |
| Envio e assinatura/transmissão | BNB/USDT com autenticação vinculada à revisão; E2E local 56/97 e ida/volta pública BNB/USDT mainnet + tBNB testnet verificadas |
| APK release | Assinado, não depurável, SHA-256/certificado e backup da assinatura confirmado |
| Download e suporte | Serviço Node/Nginx/Turnstile, manifesto aprovado para o beta; [Discord oficial](https://discord.com/invite/ujR9huP7qK) |
| Avisos e notificações | Convite na primeira home, notificações privadas e recibos de entrada/saída/compra com o app aberto; sem push/background |
| Cartão físico / maquininha | Leitura Classic 1K, vínculo local cifrado e revisão em laboratório isolado; sem liquidação |
| Origin e maquininha NFC | Integração de desenvolvimento em BSC testnet: cartão pronto sem ativação, dados cifrados, regeneração, HCE, split on-chain e recorrência por autorização inicial; RF entre dois celulares pendente, fora do beta publicado |

[Validação do cartão, maquininha e notificações](docs/CARTAO_MAQUININHA_NOTIFICACOES.md) detalha a cobertura, os testes e os limites desta etapa.

## Desenvolvimento da bandeira Caedral

A [integração Origin](docs/ORIGIN_INTEGRACAO.md) conecta a área de cartões e a maquininha ao cofre/assinador e ao contrato próprio em BSC testnet. Inclui um cartão por conta, com os mesmos dados em todas as redes, validade de cinco anos, dados cifrados com revelação autenticada, regeneração, cobrança assinada EIP-712 e NFC HCE cifrado. Não exige ativação por rede/moeda: o primeiro uso on-chain é incluído na compra. O lojista determina rede, moeda e valor; o cliente abre o cartão com biometria prioritária e usa PIN como alternativa. O pagamento recusa saldo insuficiente, incluindo o teto de gás; cashback não é crédito antecipado. A compra usa o saldo da conta vinculada e devolve o cashback na mesma moeda e transação, descontando-o da taxa Caedral.

A home apresenta o cartão da conta como acesso ao painel e as ações Trocar (desativado), Enviar, Receber e Maquininha. A prontidão de aproximação exige revalidação a cada 60 segundos, com nova autenticação para renovar.

A [maquininha e recorrência](docs/RECORRENCIA.md) organiza a cobrança por rede, moeda, tipo, intervalo/encerramento e valor. O débito automático usa autorização inicial EIP-7702 e um executor independente, com saldo da conta original, cancelamento on-chain, sem crédito nem saldo negativo. Parcelamento não integra o fluxo.

A taxa é 1% com piso literal de 0,10 do ativo; cashback Origin inicial de 0,1%, passando a 1% em 01/02/2027. [Deployment verificável](protocol/deployments/97-origin.json). Não existe implantação mainnet do Origin nesta fase. O [rascunho anterior](docs/PROTOCOLO_CAEDRAL.md) preserva a pesquisa de outros níveis, separada desta implementação. O APK público continua sem a nova integração até a validação manual NFC.

## Tecnologias em uso

As versões abaixo refletem o repositório; não significam que cada dependência seja a versão mais recente disponível.

| Camada | Tecnologia | Uso |
| --- | --- | --- |
| Linguagem | Kotlin 2.2.10 | Aplicativo Android nativo |
| Interface | Jetpack Compose / BOM 2026.02.01 | Telas declarativas e estado visual |
| Componentes | Material 3 | Campos, botões e componentes de interface |
| Plataforma | compileSdk, targetSdk e minSdk 37 | Android API 37 ou superior |
| Integração Android | Activity Compose 1.8.0, Lifecycle 2.6.1, Core KTX 1.10.1 | Activity e APIs AndroidX |
| Criptografia de carteira | Trust Wallet Core 4.8.7 | Geração e validação de frases BIP39 |
| Cifragem local | AES-256-GCM / Java Cryptography Architecture | Confidencialidade e autenticação dos dados |
| Derivação do PIN | PBKDF2-HMAC-SHA256 | 600.000 iterações e salt aleatório por cofre |
| Proteção no aparelho | Android Keystore | Chaves vinculadas à instalação/aparelho |
| Biometria | BiometricPrompt + CryptoObject | Autorização criptográfica por uso |
| Persistência | Arquivo privado + AtomicFile | Cofre versionado e gravação atômica |
| Build | Android Gradle Plugin 9.5.1 / Gradle Wrapper 9.6.0 | Compilação reproduzível pelas versões fixadas |
| Verificação | JUnit 4, AndroidX Test, Compose Test e Android Lint | Testes e análise estática |

## Fluxos

### Criar carteira

1. Informar o nome da carteira.
2. Gerar e anotar as 12 palavras.
3. Completar quatro palavras selecionadas aleatoriamente, uma em cada fileira.
4. Definir e confirmar um PIN de seis dígitos.
5. Habilitar biometria forte ou selecionar “Agora não”.
6. Salvar o cofre e acessar a interface principal.

### Importar carteira

1. Selecionar “Importar carteira” na tela inicial.
2. Informar as 12 palavras nos campos numerados.
3. Validar a frase, incluindo o checksum BIP39, no próprio dispositivo.
4. Escolher um nome e um novo PIN para este aparelho.
5. Escolher se deseja habilitar a biometria e salvar um novo cofre local.

O fluxo inicial é apresentado como recuperação CaedralPay. Uma frase BIP39 não contém a marca do aplicativo de origem: frases externas de 12 palavras compatíveis também podem ser aceitas. O seletor interno permite importar outras carteiras e recuperar contas sequenciais. A interface atual não oferece frases de 18/24 palavras, senha BIP39 adicional ou importação por chave privada.

A frase reconstrói as chaves; não transfere o arquivo do celular anterior. O nome, o PIN e a biometria são locais. Recuperar os mesmos endereços também depende do mesmo caminho de derivação e das mesmas configurações. A validação da frase, por si só, não demonstra a existência de saldo ou atividade na rede.

### Desbloquear

Quando existe um cofre, o aplicativo abre a tela de desbloqueio. A biometria é solicitada automaticamente quando configurada; o PIN permanece disponível. Após autenticação, a página principal mostra a carteira e a conta selecionadas. Ao sair do aplicativo, a sessão autenticada volta ao estado bloqueado.

## Segurança e armazenamento

O arquivo `wallet_vault.bin` fica em `Context.filesDir`, normalmente:

```text
/data/user/0/com.caedralpay.wallet/files/wallet_vault.bin
```

O cofre contém o nome e a frase cifrados com uma chave aleatória AES de 256 bits. Essa chave é cifrada por uma chave derivada do PIN. Se habilitada, a biometria oferece uma segunda forma de desbloquear a mesma chave, protegida por uma chave do Android Keystore com autenticação forte a cada uso. O registro completo recebe uma camada adicional de cifragem pelo Keystore, disponível com o dispositivo desbloqueado.

- AES-GCM usa IVs aleatórios e dados autenticados para separar os contextos criptográficos.
- O PIN não é persistido; PBKDF2 deriva a chave que abre o encapsulamento protegido pelo PIN.
- Chaves criadas no Keystore não são exportadas pelo aplicativo. A proteção por hardware depende do dispositivo; o código atual não exige StrongBox nem comprova atestação.
- A frase e o PIN não são enviados a um backend pelo fluxo implementado.
- A gravação usa `AtomicFile`; um cofre existente não pode ser substituído pelo fluxo normal de criação/importação.
- Backups e transferência do cofre estão desabilitados nas configurações Android.
- `FLAG_SECURE` restringe capturas comuns e a exibição em superfícies não seguras.
- O diagnóstico `CaedralVault` registra tipos de exceção, etapas e stack frames, sem mensagens de exceção nem conteúdo da carteira.
- Após cinco PINs incorretos, aplica-se uma espera progressiva. O contador local usa SharedPreferences; é uma barreira de interface, não uma garantia contra um aparelho comprometido ou manipulação do relógio/arquivos.

Desinstalar ou limpar os dados remove o acesso ao cofre local. A recuperação em outro aparelho depende da frase; copiar o arquivo cifrado não substitui a recuperação, pois as chaves do Keystore não o acompanham.

Os dados precisam existir temporariamente em memória para geração, exibição e uso. Alguns buffers são zerados, mas strings gerenciadas pela JVM não permitem garantir a remoção imediata de todas as cópias. A implementação não foi submetida a auditoria independente. Compilação e lint não substituem testes de integridade, recuperação, ciclo de vida e segurança em aparelhos reais.

## Arquitetura atual

```text
app/src/main/
├── java/com/caedralpay/wallet/
│   ├── MainActivity.kt        # Entrada, criação, PIN, biometria e desbloqueio
│   ├── ImportWalletScreen.kt # Importação e validação da frase
│   ├── WalletVault.kt        # Formato do cofre, cifragem e persistência
│   ├── BiometricCrypto.kt    # Chave Keystore e cipher biométrico
│   └── ui/theme/             # Tema e tipografia Compose
├── res/                      # Marca, ícones, splash e regras de backup
└── AndroidManifest.xml
```

As telas usam estado Compose e callbacks. A camada RPC é separada dos repositórios de saldo e transações; operações recentes usam coroutines e injeção por construtor para testes. O journal cifrado é local. Não há custódia ou serviço de assinatura remota. Não existe ainda banco SQL, indexador completo, contêiner de DI ou arquitetura modular MVVM; partes do onboarding original continuam usando threads dedicadas.

## Direção para uma experiência premium

O [plano de estudo e implementação da interface](docs/PLANO_INTERFACE.md) detalha a sequência de fases, os componentes próprios em Compose, o estado da branch de trabalho e os critérios de validação manual.

A [entrega do plano visual](docs/IMPLEMENTACAO_INTERFACE.md) registra a implementação local das fases 0–6, a logo ampliada, a lista/detalhes de ativos, os fluxos visuais e o ambiente isolado de testes Android.

A integração inicial está registrada em [funções essenciais — BNB Smart Chain](docs/FUNCOES_ESSENCIAIS.md). A revisão da página principal reduziu a logo para 80 dp, removeu os avisos/controles extras de rede e passou o total e os ativos para US$. A evolução atual de assinatura/transmissão e seus limites está em [validação do beta](docs/BETA_VALIDACAO.md).

Para começar a validação funcional, siga o [guia da Testnet](docs/TESTNET.md). O app inicia na BSC Testnet quando não existe preferência salva e permite alternar redes pelo ícone ao lado de Ativos. A quantidade tBNB aparece na lista, sem cotação fictícia de BNB real nem reutilização do contrato USDT da mainnet. Não há selo ou texto de testnet no cabeçalho.

O [guia de QR Code](docs/QR_CODE.md) documenta recebimento, preenchimento de pagamentos e importação das 12 palavras pela câmera interna. A leitura nunca transmite automaticamente: revisão e autenticação são etapas separadas.

O [guia de contas, segurança e aparência](docs/CONTAS_SEGURANCA_APARENCIA.md) descreve os menus de conta/carteira, renomeação, remoção sem alterar índices de derivação, revelação e troca de PIN com autenticação por ação, catálogo v3 compatível com os formatos anteriores e temas Sistema/Claro/Escuro.

O acabamento deve vir de consistência, legibilidade, previsibilidade e desempenho medido.

| Área | Direção proposta para próximas fases |
| --- | --- |
| Identidade | Design system Compose com tokens de cor, espaçamento, tipografia, formas e estados |
| Paleta | Fundo `#0B0B0B`, destaque mineral `#DDD6CD`, texto branco e superfícies discretas |
| Movimento | Transições curtas e consistentes, sem flashes claros; considerar a escala de animação do sistema |
| Acessibilidade | Contraste, fontes ampliadas, TalkBack, foco previsível e alvos de toque adequados |
| Estado e arquitetura | ViewModel, fluxo unidirecional de dados e StateFlow, com coleta vinculada ao ciclo de vida |
| Concorrência | Kotlin Coroutines para trabalho de IO e cancelamento estruturado |
| Navegação | Rotas tipadas e argumentos sem frase, PIN ou chave privada |
| Desempenho | Macrobenchmark e Baseline Profiles, orientados pelos fluxos reais |
| Qualidade | Testes de UI, recuperação e cofre, com cenários de erro, interrupção e acessibilidade |

Essas tecnologias e práticas são uma direção documentada, não autorização automática para avançar fases. A base segue as [recomendações oficiais de arquitetura Android](https://developer.android.com/topic/architecture/recommendations) e a documentação de [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview). O tema global local já usa uma paleta escura Caedral fixa; a revisão de todas as telas e dos demais itens continua por fases.

## Executar localmente

### Requisitos

- Android Studio com suporte às versões de AGP e Gradle fixadas no projeto.
- Android SDK Platform 37 e dispositivo/emulador compatível com API 37 ou superior.
- JDK compatível com AGP 9.5.1 e Gradle 9.6.0. O ambiente local foi verificado com OpenJDK 25; o alvo de bytecode Java do app é 11.
- Acesso às dependências Google, Maven Central e GitHub Packages do Wallet Core.

### Configuração

```bash
git clone git@github.com:leonardo-turque/caedralpay-wallet.git
cd caedralpay-wallet
```

Abra o projeto no Android Studio e configure o SDK pelo SDK Manager. O `local.properties` com `sdk.dir` é local e não deve ser versionado.

O repositório Wallet Core usa as propriedades `caedralGithubUser` e `caedralGithubToken`. Configure-as no arquivo pessoal `~/.gradle/gradle.properties`, com credencial autorizada a ler o pacote. Nunca coloque tokens no `gradle.properties` do repositório, em código, prints ou PRs. A instalação precisa ter acesso ao pacote e ao escopo de leitura exigido pelo GitHub Packages.

### Comandos

```bash
# Compilar o aplicativo de desenvolvimento
./gradlew :app:assembleDebug

# Rodar testes JVM existentes
./gradlew :app:testDebugUnitTest

# Compilar os testes que dependem de Android/JNI
./gradlew :app:assembleDebugAndroidTest

# Análise estática
./gradlew --no-configuration-cache :app:lintDebug
```

Os testes instrumentados podem executar em um pacote separado por `./gradlew --no-configuration-cache -PcaedralIsolatedUiTest=true :app:connectedDebugAndroidTest`. Para regressão geral, excluir explicitamente `EvmEndToEndTest`, `PublicNetworkValidationTest` e `ReleaseUpgradeTest`, que exigem seus runners próprios. Os testes públicos são opt-in e não devem ser acionados pela regressão automática. Essa propriedade usa o applicationId `com.caedralpay.wallet.validation`, com dados e chaves separados da wallet normal. O aparelho precisa estar desbloqueado; os testes mantêm a própria Activity de UI acesa, sem alterar as configurações globais de bloqueio. Sem essa propriedade, o comando usa o pacote normal e pode substituir a instalação existente.

O APK de desenvolvimento é gerado em `app/build/outputs/apk/debug/app-debug.apk`. O modo `--offline` funciona apenas com todas as dependências já presentes no cache. O lint pode exigir um download inicial independente das dependências do build.

## Verificação e testes manuais

`RecoveryPhraseTest` cobre checksum, comprimento, palavras inválidas, normalização e a reconstrução do mesmo endereço EVM a partir de uma frase gerada. Ele depende das bibliotecas nativas e deve executar em Android. Compilar esse teste não significa executá-lo. Os testes JVM padrão não constituem uma auditoria do cofre.

Antes de aprovar uma fase, validar no aparelho de teste:

1. Criação, confirmação da frase e rejeição de PINs diferentes.
2. Conclusão com biometria e com “Agora não”.
3. Reabertura com PIN correto/incorreto e biometria cancelada/indisponível.
4. Retorno do segundo plano, recriação da Activity e interrupção durante salvamento.
5. Importação com frase válida e rejeição de palavras/checksum incorretos.
6. Recuperação em outro ambiente sem apagar a única instalação existente.

Os relatórios em `qa/results/` registram a cobertura executada. Atualização assinada e recuperação passaram no A57; biometria física em outros aparelhos e revisão independente permanecem explicitamente fora dessa evidência.

## PRs e versões

- `main` é protegida; alterações entram por pull request.
- Cada fase deve descrever o comportamento, as verificações executadas e os testes manuais pendentes.
- O desenvolvimento evolui por fases validadas pelo responsável pelo produto.
- Tags anotadas de pré-lançamento (`alpha`, `beta` ou `rc`) identificam snapshots do repositório. Novas tags são criadas após o merge e não devem ser movidas.
- Tags de documentação podem identificar um novo snapshot sem alterar o binário.
- `versionCode` e `versionName` do Android são metadados separados: atualmente `2` e `0.1.0-beta.1`. Uma tag de pré-lançamento não representa publicação na Play Store ou um release de produção.

## Próximas fases

1. Acompanhar os testes dos participantes e ampliar a matriz de aparelhos Android 17+.
2. Concluir auditoria independente antes da versão estável e testar USDT público da testnet quando houver saldo de faucet.
3. Medir capacidade dos provedores conforme a coorte crescer; a amostra inicial teve cinco consultas concorrentes por rede sem falhas.
4. Evoluir histórico completo e métricas de resultado com dados adequados.
5. Estudos separados para pagamentos NFC, split on-chain e integrações externas.

## Referências

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [BiometricPrompt e operações criptográficas](https://developer.android.com/identity/sign-in/biometric-auth)
- [Jetpack Compose](https://developer.android.com/compose)
- [Trust Wallet Core](https://github.com/trustwallet/wallet-core)
- [Uso de HD wallets no Wallet Core](https://github.com/trustwallet/developer/blob/master/wallet-core/wallet-core-usage.md)

## Licença

Este repositório ainda não define uma licença própria de distribuição. Torná-lo público não concede automaticamente os direitos de uma licença open source. As dependências mantêm suas respectivas licenças; o Wallet Core utiliza Apache-2.0.
