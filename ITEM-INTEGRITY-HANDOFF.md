# ITEM-INTEGRITY-HANDOFF.md

Documento de handoff do subsistema **Item Integrity System**, construído
como um módulo novo dentro da fork do IllegalStack (`main.java.me.dniym.identity`)
para o servidor ZetraMC. Escrito pra permitir que outra IA ou desenvolvedor
continue o trabalho recebendo **apenas o zip do projeto + este documento**,
sem depender do histórico da conversa original.

Convenção de status usada neste documento:
- **IMPLEMENTED** — existe, foi escrito e revisado, compila contra stubs.
- **PARTIAL** — existe mas cobre só parte do que deveria.
- **PLANNED / TODO** — decidido em conversa, nada de código existe ainda.
- **NOT VALIDATED** — código existe mas não foi confirmado contra o
  ambiente real (build Gradle real, servidor real).

---

## 1. Resumo executivo

Item Integrity **não é** um anti-dupe convencional (que tenta bloquear
cada método conhecido de duplicação um por um). É uma camada de
**integridade de identidade**: todo item rastreável recebe um identificador
próprio, persistente e imutável (o "Item ID"), e o sistema tenta manter
registro de **onde** essa identidade está confiavelmente presente ao longo
do tempo (o "presence").

A filosofia central: **não importa qual bug/exploit causou a duplicação** —
se o mesmo Item ID for confirmado presente em duas localizações
incompatíveis ao mesmo tempo, isso é uma prova direta de que existe um
objeto duplicado, independente do mecanismo que produziu isso. O sistema
não tenta prever/bloquear cada exploit individualmente; ele detecta o
**estado impossível resultante**.

Escopo atual: **somente itens naturalmente não-empilháveis**
(`getMaxStackSize() == 1`) — espadas, armaduras, elytra, totens, shulker
boxes, etc. **Itens empilháveis (diamante, lingote, etc) estão
explicitamente fora de escopo** nesta versão — isso é uma decisão de
escopo, não uma limitação técnica esquecida. Shulker box é a prioridade
declarada da próxima etapa de desenvolvimento (etapa 5, ainda não
implementada — ver seção 16).

---

## 2. Estado atual do projeto

| Etapa | Descrição | Status |
|---|---|---|
| 1 | IdentityService (geração/leitura/escrita de Item ID via PDC) | **IMPLEMENTED** |
| 2 | MigrationService / LEGACY_IMPORT | **IMPLEMENTED** |
| 3 | Presence Registry (canonical vs observação) | **IMPLEMENTED** |
| 4 | SQLite / AuditQueue (persistência, hidratação, write-behind, write-and-confirm) | **IMPLEMENTED, NOT VALIDATED contra build Gradle real** |
| 5 | Shulker lifecycle (ItemStack ↔ TileState, transferência explícita de PDC) | **TODO** — nada de código existe |
| 6 | Fingerprint | **TODO** — nada de código existe |
| 7 | ConflictDetector (decisão handoff vs conflito, ação MONITOR/REMOVE) | **TODO** — nada de código existe |
| 8 | Quarantine / Delete-with-backup | **TODO** |
| 9 | Webhook Discord | **TODO** |
| 10 | Testes automatizados | **TODO** — só testes manuais feitos pelo usuário via consulta direta ao SQLite |

**Onde o desenvolvimento parou exatamente:** etapa 4 concluída e revisada
(incluindo uma correção posterior de comparação de holder — ver seção 6).
O próximo trabalho planejado é a etapa 5 (shulker lifecycle), mas ela
**ainda não foi iniciada** — nenhuma classe `ShulkerIdentityService` ou
listener de `BlockPlaceEvent`/`BlockBreakEvent` para shulker existe no
código atual.

---

## 3. Arquitetura atual

Pacote raiz: `main.java.me.dniym.identity` (caminho real:
`src/main/java/main/java/me/dniym/identity/`, seguindo a convenção
(estranha, mas pré-existente no projeto original) de o próprio IllegalStack
usar `main.java.me.dniym.*` como pacote).

```
identity/
├── ItemIdentity.java            value object da identidade (classe, não record)
├── ItemOrigin.java              enum de origem
├── IdentityService.java         geração/leitura/escrita do Item ID via PDC
├── TrackabilityPolicy.java      regra de rastreabilidade + integrity_exempt
├── MigrationService.java        atribui LEGACY_IMPORT a item sem ID
├── MigrationResult.java         record (identity, freshlyAssigned)
├── ItemIntegritySystem.java     composição raiz, instanciada 1x no onEnable()
│
├── presence/
│   ├── HolderType.java          enum: PLAYER, ITEM_ENTITY, SHULKER_BLOCK, CHEST, BARREL, ENDER_CHEST, CONTAINER_OTHER
│   ├── HolderRef.java           sealed interface (PlayerHolder/ItemEntityHolder/ContainerHolder) + sameOwner()
│   ├── PresenceState.java       enum: LIVE_CONFIRMED, OFFLINE_COMMITTED, PERSISTED_BLOCK, PERSISTED_CONTAINER, STALE, UNKNOWN, MISSING
│   ├── PresenceRecord.java      record: identity, holder, state, presenceRevision, lastConfirmedAtMs
│   ├── PresenceObservation.java record: canonicalBefore, candidate, sameOwnerAsCanonical, autoCommitted, canonicalAfter
│   ├── PresenceStore.java       interface: getCanonical/observe/commitHandoff/getLastDivergentObservation
│   ├── InMemoryPresenceStore.java     impl 100% em memória (fallback FAIL_OPEN se SQLite não iniciar)
│   └── SqliteBackedPresenceStore.java impl real usada quando SQLite está disponível
│
├── audit/
│   ├── AuditQueue.java           fila limitada, não-bloqueante, descarta+loga se cheia
│   ├── AuditTask.java            sealed interface: RegisterItem / RecordEvent / UpdatePresence
│   ├── ItemSnapshot.java         DTO imutável p/ registrar linha em `items`
│   ├── ItemEventSnapshot.java    DTO imutável p/ registrar linha em `item_events` (+ upsert presence)
│   ├── PresenceUpdateSnapshot.java DTO imutável p/ upsert SÓ em `presence` (sem item_events)
│   ├── IdentityEventType.java    enum: IDENTITY_ASSIGNED, PRESENCE_COMMITTED, PRESENCE_DIVERGENT_OBSERVED
│   └── DatabaseService.java      conexão SQLite, schema, writer thread único, batching, hidratação, writeAndConfirm()
│
├── config/
│   └── ItemIntegrityConfig.java  carrega/cria plugins/IllegalStack/item-integrity.yml
│
└── listeners/
    └── IdentityPlayerInventoryListener.java  join/quit/scan periódico do inventário de jogador
```

