# Canvas Desk

Projeto independente, autorizado explicitamente pelo usuário. Não adicionar suas funcionalidades ao MurilloUtils e não modificar a instalação pessoal.

- Manter Minecraft 26.3, Java 25 e toolchain estável fixada até solicitação de atualização.
- Alterar fontes e gerar JAR pelo Gradle; nunca editar binários.
- Código de cliente pertence a src/client. O servidor deve carregar sem classes de cliente.
- Documento é independente de blocos e itens. Quebra, unload e união nunca apagam conteúdo.
- Uniões entre donos diferentes exigem consentimento de todos; convidados não ganham propriedade implicitamente.
- Validar em mundos descartáveis, registrar evidências e lacunas em VALIDATION_STATUS.md, encerrar os processos de teste.
- Modelos e texturas são gerados de fontes editáveis em art/ e tools/generate-assets.py.
