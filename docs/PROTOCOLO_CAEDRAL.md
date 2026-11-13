# Protocolo Caedral — rascunho técnico v0.1

> Registro histórico do protótipo. A integração Origin posterior, incluindo deployment testnet e fluxo real do assinador, está em [ORIGIN_INTEGRACAO.md](ORIGIN_INTEGRACAO.md). Os limites abaixo descrevem aquela etapa anterior.

Atualizado em 12/11/2026. Implementação experimental, sem deployment, sem alteração do beta distribuído. Esta versão inicia o desenvolvimento; não representa protocolo completo, auditado ou habilitado para pagamentos reais.

## Regras de produto aprovadas

- Bandeira Caedral; carteira CaedralPay Wallet. Protocolo próprio, sem dependência de redes de cartões externas.
- Primeiro produto Sovereign. Origin e Obsidian permanecem previstos na tabela futura. A edição física inicial #1–#10 é uma numeração comemorativa, separada da credencial técnica aleatória de cada cartão.
- Nome escolhido: um nome e um sobrenome, com letras Unicode, acentos, hífen ou apóstrofo. Não se exige documento; o nome não é identidade verificada e nunca autoriza transações.
- Validade: cinco anos de calendário em UTC desde a emissão; emissão em 29/02 vence em 28/02 quando necessário. A carteira e os fundos não vencem junto com o cartão. A autoridade de emissão deve assinar a data exata; o contrato limita o intervalo a no máximo 1.827 dias, mas não implementa um calendário gregoriano on-chain.
- Taxa descontada do lojista: `max(ceil(valorBruto / 100), 10^(decimais-1))`, em unidades atômicas. Piso literal de 0,10 do ativo, sem conversão para dólar. Até o piso, a cobrança é recusada; o recebimento líquido precisa ser positivo. Em BNB, o piso é **0,10 BNB**, inclusive em pagamentos pequenos.
- Cashback inicial: 0,1% do bruto, no mesmo ativo, arredondado para baixo. A nova instrução substitui a decisão anterior de lançamento sem cashback.
- A partir de **01/02/2027, 00:00 de São Paulo (03:00 UTC, timestamp 1801450800)**: Origin 1%, Obsidian 2,5%, Sovereign 5%. A versão da política é imutável no contrato experimental; atualizar código não altera contratos já implantados.
- Gás é separado da taxa comercial. O usuário precisa revisar ambos antes de autorizar qualquer futura transação.

## Contabilidade e financiamento

O pagador autoriza somente o bruto e paga o gás. O lojista recebe bruto menos a taxa. O cashback não é cobrado adicionalmente do lojista. Antes da mudança, sai da receita da taxa. Depois, quando superar a taxa, a diferença sai de reserva do programa, financiada voluntariamente no ativo correspondente.

Exemplo de 100 USDT: inicialmente lojista 99, cashback 0,10, tesouraria 0,90. Sovereign na tabela futura: lojista 99, cashback 5, tesouraria zero, subsídio 4. O custo de gás do pagador não está incluído nesses números.

Sem reserva suficiente, o contrato experimental reverte tudo: não cobra nem promete um cashback sem lastro. Não reduz silenciosamente o benefício. Isso não resolve a sustentabilidade econômica. Origin a 1% consome toda a taxa em compras acima do piso; Obsidian e Sovereign exigem subsídio mesmo antes dos custos operacionais.

**Bloqueador de lançamento:** incentivo maior que a taxa permite compras circulares entre duas carteiras controladas pela mesma pessoa. Proibir pagar a si próprio não impede essa fraude. Precisamos de regras verificáveis de elegibilidade, financiamento, limites de exposição e tratamento de devoluções antes de liberar a tabela futura. A reserva é finita, não prova que houve venda real. O contrato não deve receber recursos de clientes nem reserva de produção nesta fase. A função `fundReserve` é aporte voluntário irreversível ao programa; não existe saque administrativo.

## Implementação existente nesta branch

