# Revisão interna do candidato beta

Esta é uma revisão interna do código e das evidências desta implementação. Não é auditoria independente e não certifica ausência de vulnerabilidades.

## Escopo revisto

- Manifesto, permissões, extração de dados/backup, bloqueio de screenshots/overlays e encerramento da sessão ao sair.
- `WalletVault`, `BiometricCrypto`, derivação de contas e importação: AES-GCM, KDF do PIN, Keystore, gravação atômica e autorização por ação.
- `EvmTransfer`, `EvmRpc`, assinatura Wallet Core, journal/outbox e confirmação de recibos.
- Transporte HTTPS, contratos fixos por chain ID, limites de resposta, timeouts, fallback e simulação.
- Assinatura Android, instalação isolada, atualização e proteção do download na Cloudflare/origem.

## Achados corrigidos

1. **Inicialização release:** repackaging R8 quebrava acesso entre Kotlin e AndroidX. Corrigido preservando os limites de pacote. A execução instrumentada é validada pelo XML, pois o Gradle chegou a retornar zero mesmo após crash da instrumentação.
2. **Retenção do journal:** atingir 500 envios impediria pagamentos futuros. A retenção agora remove somente registros finalizados antigos, preservando todo envelope em acompanhamento. Se só houver pendentes, recusa novo registro. Testes verificam preservação de pendentes, continuidade dos envios e recusa de adulteração sem sobrescrita.
3. **Origem de download:** reload do Nginx não tinha aplicado efetivamente a restrição de peers. A configuração foi corrigida e o marcador do reload passou a ser conferido. Acesso direto, inclusive com header Cloudflare forjado, recebe 403.

## Propriedades conferidas

- Autenticação de envio vincula conta, ação, sessão, prazo e digest de todos os campos revisados. Um token não pode ser reutilizado ou autorizar outro destinatário/valor.
- O signer só cria transferência nativa ou ERC-20 `transfer`, sem approvals/chamadas arbitrárias. Usa inteiros e chain ID explícito; valida checksum misto, chave derivada e destinatário.
- Envelope assinado é persistido de forma cifrada antes da transmissão. Retentativa preserva bytes/hash/nonce. Erro de RPC não prova falha financeira.
- Confirmação exige 15 blocos, bloco canônico, remetente/destino/status e evento Transfer coerente para token. Envelope assinado é removido do registro finalizado.
- Dados privados de QA ficam fora do Git. O runner remove a fixture temporária do aparelho e só exporta endereço, saldo, hash, bloco, resultado e taxa.
- APK distribuível tem pacote normal, assinatura própria, sem depuração e sem regras/Activity auxiliares de instrumentação. O download não ignora o manifesto bloqueado mesmo após Turnstile válido.

## Limites preservados no beta

- PIN de seis dígitos tem entropia limitada; KDF, Keystore e controle de tentativas reduzem risco, sem prometer proteção contra aparelho comprometido ou execução privilegiada.
- APIs nativas e protobuf usam memória gerenciada; zerar arrays não demonstra eliminação imediata de todas as cópias de uma chave na memória.
- Provedores RPC e de preços públicos não têm SLA contratado. A medição registrada é uma amostra, não um teste prolongado de carga ou de todos os modelos de celular.
- Histórico é local de envios, com retenção de até 500 registros; não indexa todas as entradas nem transações feitas em outro app. Substituição de nonce em outro dispositivo e transações descartadas podem exigir consulta externa e tratamento adicional. Não há alegação de P&L diário completo.
- A atualização/recuperação foi testada no A57 Android 17 em UID separado. Não prova biometria física, compatibilidade com todo aparelho ou migração automática de APK debug para assinatura release.
- A revisão independente continua pendente. Ela deve ter responsável e relatório próprios; não será marcada como feita em razão destes testes internos.