Observação importante: **`ItemOrigin` NÃO tem um valor `PLUGIN_EPHEMERAL`
nem `PLUGIN_PERSISTENT`** — esses são conceitos da seção 5 (trackability),
não valores de `ItemOrigin`. Não confundir os dois. `ItemOrigin` atual tem:
`LEGACY_IMPORT, CRAFT, SMITHING, ANVIL, PICKUP, CHEST_BREAK, SHULKER_BREAK,
ADMIN, PLUGIN, UNKNOWN` — só `LEGACY_IMPORT` é efetivamente usado hoje (os
demais existem no enum pra quando os listeners de craft/anvil/smithing
forem escritos, mas **nenhum código atribui essas origens ainda**).

`PresenceTransition`/`TransitionClassification`, mencionados numa proposta
de arquitetura anterior (antes da implementação real), **não existem no
código** — foram substituídos por `PresenceObservation` +
`sameOwnerAsCanonical`/`autoCommitted`, que cobrem a mesma necessidade de
forma mais simples. Se você (próxima IA) viu esses nomes em algum lugar,
saiba que é resíduo de uma versão de design anterior, não código real.

---

## 4. Item ID

Formato:
```
ZI-YYYYMMDDTHHmm-<world>-x<X>-y<Y>-z<Z>-<24 hex chars>
```
Exemplo real observado em teste do usuário:
`ZI-20260809T0049-spawn-x-416-y-13-z-466-16f09c88cc169d609a615134`

- **Timestamp**: `yyyyMMdd'T'HHmm`, no timezone de `identity.timezone` do
  `item-integrity.yml` (`SYSTEM_DEFAULT` por padrão = fuso do servidor).
- **World**: nome REAL do mundo, sanitizado só o mínimo necessário pra
  caber na string (troca qualquer caractere que não seja letra/dígito/`_`/`-`
  por `_`). O nome **original não-sanitizado** é gravado à parte (PDC
  `zetra:registered_world` e coluna `items.registered_world`).
- **Coordenadas**: bloco (`getBlockX/Y/Z`), incluindo sinal negativo direto
  na string (`x-182`). Se não houver `Location` confiável no momento do
  registro, vira `unknown` / `xNA-yNA-zNA`.
- **Random**: `SecureRandom`, 12 bytes → 24 caracteres hex (96 bits) — essa
  é a garantia real de unicidade, não a combinação data+local.
- **PDC keys** (namespace fixo `"zetra"`, não o namespace automático do
  plugin): `zetra:item_id` (STRING, é o Item ID completo acima),
  `zetra:registered_at` (LONG), `zetra:origin` (STRING), `zetra:registered_world`
  (STRING), `zetra:registered_x/y/z` (INTEGER, ausentes se desconhecido).
  Adicionalmente, `zetra:integrity_exempt` (INTEGER 0/1) e
  `zetra:integrity_exempt_reason` (STRING) — ver seção 5.
- **Nunca fazer parsing do ID pra recuperar dados.** Todos os campos
  estruturados são lidos diretamente das PDC keys (ou das colunas do
  banco), nunca reconstruídos fazendo parse da string do Item ID. Isso é
  uma invariante deliberada (ver seção 26).
- **Imutabilidade**: uma vez atribuído (`IdentityService.assignIdentity`),
  nada no código atual altera o Item ID de um item existente.
  `IdentityService.assignIdentity()` não verifica se já existe — é
  responsabilidade de quem chama (hoje, só `MigrationService.ensureIdentity`)
  nunca chamar isso num item que já tem identidade.

---

## 5. Trackability

`TrackabilityPolicy.isTrackable(ItemStack)`, nesta ordem:

1. **`zetra:integrity_exempt` (PDC, INTEGER, != 0) → sempre `false`,
   checado ANTES de qualquer outra regra.** Pensado pra itens técnicos de
   plugin (relógio de menu, seletor de servidor, gadget de lobby, item de
   GUI) que não representam gameplay persistente. `TrackabilityPolicy.markExempt(ItemStack, String reason)`
   existe como ponto de extensão pronto — **não é chamado por nada no
   código atual**, é só a API pra outro sistema/plugin marcar itens.
2. `FORCE_EXCLUDE` / `FORCE_INCLUDE` (`Set<Material>` vazios hoje) — pontos
   de extensão pra whitelist/blacklist futura via config. Não conectados a
   nenhuma chave do `item-integrity.yml` ainda.
3. Regra base: `type.getMaxStackSize() == 1`.

Diferença conceitual (não codificada como enum, é uma distinção de uso):
- **item real de gameplay feito por plugin** → continua rastreado
  normalmente (origin=`PLUGIN` quando algum listener futuro atribuir isso
  — hoje nada atribui `PLUGIN` como origin, só existe no enum).
- **item técnico/efêmero** (`integrity_exempt=true`) → nunca recebe Item
  ID, nunca entra no `PresenceStore`, nunca gera `LEGACY_IMPORT`, nunca
  gera alerta.

---

## 6. Presence model

**IDENTITY ≠ PRESENCE.** O Item ID (identidade) existir/estar cadastrado
no banco **não** significa duplicação. O que importa é onde a instância
**legítima conhecida** daquele Item ID está — isso é presença.

- **Canonical presence**: o `PresenceRecord` atualmente aceito como cadeia
  legítima (`PresenceStore.getCanonical`).
- **Observation / candidate presence**: uma nova observação do mesmo Item
  ID, em holder possivelmente diferente. Representada por
  `PresenceObservation`.
- **Regra central implementada**: `PresenceStore.observe()` só sobrescreve
  a canonical automaticamente se:
  - não havia canonical ainda (identidade nova), OU
  - o novo holder é o **mesmo dono lógico** da canonical atual
    (`HolderRef.sameOwner()`).

  Se o dono lógico for diferente, a observação **fica registrada como
  candidata** (acessível via `getLastDivergentObservation`), mas a
  canonical **não é tocada**. Isso é de propósito: sobrescrever
  automaticamente apagaria a evidência necessária pra um futuro
  `ConflictDetector` decidir handoff vs conflito.
- **`commitHandoff()`** existe na interface e nas duas implementações,
  pronto pra um handoff EXPLICITAMENTE decidido (uso futuro do
  `ConflictDetector`) — **nada no código atual chama isso**
  automaticamente para holders diferentes.

**`HolderRef.sameOwner(a, b)`** compara pelo **dono lógico**, não pelo tipo
de holder:
- `PlayerHolder` vs `PlayerHolder`: compara `playerId` (UUID).
- `ItemEntityHolder` vs `ItemEntityHolder`: compara `entityUuid`.
- `ContainerHolder` vs `ContainerHolder`: compara `type` + `world` + `x/y/z`
  (ignora `slot`).
- Tipos diferentes (ex: `PlayerHolder` vs `ContainerHolder`) → sempre `false`.

Exemplos concretos já cobertos pela lógica implementada e testados
manualmente pelo usuário (ver seção 23 pra o que NÃO foi testado):
- `PLAYER João slot 3` → `PLAYER João slot 8`: mesmo dono lógico (mesmo
  `playerId`), `sameOwner=true`, auto-commit, **sem** `item_event` (só slot
  mudou — ver seção 8).
