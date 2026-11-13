# Maquininha e débito automático Origin

Implementação de desenvolvimento de 13/11/2026. A recorrência usa autorização inicial do cliente e execução posterior independente do aplicativo. O APK público não recebe esta alteração automaticamente.

## Experiência

A maquininha abre em tela inteira com estas etapas:

1. Rede: somente contratos realmente implantados ficam habilitados.
2. Moeda: BNB ou USDT da rede escolhida pelo lojista.
3. Pagamento único ou recorrente; parcelamento foi removido deste fluxo.
4. Para recorrência: intervalo inteiro de 1 a 3650 dias e data final, ou sem encerramento escolhido.
5. Valor por cobrança e revisão da taxa e do líquido recebido.
6. Autenticação do lojista, apresentação por NFC e recibo após comprovação on-chain.

O cliente abre seu cartão com biometria forte prioritária/PIN alternativo. A aproximação transmite a oferta assinada, cifrada e de uso único. Para a recorrência, a revisão mostra valor, ativo, lojista, intervalo, encerramento e teto de gás inicial; **Autorizar e pagar** autentica a autorização e faz o primeiro débito. As próximas cobranças não pedem nova confirmação nem dependem do celular ligado.

**Cartões → Assinaturas** consulta as autorizações diretamente na rede, inclusive após recuperar a carteira em outro aparelho. Permite cancelar com autenticação; o cancelamento se torna efetivo quando confirmado na rede. A lista tem paginação. Os mesmos dados do cartão continuam valendo em todas as redes; o cliente não escolhe ou ativa a moeda do cartão.

## Autorização e saldo

`CaedralRecurringPayments` armazena pagador, lojista, moeda, valor fixo, intervalo, início, encerramento e validade do cartão. A oferta EIP-712 identifica chain ID e contrato, vale 120 segundos e não pode ser reutilizada. Alterar qualquer condição exige outra autorização do cliente.

Para permitir débito de BNB nativo e USDT **no endereço original da conta**, a primeira autorização utiliza EIP-7702. O contrato `CaedralRecurringAccount` é imutável: somente o endereço fixo do contrato de recorrência pode chamar `pull`, e somente para BNB/USDT. Não possui execução arbitrária, administrador, inicializador, atualização ou permissão para um operador acessar chaves do cliente. O aplicativo rejeita delegação diferente já instalada no endereço. O cofre assina localmente a autorização restrita à rede 97 e ao nonce seguinte ao da transação; não assina autorização com chain ID zero.

O primeiro débito exige o valor integral e o teto de gás. Os seguintes exigem saldo integral do ativo, e o executor paga o gás com sua própria conta operacional. Sem saldo suficiente, a operação reverte integralmente: não há saldo negativo, adiantamento nem dívida acumulada. É possível tentar novamente no mesmo período. Períodos inteiros perdidos não são cobrados em lote: no máximo um débito no período atual. O encerramento escolhido é inclusivo até o fim do dia no fuso do aparelho que criou a cobrança; depois de assinado, o instante UTC fica fixo. Mesmo “sem encerramento” respeita a validade de cinco anos do cartão usada na autorização.

Cada débito divide atomicamente o ativo: lojista recebe bruto menos taxa; Caedral cobra 1%, com piso literal de 0,10 do ativo; cashback inicial de 0,1% do bruto volta ao endereço do cliente na mesma moeda, saindo da taxa Caedral. Origin passa a 1% em 01/02/2027, 00:00 America/Sao_Paulo. Não existe reserva nem saldo separado do cartão.

Cancelar uma assinatura não altera o saldo da conta ou as demais assinaturas. Regenerar os dados locais do cartão não cancela automaticamente autorizações já concedidas; estas aparecem em Assinaturas para cancelamento explícito. A delegação permanece na conta após cancelar, sem permitir débitos fora das assinaturas ativas.

## Executor

`protocol/keeper/keeper.mjs` encontra autorizações com 15 confirmações, consulta vencimentos e simula cada cobrança antes de assinar. Qualquer operador pode executar `collect(id)`; as regras do contrato determinam destinatários e valores. O operador só possui sua própria chave de gás, não a chave, frase, PIN ou dados do cartão do cliente.

