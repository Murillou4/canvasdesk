# Canvas Desk 1.3.0

Quadros e pranchetas modulares para **Minecraft Java 26.3**, **Fabric Loader 0.19.5**, **Fabric API 0.164.0+26.3** e **Java 25**. Madeira escura, cobre e papel acompanham desenhos, textos e diagramas persistidos no mundo.

## Instalação e atualização

Coloque `canvasdesk-1.3.0.jar` e a Fabric API na pasta `mods`, substituindo o JAR anterior do Canvas Desk. Em multiplayer, servidor e todos os clientes precisam usar a versão 1.3.0 e as mesmas versões de Minecraft e Fabric API. Não use dois JARs do Canvas Desk juntos.

Faça backup do mundo inteiro antes de atualizar. O ID `canvasdesk:desk`, os documentos, os vínculos dos itens e as projeções anteriores são preservados. Blocos antigos continuam verticais, brancos e com sua montagem central. A instalação pessoal não é alterada pelo empacotamento.

Este projeto é independente de MurilloUtils e Create. A versão 1.3.0 amplia o editor, reorganiza os controles nas laterais e permite marcar tarefas diretamente no quadro. Os resultados reais de compilação, testes e smoke focado estão em `VALIDATION_STATUS.md`; colaboração real e a validação completa do editor continuam com seus estados separados.

## Receitas e formatos

Qualquer tipo de tábuas pode ser usado. Cada receita base produz um módulo branco.

**Quadro vertical** (`canvasdesk:desk`):

```text
Papel   Papel    Papel
Cobre   Redstone Cobre
Tábuas  Tábuas   Tábuas
```

**Prancheta horizontal** (`canvasdesk:drawing_desk`), apoiada sobre blocos:

```text
Papel   Papel    Papel
Tábuas  Tábuas   Tábuas
Cobre   Redstone Cobre
```

Receitas sem formato, usando um módulo e um ingrediente:

| Entrada | Resultado |
| --- | --- |
| Módulo + qualquer um dos 16 corantes | Mesmo módulo com o fundo daquela cor |
| Quadro vertical + graveto | Prancheta horizontal |
| Prancheta horizontal + graveto | Quadro vertical |

As receitas de tingimento e conversão usam a transmutação vanilla, preservando o vínculo com o documento, a região e a cor existente quando aplicável. Corante branco restaura o fundo branco. A cor pertence a cada módulo, permitindo misturar fundos no mesmo conjunto.

Também é possível usar um corante diretamente em um módulo colocado. Isso exige acesso de edição ao documento, afeta somente o módulo clicado e consome um corante quando a cor muda. Em criativo, o corante é preservado. Os desenhos e suas cores continuam intactos.

## Colocação e superfície

O formato é escolhido pelo item. Olhar para baixo, pular ou girar a câmera não converte quadros em pranchetas. Quadros novos colocados na lateral de um bloco encostam na face clicada; colocação independente sobre um suporte usa orientação horizontal do jogador. Pranchetas permanecem planas.

Segure um módulo do mesmo formato e mire em qualquer parte da face do quadro ou em uma das quatro células diretamente vizinhas, até um bloco além da borda. A prévia funciona mesmo quando a mira está no ar ou na parede atrás do espaço adjacente. Cantos diagonais não escolhem expansão. A borda mais próxima determina a direção; no centro exato, a prioridade é cima, direita, baixo e esquerda.

Uma seta e a geometria translúcida indicam o alvo e a orientação; verde com `+` indica espaço válido, vermelho com `×` indica inválido. Clique direito confirma exatamente esse alvo após nova validação no servidor. Obstáculos à frente impedem assistência através de paredes, e posições ocupadas continuam bloqueadas. O servidor conserva o fluxo vanilla e os callbacks de proteção.

**Mão vazia ou item comum:** clique direito abre o editor. Com a mão principal vazia, clicar na caixinha de uma tarefa marca ou desmarca sem abrir o editor; o texto continua abrindo. Agache para abrir mesmo sobre a caixinha. **Módulo em mãos:** toda a face e o entorno compatível acionam expansão, inclusive o centro. **Corante:** recolore o módulo conforme suas permissões. **Agachar:** preserva a colocação vanilla. A mão principal tem prioridade para módulos; um módulo na secundária é usado quando a principal está vazia.

Conectam-se módulos do mesmo documento, formato, orientação e plano. Uma união de documentos diferentes continua obedecendo às regras de consentimento. Uma prévia válida de colocação não concede propriedade nem aprovação de união.

Cada módulo corresponde a **256 × 256 unidades de documento**. As coordenadas crescem para a direita e para baixo em ambos os formatos. A moldura cobre uma faixa de 16 unidades nas bordas expostas; essa faixa fica marcada no editor e é revelada quando a borda passa a ser uma emenda conectada. Expansões preservam coordenadas, tamanho de fonte e tamanho dos desenhos.

