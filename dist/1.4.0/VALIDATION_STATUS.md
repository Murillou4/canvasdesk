# Canvas Desk 1.4.0 — digitação, contorno, navegador e materiais

Data: 29/10/2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25, Loom 1.17.21 e Gradle 9.6.0 preservados. As seções anteriores permanecem abaixo como histórico; não aprovam esta versão.

## Resultados executados

| Verificação | Estado | Evidência e limite |
| --- | --- | --- |
| Compilação main/client/browser e módulo de desenvolvimento | PASS | `artifacts/1.4.0/final-build-unit.log`, JDK 25; nenhum cliente iniciado por este comando |
| 66 testes unitários | PASS | `artifacts/1.4.0/unit-tests/summary.json` e XML: dados legados, transformações, união, histórico, revisão, ACL, deduplicação, 29 materiais, quantidades, aliases, relógio, geometria e layout |
| Smoke isolado com Chromium real | PASS | `artifacts/1.4.0/smoke/attempt-20261029-223909/{summary,runner}.json`; 68,30 s, limite total 180 s; janela oculta e sem foco |
| Dois clientes reais e servidor integrado | PASS | `artifacts/1.4.0/multiplayer/attempt-20261029-223658/{summary,runner}.json`; 130,71 s, limite total 180 s; duas janelas ocultas |
| YouTube em auxiliar real | PASS | `artifacts/1.4.0/providers/youtube-20261029-224017/summary.json`; 4,99 s, limite 25 s, player ready e posição avançando, sem erros; áudio silenciado |
| Entrada física Windows/SDL | PARCIAL | Probes aprovados nos diretórios `attempt-20261029-212302` e `attempt-20261029-213245`: texto acentuado, Enter/Ctrl+Enter e notas/checklists. São tentativas interrompidas, não aprovação de toda a rodada. O último smoke usa callbacks de texto, não teclas do Windows |
| Distribuição, conteúdo e hashes | PASS | `artifacts/1.4.0/release-summary.json`; conferência independente de execução em desenvolvimento |

O smoke final aprovou seis grupos: entrada pelo callback nativo e confirmação durável; resize/foco e cartões com acentos/quebras; importação atômica dos 29 materiais e atlas nativo no editor/mundo; decodificação de vídeo, controles do painel e da própria página, play/pausa/busca e correção de desvio maior que 500 ms; navegação/entrada HTML real, formulário local, rolagem, cancelamento e queda do auxiliar; capturas 854×480, 1280×720 e 1920×1080 com GUI 2/3, contornos em L/com vão e materiais na prancheta. `editor-selftest.log` termina em PASS.

A rodada de dois clientes comprovou entrada tardia no relógio em reprodução, rejeição de controle por leitor, convite e comandos do convidado chegando ao decodificador do dono, recusa de revisão antiga, endereço/rolagem compartilhados, desconexão/reconexão real e encerramento explícito da sessão antiga após trocar a fonte. Os logs `mediaOwner.log` e `mediaPeer.log` terminam em PASS. Isso cobre sincronização de mídia; não representa execução de toda a colaboração de desenho e de uniões com dois donos.

Capturas inspecionadas incluem `editor-854.png`, `editor-1920-materials.png`, `embedded-video-real.png`, `embedded-site-local-input.png` e `world-ring-contour.png` no diretório final do smoke. Elas mostram texto com acentos, quantidades completas e ícones nativos, conteúdo real do navegador e perímetro sem emendas internas. Outras capturas e os logs completos permanecem nos diretórios de cada tentativa.

## Áudio e uso do computador

Uma versão inicial do vídeo sintético de teste continha um tom contínuo que foi reproduzido e incomodou o usuário. Essa falha do teste não é aprovação de áudio. A faixa foi removida dos arquivos locais e o gerador agora usa `-an`. Os runners configuram o volume somente em seus perfis descartáveis, e o auxiliar de validação recebe `--mute-audio`. Não há alteração do volume do Windows ou da instalação pessoal. Audibilidade, volume e convivência com os sons normais do jogo permanecem **NOT_RUN**.

