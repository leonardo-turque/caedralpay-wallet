# Entrega do plano visual

Escopo: fases 0 a 6 de `PLANO_INTERFACE.md`, com autorização do usuário para execução integral. A validação manual final permanece com o responsável pelo produto; não há mais pausas de autorização entre as fases desta entrega.

| Fase | Implementação |
| --- | --- |
| 0 — Estudo | Referências MetaMask, decisões, desenho e créditos registrados |
| 1 — Fundação | Tema escuro fixo, paleta Caedral, escala tipográfica, formas, ícones vetoriais e estrutura de painel segura |
| 2 — Página principal | Logo de 64 dp, carteira/conta, saldo BRL com ajuste de tamanho, variação diária e quatro ações responsivas |
| 3 — Carteiras/contas | Lista agrupada com rolagem, seleção acessível, rodapé fixo, criação/importação e persistência existente |
| 4 — Ativos | BNB/USDT, quantidades/equivalente em BRL, estados carregando/vazio/indisponível/erro/dados e nova tentativa por callback |
| 5 — Apoio | Detalhes do ativo, recebimento com endereço real/copiar e envio com formulário/revisão; retorno preserva o ativo/contexto |
| 6 — Acabamento | Fundo opaco nas transições, insets, rolagem com teclado, navegação IME do PIN, palavras identificadas para acessibilidade e disposição adaptada à fonte ampliada |

## Estados reais e integrações

A tela autenticada usa o estado indisponível enquanto RPC e preços não estiverem integrados. Listar BNB/USDT não afirma que o usuário possui esses ativos. Saldos desconhecidos usam `—`, nunca zero inventado. Os demais estados têm componentes/previews e testes, para conexão futura à camada de dados.

Comprar e Trocar continuam visíveis e desativados. Enviar permite editar e revisar endereço/quantidade; a confirmação on-chain fica desativada até existirem saldo, cotação de taxa e assinatura/transmissão. A revisão visual não representa validação on-chain. Receber usa o endereço derivado da conta selecionada e oferece cópia, sem QR fictício. Nenhum histórico ou contrato de token foi inventado.

Ao trocar conta, valores e posições da seleção anterior são invalidados. A estrutura do cofre e os caminhos de derivação não são alterados pela reconstrução visual.

## Arquivos

- `ui/theme/`: paleta, tipografia e formas.
- `CaedralIcon.kt` e `CaedralSheet.kt`: elementos compartilhados.
- `WalletHomeScreen.kt`: composição e navegação contextual.
- `WalletAssets.kt`: modelos de apresentação, lista, avisos e detalhes.
- `WalletAccountsSheet.kt`: seletor e criação de conta.
- `ImportWalletScreen.kt`: importação inicial e incorporada ao painel.
- `WalletTransferSheet.kt` e `TransferDraft.kt`: formulário, revisão e validação local.
- `MainActivity.kt`: fundo estável, áreas seguras, transições e foco do onboarding.

Os créditos de referência visual estão nos componentes correspondentes. Os desenhos vetoriais e o código Compose foram escritos para CaedralPay.

## Validação isolada no aparelho

O build aceita `-PcaedralIsolatedUiTest=true`, que troca exclusivamente o applicationId para `com.caedralpay.wallet.validation`. A instalação normal continua `com.caedralpay.wallet`. O namespace de código permanece igual. Isso permite exercitar UI, JNI e cofre com outro UID/diretório/Keystore, preservando a instalação pessoal.

```bash
./gradlew --no-configuration-cache -PcaedralIsolatedUiTest=true :app:connectedDebugAndroidTest :app:testDebugUnitTest
```

Os testes do cofre usam diretórios próprios no cache da instalação de teste e frases de teste. Os testes UI usam nomes/endereços ilustrativos e não abrem o cofre do usuário. Espresso e AndroidX JUnit foram atualizados para 3.7.0/1.3.0 após detectar incompatibilidade da versão antiga com o Android 17. Referência: [notas oficiais AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test).

Após a validação, gerar novamente o APK normal, sem a propriedade de isolamento:

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
./gradlew --offline --no-configuration-cache :app:lintDebug
```

## Limites da verificação

### Resultado desta entrega

- APK normal (`com.caedralpay.wallet`) compilado; o APK final não é o pacote de validação.
- Oito testes JVM passaram: quatro de catálogo/migração, três do formulário e o teste básico existente.
- Android Lint terminou sem erros, com avisos preexistentes de dependências/recursos.
- Rodada final no Samsung A57/Android 17: **18 testes instrumentados passaram**, com zero falhas, erros ou testes ignorados: quatro de recuperação/derivação, quatro de catálogo/cofre, nove de interface e um teste básico Android.
- A captura da página principal foi inspecionada. A cor de conteúdo padrão do tema foi explicitada para garantir o contraste também em componentes isolados/previews; a suíte completa passou novamente depois desse ajuste.
- A instalação pessoal foi preservada. As instalações temporárias de validação foram removidas após obter os resultados; o APK normal foi regenerado separadamente.
- TalkBack, biometria e aprovação estética final continuam com validação manual pendente.

Os testes não substituem a avaliação manual de TalkBack, biometria, conforto das animações e aparência no aparelho do usuário. Não foram adicionados benchmarks nem alegações de desempenho sem medição. A escala das animações continua sob controle das APIs Compose e da configuração do sistema.

RPC, preços, contratos, assinatura/transmissão e outros produtos financeiros permanecem no planejamento de integrações, fora do plano visual.
