# Hotfix SQL / stack / TPS

## Entrega

- Java 21, Leaf API 1.21.11; versao do plugin 3.0.
- JAR: `C:/Users/João/Documents/New project/Illegalstack-zetramc-3.0-sql-stack-hotfix.jar`.
- SHA256: `F559A3528793FBC29EA30D7DD717C4A02BAE44360D87ECFCB951B78D935FB84E`.
- `gradlew.bat clean build --no-daemon --warning-mode all`: BUILD SUCCESSFUL in 28s.
- 24 testes, zero falhas. JDBC/nativo dentro do artefato: SQLite 3.51.3 OK.
- Nove warnings antigos de APIs depreciadas; aviso Gradle 9 sobre test framework e aviso JVM de instrumentacao Mockito.

## TPS

Encontrado bug real em StackIntegrityService: drain() reagendava a si mesmo com runTask(), delay zero.
No scheduler CraftBukkit, isso pode executar dentro do mesmo heartbeat. Quando o budget do tick
estava esgotado, o proximo drain nao progredia e se reagendava novamente. Esse caminho pode prender
a server thread. Todas as continuacoes dessa fila agora usam delay explicito de um tick.
Existe tambem guarda para nao drenar duas vezes no mesmo tick.

Outras reducoes de custo:

- getState(false) e getHolder(false) evitam snapshot completo de block entity por slot.
- Reconciliacao le cada slot uma vez, agrupando por UUID, em vez de inventario inteiro por UUID.
- Witness nao clona itens sem identidade de stack.
- Eventos de hopper/drop sem certificado nao montam snapshots de stack desnecessarios.
- Budget compartilhado limitado a 128..512 unidades/tick, default 256. Cada drain cede apos 2ms
  entre trabalhos; nao e uma garantia de tempo maximo para uma chamada Bukkit individual.
- Fila de logs limitada a 512 entradas; rejeicao nao escreve no disco pela main thread.
- Alertas de falha SQL/fila saturada limitados a um por minuto.
- Nenhuma nova tarefa periodica. Pump sob demanda, no tick seguinte enquanto houver backlog;
  revalidacoes continuam pontuais. Sem scan global ou carregamento de chunks.
- Stats mostram backlog, maxDrainMs, assigned, splits, merges e failedTagWrites.

O bug de agendamento foi identificado no codigo; nao houve acesso a um perfil spark/watchdog da
ocorrencia em producao. Nao se afirma que todo custo do IllegalStack ou de outros plugins foi eliminado.
Os timers legados do IllegalStack nao foram reescritos. Confirmar desempenho com perfil real.

## UUID de stack

- Transferencia inteira sem alterar quantidade nao cria filhos nem substitui o UUID existente.
- Divisao conservativa comprovada pode criar filhos; merge explicito altera as stacks e seus UUIDs.
- Duas copias de 64 com o mesmo UUID nao sao reidentificadas para esconder a anomalia.
- Inventario virtual cujo holder e Player nao e mais confundido com PlayerInventory.
- GUI emprestando o holder de um container nao e mapeada ao bloco, se nao e seu inventario real.
- Containers nao colocados nao geram localizadores fisicos.
- Escrita de identidade inicial e conferida por releitura do slot antes de entrar no indice RAM.
- Witness atrasado alem da janela configurada nao pode reescrever linhagem com snapshot antigo.
- Regressoes automatizadas: slot/cursor/bau ida-volta, split, duplicata inalterada, GUI, chunk
  indisponivel, leitura linear e budget saturado. Inventarios sao doubles, nao um servidor Leaf completo.

A perda relatada pelo usuario nao foi reproduzida com seus plugins externos. Os testes verificam
o fluxo de movimentacao do service; nao provam que outro plugin preserve PDC. Se persistir, enviar
UUID antes/depois, tipo de clique e stats assigned/splits/merges/failedTagWrites.
Crafting, fornalhas e outros caminhos nao suportados da beta continuam fora desta correcao.

## SQLite

- quick_check antes de flush/limpeza manual. Corrupcao suspende persistencia e writeAndConfirm
  retorna false; repetir o comando informa que a recuperacao offline e necessaria.
- Nao ha recuperacao automatica destrutiva, exclusao de banco ou descarte de tabela corrompida.
- Substituicao manual de arquivos, hardlink e exclusao de WAL/SHM removidos do caminho de compactacao.
- VACUUM transacional usa a mesma conexao. Verifica integridade, contagens de items/presence/tail/casos
  e checkpoint final. Leitores existentes continuam no mesmo banco.