Depois do pedido para liberar o computador, as rodadas usam janelas SDL ocultas/não focáveis e screenshots internos; não usam mouse/teclado do sistema. Após o relato do apito, nenhum jogo ou navegador foi iniciado novamente: somente leitura de arquivos, compilação, testes unitários e empacotamento. Os processos de teste anteriores encerraram; os runners registram preservação dos processos de base. A revisão final de processos consta no resumo de entrega.

## Limites e pendências reais

- **NOT_RUN após os últimos ajustes de interface:** rodapé compacto do diálogo de materiais, disponibilidade dos controles conforme tipo/prontidão do navegador e descarte de ícones fora da viewport foram compilados, mas não houve novo smoke após esses ajustes. A nova captura/assertion em 640×480 está preparada, não executada. As três resoluções exigidas acima foram executadas antes desse acabamento.
- **NOT_RUN:** composição IME completa em diferentes layouts, entrada física Windows em todos os campos de quantidade/busca/JSON e todas as combinações de seleção/clipboard/foco. Callbacks não substituem essa aceitação física. Probes anteriores de digitação real não foram repetidos após o pedido de não controlar o computador.
- **NOT_RUN:** todas as quatro orientações, montagem de parede e emendas exatas para os novos conteúdos; suíte completa das ferramentas, conflitos de desenho e aprovação de união entre dois donos em dois clientes reais.
- **NOT_RUN:** servidor dedicado 1.4.0, reinício completo do mundo com todos os novos cenários e queda forçada durante gravação; os testes unitários cobrem reabertura do armazenamento.
- **NOT_RUN:** Linux/macOS, resource packs personalizados e recarga enquanto dois navegadores estão ativos, carga/FPS em documentos grandes, rede remota de alta latência, perda prolongada de pacotes.
- **NOT_RUN:** credenciais reais, cookies entre reinícios, provedores com DRM/codecs proprietários e todos os bloqueios regionais/de incorporação do YouTube. O PASS do vídeo público não garante outros provedores.
- **NOT_RUN:** áudio audível de vídeos/sites, mixagem com Minecraft e feedback opcional; a validação final foi silenciosa.

## Reprodutibilidade e tentativas

Cada tentativa tem diretório próprio. Falhas e diretórios RUNNING antigos não são reutilizados como aprovação. Ajustes anteriores do harness incluíram pacote exclusivo do mixin de validação, bloqueio SDL do GameTest, preparação do atlas fora de render passes, assinatura de blit, HTTP Range do fixture, espera de carregamento HTML, recibos de comandos e configuração Loom concorrente. O runner de dois clientes inicia o segundo somente após o primeiro publicar a conexão, mantendo um único deadline total.

Comandos executados nesta versão (JDK 25 explícito):

```powershell
.\gradlew.bat --no-daemon --console=plain test compileGametestJava
.\tools\validate-features.ps1 -JdkPath 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot' -NativeInputMode none -TimeoutSeconds 180
.\tools\validate-media-multiplayer.ps1 -JdkPath 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot' -TimeoutSeconds 180
.\tools\validate-browser-provider.ps1 -JdkPath 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot' -TimeoutSeconds 25
.\gradlew.bat --no-daemon --console=plain release -x test -x check
```

A distribuição contém JAR, fontes, projeto fonte, documentação e SHA-256 em `dist/1.4.0`. O auxiliar fica aninhado como recurso; bibliotecas nativas são baixadas no primeiro uso, por plataforma e versão, verificadas por SHA-256. Licenças e notices estão preservados. Código/mixins de GameTest não entram no JAR. A instalação pessoal permanece intacta.

---
# Canvas Desk 1.3.0 — editor ampliado e checklists no mundo

Data: 29/10/2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25, Loom 1.17.21 e Gradle 9.6.0 preservados.

Painel 94% × 92%, cabeçalho e rodapé compactos e barras laterais reservadas fora da viewport. O enquadramento usa os limites físicos completos e margem de 8 unidades GUI, com escala uniforme. F e roda compartilham limites de zoom e não saltam em superfícies grandes. Nenhuma coordenada ou proporção do documento foi alterada.