- `PLAYER João` → `PLAYER Pedro`: dono lógico diferente mesmo sendo
  `PLAYER→PLAYER`, `sameOwner=false`, **não** sobrescreve canonical, vira
  observação divergente + `item_event`.
- `CHEST A` → `CHEST B`: dono lógico diferente mesmo sendo
  `CONTAINER→CONTAINER` (coordenadas diferentes), mesmo tratamento.

---

## 7. Presence states

`PresenceState` enum, com o **nível de confiança pretendido** (a matriz de
detecção completa que consumiria isso ainda não existe — ver seção 13):

| Estado | Significado | Confiança pretendida |
|---|---|---|
| `LIVE_CONFIRMED` | Verificado agora mesmo (jogador online, scan acabou de confirmar) | Alta |
| `OFFLINE_COMMITTED` | Inventário salvo no logout, confirmação forte no momento do quit | Alta |
| `PERSISTED_BLOCK` | Bloco/shulker conhecido e persistido numa localização, chunk não necessariamente carregado | Média-alta (depende de recência) |
| `PERSISTED_CONTAINER` | Presença conhecida em container persistente | Média-alta |
| `STALE` | Última localização conhecida existe no histórico, mas sem confiança suficiente de que ainda está lá | Baixa — não deve autorizar remoção sozinha |
| `UNKNOWN` | Identidade conhecida, localização atual desconhecida | Baixa — não deve autorizar remoção sozinha |
| `MISSING` | Localização anterior foi verificada e o item não estava mais lá | Indica possível perda, não confirma destruição |

**Importante**: nenhum código atual calcula transições pra `STALE`,
`PERSISTED_BLOCK`, `PERSISTED_CONTAINER` ou `MISSING` — só
`LIVE_CONFIRMED` e `OFFLINE_COMMITTED` são efetivamente produzidos hoje,
pelo único listener existente (`IdentityPlayerInventoryListener`). Os
outros estados existem no enum, prontos pra quando containers/shulkers
tiverem cobertura (etapa 5+).

---

## 8. Presence vs item_events

Separação implementada em `SqliteBackedPresenceStore.observe()`:

- **`presence`** (tabela SQLite) = estado atual/canônico, sempre
  atualizado a cada observação auto-commitada (via `AuditTask.UpdatePresence`
  ou como parte de `AuditTask.RecordEvent`).
- **`item_events`** (tabela SQLite) = só recebe linha nova quando
  `isMeaningfulChange(before, candidate)` é `true`:
  - `before == null` (identidade nova/primeira presença conhecida), OU
  - `!HolderRef.sameOwner(before.holder(), candidate.holder())` (dono
    lógico mudou — ver seção 6), OU
  - `before.state() != candidate.state()` (ex: `LIVE_CONFIRMED` →
    `OFFLINE_COMMITTED`).
  - Observações **divergentes** (dono diferente da canonical) sempre
    passam por aqui também, incondicionalmente.
- Se nada disso for verdade (só o `slot` mudou dentro do mesmo dono
  lógico), a observação vira só `AuditTask.UpdatePresence` — `presence` é
  atualizada, `item_events` não recebe linha nenhuma.

`last_confirmed_at` (coluna de `presence`) é atualizado em **toda**
observação auto-commitada, trivial ou não, porque o caminho leve
(`UpdatePresence`) também grava esse timestamp. **Não existe mais** um
mecanismo de heartbeat separado gerando `item_event` periódico — uma
versão anterior desta sessão tinha isso e foi removida a pedido do usuário
por ser redundante (o `presence.last_confirmed_at` já fica em dia sozinho).

**Preocupação de performance que a próxima IA deveria auditar:** o
listener atual (`IdentityPlayerInventoryListener`) chama
`presenceStore.observe()` pra **cada item rastreável, em cada slot, a
cada ciclo do scan periódico** (`ItemScanTimer`, default 10 ticks = 0,5s).
Num teste real do usuário, ~4 itens geraram 221 linhas de `item_events`
em ~1 minuto — isso foi **antes** da correção de holder lógico (seção 6)
e da separação presence/item_events (seção 8) terem sido aplicadas juntas;
o volume real pós-correção **não foi novamente medido em produção**. Vale
a próxima IA rodar um teste real e confirmar que o volume caiu como
esperado.

---

## 9. SQLite

- **Localização**: `plugins/IllegalStack/<sqlite.file>`, default
  `item-integrity.db` (config `item-integrity.yml`, chave `sqlite.file`).
- **WAL**: `PRAGMA journal_mode=WAL` + `PRAGMA synchronous=NORMAL`, aplicado
  uma vez no `initSchema()`.
- **Schema** (sem versionamento/migração de schema implementado — só
  `CREATE TABLE IF NOT EXISTS`, então mudanças de schema futuras precisam
  de uma estratégia de migração manual, **isso ainda não existe**):

```sql
CREATE TABLE IF NOT EXISTS items (
    item_uuid TEXT PRIMARY KEY,
    material TEXT,
    created_at INTEGER,
    origin TEXT,
    registered_world TEXT,
    registered_x INTEGER,
    registered_y INTEGER,
    registered_z INTEGER,
    first_owner TEXT,
    last_owner TEXT,
    first_seen INTEGER,
    last_seen INTEGER,
    status TEXT,        -- só 'ACTIVE' é gravado hoje (hardcoded no INSERT)
    fingerprint BLOB     -- sempre NULL hoje (etapa 6 não existe)
);

CREATE TABLE IF NOT EXISTS item_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_uuid TEXT,
    time INTEGER,
    event_type TEXT,     -- IdentityEventType: PRESENCE_COMMITTED | PRESENCE_DIVERGENT_OBSERVED
    player_uuid TEXT,
    world TEXT,
    x INTEGER, y INTEGER, z INTEGER,
    holder_type TEXT,
    slot INTEGER,
    fingerprint BLOB      -- sempre NULL hoje
);
CREATE INDEX IF NOT EXISTS idx_item_events_item_uuid ON item_events(item_uuid);

CREATE TABLE IF NOT EXISTS presence (   -- mirror durável do estado atual, não é log
    item_uuid TEXT PRIMARY KEY,
    holder_type TEXT,
    world TEXT, x INTEGER, y INTEGER, z INTEGER,
    player_uuid TEXT, player_name TEXT, slot INTEGER, entity_uuid TEXT,
    state TEXT,
    revision INTEGER,
    last_confirmed_at INTEGER
);
```

- **Writer único**: uma única `Connection` JDBC, usada exclusivamente por
  uma `Thread` dedicada (`IllegalStack-ItemIntegrity-Writer`, daemon).
  Nunca há uma segunda conexão/thread escrevendo.
- **Batching**: `AuditQueue.takeBatch(maxBatchSize, timeoutMs)` bloqueia
  até `flush-interval-ms` esperando pelo menos 1 item, depois drena o
  resto disponível sem esperar mais (até `batch-size`). Cada lote é uma
  transação só (`setAutoCommit(false)` + `commit()`/`rollback()`).