## Editor ampliado

Clique direito com a mão vazia ou um item comum para abrir. O painel ocupa 94% da largura e 92% da altura GUI, sem teto fixo de tamanho, deixando o mundo visível nas margens. Cabeçalho, rodapé e laterais estreitos reservam a maior área para escrita. A superfície física completa é enquadrada na abertura, com oito unidades GUI de margem por lado e escala proporcional à área útil. Enquanto você não navegar manualmente, alterações de módulos atualizam o enquadramento; depois de pan ou zoom manual, a câmera permanece onde foi deixada. **F** volta a enquadrar e acompanhar a superfície.

A cor e a moldura dos módulos aparecem no editor. Hachuras identificam vãos e exterior. Criação e interação direta começam na superfície disponível. Elementos podem atravessar emendas, bordas e buracos: o trecho sobre módulos aparece e pode ser selecionado; o restante permanece salvo para reaparecer em futuras expansões. Remover módulos nunca recorta os dados persistidos.

Seleção, Desfazer, Refazer e Enquadrar ficam no início da lateral esquerda, seguidos pelas ferramentas que couberem na altura; `…` oferece todas as demais. A lateral direita reúne Acesso e uniões, ajuda, fechamento e a paleta de cor, espessura e fonte. O zoom fica no rodapé direito. Dicas mostram os atalhos. Cliques nas margens do painel são consumidos pelo editor.

| Controle | Ação |
| --- | --- |
| V / B / E | Seleção / desenho / borracha |
| T / N / C | Texto direto / nota / lista |
| R / O / L / A / K | Retângulo / elipse / linha / seta / conector |
| H, Espaço + arraste ou botão do meio | Navegar |
| Roda do mouse | Zoom em torno do cursor |
| F | Enquadrar a superfície |
| Shift + seleção | Acrescentar à seleção |
| Alt + clique | Selecionar elementos visíveis do grupo |
| Enter / duplo clique | Editar o texto selecionado |
| Ctrl + Enter | Finalizar texto direto ou salvar o cartão |
| Delete / Backspace | Excluir seleção fora da digitação |
| Ctrl + Z / Ctrl + Y | Desfazer / refazer |
| F1 ou `?` | Ajuda e opção de sons |
| Esc | Finalizar digitação, fechar diálogo ou fechar editor |

Arraste em espaço vazio para selecionar vários elementos. A borracha remove elementos completos, incluindo seus trechos ocultos, quando eles são atingidos pela parte visível. O canto inferior direito redimensiona. Conectores acompanham os elementos ligados. Fonte e marcações de listas permanecem salvas e participam do histórico individual.

## Texto direto, gravação e áudio

### Tarefas diretamente no mundo

Com a mão principal vazia, mire somente na caixinha e use o botão direito para marcar ou desmarcar. O realce e a indicação Marcar/Desmarcar confirmam o alvo; manter o botão pressionado produz uma única ação. O texto e outras partes do quadro continuam abrindo o editor. Agachar abre o editor mesmo sobre a caixinha. Módulos e corantes conservam suas funções e prioridades.

A interação funciona nos quadros verticais e pranchetas, incluindo listas com linhas quebradas e caixas sobre emendas. Moldura, vãos, verso, obstáculos e elementos sobrepostos não permitem atingir uma caixa escondida. Sem metadados atuais, o clique conserva a abertura normal do editor.

Donos e convidados podem marcar tarefas; leitores abrem o editor em modo de leitura. Cada mudança usa a confirmação durável e participa do histórico individual de desfazer/refazer. Se outra pessoa alterar a lista primeiro, o quadro é atualizado sem sobrescrever a mudança. A gravação não exige uma sessão aberta do editor.

Texto comum é digitado sobre o canvas, com cursor, seleção, acentos, composição, clipboard e quebras de linha. Notas e checklists continuam usando cartões. Durante a digitação, teclas como V, B, T e Espaço pertencem ao texto. Enter quebra linha; Ctrl+Enter ou Esc finaliza a edição. O redimensionamento de TEXT escala proporcionalmente a fonte, entre 8 e 96 unidades. A seleção acompanha o conteúdo medido; textos antigos mantêm a largura de quebra existente.

A gravação ocorre após 750 ms sem alterações e, em escrita contínua, em checkpoints a cada 5 segundos. Há apenas uma operação de escrita em voo, mantendo a digitação local livre. Checkpoints consecutivos do mesmo intervalo formam uma ação de desfazer. Pausas, troca de foco e alterações intervenientes encerram o agrupamento. O indicador de gravação depende da confirmação durável do servidor.

Ao fechar, o editor aguarda as alterações pendentes. Em conflito, o rascunho continua disponível para copiar antes de recarregar a versão do servidor; nenhuma alteração remota é sobrescrita automaticamente. Saídas inesperadas com texto pendente tentam guardar uma cópia local em `config/canvasdesk-unsaved-text.json`. Esse arquivo é um rascunho de recuperação, e não uma confirmação de gravação no mundo.

