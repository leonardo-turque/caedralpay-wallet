# Origin, aproximação e maquininha Caedral

13/11/2026. Integração na branch de desenvolvimento. O APK do beta público não foi substituído. Esta etapa implementa Origin; Obsidian e Sovereign não estão habilitados.

## Como testar com dois Androids

Use o mesmo build nos dois aparelhos, Android API 37+, NFC habilitado, com contas **diferentes**. Selecione BSC testnet nas configurações existentes. O cartão não tem um saldo próprio.

1. Pagador: **Cartões**, informe um nome e um sobrenome, **Criar meu Origin**. O cartão e seus dados ficam prontos localmente, em qualquer rede selecionada. Na rede compatível, **Ativar pagamentos nesta rede**, revise o gás e confirme com PIN ou biometria. Aguarde a confirmação da ativação.
2. Lojista: **Maquininha**, escolha tBNB ou USDT, digite o valor e **Criar cobrança**. Autorize a assinatura da cobrança. O recebimento vai à conta selecionada do lojista; assinar a cobrança não consome gás.
3. Pagador: toque no Origin ou em **Aproximar para pagar**, autentique. O cartão aparece grande, vertical, com indicador NFC pulsante. Encoste as traseiras dos aparelhos; ambos devem permanecer abertos e desbloqueados.
4. Depois da entrega NFC, confira valor, endereço do lojista, cashback e gás no aparelho pagador. Confirme com PIN ou biometria. Em USDT, a primeira compra exige autorizar **somente o valor da compra**, aguardar e confirmar o pagamento; uma autorização anterior não zero pode exigir zerar antes.
5. Os dois aparelhos acompanham o recibo. O lojista só mostra **Pagamento recebido** depois de verificar a liquidação e 15 confirmações. Remova os celulares da aproximação após a entrega da cobrança.

A cobrança dura 120 segundos. Se a aprovação/autenticação demorar, uma nova cobrança será necessária. A taxa comercial é `max(1% arredondado para cima, 0,10 do token)`. Para um teste nativo, **0,12 tBNB** resulta em 0,02 tBNB líquidos ao lojista, cashback inicial de 0,00012 tBNB e 0,09988 tBNB à tesouraria; o gás é adicional. O piso não foi convertido para dólar. Ativação também requer um pouco de tBNB para gás. O lojista não precisa ter gás para receber.

**Sem instalar outro aplicativo no A57:** atualizar o pacote já usado, preservando seu armazenamento. Não desinstalar para atualizar. Os testes automatizados desta etapa foram executados no emulador; não representam a aproximação RF entre dois aparelhos.

## Cartão e proteção dos dados

- **Mesmo cartão em todas as redes da conta:** número, código, titular, ID e validade são globais para a conta, sem chave de armazenamento por rede. Reabrir a tela, mudar de rede ou ficar sem RPC não cria outros dados. A ativação on-chain é separada, por deployment; não há emissão automática na troca de rede.
- Frente Origin azul escuro em Compose, marca Caedral existente, nome escolhido e final mascarado. Temas respeitam o tema do app. Não usa imagem de cartão contendo números fixos.
- Dados locais: número privado de 16 dígitos com verificador Luhn, código aleatório de seis dígitos, nome e vencimento após cinco anos de calendário UTC. Prefixo 99 identifica o formato privado nesta implementação, sem BIN ou compatibilidade com outra bandeira. Não há cadastro de PAN global; unicidade criptográfica do protocolo é o ID de 256 bits vinculado ao endereço e salt aleatório.
- ID = SHA-256(`CaedralOriginCardId/v1` ASCII || endereço 20 bytes || salt 32 bytes). O contrato recalcula usando `msg.sender`. Outra conta não consegue reservar esse ID antecipando a transação.
- Número e código são cifrados por AES-256-GCM com a chave do cofre; AAD vincula ID, conta, endereço, salt, nome, datas e final do número. O registro recebe uma segunda camada de cifragem Android Keystore e gravação atômica.
- Revelar exige autorização forte de uso único, vinculada à conta e ao cartão. Dados somem após 30 segundos e ao pausar a tela. As janelas do app mantêm a proteção de captura existente.
- Regenerar exige nome escolhido e PIN/biometria. Novos ID, salt, número e código. O app passa a usar os novos dados locais; em cada rede, o ID antigo só é invalidado quando a transação de substituição confirma naquela rede. Uma regeneração não revoga contratos em redes que não receberam essa transação. Os fundos da conta não são movidos. Não são geradas novas palavras.
- Restaurar palavras recupera a conta e os fundos, **não** esses dados de cartão locais. Em outro aparelho, emita um novo Origin; a substituição invalida o anterior no protocolo.
- Não há saldo de cartão, servidor com seed ou autorização de débito em segundo plano. O nome não é identidade verificada. Número/código não assinam transações NFC. Checkout online por número/nome/código/validade permanece para fase futura, conforme solicitado.

## Liquidação e rede

Contrato ativo desta etapa: `CaedralOriginPayments.sol`, BSC testnet chain 97. Endereço, runtime hash, tx de deployment, tesouraria e custos constam em [97-origin.json](../protocol/deployments/97-origin.json). O cliente verifica chain ID e hash do runtime antes de preparar/assinar. A configuração não aceita endereço de contrato enviado por outro celular.