| Componente | Entrega | Limite |
| --- | --- | --- |
| SDK Kotlin interno | Política, nomes, vencimento e codec binário de cobranças | Ainda não é SDK público versionado |
| Canal de desenvolvimento NFC | ReaderMode do lojista e HCE do pagador; APDUs de tamanho fixo | Sem assinatura comercial no transporte e sem pagamento |
| Confidencialidade do canal | X25519 efêmero, HKDF-SHA256, AES-256-GCM | Não autentica identidade do par; não impede MITM/relay |
| Liquidação Solidity | Cobrança assinada EIP-712, credencial assinada, split atômico, reserva, prevenção de repetição | Não implantado nem integrado ao assinador/cofre |
| Laboratório Compose | Modos de transmitir/receber, prévia do nome e validade | Apenas build debug isolado, em Aparência |
| Testes | Política, codec, canal, APDU, assinaturas, reserva, expiração e contabilidade | Testes instrumentados não simulam ondas NFC reais |

Não foi criado servidor central de clientes nem cadastro obrigatório. A autoridade de emissão assina os benefícios de uma credencial, sem assinar em nome da carteira. Revogar o cartão impede usar aquela credencial no protocolo; não impede transferências normais da carteira. A autoridade da primeira emissão e seu modelo de proteção ainda precisam ser definidos; nenhuma chave de emissão foi criada neste trabalho.

## Transporte NFC v1 experimental

Android ISO-DEP/APDU. O celular do lojista usa ReaderMode; o pagador abre explicitamente a recepção HCE. Apenas em primeiro plano, com aparelho desbloqueado e sessão de no máximo 120 segundos. O serviço exige `BIND_NFC_SERVICE`; limpeza ao pausar, sair ou bloquear. Uma recepção válida encerra a janela de aceitação.

AID local experimental: `F0434145445201`. Não é registro EMV nem identificador mundial reservado; disponibilidade/conflitos precisam ser verificados antes da distribuição a terceiros.

1. Lojista envia `00 A4 04 00 07 F0434145445201`.
2. Pagador responde chave pública X25519 efêmera X.509 DER (44 bytes) + `9000`.
3. Lojista gera seu próprio par efêmero. A chave AES deriva do segredo X25519 via HKDF-SHA256: salt `SHA256(contexto || pubPagador || pubLojista)`, info `contexto`, saída 32 bytes. Contexto ASCII: `Caedral/NFC/v1/request`.
4. Envelope: pubLojista (44) || IV aleatório (12) || ciphertext+tag GCM (168). AAD: contexto || pubPagador || pubLojista. Tag 128 bits. Nenhuma chave da carteira participa desse canal.
5. PUT: `80 10 00 00 E0` + envelope de 224 bytes. O receptor valida tamanho, autenticação GCM, versão, limites e prazo.
6. `9000` confirma apenas **entrega**, nunca liquidação. Outros status: `6985` sessão não disponível/SELECT ausente, `6D00` comando não suportado, `6A80` conteúdo inválido.

Cobrança em texto claro antes da cifragem: 152 bytes big-endian: magic/version `43445001` (4), chainId (8), settlement (20), merchant (20), token (20), gross uint256 (32), id aleatório (32), issuedAt (8), expiresAt (8). Rede restrita a 56/97 no SDK atual. Zero address identifica moeda nativa. Nome, PIN, seed, documento e número de cartão não trafegam. Sem JSON, URL ou calldata arbitrário.

A troca efêmera protege a captura passiva do conteúdo; um intermediário ativo pode trocar chaves e transmitir outra cobrança. A assinatura da cobrança e confirmação no aparelho são obrigatórias antes de integrar fundos. A versão atual deliberadamente não tem botão para pagar a solicitação recebida. Não é protocolo NFC certificado nem canal autenticado de produção; a composição precisa de revisão criptográfica independente. Strings e objetos JVM não permitem prometer apagamento completo de memória.

## Contrato e assinatura

`protocol/src/CaedralSettlement.sol`, Solidity 0.8.28 e OpenZeppelin 5.7.0 fixado por commit. Sem proxy, upgrade, chamada arbitrária, gasto delegado do pagador ou operador de liquidação.