- **Queue**: `AuditQueue` (capacidade `sqlite.queue-capacity`, default
  20000). `offer()` nunca bloqueia — se cheia, descarta o item mais
  antigo e loga um `WARN` (throttled: 1 a cada 1000 descartes).
- **Hidratação**: `DatabaseService.hydratePresenceWithIdentity()`, uma
  query síncrona rodada **uma vez**, no construtor de
  `SqliteBackedPresenceStore` (chamado do `onEnable()`). Faz
  `presence LEFT JOIN items ON item_uuid` — 19 colunas retornadas por
  linha. `SqliteBackedPresenceStore.rowToRecord()` reconstrói o
  `ItemIdentity` **completo** (origin/registered_at/world/x/y/z reais
  vindos de `items`), não um placeholder. `ItemOrigin.UNKNOWN` só aparece
  se a linha de `items` genuinamente não existir (LEFT JOIN retornando
  null de verdade) — nesse caso um `WARN` de `ORPHAN_PRESENCE_RECORD` é
  logado.
- **Shutdown**: `DatabaseService.shutdown()` seta uma flag `running=false`,
  dá `join(5000)` na writer thread, fecha a conexão. Chamado de
  `ItemIntegritySystem.shutdown()`, chamado de `IllegalStack.onDisable()`.
  **Não testado em ambiente real** (não há como simular um shutdown
  gracioso do Bukkit no ambiente do Claude).
- **FAIL_OPEN**: se `DatabaseService`/`SqliteBackedPresenceStore` lançarem
  exceção na construção, `ItemIntegritySystem` captura e cai pra
  `InMemoryPresenceStore` (sem persistência, sem auditoria, mas o sistema
  continua funcionando em memória).

---

## 10. Write-behind e write-and-confirm

Duas classes de escrita, **implementadas e distintas de propósito**:

1. **Rotina** (presença comum) — `SqliteBackedPresenceStore` chama
   `AuditQueue.offer()`, que é fire-and-forget. **Existe uma janela real
   de write-behind**: entre a canonical mudar em memória e o writer
   thread comitar aquele lote no SQLite (até `flush-interval-ms`, default
   1000ms), se o processo morrer, esse intervalo de eventos não está no
   banco ainda. Isso é reconhecido explicitamente no Javadoc de
   `DatabaseService` como aceito — o pior caso é perder um pedaço pequeno
   de histórico de auditoria, não corromper item nem perder gameplay (a
   canonical em memória é reconstruída na próxima hidratação a partir do
   que **foi** commitado).

2. **Confirmada** (`DatabaseService.writeAndConfirm(AuditTask) → CompletableFuture<Boolean>`)
   — pra uso **futuro** de ações destrutivas (`DELETE_WITH_BACKUP`, etapa
   7+). Entra numa fila com prioridade sobre o lote de rotina
   (`confirmedWriteQueue`, drenada no início de cada iteração do
   `writerLoop`), escreve numa transação própria, e o `CompletableFuture`
   **só resolve `true` depois do commit real acontecer** (`writeBatch`
   retorna `boolean`, e só então o future é completado). **Nada no código
   atual chama `writeAndConfirm`** — é infraestrutura pronta, não
   utilizada ainda, porque não existe nenhuma ação destrutiva implementada
   (etapas 7/10 são TODO).

**Fluxo planejado (documentado, não implementado)** pra quando o
`ConflictDetector`/`QuarantineService` existirem:
```
DETECT → SNAPSHOT completo → writeAndConfirm(caso) → aguardar future=true
→ voltar pra server thread → REVALIDAR a instância (ela ainda existe? ainda
  é conflitante?) → REMOVE → LOG → WEBHOOK
```
Colocar na fila (`offer()`) **nunca** deve ser tratado como persistência
confirmada — só o `CompletableFuture` de `writeAndConfirm` resolvendo
`true` é confirmação real.

---

## 11. Threading

Regra seguida (mas nunca formalmente testada sob carga real):

- **Server thread** (ou thread de região, em Folia): toda leitura/escrita
  de `ItemStack`, PDC, `Inventory`, e qualquer manipulação de bloco/
  TileState. `IdentityPlayerInventoryListener` já usa o padrão
  Folia-aware (`IllegalStack.isFoliaServer()` + `player.getScheduler().run()`)
  reaproveitado do `BundleListener`.
- **Async** (a writer thread dedicada do `DatabaseService`): só SQLite.
  Nenhum código do pacote `identity/audit` toca em API do Bukkit.
- **Nunca** um `ItemStack` vivo do Bukkit é passado pra fora da server
  thread. Todos os DTOs que cruzam pra a fila (`ItemSnapshot`,
  `ItemEventSnapshot`, `PresenceUpdateSnapshot`) são `record`s imutáveis
  com só tipos primitivos/`String`, construídos na server thread ANTES de
  `offer()`.

---

## 12. Fail-open

Lugares onde o sistema atual já prefere "não fazer nada" a arriscar dano,
implementados:

- `ItemIntegritySystem`: se `DatabaseService`/`SqliteBackedPresenceStore`
  falharem ao construir → cai pra `InMemoryPresenceStore`, plugin continua
  funcionando.
- `AuditQueue.offer()`: nunca bloqueia, nunca lança exceção pro chamador —
  na pior hipótese descarta e loga.
- `DatabaseService.writerLoop()`: qualquer exceção não tratada dentro do
  loop é capturada, logada como `ERROR`, e o loop **continua** (não morre
  silenciosamente).
- `DatabaseService.writeBatch()`: em caso de `SQLException`, faz
  `rollback()`, loga `ERROR`, retorna `false` — nunca propaga a exceção
  pra cima de um jeito que pudesse derrubar a thread principal.
- `SqliteBackedPresenceStore.rowToRecord()` (hidratação): linha
  corrompida/incompleta → `WARN` + `null` (ignorada), nunca lança exceção
  que interrompa a hidratação do resto.

**Ainda não existe** nenhuma lógica de "dúvida → não remover", porque
**não existe nenhuma remoção** no código atual (ver seção 13/15) — a
filosofia fail-open já está codificada na infraestrutura, mas ainda não
foi exercitada por uma decisão real de conflito.

---

## 13. Duplicate detection — arquitetura planejada (TODO, não implementado)

**Nenhuma linha de `ConflictDetector` existe no código atual.** Isto é
puramente a decisão de design já acordada, documentada aqui pra a próxima
IA implementar:

Regra central: **MESMO ITEM ID + DUAS PRESENÇAS INCOMPATÍVEIS CONFIRMADAS
= DUPLICAÇÃO.**

`CANONICAL_INSTANCE` = a presença que já fazia parte da cadeia legítima
conhecida (o que `PresenceStore.getCanonical()` retorna hoje).
`CONFLICTING_INSTANCE` = uma nova presença da mesma identidade que
apareceu sem uma transferência válida enquanto a canonical continua
existindo — **nunca usar os termos "original"/"falso"** como verdade
matemática (depois de um clone perfeito, não existe como saber qual é
"o de verdade").

