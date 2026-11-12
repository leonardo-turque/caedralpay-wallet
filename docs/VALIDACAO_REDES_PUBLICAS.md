# Validação pública do beta

As contas usadas nesta execução são exclusivas de QA e têm frase nova, diferente dos vetores públicos dos testes locais. Nenhuma frase, PIN ou chave da carteira pessoal é lida pelo agente ou pelos testes. As credenciais QA ficam fora do Git, com diretório 700 e arquivo 600. `scripts/create-public-qa-wallet.py` cria uma fixture uma única vez e mostra apenas os endereços.

O dispositivo recebe os dados de fixture em arquivo privado temporário, removido pelo runner no encerramento. O cofre persistente QA continua cifrado. Não desinstalar a instalação QA ou apagar suas credenciais enquanto houver fundos a recuperar.

## Fundos

Conta inicial desta execução: `0x1985DF544b50aFe586CB777eDdcBd239eF13f02B`. Conta de retorno: `0x40dB317E84d64D9a008C0C265C8E60A9BB11c8ab`.

| Rede | Saldo solicitado na conta inicial |
|---|---|
| BSC 56 | 0,0003 BNB e 0,001 USDT |
| BSC 97 | 0,01 tBNB e 0,001 USDT de teste |

O saldo nativo cobre financiamento da segunda conta e taxas. Cada transferência de token usa somente 0,000001 unidade. São quatro envios por rede: nativo para a segunda conta, token para a segunda, token de volta, nativo de volta. O limite de taxa por transação QA é 0,00005 BNB em mainnet ou 0,001 tBNB em testnet; excedê-lo recusa a assinatura. Saldos restantes pertencem ao responsável e não são enviados a terceiros.

Mainnet usa fundos reais obtidos de carteira/corretora pela rede BEP20, conforme o [guia BNB](https://docs.bnbchain.org/bnb-smart-chain/developers/quick-guide/). O [faucet oficial](https://www.bnbchain.org/en/testnet-faucet) exige atualmente 0,002 BNB na mainnet no mesmo endereço para obter tokens de teste. Quem já possui tBNB pode transferi-lo diretamente. A [documentação oficial](https://docs.bnbchain.org/bnb-smart-chain/developers/faucet/) também orienta solicitações pelos canais oficiais de suporte.

O contrato USDT de teste configurado está listado pela [Immersve](https://docs.immersve.com/guides/obtaining-test-tokens/) com link para o faucet BNB. Não comprar token de teste ou confundi-lo com USDT real. Contratos: mainnet `0x55d398326f99059fF775485246999027B3197955`; testnet `0x337610d27c682E347C9cD60BD4b3b107C9d34dDd`.

## Executar

Instalar os APKs debug isolados compilados com `-PcaedralIsolatedUiTest=true`, manter o aparelho desbloqueado e executar:

```bash
python3 scripts/run-public-qa.py read
python3 scripts/run-public-qa.py capacity
python3 scripts/run-public-qa.py recovery
python3 scripts/run-public-qa.py send --chain 97 --confirm-public-spend
python3 scripts/run-public-qa.py send --chain 56 --confirm-public-spend
```

Para testar inicialmente apenas o nativo, acrescentar `--native-only` ao comando de envio. Isso não aprova o teste do token.

O runner confere o XML, exige exatamente o método solicitado e exporta apenas metadados públicos em `qa/results/public-network.json`. Usa `TransferRepository`, `WalletVault` e `TransactionEngine` reais, com os endpoints HTTPS do app. Reexecuções retomam os hashes do journal em vez de criar outro pagamento. A confirmação verifica 15 blocos, bloco canônico, destinatário, valor, calldata, nonce e evento Transfer do token. Nenhum relatório de leitura ou capacidade substitui os hashes dos testes de envio.

A medição de capacidade cobre três rodadas de cinco consultas concorrentes por rede. É uma amostra de funcionamento dos provedores públicos, sem SLA nem promessa de capacidade ilimitada.

`scripts/validate-release-upgrade.py` testa atualização assinada de versionCode 1002 para 1003 em outro UID (`com.caedralpay.wallet.upgradevalidation`), preservando o pacote pessoal e a conta QA financiada. Usa somente a frase pública de fixture, sem fundos, e valida acesso ao cofre, contas, preferências, journal e recuperação em um novo cofre com outro PIN. Não equivale a um teste em outro modelo físico nem à validação da impressão digital de um usuário.