Domínio EIP-712: `name=Caedral`, `version=1`, chainId da execução e endereço do contrato. O digest da cobrança inclui merchant, token, gross, id, issuedAt e expiresAt. O lojista EOA assina; validade máxima 120 segundos. Rede e deployment diferentes produzem digests diferentes. O contrato registra `consumed[merchant][id]` antes das chamadas externas e usa ReentrancyGuard. O lojista pode cancelar seu identificador. Nonce comercial não substitui o nonce EVM da transação do pagador.

A credencial assinada pela autoridade inclui holder, id aleatório, tier (0 Origin, 1 Obsidian, 2 Sovereign), issuedAt e expiresAt. Precisa corresponder ao `msg.sender`. Nome e edição comemorativa são metadados privados do produto, não dados da blockchain. EIP-1271 para lojistas/autoridade smart accounts ainda não implementado.

O pagador envia uma transação própria. ERC20 requer aprovação prévia do valor exato ao contrato conhecido; moeda nativa exige `msg.value == gross`. Tokens permitidos e decimais são fixados na implantação e conferidos com `decimals()`. Não há adição administrativa posterior. Apenas ativos convencionais previamente revisados: tokens com rebase, cobrança na transferência ou callbacks especiais não têm suporte garantido. Treasury e autoridade são imutáveis. Reentrância ou falha de transferência devem reverter a operação inteira. A liquidação não promete anonimato on-chain.

## Próximas entregas obrigatórias

1. Revisar esta especificação, contratos e ameaças; ampliar testes de reentrância, tokens hostis, callbacks, propriedades de reserva e modelagem econômica. Revisão independente antes de produção.
2. Definir emissão/revogação, proteção da chave de emissão e rotação por versão; construir persistência cifrada das credenciais. Nome escolhido não requer identidade civil.
3. Levar a assinatura EIP-712 da cobrança ao transporte e verificar no pagador antes da revisão. Tratar relay/MITM, validação de deployment/bytecode e escolha explícita de rede/token. Decidir se usar perfil padronizado revisado para o canal.
4. Integrar transações de contrato ao cofre, revisão PIN/biometria, allowance exata, simulação, outbox cifrado, confirmação canônica, recibos e retomada idempotente. Não reutilizar o envio P2P como se ele fizesse split/cashback.
5. Validar dois celulares físicos, perdas de aproximação, tela bloqueada, suspensão, restauração e concorrência; só depois deployment de testnet e testes end-to-end públicos.
6. Definir devoluções, cashback de vendas canceladas, abuso por compras circulares e reservas. Resolver financiamento dos benefícios futuros.
7. Construir API/SDK públicos versionados, testes de conformidade, exemplos de integração e política da marca. Integrações de terceiros não podem obter controle das chaves dos usuários.
8. Cartão físico: selecionar secure element e fabricante, emissão segura de credencial própria, revogação/renovação, testes RF dos materiais. Nunca copiar a seed da carteira para o cartão.
9. Assinaturas/recorrência e parcelamento exigem autorização separada, limitada e revogável; não existem nesta versão de compra avulsa. Caedral não adianta valores nem garante parcelas.

## Reproduzir

```bash
git submodule update --init --recursive
forge test --root protocol -vv
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug \
  -PcaedralIsolatedUiTest=true -PcaedralProtocolPreview=true
```

Build normal e todo release: recurso NFC desabilitado por padrão. O flag experimental exige pacote isolado e só habilita o debug; não altera o APK já distribuído. Emulador permite testar código Android e criptografia, mas não aproximação RF.

## Referências técnicas

- [Android HCE](https://developer.android.com/develop/connectivity/nfc/hce)
- [Android ReaderMode](https://developer.android.com/reference/android/nfc/NfcAdapter)
- [EIP-712](https://eips.ethereum.org/EIPS/eip-712): separação de domínio; prevenção de replay é implementada adicionalmente.
- [RFC 7748](https://www.rfc-editor.org/rfc/rfc7748): X25519.
- [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869): HKDF.
- [OpenZeppelin](https://github.com/OpenZeppelin/openzeppelin-contracts/tree/cab19933c33c2ad1d4c7a84864a3601dddfd16f3): componentes de assinatura, tokens, aritmética e reentrância.
