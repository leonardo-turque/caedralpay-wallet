# Funções essenciais — primeira integração de rede

## Entregue nesta etapa

- Logo do cabeçalho ampliada de 64 para 96 dp.
- BNB Smart Chain mainnet, chain ID 56, com BNB como ativo nativo de taxas.
- USDT BEP-20 no contrato `0x55d398326f99059fF775485246999027B3197955`, com 18 casas decimais verificadas via `decimals()` antes da leitura.
- Validação de `eth_chainId` antes de consultar saldos.
- BNB por `eth_getBalance` e USDT por `balanceOf(address)`, consultados no mesmo bloco retornado por `eth_blockNumber`.
- Conversão exata de inteiros para `BigDecimal`, sem passagem por ponto flutuante.
- Consultas por HTTPS, sem redirects, com timeout, limite de resposta e fallback apenas entre dois RPCs públicos documentados pela BNB Chain.
- Atualização inicial, manual e a cada 30 segundos enquanto a tela autenticada estiver composta. O bloqueio de sessão remove a tela e cancela sua rotina de atualização.
- Estado por identidade de conta; troca de carteira/conta cancela a consulta anterior e não reutiliza seus saldos.
- Horário da última consulta, atualização em andamento, erro e indicação explícita quando os dados exibidos são da consulta anterior.
- Painel da rede com chain ID, bloco e RPC utilizado. Contrato USDT nos detalhes e no recebimento, com acesso ao BscScan por ação explícita do usuário.

O fluxo é de leitura. A permissão `INTERNET` foi adicionada e tráfego HTTP claro foi desabilitado. A implementação usa a validação TLS padrão do Android; não instala trust managers permissivos nem ignora certificados.

## Dados consultados

As requisições RPC contêm o endereço público da conta, o contrato configurado e os dados públicos de chamada. O provedor RPC também recebe a conexão de rede do dispositivo. Frase, PIN e chaves privadas não entram nessa camada. Não foi criado um backend Caedral nem envio de segredos.

Os saldos ficam em memória de apresentação; não são persistidos em texto no cofre ou em preferências. O cofre e a derivação existentes não foram alterados para esta integração.

O envio continua com revisão visual e confirmação desativada: este cliente aceita apenas os métodos de leitura necessários e não expõe transmissão de transações. Cotações em reais e rendimento diário ainda não foram integrados. A variação de preço de um token não será apresentada como lucro do usuário sem contabilizar depósitos/saques e a posição ao longo do período.

## Limites do recorte

- Rede única nesta etapa: BNB Smart Chain mainnet. Não há seleção de outras redes ou adição de RPC arbitrário.
- Ativos suportados: BNB e o contrato USDT indicado. Não há descoberta de todos os tokens da conta nem importação de contrato personalizado.
- A existência de contrato e seus decimais não certificam o emissor de um token arbitrário; a identificação depende do endereço configurado, não somente do símbolo/nome.
- Falhas de rede e respostas inválidas não viram saldo zero. Dados anteriores da mesma conta podem permanecer visíveis, identificados como última consulta.
- Os RPCs públicos têm disponibilidade e limites próprios. Nenhuma chave de provedor foi incluída no app.

## Próximos recortes funcionais

1. Cotação BRL e política de atualização/frescor; total apenas dos ativos efetivamente consultados.
2. Envio de BNB e USDT: validações de destinatário/rede, saldo e gas, estimativa, confirmação autenticada, assinatura local e transmissão.
3. Acompanhamento de pendente/confirmada/falha e histórico por conta, com fonte de dados definida.
4. Adição de tokens por contrato e, depois, expansão de redes conforme prioridade do produto.
5. Recursos especiais Caedral, depois da base validada.

## Verificação

`BscBalanceRepositoryTest` verifica conversão exata, formatos inválidos, ABI `balanceOf`, bloco consistente, rede incorreta, decimais inesperados e fallback. `BscLiveReadTest` realiza uma leitura HTTPS real com o endereço público zero, sem abrir carteiras do usuário. Este smoke test depende de internet/RPC disponível.

Os testes no aparelho usam `-PcaedralIsolatedUiTest=true`, com outro applicationId e dados/chaves separados da instalação normal. O APK normal é gerado novamente sem essa propriedade ao final.

Resultado desta etapa no A57/Android 17: 19 testes instrumentados passaram, incluindo o smoke test HTTPS real, sem falhas ou testes ignorados. Os 15 testes JVM também passaram. A captura da UI de teste confirmou a logo de 96 dp; esse fixture visual usa saldos indisponíveis, enquanto o teste de rede valida separadamente a leitura real. A preparação da Activity de teste foi tornada síncrona para evitar acessá-la depois da destruição.

## Fontes de configuração

- [Endpoints e chain ID — BNB Chain](https://docs.bnbchain.org/bnb-smart-chain/developers/json_rpc/json-rpc-endpoint/)
- [Configuração de wallet — BNB Chain](https://docs.bnbchain.org/bnb-smart-chain/developers/wallet-configuration/)
- [Cadastro público do contrato USDT — Trust Wallet Assets](https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/assets/0x55d398326f99059fF775485246999027B3197955/info.json)

Configuração consultada em 11/11/2026. `BscNetwork.kt` é o ponto central dos valores usados no aplicativo.