O executor valida chain ID e hash do runtime, usa teto de gás/preço e orçamento total configurado. Persiste a transação assinada antes de transmiti-la, retoma o mesmo hash, espera 15 confirmações e verifica o bloco canônico. Um bloqueio de processo evita execuções concorrentes no mesmo estado. O estado privado é vinculado ao deployment. Sem gás/orçamento ou com indisponibilidade do operador/rede, a cobrança espera; disponibilidade permanente depende de manter o serviço operacional.

Instalação para laboratório:

```bash
npm ci --prefix protocol/keeper --ignore-scripts
mkdir -p ~/.local/share/caedralpay/recurring-keeper
chmod 700 ~/.local/share/caedralpay/recurring-keeper
# Criar config.json com permissão 600, fora do Git, usando uma chave operacional exclusiva.
CAEDRAL_KEEPER_CONFIG="$HOME/.local/share/caedralpay/recurring-keeper/config.json" node protocol/keeper/keeper.mjs --once
```

Campos privados de configuração: `chainId` (97), `rpc`, `settlement`, `runtimeHash`, `startBlock`, `budgetWei`, `operatorKey`. Os três campos do deployment devem vir do comprovante verificado. O exemplo de serviço de usuário está em `protocol/keeper/caedral-recurring-keeper.service`; revise o caminho antes de instalar. Nunca usar frases/chaves de clientes nem configurar mainnet nesta versão do executor.

## Verificação e limites de entrega

- Testes Solidity cobrem saldo insuficiente nativo/token, cashback, calendário, encerramento, validade, cancelamento, assinatura alterada, replay, domínio de rede e bloqueio de chamadas arbitrárias à conta.
- `qa/run-origin-tests.py` cria Anvil isolado, instala contratos locais e exercita Android/WalletCore com transações tipo 4 reais, HCE/APDUs cifradas, cobrança por outro executor com o cofre bloqueado, cancelamento e compatibilidade com a conta original. Executa também o keeper Node real em uma fixture pública local.
- `MerchantWizardUiTest` percorre o formulário sem acessar NFC ou rede ao abrir a maquininha.
- O aplicativo acompanha recibos e notificações enquanto aberto; notificações push com o processo Android encerrado não fazem parte desta etapa. O débito on-chain automático não depende dessas notificações.
- Aproximação física entre dois Androids continua exigindo validação manual. Não confundir execução APDU em teste com ensaio de rádio entre dois aparelhos.
- O executor de laboratório está instalado como serviço de usuário `caedral-recurring-keeper` neste computador, com chave operacional exclusiva de QA, 0,001 tBNB para gás e orçamento limitado a esse valor. Ele depende deste computador conectado. O provedor testnet utilizado aceita consultas de eventos; chain ID e hashes dos contratos são verificados.
- Mainnet da wallet para transferências comuns permanece separada. Contratos e executor de recorrência são de testnet; nada desta etapa publica uma cobrança automática de fundos reais.

Referências de implementação: [EIP-7702](https://eips.ethereum.org/EIPS/eip-7702), [BSC BEP-441](https://github.com/bnb-chain/BEPs/blob/master/BEPs/BEP-441.md), [WalletCore 4.8.7](https://github.com/trustwallet/wallet-core/blob/4.8.7/rust/tw_evm/src/modules/tx_builder.rs). A integração usa a assinatura explícita de autorização porque o caminho automático do WalletCore restringe o destinatário à própria conta.

Comprovantes públicos: [contratos e runtimes](../protocol/deployments/97-recurring.json), [executor de laboratório](../qa/results/recurring-keeper.json), [validação Android contra a rede pública](../qa/results/recurring-public-ready.json). A conta QA não tinha o mínimo de 0,10 tBNB para uma compra nativa pública; nessa rede foram comprovados os contratos e o bloqueio por falta de saldo, sem enviar autorização nem débito. Débitos completos nativos/token e subsequentes foram executados no EVM local.