Checklists podem ser marcados somente pela caixinha, no mundo, com a mão principal vazia. Uma pressão produz uma operação durável; manter pressionado não repete a alteração. Clicar no texto e agachar conservam abertura do editor. O servidor valida identidade, projeção, alcance, obstáculos, ACL, revisão e callbacks vanilla/Fabric. A operação altera apenas checks e participa do histórico existente.

## Verificações desta versão

| Verificação | Estado | Evidência / limite |
| --- | --- | --- |
| Compilação main/client com JDK 25 | PASS | Gradle compileJava compileClientJava |
| 30 testes de armazenamento e histórico | PASS | build/test-results/test/TEST-br.com.murillo.canvasdesk.core.CanvasStoreTest.xml |
| 5 testes de superfície + 9 de raio de colocação | PASS | build/test-results/test/TEST-br.com.murillo.canvasdesk.block.*.xml |
| 5 testes de layout, fit e zoom | PASS | build/test-results/test/TEST-br.com.murillo.canvasdesk.client.editor.CanvasViewportTest.xml |
| Smoke isolado do editor e interação no mundo | PASS | artifacts/1.3.0/smoke/summary.json e runner.json; 68,81 segundos, limite de 180 |
| Distribuição, fontes e SHA-256 | PASS | artifacts/1.3.0/summary.json; 88 classes idênticas às compiladas/validadas, Java 25 e nenhum GameTest no JAR |
| Colaboração com dois clientes reais | NOT_RUN | Core não substitui validação real de rede e duas interfaces |
| Áudio normal, feedback opcional e resource packs de fontes personalizados | NOT_RUN | Funcionalidade preservada; não fazem parte do smoke focado |
| Carga pesada/FPS, queda forçada e reinício completo com chunks reais | NOT_RUN | Sem repetição das validações antigas nesta rodada |

O smoke final aprovou 11 grupos de cenários, com a última linha não vazia exatamente PASS. As cinco tentativas anteriores encerraram dentro do limite e expuseram ajustes necessários no novo driver de testes: botão SDL, chamadas no thread correto, keycode de Enter, posição do jogador acima do piso e alinhamento real da mira ao clicar no texto. Evidências ficam em artifacts/1.3.0/attempt-01 até attempt-05; seus resultados não aprovam cenários posteriores. Nenhuma tentativa ficou esperando indefinidamente.

Cenários aprovados: abertura pelo clique vanilla; painel/rails sem sobreposição em 854×480, 1280×720 e 1920×1080, GUI 2/3; overflow; desenho, seleção, movimento, histórico e texto acentuado com Enter; menus de ajuda/propriedades; topologia L/anel com vão, câmera manual preservada e F; marcar/desmarcar listas verticais e horizontais, linhas quebradas, botão mantido, texto/agachar abrindo editor e histórico compartilhado; bloqueio por nota sobreposta; recusa de revisão, projeção e identidade antigas, obstáculo e alcance; callbacks de proteção cancelando ou substituindo a âncora; leitor e convidado após atualização de ACL sem alteração de pixels.

As capturas finais foram inspecionadas visualmente: editor-1280x720-gui2-pair.png, editor-854x480-gui2-overflow.png, editor-1920x1080-gui2.png, editor-1280x720-gui3.png, editor-L.png, editor-ring-gap.png, editor-help.png, editor-properties.png e world-*-checkbox.png. A superfície ocupa a dimensão limitante da viewport, mantendo a proporção física e os vãos.

Permanecem NOT_RUN: clique de checklist exatamente sobre emenda, todas as quatro orientações e montagem de parede, GUI 1/4/ultrawide/4K e suíte completa de todas as ferramentas. Esses limites não são aprovados pelas capturas e testes focados.

Comandos desta rodada:

```powershell
.\gradlew.bat --no-daemon --console=plain test --tests '*CanvasStoreTest' --tests '*SurfaceLayoutTest' --tests '*PlacementRayTest' --tests '*CanvasViewportTest' compileGametestJava
.\tools\validate-editor.ps1 -JdkPath 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot' -TimeoutSeconds 180
.\gradlew.bat --no-daemon --console=plain release -x test -x check
```