Matriz de confiança pretendida (nenhuma parte disso está codificada
ainda):

| Combinação | Confiança | Ação pretendida |
|---|---|---|
| `LIVE_CONFIRMED` + `LIVE_CONFIRMED` | Alta | Remover conflicting |
| `OFFLINE_COMMITTED` + `LIVE_CONFIRMED` | Alta (salvo contexto administrativo) | Remover conflicting |
| `PERSISTED_BLOCK` confirmado recentemente + `LIVE_CONFIRMED` | Alta/média (depende de recência) | Remover conflicting |
| `STALE` + `LIVE_CONFIRMED` | Baixa | Reconciliar, não remover |
| `UNKNOWN` + `LIVE_CONFIRMED` | Baixa | Reconciliar, não remover |
| ambíguo + ambíguo | Nenhuma | Logar/revisar, nunca remover automaticamente |

O próprio `PresenceObservation` retornado por `PresenceStore.observe()`
hoje **já carrega os dados brutos** que essa matriz consumiria
(`canonicalBefore`, `candidate`, `sameOwnerAsCanonical`, `autoCommitted`)
— o `ConflictDetector` futuro deveria consumir isso (ou algo equivalente
vindo do `getLastDivergentObservation`), não reinventar a captura de dados.

---

## 14. Monitor / dry run (TODO, não implementado)

Decisão de estratégia de lançamento (documentada, sem código):
`ConflictDetector` deveria rodar primeiro em modo `MONITOR` — tomando
**exatamente a mesma decisão** que tomaria em produção, mas em vez de
remover, registrar `ACTION = WOULD_REMOVE` com todos os dados do caso
(Item ID, canonical, conflicting, motivo, confidence, Case ID). Depois de
validação (a ordem de grandeza sugerida em conversa foi "~1 dia"), a
remoção real seria ativada por config.

**Importante pra próxima IA**: `MONITOR` e `REMOVE` devem compartilhar o
**mesmo** `ConflictDetector`/lógica de decisão — a diferença deve ser
só a ação final (logar vs remover de verdade), nunca duas implementações
de detecção separadas que poderiam divergir silenciosamente.

---

## 15. Delete with backup (TODO, não implementado)

Fluxo decidido (nenhuma linha de código existe pra isso ainda):
```
DETECT → SNAPSHOT COMPLETO (ItemStack serializado inteiro, não só descrição
textual) → PERSIST CASE (writeAndConfirm) → CONFIRM COMMIT → voltar pra
SERVER THREAD → REVALIDAR a instância (ainda existe? ainda é conflitante?)
→ REMOVE → LOG → WEBHOOK
```
Se qualquer etapa **antes** da remoção falhar → FAIL_OPEN, não remove.
`DatabaseService.writeAndConfirm()` (seção 10) já é a peça de
infraestrutura que esse fluxo usaria pra "PERSIST CASE → CONFIRM COMMIT" —
está pronta e não utilizada.

---

## 16. Shulker lifecycle (TODO — próxima prioridade declarada)

**Nada disto existe no código atual.** `IdentityPlayerInventoryListener`
só observa itens dentro do **inventário de jogador** — nenhum listener de
`BlockPlaceEvent`/`BlockBreakEvent` para shulker existe, nenhuma classe
`ShulkerIdentityService` existe.

Ciclo que precisa ser coberto:
```
ItemStack (com zetra:item_id na PDC)
→ BlockPlaceEvent
→ ShulkerBox vira TileState — a PDC do TileState precisa da MESMA
  identidade, TRANSFERIDA EXPLICITAMENTE (não assumir que o Paper faz isso
  sozinho)
→ chunk unload / restart do servidor
→ chunk load
→ BlockBreakEvent
→ ItemStack resultante precisa ter o MESMO zetra:item_id de volta
→ (opcionalmente vira ItemEntity no chão, depois pickup)
```
Foi testado manualmente pelo usuário: colocar e quebrar uma shulker no
criativo gera 2 shulkers (bug conhecido do Minecraft/servidor, não do
nosso código). Como o lifecycle não existe, **hoje as duas cópias recebem
Item IDs diferentes** (cada uma vira "item sem identidade" depois da
quebra e recebe `LEGACY_IMPORT` independente) — ou seja, **o sistema
atual não detecta esse caso**, e não vai detectar até essa etapa ser
implementada.

---

## 17. Fingerprint (TODO, não implementado)

Nenhum código existe. Decisão de design documentada: fingerprint é
conceito **separado** de identidade — Item ID responde "é o mesmo
objeto?", fingerprint responde "esses objetos têm estado extremamente
parecido/suspeito?". Pra shulker, o fingerprint deveria incluir os 27
slots em ordem + metadados normalizados do conteúdo. **Fingerprint sozinho
nunca deve autorizar remoção/punição** — só sinaliza suspeita pra revisão.
A coluna `fingerprint BLOB` já existe em `items` e `item_events`, sempre
`NULL` hoje.

---

## 18. Webhook (TODO, não implementado)

Nenhum código existe. Decisões de design documentadas em conversa (não
codificadas): Discord webhook configurável, payload incluindo Case ID,
Item ID, material, fingerprint, registro original, canonical/conflicting
holder+location, jogador relacionado, jogadores próximos como **contexto,
nunca prova automática**, motivo, confiança, ação. Pra shulker, resumo
legível dos 27 slots (não serialização/base64 no Discord). Async, com
rate limiting/batching (agrupar em caso de dupe em massa), retry limitado
(não infinito). Falha de webhook nunca deve interferir numa decisão já
persistida no banco.

---

## 19. Logging

Níveis atualmente em uso no código (revisados nesta sessão de
finalização):

- **INFO**: `ItemIntegritySystem` startup (habilitado/etapas ativas),
  `DatabaseService` conexão SQLite estabelecida, contagem de presenças
  hidratadas (`SqliteBackedPresenceStore.hydrate`).
- **WARN**: `ORPHAN_PRESENCE_RECORD` (presença hidratada sem `items`
  correspondente), linha de `presence` corrompida/incompleta na
  hidratação, `AuditQueue` descartando entradas por estar cheia
  (throttled a 1 log a cada 1000 descartes), observação divergente
  (`IdentityPlayerInventoryListener`, rebaixado de INFO pra WARN nesta
  sessão de finalização — é um estado ambíguo real, não spam de rotina).
- **ERROR**: falha ao gravar lote no SQLite (`writeBatch`), erro não
  tratado no writer thread.
- **DEBUG**: **nada usa DEBUG hoje.** Não há log por item/slot em uso
  normal — o design evita isso por construção (ver seção 8), não por
  filtro de nível de log.

Nenhum `log4j2.xml` próprio foi criado pra esse subsistema — os loggers
usam `LogManager.getLogger("IllegalStack/ItemIdentity")` e
`LogManager.getLogger("IllegalStack/ItemIntegrity")`, herdando o nível
efetivo padrão da instalação (tipicamente INFO no console do
Paper/Spigot), então `DEBUG` já ficaria invisível por padrão mesmo se
alguém adicionar chamadas `.debug()` no futuro.

