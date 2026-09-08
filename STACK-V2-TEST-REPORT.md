# Stack V2 - reconstrucao para testes, nao liberada para producao

## Estado da entrega

Java 21 / Leaf API 1.21.11 / IllegalStack 3.0.
JAR: `C:/Users/João/Documents/New project/Illegalstack-zetramc-3.0-stack-v2-test.jar`.

O modelo de UUID/parent foi reimplementado e a limpeza esta disponivel. O empilhamento por
click, shift-click, duplo clique, pickup de player e hopper recebeu tratamento explicito.
NAO foi concluida a cobertura de todos os merges nativos. Nao ativar em producao como se fosse
uma correcao definitiva do empilhamento: ver pendencias abaixo.

Build real: `gradlew.bat clean build --no-daemon`, BUILD SUCCESSFUL in 25s.
34 testes, zero falhas. JDBC/nativo SQLite 3.51.3 verificado dentro do JAR final.
9 warnings antigos de APIs depreciadas, aviso Mockito/JVM e deprecacao Gradle 9 do test framework.
Nao houve servidor Leaf real com jogadores/plugins externos nesta validacao.

## Regras

- Stack intacta conserva o mesmo UUID ao mudar de slot/holder.
- Divisao 64 -> 32/32 ou 1/63 cria dois filhos com UUIDs novos.
- Reducao de quantidade cria novo UUID no sobrevivente.
- Merge cria novo UUID no destino e, se sobrar, tambem na origem.
- O destino do merge referencia os dois pais; a sobra referencia apenas o pai da origem.
- Sem historico de UUIDs aposentados em SQLite ou RAM. Removido StackLedger.
- Indices limitados por localizacao fisica, UUID atual e pai direto.
- Revalidacao pontual procura duas stacks fisicas com mesmo UUID ou pai fisico junto de filho.
- Divisao ainda conservada no UUID original e reconciliada antes do alerta, para nao anunciar
  um estado transitorio. Esse criterio nao prova ausencia de copias ainda nao observadas.
- Presenca RAM antiga, quantidade inesperada sozinha e inventario virtual nao provam dupe.
- Casos continuos deduplicados. Sem spam de casos ambiguos de stack por operacao nao observada.
- Somente MONITOR. Nenhuma remocao automatica de item por deteccao.

## Propriedades novas no PDC

- `zetra:stack_uuid`: UUID atual (STRING).
- `zetra:parent_stack`: pais diretos separados por virgula; vazio para raiz (STRING).
- `zetra:stack_amount`: quantidade correspondente ao UUID (INTEGER).

O prefixo ZS- aparece na inspecao; nao e lore nem nome. A chave binaria antiga
`zetra:stack_identity` e removida ao migrar. A migracao do modelo antigo REINICIA a identidade
da stack uma unica vez, pois sua linhagem antiga nao e confiavel. Migrar novamente uma stack
ja no formato V2 mantem o UUID. Migracao normal ocorre quando a stack e observada.

Nenhum comando de limpeza remove `zetra:item_id`, registered_at, origin, campos de localizacao,
isencoes, nome/lore/encantamentos ou propriedades de outros plugins.

## Configuracao

Arquivo: `plugins/IllegalStack/AntDupe-Stack-Config.yml`.
O arquivo existente e preservado, inclusive materiais e webhooks. Configs sem model-version: 2
NAO ativam o novo tracking, mesmo se enabled antigo estiver true. Isso evita ativar a nova beta
automaticamente na producao.

Para recuperar o empilhamento vanilla, desligando stacks e limpando suas tags conforme acessadas:

```yaml
model-version: 2
enabled: false
cleanup-only: true
```

Reiniciar normalmente. Limpeza em lotes, por join/abertura/click/movimento de hopper/drop,
sem criar IDs nem gerar casos de stacks. Nao e scan global nem confirmacao de limpeza de todos
os itens offline. Equips continuam com o sistema Item Integrity original.

Para testar o modelo novo APENAS na instancia de teste:

```yaml
model-version: 2
enabled: true
cleanup-only: false
mode: MONITOR
```

Reiniciar normalmente. Remover as antigas opcoes memory.max-retired-ids, memory.retention-seconds
e merging e opcional: elas nao sao usadas no modelo novo. O merge de gameplay nao depende de
historico, inFlight ou budget disponivel do scanner. O trabalho de cada interacao fisica e limitado
pelo tamanho do inventario relevante, mas nao e gratuito sob grande volume de hoppers.

## Comandos

- `/istack inspect` e `/istack stack`: leitura da mao principal, incluindo Parent_stack.
- `/istack stack stats`: estatisticas.
- `/istack stack clean [hand|inventory|block]`: remove somente as quatro chaves de stack conhecidas.
- `/istack stack migrate [hand|inventory|block]`: troca formato antigo pelo novo; V2 mantem ID.

clean/migrate exigem illegalstack.admin, alem do acesso ao comando de inspecao.
Sem parametro de alvo, usam hand. block exige olhar um container fisico a ate 6 blocos.
Com tracking ativo, um item limpo recebe nova identidade ao ser observado novamente.
Portanto use cleanup-only ou enabled:false para voltar definitivamente ao comportamento vanilla.