O smoke usa perfil .codex-temp/editor-client-130 e mundo descartável. Onboarding de acessibilidade é preparado antes da partida. Todas as esperas de tela/estado são limitadas a 200 ticks; o watchdog externo encerra apenas descendentes novos registrados por PID e data de criação, preservando processos que já existiam.

As regras de colaboração continuam inalteradas: somente donos/convidados editam; uniões entre donos exigem aprovação de todos. Não há migração destrutiva, nova receita ou mudança de versão do Minecraft/Fabric/Java. A instalação pessoal não é modificada. O cliente de teste e o servidor integrado encerraram normalmente; stoppedNewDescendants está vazio e a inspeção final não encontrou processo Java/Minecraft restante.

JAR SHA-256: `ceddc101a39d2cede8a8f66b5536ac4e83b4ac7d601df6d22cd3a13d296c92d9`. JAR, fontes, projeto fonte, documentação e SHA256SUMS.txt ficam em dist/1.3.0. Os 272 arquivos de assets/dados da 1.2.1, incluindo as 36 receitas, são idênticos byte a byte. O smoke usa as classes de desenvolvimento; todas as 88 classes do JAR foram comparadas byte a byte com esse build, separando execução real de conferência do empacotamento.

---
# Canvas Desk 1.2.1 — correção de interação

Data: 29/10/2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 e Java 25 preservados.

O relato do usuário expôs uma falha que os testes anteriores não cobriam: abrir diretamente DeskServer.open não exercitava o fallback vanilla do clique direito. Em 26.3, PASS impede useWithoutItem; o fallback correto é TRY_WITH_EMPTY_HAND. A expansão anterior dependia de atingir um DeskBlock e de uma faixa interna de 20%, impedindo assistência no espaço vizinho.

## Evidências da 1.2.1

| Verificação | Estado | Evidência / escopo |
| --- | --- | --- |
| Compilação main/client e JAR 1.2.1 | PASS | artifacts/1.2.1/release.log e summary.json; SHA-256 conferido |
| 41 testes unitários de dados/superfície/resolvedor geométrico | PASS | artifacts/1.2.1/unit-tests; limite de dois minutos |
| Clique nativo: mão vazia/item comum, vertical/parede/horizontal | PASS | artifacts/1.2.1/smoke/summary.json e open-*.png |
| Superfície inicial em atualização e cliques repetidos | PASS | Loading observado uma vez, ready e epoch estável |
| Expansão pelo centro/borda/célula vizinha/ar | PASS | Prévia e bloco coincidem, consumo de exatamente um item; secundária coberta |
| Alvo ocupado/oclusão/alcance/Shift/slot e duplicidade | PASS | Sem colocação alternativa, consumo indevido ou abertura de editor |
| Callback que cancela ou substitui âncora por baú | PASS | Bloqueio após callbacks antes de usar o bloco substituto, sem UI indevida |
| Colaboração com dois clientes e editor completo | NOT_RUN | Fora do smoke focado desta correção |
| Áudio e todos os providers de resource packs | NOT_RUN | Sem novas alterações nessa área |

O smoke completou em 66.8 segundos, dentro do deadline de 180 segundos. O cliente entrou em mundo descartável e encerrou normalmente; nenhum processo Minecraft de teste ficou aberto. O daemon Gradle que já existia foi preservado.

Comandos executados:

```powershell
.\gradlew.bat --no-daemon --console=plain test --tests '*CanvasStoreTest' --tests '*SurfaceLayoutTest' --tests '*PlacementRayTest' compileClientJava compileGametestJava
.\tools\validate-interaction.ps1 -JdkPath 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot' -TimeoutSeconds 180
.\gradlew.bat --no-daemon --console=plain release -x test -x check
```

O cliente de teste usa perfil e mundo descartáveis próprios, onboarding preparado e deadline externo de 180 segundos. A limpeza encerra apenas processos novos pertencentes à execução; um cliente já aberto é preservado. Falhas e timeouts são registrados com os logs disponíveis, sem aguardar indefinidamente.

