# Origin, aproximação e maquininha Caedral

13/11/2026. Integração na branch de desenvolvimento. O APK do beta público não foi substituído. Esta etapa implementa Origin; Obsidian e Sovereign não estão habilitados.

O fluxo de maquininha foi reorganizado em etapas; consulte [recorrência e autorização inicial](RECORRENCIA.md) para débito automático, calendário, cancelamento e executor. Parcelamento foi retirado do fluxo.

## Como testar com dois Androids

Use o mesmo build nos dois aparelhos, Android API 37+, NFC habilitado, com contas **diferentes**. Selecione BSC testnet nas configurações existentes. O cartão não tem um saldo próprio.

1. Pagador: toque no **cartão na home**, informe um nome e um sobrenome, **Criar meu Origin**. Os dados ficam prontos localmente, sem ativar rede ou moeda e sem transação de emissão.
2. Lojista: na rede que escolheu, abra **Maquininha**, escolha a moeda, digite o valor e **Criar cobrança**. O lojista assina a cobrança. O cliente não escolhe nem troca a moeda dessa compra.
3. Pagador: toque no Origin ou em **Aproximar para pagar**. A autenticação tenta primeiro a biometria forte configurada no aparelho/cofre. O PIN aparece como alternativa, quando necessário. O cartão fica vertical e o indicador NFC pulsa. Essa etapa não depende da rede selecionada na home nem de um registro prévio on-chain.
4. Encoste as traseiras dos aparelhos, com ambos abertos/desbloqueados. A cobrança recebida determina rede, moeda, lojista e valor. Revise o valor e o teto total de gás e confirme a compra. Não há tela de ativação de rede ou de moeda. Se o token precisar de aprovação técnica, o app a executa como parte da compra já consentida, com valor exato, destinos fixos e orçamento limitado.
5. Os aparelhos acompanham o recibo. O lojista só mostra **Pagamento recebido** após verificar a liquidação e 15 confirmações. Entrega NFC não significa pagamento. O cashback é transferido à conta pagadora dentro da mesma transação da compra.

A cobrança dura 120 segundos. Se a aprovação/autenticação demorar, uma nova cobrança será necessária. A taxa comercial é `max(1% arredondado para cima, 0,10 do token)`. Para um teste nativo, **0,12 tBNB** resulta em 0,02 tBNB líquidos ao lojista, cashback inicial de 0,00012 tBNB e 0,09988 tBNB à tesouraria; o gás é adicional. O piso não foi convertido para dólar. Não existe taxa de ativação: gás é usado nas transações necessárias à compra. O lojista não precisa ter gás para receber.

**Sem instalar outro aplicativo no A57:** atualizar o pacote já usado, preservando seu armazenamento. Não desinstalar para atualizar. Os testes automatizados desta etapa foram executados no emulador; não representam a aproximação RF entre dois aparelhos.

## Cartão e proteção dos dados

- **Mesmo cartão em todas as redes da conta:** número, código, titular, ID e validade são globais para a conta, sem chave de armazenamento por rede. Reabrir a tela, mudar de rede ou ficar sem RPC não cria outros dados. Não há etapa de ativação por rede ou por moeda. O contrato registra o primeiro uso dentro do pagamento, sem transação separada.
- Frente Origin azul escuro em Compose, marca Caedral existente, nome escolhido e final mascarado. Temas respeitam o tema do app. Não usa imagem de cartão contendo números fixos.
- Dados locais: número privado de 16 dígitos com verificador Luhn, código aleatório de seis dígitos, nome e vencimento após cinco anos de calendário UTC. Prefixo 99 identifica o formato privado nesta implementação, sem BIN ou compatibilidade com outra bandeira. Não há cadastro de PAN global; unicidade criptográfica do protocolo é o ID de 256 bits vinculado ao endereço e salt aleatório.
- ID = SHA-256(`CaedralOriginCardId/v1` ASCII || endereço 20 bytes || salt 32 bytes). O contrato recalcula usando `msg.sender`. Outra conta não consegue reservar esse ID antecipando a transação.
- Número e código são cifrados por AES-256-GCM com a chave do cofre; AAD vincula ID, conta, endereço, salt, nome, datas e final do número. O registro recebe uma segunda camada de cifragem Android Keystore e gravação atômica.
- Revelar exige autorização forte de uso único, vinculada à conta e ao cartão. Dados somem após 30 segundos e ao pausar a tela. As janelas do app mantêm a proteção de captura existente.
- Regenerar exige nome escolhido e PIN/biometria. Novos ID, salt, número e código. O app passa a usar os novos dados locais; o assinador da Wallet deixa de aceitar os dados antigos. On-chain, o primeiro pagamento com o novo ID substitui o ID anterior naquele contrato. Uma rede sem qualquer interação não recebe uma revogação global por si só; possuir as palavras continua permitindo controlar a conta fora do aplicativo. Os fundos da conta não são movidos. Não são geradas novas palavras.
- Restaurar palavras recupera a conta e os fundos, **não** esses dados de cartão locais. Em outro aparelho, emita um novo Origin; a substituição invalida o anterior no protocolo.
- Não há saldo de cartão, servidor com seed ou autorização de débito em segundo plano. O nome não é identidade verificada. Número/código não assinam transações NFC. Checkout online por número/nome/código/validade permanece para fase futura, conforme solicitado.

