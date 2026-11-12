# Validação do protótipo Caedral v0.1

12/11/2026. Branch `feat/caedral-protocol-nfc`.

## Executado

- 52 testes unitários Kotlin/JVM, incluindo 12 testes novos de política, nome escolhido, calendário, codec, APDU e canal cifrado: zero falhas.
- Build debug, build de instrumentação e Android Lint do modo isolado: aprovados.
- 5 testes instrumentados Android API 37 no emulador Pixel_10_Pro: permissões do serviço, sessão fechada, entrega cifrada única, deativação da seleção e interface de prévia do nome/validade. Zero falhas. Teste Android confirmou a compatibilidade do provedor XDH com inicialização numérica de 255 bits; a inicialização via NamedParameterSpec da JVM não é compatível com esse provedor Android.
- 26 testes Solidity, incluindo fuzz de contabilidade com 256 entradas: zero falhas. Cobrem divisão, piso, transição temporal, três níveis, reserva financiada/insuficiente, alterações da cobrança, outra rede/deployment, repetição/cancelamento, portador, revogação/expiração, moedas nativas/ERC20, decimais incorretos, token que desconta taxa e callback de reentrância.
- O teste de UI usa somente dados fictícios e não cria carteira, conta ou credencial real.
- Instalação de teste somente no pacote isolado do emulador. Nenhuma instalação ou transação no A57; nenhuma movimentação de ativos.

## Limites da evidência

O teste instrumentado chama o serviço/APDU em software. Não houve aproximação entre dois celulares, cartão físico, captura RF, avaliação de alcance ou resistência a relay. A chave efêmera e a tag GCM passaram no Android e na JVM; isso não é auditoria da composição criptográfica.

O contrato não foi implantado em rede pública. Os testes Solidity não comprovam integração com a Wallet: o laboratório NFC ainda recebe cobranças sem assinatura comercial verificada e não executa pagamentos. Não há API pública implantada, emissão real de cartões, renovação, recorrência, parcelamento ou devolução implementados nesta fase.

A especificação em PROTOCOLO_CAEDRAL.md registra os bloqueadores, inclusive financiamento e compras circulares no cashback futuro. Esses testes não autorizam distribuição do protocolo como produto de pagamentos pronto.