JAR SHA-256: `7c48714955fad008bb6bac2fc23279732b8c770ea4a3a9108bc84af660a39709`. Todas as classes distribuídas foram comparadas byte a byte com as classes compiladas usadas na validação; 36 receitas e os recursos existentes foram preservados, sem classes de GameTest no JAR.

A captura open-vertical.png e as capturas de parede/horizontal foram inspecionadas visualmente. O smoke usa classes de desenvolvimento com o mesmo bytecode do JAR; o empacotamento e hashes são conferidos separadamente. Não representa teste completo de texto, multiplayer ou áudio.

Documentos, histórico, ACLs, versões de Minecraft/Fabric/Java, modelos e receitas são preservados. A instalação pessoal não é modificada. Os resultados antigos abaixo são históricos e não aprovam o hotfix.

---

# Canvas Desk 1.2.0 — estado da entrega

Data: 28/10/2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25, Loom 1.17.21 e Gradle 9.6.0.

O pedido inicial dispensava testes e clientes. Depois, o usuário permitiu retomar testes, com cuidado para evitar bloqueios. Nesta rodada foram escolhidos testes unitários e validação estática, sem abrir Minecraft, executar GameTests, iniciar servidor dedicado ou automatizar interfaces. A tarefa unitária tem limite de dois minutos. A carga de 10 mil elementos foi excluída desta execução focada.

## Evidências desta versão

| Verificação | Estado | Evidência / limite |
| --- | --- | --- |
| Compilação main/client com JDK 25 | PASS | Compilações de desenvolvimento concluídas; a entrega final consta abaixo |
| 27 testes de armazenamento e histórico | PASS | build/test-results/test/TEST-br.com.murillo.canvasdesk.core.CanvasStoreTest.xml |
| 5 testes de superfície e visibilidade | PASS | build/test-results/test/TEST-br.com.murillo.canvasdesk.block.SurfaceLayoutTest.xml |
| Recursos: 224 JSON, 6.144 combinações multipart, dois formatos e 16 cores | PASS | art/resource-validation.json; validação estática, sem crafting no jogo |
| Geração da distribuição 1.2.0 | PASS | artifacts/1.2.0/release.log; release -x test -x check |
| Metadados, 36 receitas, exclusão de classes de validação e SHA-256 | PASS | artifacts/1.2.0/summary.json e dist/1.2.0/SHA256SUMS.txt |
| Entrada em mundo / bootstrap real do JAR | NOT_RUN | Validação manual nesta rodada |
| Editor compacto, correspondência visual, vãos e atualização ao vivo | NOT_RUN | Compilação e máscaras unitárias não substituem o teste no mundo |
| Colocação assistida, montagem de parede, colisão e orientação | NOT_RUN | Prévia e aplicação usam o mesmo resolvedor; interação real pendente |
| Texto inline: cursor, seleção, composição, acentos, fonte e fechamento | NOT_RUN | Modelo nativo integrado; entrada e aparência reais pendentes |
| Receitas executadas, loot e conversão de referências vinculadas | NOT_RUN | Definições e componentes verificados estaticamente; crafting real pendente |
| Colaboração real, consentimento e conflitos entre dois clientes | NOT_RUN | Regras do core cobertas por testes; rede e UI reais pendentes |
| Sons normais e feedback opcional | NOT_RUN | Nenhuma chamada para mutar volume/parar sons foi encontrada; relato não confirmado |
| FPS e carga de grandes superfícies no cliente | NOT_RUN | Cache/máscara indexados; nenhuma medição de runtime nesta versão |
| Encerramento e reabertura de mundo com os novos formatos | NOT_RUN | Persistência do core e histórico coberta; chunks reais pendentes |

Comandos usados, sem cliente:

```powershell
.\gradlew.bat --no-daemon --console=plain test --tests '*CanvasStoreTest' --tests '*SurfaceLayoutTest' compileClientJava
python tools/generate-assets.py --validate
.\gradlew.bat --no-daemon --console=plain release -x test -x check
```

O primeiro comando foi repetido após correções de compilação e novas alterações. Os resultados acima correspondem à execução final aprovada, e não a tentativas intermediárias.

