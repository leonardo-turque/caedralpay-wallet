# Plano de interface — CaedralPay Wallet

## Objetivo

Construir uma interface Android própria em Kotlin/Jetpack Compose, usando o estudo de fluxos de carteiras consolidadas como referência de usabilidade. A identidade é CaedralPay: fundo escuro, destaque `#DDD6CD`, texto legível, hierarquia clara e movimento discreto.

Este documento organiza o trabalho. As fases de implementação são executadas individualmente, com build e teste manual do responsável pelo produto antes do avanço. Um plano não significa que as telas descritas já existem ou foram aprovadas.

**Atualização de execução:** o usuário autorizou posteriormente a implementação integral das fases. O escopo visual implementado e os resultados/limites da validação estão em [IMPLEMENTACAO_INTERFACE.md](IMPLEMENTACAO_INTERFACE.md). A validação manual final continua pendente, sem bloquear a execução das fases já autorizadas.

## Ponto de partida

Na branch de trabalho `fase/interface-principal`, já existem implementações locais de:

- Cabeçalho com marca e nome da carteira, saldo BRL e variação diária com estado indisponível.
- Painel inferior para listar carteiras/contas, selecionar uma conta, criar conta derivada e importar outra carteira.
- Persistência cifrada do catálogo e da seleção, com leitura do formato anterior do cofre.
- Onboarding, importação inicial, PIN e biometria.

Por decisão do usuário na fase 0, a apresentação atual da página principal e do seletor será substituída. As capacidades funcionais já desenvolvidas serão preservadas e conectadas à interface reconstruída. Build, lint e testes JVM foram executados na etapa anterior; a execução dos novos testes instrumentados continua pendente. A `main` publicada anteriormente não contém automaticamente as alterações locais desta branch.

## Referência de estudo

