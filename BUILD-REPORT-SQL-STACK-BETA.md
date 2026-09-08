# Entrega 3.0 - SQL enxuto e beta de empilhaveis

Data: 2026-09-05. Alvo de compilacao: Leaf API 1.21.11, Java 21.

## Artefato

Arquivo entregue:
`C:/Users/João/Documents/New project/Illegalstack-zetramc-3.0-sql-stack-beta.jar`

Origem: `build/libs/Illegalstack-zetramc-3.0.jar`

Tamanho: 14.561.952 bytes.

SHA-256:
`C37B0EEFC22DC597110AE4803B2FDF28C8BC2E06016B6F1997B206AC17A91E90`

O JAR antigo da pasta principal nao foi substituido.

## Validacao executada

Comando final: `./gradlew.bat clean build --no-daemon`.

Saida final relevante:

```text
> Task :test
> Task :verifySqliteArtifact
Embedded JDBC + native SQLite 3.46.1: OK
> Task :check
> Task :build
BUILD SUCCESSFUL in 16s
8 actionable tasks: 8 executed
```

14 testes JUnit, zero falhas/erros:

- DatabaseServiceTest: 5 testes; schema legado, compactacao repetida,
  preservacao de snapshots antigos, expiracao de possiveis, historico limitado
  e protecao contra gravacao de presenca com revisao antiga.
- StackLedgerTest: 7 testes; conservacao de quantidade, pai/filhos,
  impedimento de reutilizar origem, split/merge, consumo parcial, saturacao,
  expiracao e codec binario limitado.
- HolderRefTest: 2 testes existentes preservados.

O teste adicional `verifySqliteArtifact` abriu uma conexao, criou uma tabela,
gravou e leu dados usando SOMENTE o JAR final, sem a dependencia externa do
classpath de testes. Isso detectou e permitiu corrigir a relocation antiga
incompativel com os nomes JNI do sqlite-jdbc. O driver permanece embutido em
`org.sqlite`; META-INF/services/java.sql.Driver aponta para org.sqlite.JDBC.
Bibliotecas Linux x86_64 e Windows x86_64 e a configuracao nova foram verificadas
dentro do arquivo. O teste nativo executado foi no Windows; nao houve servidor
Leaf em execucao nem validacao de gameplay nesta etapa.

Os 51 nomes de materiais da configuracao foram conferidos contra a API Leaf.
`plugin.yml` continua com nome IllegalStack, versao 3.0 e permissoes existentes.

Warnings: 9 avisos de APIs antigas marcadas para remocao, em BadPotionCheck,
Protections, Msg e fListener; tambem ha avisos existentes de unchecked/deprecated
e de carregamento automatico do framework de testes no Gradle 9. Nao impedem o
build atual (Gradle 8.10.2).

O Java local apresentou erro de sockets Unix durante a comunicacao do Gradle.
Foi usado, apenas nos processos de build, JAVA_TOOL_OPTIONS com um diretorio
Unix-socket inexistente para fazer o JDK usar seu fallback TCP. Nao houve
alteracao permanente do Java/configuracao da maquina.

## Uso

Substitua o JAR do plugin e reinicie o servidor. A configuracao nova sera criada
em `plugins/IllegalStack/AntDupe-Stack-Config.yml`. Ela vem desativada para testes;
o sistema anterior continua usando `item-integrity.yml`.

`/istack restart sql` permanece disponivel com `illegalstack.admin`. Preserva
items/presence e provas, importa o ultimo evento legado por ID ao tail, remove
historico excedente e resumos antigos, e compacta o arquivo. `item_events: ... -> 0`
passa a ser esperado; isso nao significa apagar identidades. A saida informa
`item_event_tail`, tamanhos antes/depois e conclusao.

`sqlite.possible-retention-days: 7` controla apenas os possiveis. Historico de
rotina deixa de acumular indefinidamente. Provas de confirmados/WOULD_REMOVE
legados/REMOVED/DELETE_ABORTED ficam preservadas. Nenhum banco de producao foi
alterado nesta tarefa e a economia em GB ainda precisa ser medida no host.

Empilhaveis: `/istack stack`, `/istack stack stats` e leitura via `/istack inspect`.
Permissao reutilizada: `illegalstack.itemintegrity.inspect` (alem do acesso base
ao comando istack). Nao foi adicionada permissao de edicao/criacao de ID.

## Escopo da beta

Ha identidade binaria, operacoes recentes limitadas em RAM, observacao fisica,
split testemunhado, pontes de merge para cliques/hoppers em armazenamento fisico,
revalidacao pontual, logs, SQLite para casos e webhooks separados.

Nao e cobertura completa de empilhaveis. Receitas/fundicao, continuidade
colocar/quebrar minerais, inventarios virtuais, merges automaticos de drops de
IDs diferentes e pickup em inventario cheio ainda tem limitacoes. PDC diferente
pode impedir empilhamento nesses caminhos. Por isso a beta nao e habilitada
automaticamente nem suporta DELETE.

Veja `STACK-INTEGRITY-BETA.md` para a matriz de limites e os testes manuais.
Nenhum benchmark com 150 CCU foi executado. Nao ha novo scanner periodico:
usamos o scanner existente, fila pontual com budget compartilhado (256 por tick
por padrao) e revalidacoes de 3 ticks. Custos crescem com movimentacoes, jogadores,
slots observados e incidentes, ate os limites de fila/memoria configurados.

## Arquivos alterados

- build.gradle.kts
- src/main/java/main/java/me/dniym/identity/ItemIntegritySystem.java
- src/main/java/main/java/me/dniym/identity/audit/DatabaseService.java
- src/main/java/main/java/me/dniym/identity/config/ItemIntegrityConfig.java
- src/main/java/main/java/me/dniym/identity/conflict/DiscordWebhookNotifier.java
- src/main/java/main/java/me/dniym/identity/listeners/IdentityPlayerInventoryListener.java
- src/main/java/main/java/me/dniym/commands/ItemIntegrityInspectCommand.java
- src/test/java/main/java/me/dniym/identity/audit/DatabaseServiceTest.java

## Arquivos novos

- src/main/resources/AntDupe-Stack-Config.yml
- src/main/java/main/java/me/dniym/identity/stack/StackConfig.java
- src/main/java/main/java/me/dniym/identity/stack/StackToken.java
- src/main/java/main/java/me/dniym/identity/stack/StackLedger.java
- src/main/java/main/java/me/dniym/identity/stack/StackSlot.java
- src/main/java/main/java/me/dniym/identity/stack/StackIdentity.java
- src/main/java/main/java/me/dniym/identity/stack/StackIntegrityService.java
- src/main/java/main/java/me/dniym/identity/stack/StackLifecycleListener.java
- src/test/java/main/java/me/dniym/identity/stack/StackLedgerTest.java
- src/test/java/main/java/me/dniym/identity/audit/SqliteArtifactProbe.java
- STACK-INTEGRITY-BETA.md
- BUILD-REPORT-SQL-STACK-BETA.md