## Roteiro de aceitação manual

Use uma cópia descartável do mundo ou faça backup completo antes de atualizar. Todos devem usar o mesmo JAR.

1. **Superfície:** desenhe marcas de tamanho conhecido em um módulo. Com o editor aberto em outro cliente, amplie à direita, esquerda, acima e abaixo. Verifique nova área, coordenadas e tamanho dos desenhos. Navegue manualmente, repita a expansão e confirme que a câmera permanece estável; F deve reenquadrar.
2. **Vãos e moldura:** monte um L e um anel com buraco central. Desenhe uma linha/formas/texto atravessando emendas e vãos. Compare editor e mundo nas quatro orientações. Retire e recoloque módulos: somente os trechos com superfície devem aparecer, sem perda dos demais. A borda revelada deve manter a escala de 256 unidades por módulo.
3. **Sessões:** remova uma ponte com dois editores abertos; cada um deve representar seu fragmento, mantendo o documento. Remova o módulo aberto com outros módulos próximos e confira reancoragem. Remova todos os candidatos: deve fechar com aviso, preservando rascunho pendente quando possível.
4. **Colocação:** segurando módulos, mire na faixa de borda da face ampla e amplie para cima/lados. Compare seta, prévia e bloco final. Verifique alvo ocupado, jogador na colisão, limite de alcance e mundo protegido. Clique em paredes de quatro orientações; confira encaixe e ausência de interseções. Olhar para baixo ou pular deve preservar o formato.
5. **Formatos e cores:** fabrique os dois itens, monte pranchetas em X/Z, varie orientação e fundos. Quebre um módulo com documento, tinja e converta nos dois sentidos; reponha e confirme mesmo vínculo/região/cor. Teste branco para restaurar o padrão. Um leitor sem acesso não deve tingir módulos colocados.
6. **Texto:** use frases com á, é, í, ó, ú, ã, õ e ç, várias linhas, composição, seleção com mouse/Shift, Ctrl+A/C/X/V e paste grande. Digite V/B/T/E e Espaço sem trocar ferramenta. Confira caixa ajustada ao conteúdo, fonte proporcional no resize e recorte/sinalização de conteúdo fora da superfície. Notas e listas devem manter cartões e marcações.
7. **Gravação e histórico:** digite com pausas e continuamente por mais de 5s. Aguarde a confirmação e teste desfazer/refazer por intervalo. Feche durante gravação. Faça dois jogadores editarem o mesmo texto: o conflito deve preservar rascunho e impedir sobrescrita silenciosa. Teste revogação de acesso durante edição.
8. **Permissões e recuperação:** una documentos de donos diferentes, confira a lista antes de aprovar e exija consentimento de todos. Recoloque referências separadas, use recover e reinicie o mundo. IDs, elementos, convidados e fundos devem permanecer corretos.
9. **UI e áudio:** confira painel em resoluções pequenas/grandes e escalas GUI usuais, menus excedentes, atalhos e mundo visível nas margens. Compare áudio normal com editor fechado/aberto. Ative/desative sons opcionais e teste volumes geral/de blocos em zero.

## Distribuição e limitações

A instalação pessoal não foi modificada. Nenhum cliente ou servidor Minecraft foi iniciado nesta rodada. As execuções de compilação e testes foram concluídas. Nenhum processo Minecraft foi aberto por esta rodada. Evidências antigas abaixo são históricas e não comprovam os novos comportamentos.

O JAR final tem SHA-256 `02068b48705ee5ab3e2119cae711fa234ee652fb70fd0e8842eae4aed69a0a34`. Os relatórios unitários e o resultado estático foram copiados para artifacts/1.2.0.

A prévia art/asset-reference.png é um render de authoring dos modelos, não uma captura de Minecraft. Acentos usuais são cobertos pela fonte bitmap padrão; equivalência com providers TTF/unihex personalizados de resource packs não foi validada. O rascunho local de recuperação é uma cópia auxiliar e não substitui o ACK durável do servidor.

---

# Histórico 1.1.0 — resultados anteriores, sem aprovação da 1.2.0

