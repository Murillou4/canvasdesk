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
