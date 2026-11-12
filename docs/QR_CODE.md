# QR Code

## Fluxos implementados

- **Receber:** QR da conta selecionada, com quantidade opcional. Alterar a quantidade atualiza o QR; quantidade inválida remove o código até a correção. Copiar endereço continua copiando somente o endereço público.
- **Enviar → Ler QR Code:** câmera interna preenche destinatário, ativo e quantidade e retorna ao formulário. Um QR sem quantidade limpa o valor anterior. O usuário revisa os dados: ler um código nunca assina ou transmite automaticamente.
- **Importar por QR Code:** disponível na importação inicial e em Importar outra carteira. Preenche as 12 palavras, mantendo-as ocultas, após validar o checksum BIP-39. Prossegue pelo fluxo de nome/PIN/biometria ou importação adicional existente.

A evolução do beta integrou assinatura e transmissão após revisão e autenticação. O QR apenas preenche o formulário; não concede autorização para pagar. Os critérios de liberação continuam em [BETA_VALIDACAO.md](BETA_VALIDACAO.md).

## Formatos e validação

Pagamentos usam o subconjunto suportado do [ERC-681](https://eips.ethereum.org/EIPS/eip-681): endereço EVM puro; `ethereum:DESTINATARIO@97?value=QUANTIDADE_ATOMICA` para ativo nativo; `ethereum:CONTRATO@56/transfer?address=DESTINATARIO&uint256=QUANTIDADE_ATOMICA` para USDT da mainnet. Quantidade pode ser preenchida pelo pagador quando ausente. Os ativos suportados têm 18 casas; não há arredondamento das quantidades.

URIs precisam indicar a rede atual explicitamente. Ausência de chain ID implica Ethereum mainnet no padrão; não é interpretada como BSC. Outras redes, contratos desconhecidos, funções arbitrárias, campos repetidos, parâmetros não suportados, unidades atômicas fracionárias e overflow de uint256 são recusados. ENS, URLs web, WalletConnect e aprovações não fazem parte desta etapa. Não foram adicionados deep links externos.

Recuperação aceita 12 palavras em texto separadas por espaços/quebras de linha ou `caedralpay:recovery?words=PALAVRAS_CODIFICADAS_EM_URL`. O checksum é validado pelo Trust Wallet Core. Endereço e chave privada não são aceitos como frase. BIP-39 não identifica a marca de origem: a validação não comprova que as palavras foram geradas pela CaedralPay. Esta etapa não cria exportação da frase; o QR público de recebimento não importa/restaura uma carteira.

## Câmera e privacidade

[CameraX 1.6.2](https://developer.android.com/jetpack/androidx/releases/camera) controla preview/análise pelo ciclo de vida. [ZXing Core 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4) gera e decodifica localmente, sem downloads de modelos ou aplicativo externo. A permissão de câmera é solicitada ao abrir o leitor; digitação manual permanece disponível.

O scanner usa diálogo protegido por FLAG_SECURE. Não grava imagens/arquivos nem envia frames ou frases pela rede. Conteúdo não é registrado em logs. O buffer de luminância é zerado após cada análise; strings têm as limitações normais de memória gerenciada da JVM. A câmera é desvinculada ao fechar. A frase não usa rememberSaveable, clipboard ou estado persistido do scanner. O armazenamento posterior usa o cofre existente.

## Validação manual no A57

1. Em Receber, confira a conta e leia o QR com outro dispositivo. Adicione um valor, repita e limpe o campo. Um valor inválido deve remover o QR.
2. Em Enviar, leia esse QR na mesma rede e confira destinatário/ativo/quantidade. Leia um endereço puro: a quantidade anterior deve ser limpa.
3. Leia um código de outra rede/token ou link web: o leitor deve mostrar erro e permitir outra leitura, sem abrir sites.
4. Recuse a câmera e cancele: digitação deve continuar disponível. Libere a permissão e reabra o leitor.
5. Importe uma frase de teste válida por QR e prossiga no fluxo existente. Endereço público ou checksum inválido devem ser rejeitados.
6. Cancele/reabra o leitor e confira o encerramento/retomada da câmera. Leve o app ao segundo plano e confira o bloqueio existente.

Testes JVM cobrem geração/decodificação dos pixels, precisão, rede/token/operações e formatos de recuperação. Os testes instrumentados cobrem streaming da câmera real, cancelamento, exibição do QR, valor inválido, entradas de importação e checksum. Use o pacote isolado de validação, preservando a carteira pessoal.

Validação desta entrega: 32 testes JVM e 29 instrumentados no A57/Android 17 passaram, sem falhas ou testes ignorados. A câmera traseira atingiu o estado STREAMING no teste. A leitura física de códigos entre dois dispositivos continua no roteiro manual acima. O teste de cofre mantém sua própria Activity acesa durante a execução, sem alterar o tempo de bloqueio do aparelho.