## Liquidação e rede

Contrato ativo desta etapa: `CaedralOriginPayments.sol`, BSC testnet chain 97. Endereço, runtime hash, tx de deployment, tesouraria e custos constam em [97-origin.json](../protocol/deployments/97-origin.json). O cliente verifica chain ID e hash do runtime antes de preparar/assinar. A configuração não aceita endereço de contrato enviado por outro celular.

Somente moeda nativa e o USDT de teste fixado, ambos com 18 decimais. O primeiro uso é incluído na compra assinada pelo titular. Não existe função pública de ativação no contrato atual. Cada pagamento vincula ID/salt/validade ao `msg.sender`; o ID atual e os IDs já substituídos são registrados pelo próprio pagamento. A validade não é renovada por mudar de rede. O contrato aceita vencimento futuro limitado a 1.827 dias, enquanto a emissão local calcula cinco anos exatos de calendário. Apenas o titular pode gastar; um número de cartão não dá permissão de gasto.

Lojista EOA assina EIP-712: domínio `CaedralOrigin`, versão `1`, chain ID e contrato; corpo inclui lojista, token, bruto, ID aleatório e prazo. O pagador assina a transação específica com Wallet Core depois de revisar. Autorização local vincula nonce, destino, calldata, valor e limite de gás; não autoriza transação arbitrária.

Split atômico: lojista recebe bruto menos taxa; cashback 0,1% inicialmente, Origin 1% em **01/02/2027 03:00 UTC**; tesouraria recebe taxa menos cashback. O contrato envia o cashback diretamente a `msg.sender` na mesma moeda e transação da compra, sem crédito pendente, saque ou saldo separado. Exemplo de 100 USDT: 99 ao lojista, 0,1 USDT ao pagador e 0,9 USDT à Caedral; o gás é adicional. O saldo na home é atualizado após o evento de confirmação verificado. A partir dessa data, acima do piso, Origin pode consumir toda a receita comercial. Esta implantação não habilita benefícios de Obsidian/Sovereign nem reserva subsidiada. Moeda nativa usa valor exato; ERC-20 usa SafeERC20 e verifica o valor recebido. Guard de reentrância e consumo único da cobrança. Não há proxy nem saque administrativo.

Outbox cifrado grava transação assinada antes de transmitir. Retomada retransmite o **mesmo hash**, sem fabricar uma segunda compra; operações de envio comum e Origin compartilham bloqueio para evitar nonce concorrente. Recibos conferem hash, origem/destino, bloco canônico, status, eventos e valores. Operações técnicas, compras e cobranças aparecem na atividade local; registros históricos de ativação continuam legíveis, sem criar novas ativações.

As versões bootstrap e de ativação manual foram substituídas antes do beta público. A versão atual remove a ativação e inclui o primeiro uso na compra. Fontes e provas anteriores ficam em `protocol/deployments/archive/`, explicitamente supersedidas. O outbox fixa o contrato/spender assinado por operação; atualizar a configuração não redireciona transações históricas. Os deployments anteriores estão descontinuados nesta integração. Não existe implantação Origin mainnet nesta entrega; BNB/USDT P2P mainnet já existentes continuam separados.

## Transporte NFC

AID privado experimental **F0434145445202**, separado do laboratório antigo `...01`. Android ReaderMode no lojista e HostApduService no pagador; serviço exige `BIND_NFC_SERVICE`, desbloqueio e tela acesa.

- SELECT: `00 A4 04 00 07 F0434145445202`.
- Resposta: chave pública efêmera X25519 DER de 44 bytes + `9000`.
- Solicitação: invoice binária de 152 bytes + assinatura ECDSA de 65 bytes = 217 bytes.
- Envelope X25519/HKDF-SHA256/AES-256-GCM: 44 bytes de chave pública + 12 IV + 217 ciphertext + 16 tag = 289 bytes. AAD inclui contexto e ambas as chaves públicas.
- Dois APDUs `80 20 seq final tamanho dados`, payload de 200 e 89 bytes. Sequência e limites estritos. `9000` significa entrega, nunca pagamento.
- Sessão de 60 segundos, efêmera, uma solicitação por autenticação do cartão. Pausa, saída e bloqueio apagam a sessão. Dados do cartão, nome, PIN, seed e chave de gasto não passam no NFC.

A assinatura impede alteração silenciosa da cobrança; o canal não oferece prova criptográfica de distância e não elimina relay ativo. O pagador revisa e autoriza a compra no próprio aparelho. Identificador experimental não implica certificação NFC/EMV ou registro mundial do AID.

O cartão branco Classic 1K continua no laboratório de identificação local; não virou credencial criptográfica de pagamento. RC522/Mega não foi usado como terminal HCE nesta etapa.

## Notificações

