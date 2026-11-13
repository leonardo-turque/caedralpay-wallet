# Cartão físico, celular como leitor e avisos locais

> Registro histórico do protótipo. A integração Origin posterior, incluindo deployment testnet e fluxo real do assinador, está em [ORIGIN_INTEGRACAO.md](ORIGIN_INTEGRACAO.md). Os limites abaixo descrevem aquela etapa anterior.

12/11/2026. Continuação do protótipo no PR de desenvolvimento do protocolo. O beta público não foi atualizado.

## Cartão e maquininha

O laboratório de cartão físico agora é uma tela separada, com três passos: aproximar, vincular conta e revisar cobrança. O laboratório HCE/cartão virtual permanece separado. Ambos exigem build debug isolado com `caedralProtocolPreview=true`; a maquininha é oferecida somente na seleção BSC testnet.

`PhysicalCardReader` usa Android ReaderMode/MIFARE Classic. Lê somente o bloco 4 após autenticação com a chave padrão do cartão de teste. Não grava nenhum bloco, UID, chave ou permissão. O fingerprint local é SHA-256 de domínio, UID e bloco; não é assinatura nem prova de autenticidade. Cartões clonáveis continuam clonáveis.

`TestCardBindings` associa esse fingerprint à carteira, índice da conta, endereço e validade de cinco anos, em arquivo cifrado com AES-256-GCM/Android Keystore e gravação atômica. O vínculo não contém seed, chave privada, PIN ou autorização de gasto. Não é enviado a servidor. Só pode ser criado no modo experimental. Um arquivo corrompido não é sobrescrito como se estivesse vazio.

O recebimento por aproximação identifica o protótipo; não autoriza dinheiro. A revisão calcula a política comercial existente em tBNB, inclusive piso literal de 0,10 tBNB. Não existe botão para liquidar no laboratório: integração do contrato ao assinador, aprovação do pagador, comprovante e financiamento continuam pendentes. Esta tela não representa certificação de terminal nem um checkout concluído.

### Verificação física realizada

No A57 foi executado um teste explícito e isolado: leitura do cartão compatível com MIFARE Classic 1K e vínculo cifrado à conta exclusiva QA que já tem tBNB. A conta foi recuperada da fixture QA privada, nunca da instalação pessoal. Resultado: `read=true`, `bound=true`, `cardWritten=false`, `transactionSigned=false`, chainId 97. O arquivo temporário de recuperação do teste foi removido ao terminar; o cofre QA cifrado foi preservado.

A primeira versão dessa tela misturava campos de cartão virtual, contrato e cartão físico. Após o usuário apontar a confusão, o fluxo físico foi extraído para `PhysicalTerminalSheet`, com uma tarefa por etapa. O leitor e o armazenamento validados foram preservados. O layout simplificado é verificado por teste de UI; não confundir essa verificação com uma segunda operação RF.

A identificação de memória do cartão não prova assinatura APDU/ISO-DEP. RC522/Mega não foi acionado: permanece pendente a adaptação dos sinais digitais de 5 V para 3,3 V. Também não houve teste de celular HCE aproximado do RC522. Não foi publicada nenhuma identificação individual do cartão nos registros do GitHub.

## Pop-ups e notificações

- Pop-ups Material 3 na página principal e nas telas modais, sem cobrir o conteúdo com diálogos bloqueantes.
- Estados distintos: envio em andamento, confirmado, falhou, recebimento confirmado e cartão reconhecido.
- Aproximação nunca gera “Compra confirmada”. O tipo de compra está reservado para futura integração com um comprovante de liquidação; não existe disparo de gasto de cartão nesta fase.
- Eventos de saída partem do outbox cifrado, depois da gravação durável. Assinatura ainda sem transmissão não é anunciada como envio. Confirmação usa o verificador de recibos existente.
- Referências de eventos são resumidas por SHA-256 para deduplicação. Os últimos 512 identificadores são persistidos cifrados. Não há lista de dados financeiros em SharedPreferences.
- Notificações Android exigem habilitação explícita em Aparência → Notificações e permissão `POST_NOTIFICATIONS`. Recusa não impede a Wallet de funcionar.
- Conteúdo genérico: sem valor, endereço, nome ou identificação de contraparte. Tela bloqueada recebe a versão pública “Há uma atualização na sua carteira”. O toque abre o acesso normal da Wallet, sem conceder autorização ou transportar dados de pagamento em Intent.
- Erros de notificação não mudam o resultado financeiro nem invalidam o histórico.
- Sem FCM, SDK de analytics ou servidor novo de clientes. O Android pode decidir como apresentar banners, sons e notificações conforme as configurações do aparelho.

## Recebimentos: cobertura exata

`IncomingActivityWatcher` começa no bloco atual com 15 confirmações, quando a conta é aberta. Não notifica como novos os recebimentos históricos. Enquanto a página principal está ativa, verifica até seis blocos por ciclo, incluindo encadeamento e checkpoint canônico. RPCs são os já usados pela Wallet, com checagem de chainId.

Recebimentos diretos de BNB precisam de valor positivo, destinatário correspondente e recibo bem-sucedido no bloco esperado. USDT usa `eth_getLogs` filtrado pelo contrato da rede e destinatário, com conferência do evento dentro do recibo bem-sucedido e do bloco canônico. Um log isolado, um aumento de saldo, recibo de outro bloco ou um token com mesmo símbolo não gera confirmação. Autotransferências para o mesmo endereço e valores zero são ignorados.

**Não é um indexador completo:** não acompanha aplicativo fechado, não reconstrói entradas durante inatividade, não identifica transferências internas de BNB por chamadas de contratos e não acompanha saídas feitas em outro dispositivo. Trocar/reabrir conta reinicia o checkpoint. Indisponibilidade de RPC ou reorg do checkpoint interrompe a confirmação de entradas, sem produzir aviso especulativo; a verificação de envios continua separadamente. Respostas e quantidade de eventos são limitadas. Push/background, backfill durável e transferências internas exigem trabalho posterior.

## Testes e reprodução

Validação local: 56 testes unitários aprovados, 12 testes instrumentados no emulador aprovados e 1 teste RF/vínculo no A57 aprovado. Builds debug normal e isolado, compilação de instrumentação e lint aprovados. A liquidação de compras não foi executada.


- Testes unitários de identificadores estáveis e separação de rede/estado/cartão vs compra.
- Testes Android de deduplicação, criptografia/AAD, vínculo persistente, corrupção sem sobrescrita, privacidade da notificação, recibo nativo adulterado e inclusão de evento USDT no recibo.
- Teste de UI assegura que a tela inicial da maquininha tenha uma tarefa e nenhum campo do laboratório virtual.
- Teste RF `PhysicalTerminalValidationTest` exige opt-in e fixture QA privada; não roda na CI normal nem assina transações.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  -PcaedralIsolatedUiTest=true -PcaedralProtocolPreview=true
```