Somente moeda nativa e o USDT de teste fixado, ambos com 18 decimais. Ativação é transação do titular, sem chave central de emissão. Um Origin ativo por endereço. Expiração e substituição são verificadas on-chain. É possível ativar o mesmo ID/validade dias depois em outro deployment: o contrato aceita vencimento futuro limitado a 1.827 dias, enquanto a emissão local calcula cinco anos exatos de calendário. A ativação não renova a validade nem gera números. Apenas o titular pode gastar; um número de cartão não dá permissão de gasto.

Lojista EOA assina EIP-712: domínio `CaedralOrigin`, versão `1`, chain ID e contrato; corpo inclui lojista, token, bruto, ID aleatório e prazo. O pagador assina a transação específica com Wallet Core depois de revisar. Autorização local vincula nonce, destino, calldata, valor e limite de gás; não autoriza transação arbitrária.

Split atômico: lojista recebe bruto menos taxa; cashback 0,1% inicialmente, Origin 1% em **01/02/2027 03:00 UTC**; tesouraria recebe taxa menos cashback. O contrato envia o cashback diretamente a `msg.sender` na mesma moeda e transação da compra, sem crédito pendente, saque ou saldo separado. Exemplo de 100 USDT: 99 ao lojista, 0,1 USDT ao pagador e 0,9 USDT à Caedral; o gás é adicional. O saldo na home é atualizado após o evento de confirmação verificado. A partir dessa data, acima do piso, Origin pode consumir toda a receita comercial. Esta implantação não habilita benefícios de Obsidian/Sovereign nem reserva subsidiada. Moeda nativa usa valor exato; ERC-20 usa SafeERC20 e verifica o valor recebido. Guard de reentrância e consumo único da cobrança. Não há proxy nem saque administrativo.

Outbox cifrado grava transação assinada antes de transmitir. Retomada retransmite o **mesmo hash**, sem fabricar uma segunda compra; operações de envio comum e Origin compartilham bloqueio para evitar nonce concorrente. Recibos conferem hash, origem/destino, bloco canônico, status, eventos e valores. Ativação, autorizações de token, compras e cobranças aparecem na atividade local.

As versões bootstrap e de ativação inicial foram substituídas antes do beta público, para vincular o ID ao titular e permitir ativação posterior do mesmo cartão sem alterar sua validade. Fontes e provas anteriores ficam em `protocol/deployments/archive/`, explicitamente supersedidas. O outbox fixa o contrato/spender assinado por operação; atualizar a configuração não redireciona transações históricas. Não enviar pagamentos para ela. Não existe implantação Origin mainnet nesta entrega; BNB/USDT P2P mainnet já existentes continuam separados.

## Transporte NFC

AID privado experimental **F0434145445202**, separado do laboratório antigo `...01`. Android ReaderMode no lojista e HostApduService no pagador; serviço exige `BIND_NFC_SERVICE`, desbloqueio e tela acesa.

- SELECT: `00 A4 04 00 07 F0434145445202`.
- Resposta: chave pública efêmera X25519 DER de 44 bytes + `9000`.
- Solicitação: invoice binária de 152 bytes + assinatura ECDSA de 65 bytes = 217 bytes.
- Envelope X25519/HKDF-SHA256/AES-256-GCM: 44 bytes de chave pública + 12 IV + 217 ciphertext + 16 tag = 289 bytes. AAD inclui contexto e ambas as chaves públicas.
- Dois APDUs `80 20 seq final tamanho dados`, payload de 200 e 89 bytes. Sequência e limites estritos. `9000` significa entrega, nunca pagamento.
- Sessão de 120 segundos, efêmera, uma solicitação por ativação. Pausa, saída e bloqueio apagam a sessão. Dados do cartão, nome, PIN, seed e chave de gasto não passam no NFC.

A assinatura impede alteração silenciosa da cobrança; o canal não oferece prova criptográfica de distância e não elimina relay ativo. O pagador revisa e autoriza a compra no próprio aparelho. Identificador experimental não implica certificação NFC/EMV ou registro mundial do AID.

O cartão branco Classic 1K continua no laboratório de identificação local; não virou credencial criptográfica de pagamento. RC522/Mega não foi usado como terminal HCE nesta etapa.

## Notificações

Na primeira entrada na home após criação/importação, o app pergunta se deseja receber notificações e, se aceitar, pede a permissão Android. Recusa é lembrada; é possível alterar nas configurações. Instalações existentes também recebem esse convite uma vez se não habilitaram antes.

Compra e recebimento comercial só geram aviso depois do recibo verificado. Deduplicação persistida cifrada, conteúdo privado na tela bloqueada. Monitoramento desta versão é **com o aplicativo aberto**; não é serviço push nem acompanhamento garantido com o processo encerrado.

## Validação e limites

`forge test --root protocol`: testes adversariais, contabilidade, assinaturas, replay, expiração e troca de cartão. `qa/run-origin-tests.py`: Anvil local chain 97 + emulador isolado, Wallet Core/cofre reais, ativação, assinatura comercial, APDUs cifrados em software, pagamento nativo e ERC-20, recibo do lojista, persistência e regeneração. Não usa fundos públicos nem instala pacote em celular.

Registro público de um Origin QA na testnet valida RPC, runtime fixado, assinatura Android e confirmação real. Compra pública e RF entre dois celulares precisam da validação manual separada. Resultados desta etapa estão em `qa/results/origin-integration.json`. Não representa auditoria independente ou garantia de ausência de falhas.