Shulkers guardadas como item nao sao percorridas recursivamente. Para limpar seus conteudos,
colocar a shulker e usar clean block ou abrir em cleanup-only. Inventarios offline e GUIs de
plugins sem holder fisico precisam ser acessados por um caminho suportado antes da limpeza.

## Testes automatizados

- 7 testes SQLite mantidos, 2 HolderRef.
- 6 testes de mutacao: intacta, 32/32, 1/63, consumo, aumento nao conservado e quantidades invalidas.
- 3 testes PDC: formato/Parent_stack, remocao do formato antigo, limpeza e metadata externa preservada.
- 16 testes de service: fila saturada, slot/cursor/bau, nao ocultar copia atribuindo outro ID,
  split, GUI versus holder fisico, chunk indisponivel, leitura linear, merge com budget esgotado,
  sobra de merge, item sem ID, split imediato, movimento inteiro, caso deduplicado de duas copias,
  pai+filho sem historico e split transitorio sem alerta.

Inventarios/entidades/PDC dos testes usam doubles. Eles nao substituem testes com CraftInventory,
pacotes reais do cliente, protecoes de terceiros ou a carga de 150 jogadores.

## Pendencias que impedem liberacao em producao

1. Arrastar sobre stacks ja ocupadas com PDC diferente: vanilla pode excluir esses slots antes
   do InventoryDragEvent. O listener nao recebe toda a intencao do arraste para reconstruir esse caso.
2. Merge automatico de ItemEntities com UUIDs diferentes: vanilla pode rejeitar antes de ItemMergeEvent.
   Nao foi adicionado scan de entidades para contornar isso; drops podem ficar separados no chao.
3. Inventory#addItem de outros plugins, GUIs virtuais, receitas/maquinas e plugins de shulker na mao
   nao tem integracao completa. A comparacao de PDC continua valendo fora das pontes implementadas.
4. Pickup manual verifica owner/pickup delay, reemite os eventos legacy/moderno para respeitar cancelamentos
   e revalida o item. Animacoes/estatisticas e comportamento com plugins de terceiros exigem teste real.
5. Hopper pode precisar observar a origem primeiro para localizar com seguranca o slot que vanilla
   removeu temporariamente. Nao se escolhe um slot ambiguo por fingerprint.
6. Normalizacao de quantidade observada ocorre na proxima reconciliacao direcionada, nao sincronamente
   em todas as APIs externas. Nao se promete detectar qualquer dupe que se transforme antes de ser observado.

Cobrir TODOS os merges de forma transparente requer estudar uma integracao mais profunda com o
servidor; nao basta simplificar as propriedades do PDC. Nao foi alterado o Leaf nem copiado codigo externo.

## Testes manuais obrigatorios

1. Em copia de teste, clean inventory com stacks desativadas: empilhamento vanilla deve voltar.
2. Ativar modelo 2. Inspecionar 64 diamantes, mover slot/cursor/bau/shulker e voltar: mesmo UUID.
3. Dividir 32/32 e 1/63: dois novos UUIDs, mesmo pai, soma 64 e nenhum caso confirmado.
4. Unir 32+32: 64, UUID novo e dois pais. Unir 63+63: 64+62, dois novos UUIDs, soma 126.
5. Shift-click, duplo clique, pickup com inventario cheio mas espaco nas stacks, hopper e hopper-pickup.
6. Repetir com metadados customizados diferentes: NAO unir itens realmente diferentes.
7. Cancelar pickup/click em plugin de protecao: nao movimentar itens contra o cancelamento.
8. Duas copias fisicas completas com o mesmo UUID: um caso. Pai completo + filho: um caso.
9. Restart com itens no bau/shulker e reabrir. UUID V2 intacto nao pode mudar.
10. Testar cada pendencia acima; nao liberar tracking em producao ate resolver as que fazem parte do servidor.

## Arquivos desta rodada

- commands/ItemIntegrityInspectCommand.java
- identity/stack/StackConfig.java, StackIdentity.java, StackIntegrityService.java, StackLifecycleListener.java, StackSlot.java
- identity/stack/StackMutation.java e StackCleanupListener.java (novos)
- identity/stack/StackLedger.java (removido)
- src/main/resources/AntDupe-Stack-Config.yml
- testes StackLifecycleRegressionTest, StackIdentityV2Test, StackMutationTest; removido StackLedgerTest antigo
- STACK-V2-TEST-REPORT.md

SQL e Item Integrity de equipamentos nao foram modificados nesta rodada. O hotfix SQL anterior esta
incluido, mas NAO recupera o banco corrompido de producao.

Referencias: API Paper 1.21.11 PlayerAttemptPickupItemEvent e InventoryMoveItemEvent; patch Paper
ItemEntity.java para conferir o momento de contagem/pickup e cancelamento. Sem copia de implementacoes.
- https://jd.papermc.io/paper/1.21.11/org/bukkit/event/player/PlayerAttemptPickupItemEvent.html
- https://jd.papermc.io/paper/1.21.11/org/bukkit/event/inventory/InventoryMoveItemEvent.html
- https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/world/entity/item/ItemEntity.java.patch
