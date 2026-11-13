# Pesquisa: Mastercard fiduciário e recebimento em cripto

Consulta em 13/11/2026. Pesquisa de arquitetura; nenhuma integração fiduciária foi implementada, habilitada ou contratada.

## Resposta

É possível projetar um fluxo em que a Caedral nunca receba o dinheiro da venda nem controle as chaves da carteira de destino. Receber de um Mastercard existente **sem depender de participantes externos**, porém, não é viável: a transação precisa do emissor, da rede Mastercard e da adquirência. Ter API e SDK próprios não remove esses participantes. A própria Mastercard orienta comerciantes a procurar um adquirente/facilitador; define o adquirente como instituição licenciada para aceitar pagamentos da rede. [Fonte: Mastercard, aceitação e adquirência](https://www.mastercard.com/gb/en/personal/get-support/frequently-asked-questions.html).

| Condição | Viabilidade |
| --- | --- |
| Cliente paga com Mastercard; destinatário recebe stablecoin | Possível em modelos comerciais específicos, sujeito a parceiros, mercado e aprovação |
| Caedral fica só com a interface e não recebe fiat/cripto do cliente | Arquitetura possível; depende dos contratos e do fluxo efetivo de fundos |
| Sem PSP comercial intermediando nossa API | Pode-se buscar integração direta/licenciamento; não equivale a eliminar emissor, bandeira e adquirência |
| Sem qualquer participante externo, sem acesso autorizado à rede | Incompatível com aceitar cartões Mastercard existentes |
| Liquidação imediata, irreversível e sem alguém assumir risco do cartão | Não decorre da conversão para cripto; é preciso tratar disputas e liquidez |

## Caminho possível sem custódia pela Caedral

Uma arquitetura a avaliar seria:

`Cartão do pagador → emissor/rede/adquirente → participante responsável pela conversão/liquidez → endereço cripto do destinatário`

A Caedral apresentaria a cobrança, encaminharia a sessão de pagamento ao participante autorizado e acompanharia a transferência on-chain. Não haveria conta coletora Caedral, carteira de passagem administrada por nós ou pedido da frase/chave do destinatário. Isso é uma proposta de arquitetura, não uma confirmação de que algum fornecedor aceite o arranjo, os custos desejados ou todas as jurisdições.

É preciso distinguir dois produtos: pagar uma compra e liquidar o lojista em stablecoin; ou usar cartão para comprar cripto e carregar a própria carteira. O segundo é um on-ramp. Aprovação de um produto não demonstra aprovação do outro.

Em 2025, a Mastercard anunciou colaboração com Nuvei/Circle para permitir recebimento de comerciantes em USDC mesmo quando o consumidor escolhe outra forma de pagamento. Isso demonstra a existência do modelo, sem demonstrar disponibilidade universal. [Fonte: Mastercard, 28/04/2025](https://investor.mastercard.com/investor-news/investor-news-details/2025/Mastercard-Unveils-End-to-End-Capabilities-to-Power-Stablecoin-Transactions--From-Wallets-to-Checkouts/default.aspx).

Em 03/06/2026, anunciou expansão da liquidação com stablecoins reguladas e instituições participantes, sujeita à implantação por mercados. Essa infraestrutura continua dentro da rede e preserva seus processos de proteção/disputa. Não é uma API pública que permita a qualquer wallet converter pagamentos bancários isoladamente. [Fonte: Mastercard, 03/06/2026](https://www.mastercard.com/us/en/news-and-trends/press/2026/june/mastercard-expands-settlement-capabilities-to-include-stablecoin.html).

## Decisões que ainda precisariam de pesquisa comercial

A transferência cripto e a autorização de um cartão não têm a mesma finalidade operacional. Se a cripto for entregue e a compra do cartão for posteriormente contestada, alguém precisará cobrir a diferença. É uma inferência de arquitetura: o desenho precisaria atribuir esse risco ao participante contratado, usar garantias/reserva, prazo de liberação ou liquidez própria. Ausência de custódia pela Caedral, sozinha, não resolve isso.

Os anúncios consultados não confirmam liquidação de Mastercard em USDT na BNB Smart Chain, que é a combinação atual da wallet. Esse suporte precisaria ser confirmado ou exigiria uma conversão adicional.

Também seria preciso confirmar custos completos, reversões/reembolsos, destinatário contratual da liquidação, limites, exigências de identificação e disponibilidade no Brasil. As regras Mastercard disciplinam os participantes e os acordos de adquirência; escrever um software próprio não substitui essa relação. [Fonte: Mastercard Rules](https://www.mastercard.com/content/dam/mccom/shared/business/support/rules-pdfs/mastercard-rules.pdf).

Para preservar a proposta atual, a alternativa coerente é manter pagamentos Caedral cripto nativos e, se houver interesse futuro, estudar um on-ramp ou adquirência independente em que um participante externo faça a conversão e deposite diretamente no endereço do destinatário. Nenhuma parte desse caminho foi adicionada ao aplicativo nesta etapa.