---

## 20. Status futuros (TODO, coluna já existe)

`items.status` é `TEXT` livre, hoje **sempre `'ACTIVE'`** (hardcoded no
`INSERT ... ON CONFLICT DO NOTHING` de `DatabaseService.writeBatch`).
Nenhuma lógica de transição de status existe. Valores futuros pretendidos
(documentados, não implementados): `DESTROYED`, `CONSUMED`,
`RESURRECTED_ITEM` (Item ID marcado como destruído/consumido reaparecendo
fisicamente — sinal de altíssima severidade). Regra decidida: **um item
simplesmente deixar de ser observado NUNCA deve virar `DESTROYED`
automaticamente** — isso exige evidência forte (ex: um listener futuro de
"consumo confirmado", como comer um totem). Desaparecimento sem essa
evidência deve virar `MISSING`/`UNKNOWN`/reconciliação, nunca
`DESTROYED`.

---

## 21. Build

- **Java 21** — toolchain e `compileJava.options.release` ambos setados
  pra 21 em `build.gradle.kts`.
- **Por que o Java 8 original foi abandonado nesta fork**: o
  `build.gradle.kts` original do IllegalStack tinha
  `options.release.set(8)` (pra compatibilidade com servidores muito
  antigos, 1.8+). Isso **quebra** a sintaxe usada em boa parte do código
  novo desta fork (`record`, `sealed interface`, pattern matching em
  `instanceof`, record patterns) — confirmado empiricamente compilando
  com `javac --release 8` real (deu erro de compilação de verdade, não
  suposição). Como o Leaf 1.21.11 (o server alvo desta fork específica) já
  exige Java 21 pra sequer rodar, não fazia sentido manter compatibilidade
  com Java 8. `release` foi subido primeiro pra 17, depois pra 21 (porque
  `DatabaseService` usa record patterns em `instanceof`, feature exclusiva
  de Java 21+).
- **Shadow Plugin**: `id("com.gradleup.shadow") version "8.3.5"` (o fork
  mantido do antigo `com.github.johnrengelman.shadow`, que está sem
  manutenção desde meados de 2023 — **não usar o plugin antigo**).
- **sqlite-jdbc**: `implementation("org.xerial:sqlite-jdbc:3.46.1.3")`.
- **Relocation configurada**:
  ```kotlin
  tasks.shadowJar {
      archiveClassifier.set("")
      relocate("org.sqlite", "main.java.me.dniym.libs.sqlite")
      mergeServiceFiles()
      minimize {
          exclude(dependency("org.xerial:sqlite-jdbc:.*"))
      }
  }
  tasks.build {
      dependsOn(tasks.shadowJar)
  }
  ```
  `mergeServiceFiles()` existe especificamente pra não perder o
  `META-INF/services/java.sql.Driver` do sqlite-jdbc (necessário pro
  `DriverManager.getConnection("jdbc:sqlite:...")` funcionar via
  auto-registro do driver).
- **Comando de build**: `./gradlew clean build`.
- **Qual JAR usar**: `build/libs/IllegalStack.jar` (o `shadowJar` está
  configurado com `archiveClassifier.set("")`, então ele **substitui** o
  jar padrão nesse mesmo caminho — não existe um jar "-all" separado pra
  procurar).
- **Como verificar se o sqlite foi empacotado**:
  ```
  unzip -l build/libs/IllegalStack.jar | grep -i sqlite
  ```
  Deve aparecer classes sob `main/java/me/dniym/libs/sqlite/...` (caminho
  relocado). Se não aparecer nada, o Shadow não empacotou a dependência
  corretamente.

**DESTAQUE OBRIGATÓRIO**: **o build Gradle real NUNCA foi executado no
ambiente do Claude** — nem o próprio Gradle Wrapper consegue baixar a
distribuição (`services.gradle.org` retorna 403 nesse ambiente sandboxed;
nenhum repositório Maven é alcançável). A validação feita foi
`javac --release 21` real, contra **stubs escritos à mão** da API do
Bukkit/Paper (cobrindo só a superfície usada pelo pacote `identity/`) —
isso pega erro de sintaxe/tipo interno, mas **não confirma a API real do
Bukkit/Paper, nem que o Shadow relocate funciona, nem que o sqlite-jdbc
entra no jar**. A próxima IA (ou o usuário) **precisa** rodar o build real
antes de considerar isso pronto pra produção. Isso nunca foi feito até o
momento deste documento.

---

## 22. Alterações no IllegalStack (fora do pacote identity/)

Resumo do que foi alterado no projeto original antes/durante o
desenvolvimento do Item Integrity, na mesma fork:

- **`BundleCheck.java` / `BundleListener.java`** (pacotes `checks/` e
  `listeners/`, novos) — módulo de bloqueio de Bundle (`Material.BUNDLE`
  e as 16 variações coloridas via `Tag.ITEMS_BUNDLES`), com scanner
  periódico e `player.updateInventory()` pra evitar dessincronia visual
  (especialmente Bedrock/Floodgate). Testado manualmente pelo usuário e
  funcionando.
- **`BadPotionCheck.java`** (arquivo original, modificado) — corrigido bug
  upstream conhecido (`PotionType.UNCRAFTABLE` removido a partir do
  1.20.6, e `getBasePotionData()` podendo retornar `null` a partir da API
  1.21.1+) trocando pra `hasBasePotionType()`/`getBasePotionType()`, com
  fallback pra servidores antigos via reflection.
- **`Protections.java`** (enum original, modificado) — novas entradas
  `BlockBundles` (id 200), `DropBundleItemsOnGround` (id 201). **A
  entrada `UniqueItemGuard` (id 202) que existiu brevemente foi removida**
  — ver próximo item.
- **`Msg.java`** (enum original, modificado) — novas mensagens
  `BundleContentsExtracted`, `BundleOverflowDropped`,
  `BundleOverflowDiscarded`. A mensagem `DuplicateUniqueItemDetected` que
  existiu brevemente foi removida junto com o `UniqueItemGuard`.
- **`IllegalStack.java`** (original, modificado) — registro condicional do
  `BundleListener` no `onEnable()`; registro **incondicional** do
  `ItemIntegritySystem` (não passa por `Protections`, usa o
  `item-integrity.yml` próprio — ver seção 3); campo `itemIntegritySystem`
  guardado na classe; chamada de `itemIntegritySystem.shutdown()`
  adicionada ao `onDisable()` existente.
- **`build.gradle.kts`** (original, modificado) — ver seção 21 completa.

**Confirmado nesta sessão**: `UniqueItemGuard` — uma implementação rápida
de UUID-em-shulker feita **antes** da especificação completa do Item
Integrity ser definida pelo usuário — foi explicitamente descartada pelo
usuário e **removida do código-fonte** (`UniqueItemCheck.java`,
`UniqueItemLedger.java`, `UniqueItemListener.java` deletados; entradas em
`Protections`/`Msg`/`IllegalStack.onEnable()` removidas). Uma busca
`grep -rn "UniqueItem"` no diretório `src/` não deve retornar nada — se
retornar, é uma regressão.

