# Canvas Desk

Uma bancada modular de escribas para Minecraft Java **26.3**, Fabric e **Java 25**. Madeira, papel, cobre e redstone formam uma superfície onde desenhos, textos e diagramas permanecem salvos no mundo.

## Instalação

1. Instale o Fabric Loader **0.19.5** para Minecraft **26.3**.
2. Coloque `canvasdesk-1.0.0.jar` e **Fabric API 0.164.0+26.3** na pasta `mods` da instância.
3. Em multiplayer, instale os dois mods no servidor e nos clientes. Use Java 25.

Este projeto é independente do MurilloUtils. O JAR não é compatível com Minecraft 1.21.11. A entrega não modifica sua instalação pessoal.

## Construção e uso

Fabrique um módulo com a seguinte receita (qualquer tipo de tábuas):

```text
Papel   Papel    Papel
Cobre   Redstone Cobre
Tábuas  Tábuas   Tábuas
```

Posicione módulos lado a lado na mesma altura. Cada tampo exibe 256 × 256 unidades do documento. Clique com o botão direito para editar; não é necessário fabricar uma caneta. O editor permite desenhar, inserir texto, notas, listas marcáveis, formas, setas e conectores. As alterações aparecem no tampo real.

| Controle | Ação |
| --- | --- |
| V / B / E | Seleção / desenho / borracha |
| T / N / C | Texto / nota / lista |
| R / O / L / A / K | Retângulo / elipse / linha / seta / conector |
| H ou Espaço + arraste | Navegar |
| Botão do meio + arraste | Navegar |
| Roda do mouse | Zoom em torno do cursor |
| F | Enquadrar o conteúdo |
| Shift + seleção | Acrescentar à seleção |
| Alt + clique | Selecionar grupo visível de uma união |
| Enter / duplo clique | Editar texto selecionado |
| Ctrl + Enter | Salvar o diálogo de texto |
| Delete | Excluir seleção |
| Ctrl + Z / Ctrl + Y | Desfazer / refazer |
| Esc | Fechar diálogo ou editor |

Arraste no espaço vazio com a ferramenta de seleção para selecionar vários elementos. O canto inferior direito da seleção redimensiona o elemento. A borracha remove elementos completos. Selecione dois elementos com a ferramenta de conector para criar uma ligação que acompanha seus movimentos. Cor e espessura podem ser alteradas na barra e aplicadas à seleção.

## Documentos, conexões e acesso

- Partes separadas continuam ligadas ao mesmo documento. Remover uma ponte não divide nem copia os desenhos.
- Quebrar um módulo devolve um item vinculado ao documento. Recolocá-lo isoladamente restaura sua região; conectado, ele adota a projeção da superfície existente.
- Reconexões com projeções diferentes preservam a superfície mais antiga. Isso pode mudar a região exibida na outra parte, sem mover ou apagar os desenhos.
- Mesas do mesmo dono podem unir automaticamente quando esse dono fizer a conexão. Donos diferentes precisam aprovar na opção **Acesso e uniões**. Os donos e convidados resultantes são mostrados antes da aprovação.
- A união preserva os elementos e grupos de origem. O histórico de desfazer anterior à união é reiniciado para evitar aplicar operações em coordenadas antigas.
- Donos convidam ou removem editores. Outros jogadores próximos podem ler. A proteção do documento não transforma o bloco em uma área protegida: a quebra continua seguindo as regras do mundo.
- O histórico guarda até 100 ações por jogador. Uma alteração posterior de outro jogador impede desfazer aquele elemento silenciosamente.

## Recuperação e backup

Mesmo que os itens sejam destruídos, o conteúdo permanece no mundo:

```text
/canvasdesk list
/canvasdesk recover <UUID-do-documento>
```

Donos podem recuperar seus documentos; operadores podem recuperar documentos administrativamente. Recuperar produz outra referência ao mesmo documento, sem duplicar seu conteúdo. Referências transportadas de outro mundo não importam os dados ausentes.

Faça backup do **mundo inteiro**, incluindo a pasta `canvasdesk`. Ela contém os documentos, índices, objetos imutáveis, snapshots e o registro de operações. Não copie apenas `snapshot.json` e não edite os arquivos durante o jogo. O registro histórico e objetos antigos são mantidos para recuperação; não há limpeza automática destrutiva.

Depois de uma queda, alterações confirmadas do documento permanecem recuperáveis. Os chunks vanilla são reconciliados separadamente: uma união confirmada pode sobreviver mesmo que o Minecraft reverta a colocação do bloco que conectava as partes.

## Compilação e validação

Requisitos: JDK 25 e acesso aos repositórios oficiais na primeira compilação. O wrapper fixa Gradle 9.6.0; o projeto fixa Loom 1.17.21 e dependências de release.

```powershell
$env:JAVA_HOME = 'C:\caminho\para\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon --console=plain clean build
```

Artefatos: `build/libs/canvasdesk-1.0.0.jar` e `build/libs/canvasdesk-1.0.0-sources.jar`. Os testes de cliente ficam em um source set separado e não são incluídos no JAR distribuível. Consulte **VALIDATION_STATUS.md** para os resultados reais, comandos, evidências e limitações.

Os modelos, texturas e ícones próprios podem ser regenerados por `tools/generate-assets.py`. As fontes editáveis e a validação estática estão em `art/`. A prévia de authoring não substitui as capturas do Minecraft.

Código organizado em `block`, `core`, `server`, `network` e `client`, com editor e renderização isolados no cliente. A renderização usa tiles descartáveis com cache LRU; o documento persistido é vetorial e não depende de uma textura gigante. O tamanho lógico da mesa não tem limite configurado; continuam valendo os recursos disponíveis e os limites do próprio Minecraft.

Licença MIT. Não é um produto oficial de Minecraft, Mojang ou Microsoft.