Repositório: [MetaMask Mobile](https://github.com/MetaMask/metamask-mobile).

Snapshot consultado: `a216ff1ef6c0af89eafbf8a19aab1a15b4b281af`. A cópia de estudo fica fora do aplicativo; o plano e os links abaixo não dependem da permanência dessa cópia temporária.

A [licença pública nesse snapshot](https://github.com/MetaMask/metamask-mobile/blob/a216ff1ef6c0af89eafbf8a19aab1a15b4b281af/LICENSE) permanece como referência de origem. Em 11/11/2026, o responsável pela CaedralPay informou ter obtido autorização de um desenvolvedor da MetaMask para uso/adaptação visual, com créditos em comentários. O registro representa a autorização relatada pelo responsável; não modifica a licença pública. Os componentes visuais aproveitados terão origem e adaptações identificadas conforme o [registro da fase 0](FASE_0_INTERFACE.md).

A referência usa React Native/TypeScript; o aplicativo CaedralPay usa Compose. Não haverá migração de framework nesta iniciativa.

| Área de estudo | Referência no snapshot | O que observar | Entrega própria |
| --- | --- | --- | --- |
| Cabeçalho | `app/components/Views/Wallet/components/WalletHeader/` | Hierarquia, nome longo, acesso ao seletor | Cabeçalho Compose com marca Caedral |
| Carteiras/contas | `app/components/Views/AccountSelector/` | Agrupamento, seleção atual, ações secundárias | Painel inferior conectado ao catálogo existente |
| Painéis inferiores | `app/component-library/components/BottomSheets/` | Abertura, fechamento, rolagem, teclado | Componentes Material 3 com estado explícito |
| Página principal | `app/components/Views/Wallet/` | Ordem das informações e estados de carregamento | Página organizada em seções independentes |
| Componentes de ação | `app/component-library/components/Buttons/` | Prioridade, feedback e estados desabilitados | Botões e campos padronizados Caedral |

Esses diretórios são um roteiro de estudo, não uma declaração de que todos os componentes já foram examinados em detalhe. Cada fase começa pela observação do comportamento relevante e termina com decisões próprias documentadas no PR.

## Regras de implementação

- Usar o desenho e os requisitos aprovados pelo usuário como fonte principal do visual.
- Estudar o recorte da fase e implementar/adaptar o visual para Compose, registrando créditos nos trechos que aproveitarem a referência MetaMask.
- Separar componentes de apresentação das operações do cofre. A UI recebe nomes, endereços, seleção e estados; não recebe PIN, frase ou chaves para desenhar componentes.
- Reaproveitar a integração CaedralPay existente. Não incorporar controllers, keyrings, serviços ou infraestrutura da MetaMask.
- Manter as propriedades de proteção já existentes, incluindo bloqueio de sessão e proteção de janelas que exibem informações sensíveis.
- Não modificar algoritmos, formato do cofre ou caminhos de derivação por conveniência visual. Mudanças necessárias nessa área constituem uma tarefa separada, com testes de compatibilidade.
- Dados fictícios ficam em previews/fixtures de teste. A tela real distingue carregamento, ausência de dados, saldo zero e erro.
- Toda mudança de conta deve atualizar a identidade e invalidar o estado de saldo/ativos da seleção anterior antes de integrar dados reais.
- Não adicionar botões, abas ou funcionalidades apenas porque existem no aplicativo de referência.

## Fase 0 — Inventário e contrato visual

**Estado:** estudo e contrato de reconstrução documentados em [FASE_0_INTERFACE.md](FASE_0_INTERFACE.md); proposta visual disponível para validação do responsável.

**Entrega:** inventário do código atual e da referência MetaMask, diagnóstico, decisões de identidade, mapa de componentes/funções e proposta visual. A interface anterior foi rejeitada como direção estética. O teste no aparelho ocorrerá a cada etapa implementada da reconstrução.

**Aceite:** escopo legível, diferenças entre implementação local e publicada identificadas e nenhuma alteração funcional feita apenas para produzir o plano.

## Fase 1 — Fundação visual CaedralPay

**Estudo:** hierarquia de componentes, tipografia, espaçamentos e estados de interação.

**Entrega:** centralizar cores, superfícies, tipografia, formas e espaçamentos; definir componentes próprios para botão primário/secundário, campo, linha selecionável, carregamento e mensagem de erro. Consolidar um tema escuro consistente, evitando que cores dinâmicas do sistema alterem a marca. Padronizar fundo da janela e dos painéis.

**Arquivos de partida:** `ui/theme/`, `WalletHomeScreen.kt`, `WalletAccountsSheet.kt` e `ImportWalletScreen.kt`. Extrair componentes reutilizáveis quando houver uso concreto; não criar uma arquitetura inteira antecipadamente.

**Aceite:** cores da marca consistentes em foco, seleção e carregamento; sem flashes claros; fontes do sistema ou com licença adequada; alvos interativos de pelo menos 48 dp; textos legíveis com fonte ampliada. Migrar as telas por recortes revisáveis.

## Fase 2 — Cabeçalho e resumo da conta

**Estudo:** seletor no cabeçalho e hierarquia entre identidade, saldo e variação.

**Entrega:** reconstruir o topo com marca, carteira e conta ativa, indicação de abertura do seletor e saldo BRL em destaque. Incluir a fileira de ações **Comprar, Trocar, Enviar e Receber**, nesta ordem, com Comprar/Trocar visíveis e desativados. Ajustar tamanhos e espaçamento conforme a referência visual aprovada. Representar ganhos em verde, perdas em vermelho e ausência de dados em tom neutro.

**Aceite:** nomes longos não deslocam controles; números grandes não são truncados; valor e percentual usam formatação brasileira; ganho/perda também são compreensíveis pelo sinal/texto; nenhuma rentabilidade inventada. Dados de preview não aparecem no fluxo autenticado.

## Fase 3 — Painel de carteiras e contas

**Estudo:** agrupamento, seleção, rolagem, feedback e retorno entre etapas.

**Entrega:** refinar `WalletAccountsSheet` com hierarquia clara entre carteira e contas, marcador acessível da seleção, endereços abreviados e ações para criar conta e importar carteira. Harmonizar os formulários já existentes com a fundação visual.

**Comportamento existente a preservar:** conta nova deriva da mesma frase; carteira importada tem sua própria frase; a recuperação de múltiplas contas é sequencial e a quantidade é manual nesta etapa. A frase não informa quantas contas eram utilizadas, e ainda não há descoberta por atividade na rede.

**Aceite:** toque no nome abre o painel suavemente; selecionar salva e atualiza o cabeçalho; fechar sem selecionar mantém a conta anterior; falhas de gravação preservam o estado anterior; toque repetido durante salvamento não duplica operações. Validar carteira duplicada, lista longa, fonte ampliada, teclado, cancelar, bloquear e reabrir. Aprovar persistência e migração em testes próprios do cofre, separados dos ajustes visuais.

## Fase 4 — Lista de ativos e estados da página

**Estudo:** leitura rápida de ativo, rede, quantidade e equivalente monetário.

**Entrega:** seção de ativos conforme desenho aprovado, com linhas próprias para nome/símbolo, identificação da rede, quantidade e valor em reais. Preparar estados vazio, carregando, indisponível e erro com nova tentativa. O primeiro recorte considera BNB Smart Chain e USDT, conforme o escopo do produto.

**Dependência:** consulta de saldos, identificação dos tokens, preço BRL, gas e atualização por conta/rede são integrações separadas. Até existirem, a interface mantém estado indisponível ou previews claramente separados. Não apresentar saldo zero quando a consulta falhou ou não ocorreu.

**Aceite:** hierarquia consistente, precisão sem arredondamento enganoso, estados distinguíveis e nenhum dado de outra conta aparecendo após troca de seleção.

## Fase 5 — Telas de apoio aprovadas

**Estudo:** continuidade entre página principal, detalhes e retorno.

**Entrega:** desenhar e implementar os destinos visuais de Enviar e Receber, cujos pontos de entrada foram solicitados pelo usuário na revisão da fase 0. Outras telas, como detalhes de ativo ou atividade, serão definidas individualmente. Comprar e Trocar permanecem visíveis e desativados; QR, histórico e navegação inferior não são adicionados automaticamente por constarem da referência. Ativar um ponto de entrada não significa implementar envio on-chain nesta fase visual.

**Dependência:** recebimento real exige endereço/rede validados; histórico exige fonte de dados; envio exige fluxo completo de transação e confirmação. Cada integração tem escopo e aceite próprios.

**Aceite:** voltar retorna ao contexto correto; a conta ativa é explícita; gestos e navegação do sistema funcionam; não há ação visual que aparente executar uma função ainda inexistente.

## Fase 6 — Consistência, movimento e acabamento

**Entrega:** revisar as telas aprovadas como um conjunto: animações discretas, fundos consistentes, foco, teclado, feedback de toque, erro e carregamento. Harmonizar o onboarding com os componentes já aprovados sem alterar suas regras. Avaliar TalkBack, fonte ampliada, barras do sistema e diferentes tamanhos de tela.

**Aceite:** nenhum flash branco, salto perceptível de layout ou animação que impeça o uso; respeitar a escala de animação do sistema; foco previsível; painéis sensíveis protegidos; retorno do segundo plano exige o desbloqueio previsto. Medir desempenho antes de adicionar bibliotecas ou otimizações.

## Execução de cada fase

1. Registrar o recorte: comportamento de referência, desenho Caedral, estados e critérios de aceite.
2. Implementar componentes próprios em Compose e previews dos estados relevantes.
3. Executar build e lint; adicionar testes que cubram riscos reais da alteração. Mudanças no cofre exigem testes de persistência e compatibilidade, não apenas previews.
4. Entregar ao usuário o resultado concreto e os pontos para testar no aparelho.
5. Aguardar o build/teste manual e corrigir o que for observado antes de avançar a fase.
6. Na consolidação autorizada, abrir PR com escopo e evidências; fazer merge e criar tag conforme a política do repositório.

Comandos disponíveis:

```bash
./gradlew :app:assembleDebug
./gradlew --no-configuration-cache :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
```

O último comando compila os testes Android, mas não os executa. A instalação e execução em aparelho seguem o ambiente de teste definido com o usuário.

## Fora deste plano visual

Mudanças na custódia, criptografia, autenticação e derivação; migração para React Native; importação dos serviços/controllers/keyrings da MetaMask; implementação de RPC, preços, transações, NFC, contratos de split, on-ramp e outros produtos financeiros. Esses assuntos têm planejamento e aprovação próprios.

## Próxima entrega

A implementação integral autorizada está registrada em [IMPLEMENTACAO_INTERFACE.md](IMPLEMENTACAO_INTERFACE.md). O próximo passo de produto é validar o conjunto no aparelho e ajustar os pontos observados. Integrações de dados e transações continuam fora deste plano visual.
