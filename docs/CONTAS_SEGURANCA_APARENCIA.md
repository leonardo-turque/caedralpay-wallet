# Contas, ações sensíveis e aparência

## Menus

Cada conta tem um menu de três pontos para renomear ou remover da lista local. Cada carteira tem outro menu para renomear, revelar a frase e trocar o PIN. A interação toma como referência o código estudado de MetaMask Mobile (`AccountActions/AccountActions.tsx` e `AccountListCell.tsx`, commit a216ff1), reimplementado em Compose. Não foram incorporados controllers ou criptografia MetaMask.

Remover uma conta exige PIN ou biometria forte e confirmação explícita. O índice de derivação é preservado: remover a conta 2 não transforma a conta 3 em outro endereço. A próxima conta usa o próximo índice original, sem reutilizar o índice removido. A conta sai da interface e seu nome é apagado do catálogo; a frase continua derivando aquele endereço. Pelo menos uma conta deve permanecer visível por carteira. Remoção de carteira inteira não faz parte deste pedido.

O catálogo cifrado agora grava v3, com índices ocultos, e continua lendo os formatos originais e v2. Alterações usam a gravação atômica existente. Selecionar um índice removido é recusado; remover a conta selecionada seleciona uma conta restante da mesma carteira.

## Autenticação

Renomear exige uma sessão aberta. Remover, revelar e trocar PIN exigem autenticação nova para aquela ação. O cofre emite uma autorização de uso único, vinculada à sessão, operação, carteira e conta quando aplicável, com validade de 60 segundos. Seleção/mutação da sessão, bloqueio e fechamento do painel invalidam a autorização. Não há um booleano de UI que libere a operação no cofre.

PIN usa a mesma validação PBKDF2/AES e o mesmo limite progressivo de tentativas do desbloqueio. Biometria usa `BiometricPrompt` com `CryptoObject`, chave Keystore de autenticação por uso e biometria forte, conforme a [documentação Android](https://developer.android.com/identity/sign-in/biometric-auth). Se indisponível/cancelada, o PIN continua disponível.

A frase só é carregada após autenticação e aparece num painel protegido por FLAG_SECURE. Fecha/oculta após 30 segundos e é removida do estado ao sair/bloquear. Não há copiar, compartilhar ou salvar frase nesse painel. Strings mantêm as limitações de remoção de memória gerenciada da JVM; isso não é alegação de proteção contra um aparelho comprometido.

A troca de PIN exige autenticação atual e confirmação do novo PIN de seis dígitos. O PIN protege o cofre de **todas as carteiras deste dispositivo**, informação apresentada na tela. A gravação reembrulha a chave de dados com novo salt e PIN, preservando dados, frases, endereços e o envelope biométrico existente. Não é alterado o PIN do Android.

## Aparência e acabamento

O ícone Aparência no cabeçalho permite Sistema, Claro ou Escuro. A escolha é salva localmente. Os temas alcançam criação, confirmação de palavras, importação, PIN, biometria, desbloqueio, página principal, listas, detalhes, QR e painéis. Cores de texto, cursor, erros, ganhos/perdas, superfícies, logo e barras do sistema respondem ao tema. Botões principais mantêm o mineral Caedral #DDD6CD.

Manrope é empacotada localmente em quatro pesos, sem download no uso, sob SIL OFL 1.1. A licença está em `app/src/main/assets/licenses/Manrope-OFL.txt`; origem: [Google Fonts/Manrope](https://fonts.google.com/specimen/Manrope). Endereços permanecem monoespaçados. Tipografia respeita a escala do sistema; grids de palavras e ações adaptam colunas em fonte grande.

Botões principais têm altura mínima de 52 dp, formas consistentes e estados desativados. Entradas deslizam discretamente sobre fundo opaco; bloqueio não mantém a página sensível numa transição de saída. Painéis animam a mudança de tamanho e mantêm fechamento/teclado consistentes. Carregamento inicial dos ativos usa placeholders pulsantes com semântica de acessibilidade, sem apresentar números fictícios. A animação usa as APIs Compose e respeita a escala de animação do sistema.

## Validar manualmente

1. Abra os menus de cada conta e carteira. Renomeie ambas e reabra o app para conferir persistência.
2. Com duas ou mais contas, remova a selecionada após PIN; confira os endereços das restantes. Repita com biometria, cancelando antes para verificar que nada é removido.
3. Tente revelar com PIN incorreto. Revele com PIN correto e depois biometria, confira a carteira escolhida e o ocultamento ao fechar/ir ao segundo plano.
4. Troque o PIN; confirme que os valores precisam coincidir. Bloqueie, teste o PIN antigo e depois o novo. Confirme o acesso biométrico no aparelho com biometria previamente habilitada.
5. Alterne Claro/Escuro/Sistema, reabra o app e teste os fluxos, teclado, QR, fonte ampliada e menus. Verifique as animações também com animações desativadas no Android.

Testes automatizados usam exclusivamente o pacote de validação e frases públicas de fixture. A biometria real e a avaliação subjetiva do movimento permanecem parte da validação manual no A57.

Resultado: 39 testes instrumentados no A57/Android 17 e 35 testes JVM passaram, sem falhas ou testes ignorados. Capturas dos dois temas foram revisadas. A instalação pessoal foi preservada e os pacotes temporários de validação removidos.