Na primeira entrada na home após criação/importação, o app pergunta se deseja receber notificações e, se aceitar, pede a permissão Android. Recusa é lembrada; é possível alterar nas configurações. Instalações existentes também recebem esse convite uma vez se não habilitaram antes.

Compra e recebimento comercial só geram aviso depois do recibo verificado. Deduplicação persistida cifrada, conteúdo privado na tela bloqueada. Monitoramento desta versão é **com o aplicativo aberto**; não é serviço push nem acompanhamento garantido com o processo encerrado.

## Validação e limites

`forge test --root protocol`: testes adversariais, contabilidade, assinaturas, replay, expiração e troca de cartão. `qa/run-origin-tests.py`: Anvil local chain 97 + emulador isolado, Wallet Core/cofre reais, primeira compra sem ativação, assinatura comercial, APDUs cifrados em software, pagamento nativo e ERC-20, recibo do lojista, persistência e regeneração. Não usa fundos públicos nem instala pacote em celular.

A validação pública QA verifica runtime fixado, cobrança assinada pelo lojista e prontidão do cartão sem enviar ativação. As transações históricas de registro pertencem aos deployments anteriores arquivados. Compra pública e RF entre dois celulares precisam da validação manual separada. Resultados desta etapa estão em `qa/results/origin-integration.json`. Não representa auditoria independente ou garantia de ausência de falhas.

## Saldo e consentimento de uma compra

A Wallet recusa saldo negativo/inválido, saldo inferior ao bruto ou falta de moeda nativa para gás. Em token, o saldo deve cobrir o **bruto inteiro**; em moeda nativa, o bruto mais o teto de gás. Cashback futuro não financia o pagamento. O saldo é consultado novamente antes de assinar cada transação. O contrato exige valor nativo exato e saldo de token suficiente; falta de fundos reverte a liquidação, sem crédito ao lojista ou cashback.

A confirmação autoriza somente uma cobrança assinada, o cartão/conta correspondente e um teto de gasto em gás. A capacidade é opaca, temporária (até 90 segundos e limitada pelo prazo da cobrança), vinculada à sessão desbloqueada e não é persistida. Ela permite apenas a sequência necessária: pagamento, aprovação exata + pagamento, ou zerar aprovação + aprovação exata + pagamento. Cada etapa recebe uma autorização de assinatura de uso único; destino, calldata, nonce e gás continuam validados pelo assinador/outbox. Não se aceita aumento de preço de gás além do preço consentido, outra cobrança, outro cartão ou repetição de etapa. O orçamento de gás restante diminui a cada assinatura. Pausa, fechamento ou bloqueio encerram a capacidade; retomar pode exigir nova confirmação, mas nunca inventa um novo hash para um envio anterior.

O cliente não recebe crédito nem utiliza o saldo de outra moeda como substituto. A moeda é sempre a da cobrança do lojista. As aprovações ERC-20 fazem parte da compra autorizada e não habilitam débito sem assinatura do titular. A integração de pagamentos desta fase permanece fixada ao deployment testnet verificado; isso não representa disponibilidade em blockchains ainda não integradas.

## Ajustes de home e aproximação — 13/11/2026

O cartão da conta selecionada aparece diretamente na home; tocar nele abre o painel de dados/regeneração. As ações são Trocar (desativado), Enviar, Receber e Maquininha, com ícone vetorial próprio de terminal. Assinaturas ganhou acesso em formato de linha de navegação. O botão Encerrar aproximação foi removido; fechar o painel ou sair do app encerra a sessão.

A prontidão NFC expira em 60 segundos no serviço HCE. Se o painel continua ativo e em primeiro plano sem receber uma cobrança, o aplicativo pede nova autenticação (biometria prioritária/PIN alternativo); só após validá-la gera outra sessão. A janela não se estende por detectar um leitor. O prazo da oferta do lojista continua separado (120 segundos).

A tela de recebimento mostra uma animação vetorial de cartão aproximando do telefone e ondas NFC, com suporte a movimento reduzido. A leitura começa automaticamente quando a cobrança está pronta, sem botão Apresentar cobrança, e retoma ao voltar ao primeiro plano enquanto a oferta ainda é válida e não foi entregue.

A detecção do celular pelo RC522 não comprova seleção do AID Caedral, troca APDU cifrada ou liquidação. A biblioteca padrão não oferece o caminho de smartphone/HCE; a discussão técnica da NXP indica que uma implementação ISO-DEP adicional pode ser necessária. O laboratório Mega/RC522 continua sem comandos novos enquanto a adaptação elétrica dos sinais de 5 V para 3,3 V não estiver resolvida. [Biblioteca](https://github.com/miguelbalboa/rfid#what-works-and-not) · [NXP](https://community.nxp.com/t5/NFC/Either-MFRC522-or-PN532-is-compliant-with-ISO14443-4-Type-Tag/m-p/905926).

A pesquisa separada sobre Mastercard fiduciário está em [Fiat e cripto](PESQUISA_FIAT_CRIPTO.md); não representa implementação ou habilitação de Mastercard.