---

## 23. Limitações conhecidas

- **Build Gradle real nunca foi executado** (ver seção 21 — bloqueante).
- Lifecycle completo de shulker **não existe** (seção 16).
- `ConflictDetector` **não existe** — o sistema hoje só observa e loga,
  não detecta nem age sobre duplicatas de verdade.
- Fingerprint **não existe**.
- Delete/quarantine **não existe**.
- Webhook **não existe**.
- Itens empilháveis (stackables) estão **fora de escopo** por decisão
  explícita, não implementados.
- Integração com plugins que removem/limpam itens de outros jogadores
  (ex: um plugin de "limpar inventário no join") **não existe** — o
  usuário encontrou esse gap num teste real: um item rastreado foi
  apagado por outro plugin, e o Item Integrity não tem como saber disso
  (ficaria, na melhor das hipóteses, com presença `MISSING` quando essa
  lógica existir — hoje nem isso, porque a transição pra `MISSING` não
  está implementada).
- Cobertura de containers (baú, barril, ender chest) **não existe** — só
  inventário de jogador é observado hoje.
- Teste de sobrevivência a restart do servidor **não foi validado com
  sucesso** — a primeira tentativa do usuário foi invalidada por outro
  plugin limpando o inventário antes da comparação.
- Teste de observação divergente com dois jogadores reais **não foi
  executado** — só validado por análise de código + inferência lógica.
- Sem versionamento/migração de schema SQLite — mudanças de schema
  futuras precisam de estratégia própria (`CREATE TABLE IF NOT EXISTS`
  não migra colunas existentes).

---

## 24. Riscos para auditoria — avaliação técnica

Pontos que considero que merecem revisão cuidadosa antes de continuar,
em ordem aproximada de importância:

1. **Comparação de holder em `HolderRef.sameOwner()` / `ContainerHolder`
   equality** — a comparação de containers usa `world` (String) +
   coordenadas inteiras exatas. Isso significa que **um mesmo bloco físico
   observado a partir de dois nomes de mundo diferentes (ex: um mesmo
   mundo carregado sob alias, ou um mundo recriado com nome igual mas
   instância diferente) seria tratado como containers diferentes**. Não é
   um bug per se, mas é uma premissa que vale confirmar contra o setup
   real do ZetraMC (mundos com portais/instâncias, se existir).

2. **Race condition entre hidratação e o primeiro scan periódico** — a
   hidratação roda no construtor de `SqliteBackedPresenceStore`, chamado
   durante `onEnable()`. Se o primeiro tick do scanner periódico (ou um
   `PlayerJoinEvent` de um jogador que reconecta rápido após restart)
   rodar **antes** da hidratação terminar, ele veria o cache ainda vazio e
   trataria tudo como identidade "nova" (sem canonical prévia) —
   silenciosamente perdendo a continuidade da presença anterior ao invés
   de detectar conflito ou reconciliar. Não constatei se isso é
   fisicamente possível dado que `onEnable()` é síncrono e a hidratação
   também é síncrona (então deveria terminar antes de qualquer evento
   Bukkit poder disparar) — mas vale confirmar isso explicitamente, é uma
   suposição implícita não testada.

3. **Write volume real pós-correção não foi medido** (ver seção 8) — a
   correção de holder lógico + separação presence/item_events foi feita
   na mesma leva, nunca testada em conjunto contra dados reais de
   produção.

4. **`AuditQueue` descarta o item mais ANTIGO quando cheia** (`queue.poll()`
   antes de `offer()` de novo) — isso significa que, sob sobrecarga
   sustentada, o sistema perde preferencialmente o histórico mais antigo
   da fila, mantendo o mais recente. Pode ser o comportamento certo ou
   errado dependendo do que se prioriza (recência vs completude
   cronológica) — vale uma decisão consciente, não só aceitar o que está
   implementado.

5. **`writeAndConfirm()` nunca foi exercitado** — a lógica existe e
   compila, mas como nada chama esse método ainda, não há nenhuma
   evidência de que o caminho de prioridade (`confirmedWriteQueue` sendo
   drenada antes do lote de rotina, dentro do mesmo `writerLoop`) funciona
   como esperado sob concorrência real (ex: uma escrita confirmada
   chegando no meio de um lote grande de rotina já em andamento).

6. **Shutdown flush** (`DatabaseService.shutdown()`, `join(5000)`) — um
   timeout fixo de 5 segundos pode não ser suficiente se a `AuditQueue`
   estiver com muito volume acumulado no momento do `/stop`. Não há
   nenhum log/aviso se o `join` estourar o timeout e a thread for
   abandonada ainda rodando.

7. **Memory growth em `SqliteBackedPresenceStore`** — os `Map`s
   `canonical` e `lastDivergent` (`ConcurrentHashMap`) crescem
   indefinidamente conforme novas identidades são observadas, sem nenhum
   mecanismo de eviction/limite. Num servidor de longa duração com muitos
   itens únicos, isso é crescimento de memória sem teto. Pode ser
   aceitável dependendo da escala esperada, mas não foi uma decisão
   consciente documentada em conversa — vale revisar.

8. **Trecho redundante `condição ? X : X`** — em `TrackabilityPolicy.markExempt`
   (`stack.hasItemMeta() ? stack.getItemMeta() : stack.getItemMeta()`)
   existe um resíduo de edição onde os dois lados do ternário são
   idênticos. Funciona na prática (Bukkit sempre retorna um `ItemMeta`
   não-nulo), mas é código morto/confuso que vale limpar, e é o tipo de
   coisa que sugere valer a pena procurar outros resíduos parecidos no
   restante do arquivo.

---

## 25. Minha recomendação para a próxima etapa

**O que eu faria primeiro**: rodar o `./gradlew clean build` real (bloqueante,
nada mais importa até isso passar), depois um teste de carga simples (uns
10-15 minutos com 2-3 jogadores segurando itens rastreados, mexendo neles
ativamente) medindo o volume real de `item_events` gerado, pra validar que
a correção da seção 6/8 realmente reduziu o volume como esperado — isso é
rápido de fazer e destrava confiança pra seguir.

**O que eu NÃO faria ainda**: não começaria o `ConflictDetector` (etapa 7)
antes do `ShulkerIdentityService` (etapa 5) — shulker foi declarado
prioridade pelo usuário repetidas vezes, e sem o lifecycle completo,
qualquer teste de duplicata real com shulker (o caso de uso mais citado
na conversa original) simplesmente não é detectável, tornando difícil
validar o `ConflictDetector` de forma realista antes disso existir.