Data: 28/10/2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25, Loom 1.17.21 e Gradle 9.6.0.

O usuário solicitou interromper a validação restante e gerar o JAR. Esta entrega não representa aprovação completa do multiplayer. Nenhum teste adicional será iniciado para o empacotamento.

## Resultados obtidos antes da interrupção

| Verificação | Estado | Evidência |
| --- | --- | --- |
| Build limpo e 24 testes de armazenamento/carga | PASS | `artifacts/build.log`, `build/reports/tests/test/index.html` |
| Recursos, traduções, receita, loot e 128 estados multipart | PASS | `art/resource-validation.json` |
| Receita real, colocação, quatro orientações, expansão vertical, L, buraco e chunk | PASS | `artifacts/runtime/summary.json` |
| Ferramentas, cor personalizada, checklist maior, fonte, resize proporcional, histórico e leitura | PASS | `artifacts/runtime/ui-selftest.log` |
| Quebra, recolocação, recuperação e reabertura real do mundo | PASS | `artifacts/runtime/gameplay-selftest.log` |
| Capturas a três blocos, 1280×720 e 854×480, quatro frentes sem espelhamento | PASS | `artifacts/visual/visual-selftest.log`, `artifacts/visual/*.png` |
| Cliente normal, sem sincronizador de GameTest | PASS | `artifacts/performance/summary.json`: mínimo 29, mediana 30 FPS, 12 amostras, limite configurado em 30 FPS, 854×480 e distância de renderização 3 |
| Servidor dedicado sem classes de cliente; 1.024 blocos e 10 mil elementos | PASS | `artifacts/dedicated/summary.json` |
| Reinício dedicado preservando 1.024 módulos, 10 mil elementos e projeções | PASS | `artifacts/dedicated/restart-summary.json` |
| Conclusão da colaboração com dois clientes reais | NOT_RUN | Tentativas bloqueadas por whitelist/limite de uma vaga do ambiente de testes. Configuração ajustada; repetição dispensada pelo usuário. |
| Novos testes após o pedido de interrupção | NOT_RUN | Intencionalmente dispensados. |
| Queda forçada do sistema e FPS com 10 mil elementos no cliente | NOT_RUN | Recuperação de journal corrompido coberta pelos testes unitários; carga pesada separada do cliente visual. |

O relatório da suíte singleplayer declara dois clientes como NOT_RUN porque seu escopo é um cliente e servidor integrado. As tentativas multiplayer não produziram uma aprovação e foram encerradas. Os registros anteriores permanecem em `artifacts/history` e `artifacts/multiplayer`.

## Desempenho e correções

O fluxo anterior executava a carga pesada junto do sincronizador visual de GameTest, bloqueando quadros durante operações de servidor. A carga agora roda em um processo dedicado sem janela. A medição de 29–30 FPS foi feita no cliente normal com teto de 30 FPS; não é uma medição do FPS máximo da placa.

Na carga dedicada: colocação de 1.024 módulos em 5.487 ms, gravação de 10 mil elementos em 32.865 ms e consulta fria de uma região com 16 elementos em 139 ms. Heap instantâneo: 331.501.240 bytes antes e 222.159.480 depois; houve coleta de lixo, portanto esses valores não representam pico de memória.

Foram corrigidos o foco da entrada de cor, descarte de texturas fora da thread gráfica, reposição do cache e sua fila, consentimento explícito dos donos, fechamento prematuro do armazenamento e reconciliação reentrante durante carga de chunks.

## Distribuição

Comando de empacotamento, sem testes:

```powershell
.\gradlew.bat --no-daemon --console=plain release -x test -x check
```

Arquivos em `dist/1.1.0`: `canvasdesk-1.1.0.jar`, `canvasdesk-1.1.0-sources.jar`, `CanvasDesk-1.1.0-source.zip` e `SHA256SUMS.txt`.

A instalação pessoal não foi modificada. Os processos de teste foram encerrados antes do empacotamento. Os testes de cliente usam as classes de desenvolvimento; o bootstrap do JAR em uma instância pessoal fica para o teste com os amigos.
