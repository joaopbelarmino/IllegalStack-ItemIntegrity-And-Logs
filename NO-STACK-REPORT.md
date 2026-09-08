# Remocao do subsistema de stacks

Data: 2026-09-07

## Resultado

Removido integralmente o codigo de identidade de stacks: tracking, limpeza,
listeners, fila de trabalho, scheduler, comandos e configuracao independente.
Nao foi adicionada tarefa periodica ou rotina de limpeza substituta.

O listener removido StackCleanupListener.hopper enfileirava os inventarios
de origem e destino a cada transferencia por funil. O custo de entrada por
evento existia mesmo com processamento posterior parcelado. A porcentagem
do perfil fornecido nao permite prometer um ganho exato de TPS.

Item Integrity de equipamentos/shulkers, SQLite, casos/webhooks existentes,
inspecao e /istack restart sql foram preservados. fListener.onHopperXfer e as
protecoes tradicionais contra overstack nao foram modificados.

## Arquivos alterados

- src/main/java/main/java/me/dniym/identity/ItemIntegritySystem.java
- src/main/java/main/java/me/dniym/identity/listeners/IdentityPlayerInventoryListener.java
- src/main/java/main/java/me/dniym/commands/ItemIntegrityInspectCommand.java

## Arquivos removidos

- src/main/java/main/java/me/dniym/identity/stack/StackCleanupListener.java
- src/main/java/main/java/me/dniym/identity/stack/StackConfig.java
- src/main/java/main/java/me/dniym/identity/stack/StackIdentity.java
- src/main/java/main/java/me/dniym/identity/stack/StackIntegrityService.java
- src/main/java/main/java/me/dniym/identity/stack/StackLifecycleListener.java
- src/main/java/main/java/me/dniym/identity/stack/StackMutation.java
- src/main/java/main/java/me/dniym/identity/stack/StackSlot.java
- src/main/java/main/java/me/dniym/identity/stack/StackToken.java
- src/main/resources/AntDupe-Stack-Config.yml
- src/test/java/main/java/me/dniym/identity/stack/StackIdentityV2Test.java
- src/test/java/main/java/me/dniym/identity/stack/StackLifecycleRegressionTest.java
- src/test/java/main/java/me/dniym/identity/stack/StackMutationTest.java

Testes especificos do recurso excluido foram removidos junto com ele.
Relatorios e JARs antigos foram mantidos como historico, nao fazem parte do JAR novo.

## Verificacao

Comando: .\gradlew.bat clean build --no-daemon
BUILD SUCCESSFUL in 22s
8 actionable tasks: 8 executed
9 testes: 7 DatabaseServiceTest, 2 HolderRefTest; zero falhas/erros/skips.
Embedded JDBC + native SQLite 3.51.3: OK
JAR inspecionado: nenhuma entrada identity/stack/ ou AntDupe-Stack.
Classes de IdentityService, ShulkerIdentityListener, ConflictDetector,
DatabaseService, ItemIntegrityConfig e fListener presentes.
plugin.yml preservado, versao 3.0.

Warnings: 9 avisos de API deprecated/marked-for-removal no codigo legado;
notas de operacoes unchecked e uso de funcionalidades Gradle depreciadas
para Gradle 9. Nenhuma alteracao de dependencias ou API nesta entrega.
Nao foi realizado teste de carga ou gameplay em Leaf nesta maquina.

## Entrega e instalacao

JAR: C:/Users/João/Documents/New project/Illegalstack-zetramc-3.0-no-stack.jar
SHA256: 0423F907C8EF8858973887A92302CF66E08D841E3844EB8EB485EC715D89E619

Parar o servidor, substituir o JAR antigo (sem duplicatas) e iniciar normalmente.
AntDupe-Stack-Config.yml existente no servidor nao sera mais lido nem recriado;
pode ser excluido. Nenhum arquivo remoto foi apagado por esta alteracao.
Nao apagar item-integrity.yml ou SQLite.

Tags de stacks que ainda existirem nos itens NAO serao removidas por esta versao.
Esses itens ainda podem nao empilhar com itens de metadata diferente.
/istack stack, clean, migrate e stats do subsistema removido nao existem mais.
Depois do restart, repetir o perfil na mesma carga: StackCleanupListener nao
deve aparecer. Validar /istack inspect em equipamento, lifecycle de shulker
e transferencias normais por funil. Nao atribuir todo custo restante ao
subsistema removido sem novo perfil.
