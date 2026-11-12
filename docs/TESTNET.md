# Teste básico na BNB Smart Chain Testnet

## Suporte atual

O aplicativo permite selecionar Mainnet (chain ID 56) e Testnet (chain ID 97). Na ausência de uma preferência salva, começa na **Testnet**. A escolha é persistida localmente. A página principal mantém o mesmo visual em US$, sem selos de testnet, nomes de rede, horário de consulta ou botão Atualizar. O seletor explícito de redes fica no ícone ao lado de Ativos. A quantidade do ativo nativo continua identificada como **tBNB** na lista.

O total em dólares soma os valores de todos os ativos suportados da conta selecionada, sem arredondamento intermediário. BNB/USDT da mainnet usam preços públicos em USD da CoinGecko, com verificação de atualização; falhas de cotação não viram saldo zero nem total parcial. tBNB mantém a quantidade on-chain, com valor monetário zero, sem utilizar o preço do BNB real. O P&L diário mantém a linha em dólares e percentual: na mainnet os valores ficam pendentes até haver histórico de movimentações para cálculo correto. Variação de preço em 24h não é apresentada como lucro pessoal, e depósitos não são contabilizados como lucro.

A consulta de saldos é isolada pela combinação rede + carteira + conta + endereço. Trocar a rede cancela a rotina anterior e recria o estado de consulta, sem reaproveitar o saldo da outra rede. Cada RPC precisa responder com o chain ID da rede escolhida antes de qualquer saldo ser aceito.

O endereço EVM derivado pode ser igual nas duas redes; isso não une os saldos. A seleção da rede determina quais registros são consultados. O cofre, a frase e a derivação não foram modificados para esta entrega.

## Passo a passo no app

1. Instale o APK normal atualizado ou execute o build pelo Android Studio.
2. Desbloqueie/crie/importe a carteira.
3. Se necessário, toque no ícone ao lado de Ativos e escolha **BNB Smart Chain Testnet**. O seletor também permite retornar explicitamente à mainnet.
4. Opcionalmente crie uma conta chamada “Testes” no seletor de carteiras/contas.
5. Toque em **Receber**. A tela deve identificar **Receber tBNB** e a conta correta.
6. Copie o endereço e abra o botão **Obter tBNB no faucet oficial**. Cole somente o endereço público no formulário do faucet.
7. Após a solicitação e a confirmação na rede, retorne ao aplicativo e desbloqueie. A rotina consulta automaticamente a cada 30 segundos enquanto a página está aberta.
8. Compare o saldo/endereço com **Ver conta no explorador**, que abre o BscScan da testnet.
9. Alterne entre contas e redes e confirme que os saldos não são misturados. Reabra o aplicativo e confira a persistência da rede selecionada.

Abrir um navegador leva o app ao segundo plano e pode exigir novo desbloqueio ao retornar, conforme a proteção de sessão existente.

## Faucet

- [Faucet oficial BNB Chain](https://www.bnbchain.org/en/testnet-faucet)
- [Documentação oficial e canais alternativos](https://docs.bnbchain.org/bnb-smart-chain/developers/faucet/)

A página do faucet, consultada nesta implementação, informa requisito de saldo mínimo de 0,002 BNB na mainnet e limites por período. Esses requisitos são do serviço externo e podem mudar. Se a solicitação não estiver disponível, consulte os faucets alternativos listados na documentação oficial; o aplicativo não compra BNB nem solicita tokens automaticamente.

## O que podemos validar agora

- Criação/importação, PIN e biometria opcional.
- Contas derivadas e seleção persistida.
- Seleção da rede e identificação de testnet.
- Recebimento de tBNB, endereço/copiar e consulta real de saldo.
- Atualização, erro de conexão e separação de dados entre redes/contas.

## Enviar e acompanhar

Enviar agora consulta saldo, nonce e gas, prepara revisão, exige PIN/biometria e assina/transmite no aparelho. Atividade acompanha envios deste dispositivo e permite retomar os mesmos bytes/hash após falhas de conexão. Testes locais com Anvil/A57 passaram nos dois chain IDs; transferências públicas com autenticação do proprietário e os demais critérios de lançamento ainda precisam de evidência em `distribution/beta-readiness.json`.

O USDT da mainnet **não é consultado na testnet**. Na testnet foi configurado o contrato distinto `0x337610d27c682E347C9cD60BD4b3b107C9d34dDd`, com `decimals=18` verificado por RPC. A lista permite BNB e esse token; os valores monetários da testnet permanecem zero. Não foi implantado contrato em rede pública nesta etapa. Veja [escopo e validação do beta](BETA_VALIDACAO.md).

## Configuração técnica

| Ambiente | Chain ID | Ativo nativo | Explorador |
| --- | --- | --- | --- |
| Mainnet | 56 / `0x38` | BNB | `https://bscscan.com` |
| Testnet | 97 / `0x61` | tBNB | `https://testnet.bscscan.com` |

RPCs da testnet: `https://bsc-testnet-dataseed.bnbchain.org` e `https://bsc-testnet.bnbchain.org`, conforme os [endpoints oficiais](https://docs.bnbchain.org/bnb-smart-chain/developers/json_rpc/json-rpc-endpoint/).

`BscChain` contém a configuração de cada ambiente. A preferência de rede é pública e fica em `network_selection`; chaves/frases permanecem no cofre cifrado. `BscLiveReadTest` cobre as duas redes com um endereço público de teste. Os testes JVM asseguram que a testnet não consulta contratos de mainnet e rejeita resposta com chain ID 56.

Os testes instrumentados são executados no pacote isolado, preservando a instalação pessoal; o APK normal é regenerado para uso manual. A cobertura inclui leitura HTTPS nas duas redes, ausência dos avisos removidos na página principal, total em USD e estados sem cotação. Testes JVM verificam a soma decimal, preço ausente, saldo desconhecido e separação entre tBNB e BNB real.