**Classes que eu auditaria antes de continuar**: `SqliteBackedPresenceStore`
(é o coração da lógica de decisão, e teve duas rodadas de correção nesta
sessão — vale ler com atenção extra, não assumir que a segunda correção
é definitivamente a última necessária) e `DatabaseService.writerLoop()`
(a interação entre `confirmedWriteQueue` e o lote de rotina nunca foi
testada sob carga real, ver risco #5).

**Testes que eu faria, em ordem**:
1. Build real + verificação do jar (seção 21).
2. Restart do servidor **de verdade** com um item de teste guardado num
   baú (não no inventário, pra evitar o problema do plugin que limpa
   inventário que o usuário encontrou) — confirma hidratação completa.
3. Dois jogadores reais, cópias idênticas do mesmo item (pick-block em
   criativo), simultaneamente online — confirma que a canonical não é
   sobrescrita e que a observação divergente é registrada corretamente.
4. Medição de volume de `item_events` sob uso ativo normal (não só
   parado) por um período mais longo (30min+).

**Decisões arquiteturais que eu manteria**: a separação `PresenceStore`
(interface) com duas implementações trocáveis; o modelo canonical vs
observação nunca sobrescrevendo automaticamente; o `writeAndConfirm()`
como caminho separado do fire-and-forget; a filosofia fail-open em toda
falha de infraestrutura.

**Se recomendo refatorar algo antes da etapa 5**: não recomendo uma
refatoração grande. Recomendo só: (a) resolver o risco #2 (confirmar/
garantir que a hidratação sempre termina antes de qualquer evento Bukkit
poder disparar, com um teste ou uma trava explícita se necessário), e
(b) limpar o resíduo mencionado no risco #8 — é cosmético, mas indica
pressa em algum ponto da edição e vale checar se não há mais casos assim
no restante do código.

**Pontos que considero sólidos**: o modelo de dados (`HolderRef` selado,
`ItemIdentity` imutável, DTOs de auditoria imutáveis cruzando threads), a
separação clara entre decisão em tempo real (memória) e persistência
(SQLite assíncrono), e a disciplina de nunca ter implementado remoção
automática antes da infraestrutura de confirmação existir.

**Pontos que considero frágeis**: a ausência total de testes automatizados
(tudo validado manualmente até agora, incluindo por mim via stubs escritos
à mão) e a completa falta de cobertura de containers/blocos — o sistema
hoje é essencialmente "identidade de item no inventário do jogador", e
generalizar isso pra containers/shulkers (etapa 5) é uma extensão real de
escopo, não um ajuste pequeno.

---

## 26. Invariantes que não devem ser quebradas

- Registro de um Item ID no banco (tabela `items`) **não** significa
  duplicação — é só prova de que a identidade existe/é conhecida.
- Item ID é **imutável** depois de atribuído.
- Uma nova observação **nunca** substitui a canonical automaticamente
  quando o dono lógico é diferente — só quando é o mesmo dono
  (`HolderRef.sameOwner`) ou quando não havia canonical antes.
- Handoff (mudança legítima de dono) precisa ser **distinguido**
  explicitamente de conflito — nunca inferido automaticamente por padrão.
- `STALE`/`UNKNOWN` **nunca** autorizam auto-delete.
- Duas presenças confirmadas incompatíveis (mesmo Item ID, donos lógicos
  diferentes, ambas verificáveis ao mesmo tempo) são o sinal principal e
  mais forte de conflito.
- Persistência precisa ser **confirmada** (commit real, via
  `writeAndConfirm`) antes de qualquer ação destrutiva futura — nunca
  tratar "coloquei na fila" como persistido.
- Depois do commit confirmado, **revalidar a instância na server thread**
  antes de remover (ela pode ter deixado de existir/mudado entre a
  detecção e a confirmação).
- **Fail-open** sempre que houver dúvida ou falha de infraestrutura.
- Item marcado `zetra:integrity_exempt` **nunca** entra no ledger, em
  nenhuma circunstância.
- Movimento trivial dentro do mesmo dono lógico (só slot mudando) **não**
  deve poluir `item_events`.
- A identidade de uma shulker (`ItemStack` ↔ `TileState`) precisa
  sobreviver ao ciclo completo colocar/quebrar — quando essa etapa for
  implementada, a transferência de PDC deve ser **explícita**, nunca
  assumida como automática pelo Paper.
- `MONITOR` e a futura ação real (`REMOVE`) devem compartilhar o **mesmo**
  `ConflictDetector`/lógica de decisão — nunca duas implementações
  paralelas.

---

## 27. Handoff para a próxima IA

**Estado atual**: etapas 1-4 implementadas e revisadas (com uma correção
posterior de comparação de holder). Etapa 5 (shulker lifecycle) é o
próximo trabalho planejado e **não foi iniciado**. O build Gradle real
**nunca foi executado** neste ambiente — isso é o bloqueio mais imediato
antes de qualquer coisa ir pra produção ou antes de continuar
desenvolvendo em cima disso.

**O que ler primeiro**: este documento inteiro, depois, nesta ordem:
`ItemIdentity.java` → `IdentityService.java` → `TrackabilityPolicy.java`
→ `presence/HolderRef.java` → `presence/PresenceStore.java` →
`presence/SqliteBackedPresenceStore.java` → `audit/DatabaseService.java`
→ `listeners/IdentityPlayerInventoryListener.java` →
`ItemIntegritySystem.java`. Essa ordem segue o fluxo de dados real: da
identidade do item, pra política de rastreabilidade, pro modelo de
presença, pra persistência, pro único listener que costura tudo.

**O que auditar antes de editar**: rode o build real primeiro (seção 21).
Depois, compare o código de `SqliteBackedPresenceStore.isMeaningfulChange()`
e `HolderRef.sameOwner()` contra a seção 6/8 deste documento — essa lógica
foi corrigida duas vezes nesta sessão (primeiro errado comparando tipo de
holder, depois corrigido pra comparar dono lógico), então vale checar com
atenção extra que não ficou nenhuma inconsistência entre as duas.

**Próximo passo recomendado**: seção 25 acima, na ordem descrita.

**Arquivos críticos**: `SqliteBackedPresenceStore.java` (decisão central),
`DatabaseService.java` (persistência + threading), `HolderRef.java`
(modelo de identidade de holder), `build.gradle.kts` (build real
pendente de validação).

**Funcionalidades ainda ausentes**: shulker lifecycle, fingerprint,
ConflictDetector, delete/quarantine, webhook, cobertura de containers,
qualquer coisa envolvendo itens empilháveis (fora de escopo), testes
automatizados.

**Testes necessários**: ver lista ordenada na seção 25.

### Instrução explícita para a próxima IA

**NÃO comece alterando código.** Primeiro:
1. Leia este documento inteiro.
2. Inspecione o código real (não confie cegamente neste documento — ele
   foi escrito com cuidado pra refletir o estado real, mas pode haver
   divergências não percebidas).
3. Compare implementação vs este documento.
4. Liste divergências/bugs/riscos que encontrar.
5. Proponha um plano de correção/continuação.
6. Só então altere código, com aprovação explícita do usuário.