Se o módulo aberto for removido, a sessão procura outro módulo carregado do conjunto anterior dentro do alcance de 10 blocos, preservando a câmera. Sem candidato, fecha com aviso. Remover uma ponte faz cada editor representar seu fragmento físico, mantendo o documento compartilhado.

**Sons do editor** fica em `?`/F1, desligado por padrão. O feedback adicional de digitação e ações é discreto, limitado em frequência e respeita volume geral e de blocos. A preferência fica em `config/canvasdesk-client.json`. O editor não altera os volumes nem interrompe o motor de áudio; o relato de ausência dos sons normais ainda precisa de confirmação no jogo.

## Documentos, conexões e acesso

- Quebrar um módulo devolve um item vinculado. Recolocado isoladamente, ele restaura sua região; conectado, adota a projeção da superfície existente.
- Partes separadas continuam ligadas ao mesmo documento. Remover uma ponte não divide nem copia desenhos. Descarregar chunks preserva a superfície conhecida.
- Reconexões com projeções diferentes preservam a superfície mais antiga, podendo mudar a região exibida pela outra parte sem mover os elementos do documento.
- Documentos do mesmo dono podem unir automaticamente quando esse dono fizer a conexão. Donos diferentes precisam aprovar em **Acesso e uniões**; donos e convidados resultantes aparecem antes da aprovação.
- A união preserva identidades e grupos de origem. O histórico anterior à união é reiniciado para evitar operações em coordenadas antigas.
- Donos convidam e removem editores. Outros jogadores próximos podem ler. Quebra de blocos continua seguindo as regras do mundo.
- O histórico mantém até 100 ações por jogador e recusa desfazer silenciosamente uma alteração posterior de outro jogador.

## Recuperação e backup

```text
/canvasdesk list
/canvasdesk recover <UUID-do-documento>
```

Donos recuperam seus documentos; operadores podem recuperar administrativamente. Recuperar produz outra referência ao mesmo documento. Itens transportados de outro mundo preservam o vínculo desconhecido, sem importar conteúdo ausente nem criar um documento substituto.

Faça backup do **mundo inteiro**, incluindo a pasta `canvasdesk`, seus documentos, objetos imutáveis, snapshots, índice e journal. Não copie apenas o snapshot e não edite os arquivos durante o jogo. Registros e objetos antigos permanecem para recuperação; não há limpeza destrutiva automática.

Alterações confirmadas permanecem recuperáveis após queda. Os chunks vanilla são reconciliados separadamente: uma união confirmada pode sobreviver mesmo que Minecraft reverta o bloco que conectava as partes.

## Compilação e evidências

Toolchain fixada: Gradle 9.6.0, Loom 1.17.21, Java 25 e as versões de Minecraft/Fabric indicadas acima.

```powershell
$env:JAVA_HOME = 'C:\caminho\para\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon --console=plain release -x test -x check
```

`dist/1.3.0` contém JAR, JAR de fontes, projeto fonte ZIP, documentação e `SHA256SUMS.txt`. Classes de GameTest e validação de desenvolvimento não entram no JAR distribuível.

Testes unitários limitados a dois minutos, sem abrir Minecraft:

```powershell
.\gradlew.bat --no-daemon --console=plain test --tests '*CanvasStoreTest' --tests '*SurfaceLayoutTest' --tests '*PlacementRayTest' --tests '*CanvasViewportTest'
```

Smoke focado e isolado, após compilar as classes de teste:

```powershell
.\gradlew.bat --no-daemon --console=plain testClasses compileGametestJava
.\tools\validate-editor.ps1 -JdkPath 'C:\caminho\para\jdk-25'
```

O smoke 1.3.0 usa perfil e mundo descartáveis próprios, onboarding preparado e limite total de três minutos. Exercita o editor em três resoluções, topologia ao vivo, entrada de texto e checklists no mundo pelo clique nativo. Ele não modifica instâncias pessoais nem encerra um cliente que já estava aberto. O relatório distingue clique nativo, geometria, colocação real e verificações pendentes.

Recursos são gerados de `art/geometry.json` e dos scripts em `tools/`. `python tools/generate-assets.py` apenas gera; `--validate` acrescenta verificações estáticas, sem cliente. A renderização mantém cache LRU de pixels de 32 MiB e resolução 256 próxima/64 distante. Máscaras e índices físicos são reconstruídos quando a topologia muda, sem buscas completas por frame. Documentos permanecem vetoriais.

Consulte `VALIDATION_STATUS.md` para resultados desta versão e o roteiro manual. Evidências antigas da versão 1.1.0 não aprovam a versão 1.3.0.

Licença MIT. Não é um produto oficial de Minecraft, Mojang ou Microsoft.