- Preflight conservador de espaco para VACUUM: cerca de 2x DB+WAL+SHM mais 64MiB livres.
  Tambem e preciso espaco no diretorio temporario do SQLite. Quota da hospedagem pode diferir do disco.
- Caso falte espaco depois da limpeza em lotes, o historico ja pode estar reduzido sem o arquivo
  ter encolhido. Nao e correto interpretar uma falha como rollback de toda a manutencao.
- Flush antes da manutencao e limitado ao backlog inicial; nao persegue produtores indefinidamente.
- Acesso da hidratacao inicial e do writer serializado; rotina continua sem SQL na main thread.
- Writer fecha a propria conexao ao encerrar. Falha de hidratacao encerra a instancia do writer.
- Driver embutido atualizado para 3.51.3.0, sem relocation JNI. Log imprime versao realmente carregada;
  o servidor pode fornecer outro org.sqlite. Nao foi comprovado qual componente causou a corrupcao.
- Testes com SQLite real: corrupcao de B-tree de fixture, abort sem apagar items/presence/casos,
  bloqueio de escrita confirmada, manutencao repetida, retencao e leitor aberto durante compactacao.

O banco de producao com SQLITE_CORRUPT NAO foi reparado. Parar servidor normalmente, preservar DB e
sidecars restantes juntos, e trabalhar sobre copia offline. Restaurar backup validado ou recuperar
para outro arquivo e validar antes de substituir. Nao apagar WAL/SHM manualmente.

## Teste antes da producao

1. Instalar somente este JAR na instancia de teste, sem manter duas versoes do IllegalStack.
2. Ativar stacks somente no teste; continuam MONITOR e desativadas por default no arquivo distribuido.
3. Inspecionar 64 diamantes; mover inteiro via cursor, slots, bau vazio e volta, incluindo shift-click.
   Mesmo ZS em todas as etapas. Usar bau vazio para nao confundir transferencia com merge.
4. Repetir com bau duplo, drop/pickup e restart. Nenhuma identidade nova em movimento inteiro.
5. Dividir 64 em 32/32: novos filhos com ancestral original. Unir stacks: alteracao intencional.
6. Duplicar deliberadamente uma stack sem modificar quantidade: nunca atribuir outro ID para esconder.
7. Exercitar hoppers/inventarios simultaneos; consultar /istack stack stats e perfil spark/MSPT.
8. /istack restart sql apenas em banco saudavel ou copia recuperada. Esperar Status OK.
9. Em fixture corrompida, esperar FAIL_OPEN e persistencia suspensa; nao remocao automatica.

## Arquivos alterados nesta rodada

- build.gradle.kts
- src/main/java/main/java/me/dniym/commands/ItemIntegrityInspectCommand.java
- src/main/java/main/java/me/dniym/identity/ItemIntegritySystem.java
- src/main/java/main/java/me/dniym/identity/audit/AuditQueue.java
- src/main/java/main/java/me/dniym/identity/audit/DatabaseService.java
- src/main/java/main/java/me/dniym/identity/conflict/CaseFileLogger.java
- src/main/java/main/java/me/dniym/identity/stack/StackConfig.java
- src/main/java/main/java/me/dniym/identity/stack/StackIntegrityService.java
- src/main/java/main/java/me/dniym/identity/stack/StackLifecycleListener.java
- src/main/java/main/java/me/dniym/identity/stack/StackSlot.java
- src/test/java/main/java/me/dniym/identity/audit/DatabaseServiceTest.java
- src/test/java/main/java/me/dniym/identity/stack/StackLifecycleRegressionTest.java (novo)
- HOTFIX-SQL-STACK-REPORT.md (novo)

## Referencias consultadas

- https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/org/bukkit/craftbukkit/scheduler/CraftScheduler.java
- https://jd.papermc.io/paper/1.21.11/org/bukkit/block/Block.html#getState(boolean)
- https://www.sqlite.org/lang_vacuum.html
- https://www.sqlite.org/howtocorrupt.html
- https://www.sqlite.org/wal.html#walreset
- https://github.com/xerial/sqlite-jdbc/discussions/1393

Referencias para validar comportamento; nenhum codigo externo copiado.
