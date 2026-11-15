# Como contribuir

Relatos reproduzíveis e revisões cuidadosas são tão úteis quanto código novo.

## Bugs
Use o template de issue. Inclua branch/commit, Java, build do Leaf, comportamento esperado e observado. Para falsos positivos, descreva a transferência completa e as duas presenças, sem expor dados pessoais ou credenciais.

## Pull requests
Crie uma branch específica e mantenha as mudanças pequenas. Explique causa, solução, riscos, custo na thread principal e testes. Preserve a arquitetura existente e os créditos.

Não adicione varreduras globais, SQL síncrono no tick ou Bukkit assíncrono. Não trate um snapshot histórico como prova de duas cópias simultâneas. Toda ação destrutiva exige evidência persistida e revalidação.

Execute o build com Java 21:
```bash
bash ./gradlew clean build --no-daemon
```

No Windows: .\\gradlew.bat clean build --no-daemon.

Documente o que não foi testado no servidor. Código assistido por IA é aceito, mas o autor do PR deve revisar o resultado, compreender as mudanças e informar as limitações.

## Segurança e licenças
Não envie bancos, playerdata, JARs de terceiros, logs completos ou URLs de webhook. Não copie código externo sem identificar a licença e justificar a incorporação. Para vulnerabilidades, consulte SECURITY.md.
