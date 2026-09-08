# Alterações feitas sobre o IllegalStack oficial (dniym/IllegalStack, GPLv3)

Base: HEAD do repositório oficial na data desta modificação (versão 2.9.12a,
mesma do jar que você já usa no ZetraMC).

- `src/main/java/main/java/me/dniym/checks/UniqueItemLedger.java` (novo)
  Registro central de "onde cada UUID de item foi visto por último". Antes
  de confirmar uma duplicata, sempre reconfirma AO VIVO se o registro
  anterior ainda é verdade — evita falso positivo quando o item só se moveu
  de lugar.

- `src/main/java/main/java/me/dniym/checks/UniqueItemCheck.java` (novo)
  Marca itens rastreados (shulker boxes, todas as cores, via
  `Tag.ITEMS_SHULKER_BOXES`) com um UUID via `PersistentDataContainer` na
  primeira vez que são vistos. Se o mesmo UUID já existir marcado, delega
  pro `UniqueItemLedger` a checagem de duplicata.

- `src/main/java/main/java/me/dniym/listeners/UniqueItemListener.java` (novo)
  Observa itens rastreados em: inventário de jogador (join + varredura
  periódica, mesmo `ItemScanTimer`), qualquer `Container` do mundo aberto
  por um jogador (baú, barril, shulker colocado, ender chest), e
  colocação/quebra do bloco. Ao confirmar uma duplicata, remove a cópia
  recém-detectada e loga pra staff com local anterior e novo.

  **Cobertura conhecida**: não escaneia containers do mundo que nunca foram
  abertos por ninguém desde que o servidor ligou (custo alto demais pra
  fazer isso periodicamente em todo o mundo carregado) — a checagem dispara
  quando alguém interage com uma das cópias.

- `src/main/java/main/java/me/dniym/enums/Protections.java`
  Nova entrada `UniqueItemGuard` (id 202, default true), config em
  `Exploits.Other.UniqueItemGuard`.

- `src/main/java/main/java/me/dniym/enums/Msg.java`
  Nova mensagem `DuplicateUniqueItemDetected`.

- `src/main/java/main/java/me/dniym/IllegalStack.java`
  Registro do `UniqueItemListener` no `onEnable()`, condicionado a
  `UniqueItemGuard.isEnabled()`.

## Revisão final de fechamento da sessão (item-integrity)

- `identity/TrackabilityPolicy.java` — nova checagem `zetra:integrity_exempt`
  (PDC), verificada ANTES de qualquer outra regra. Itens técnicos/
  temporários de plugin (relógio de menu, seletor de servidor, gadget de
  lobby) marcados com essa flag nunca recebem identidade, nunca entram no
  PresenceStore, nunca geram LEGACY_IMPORT nem alerta. `markExempt()`
  incluído como ponto de extensão (não chamado por nada ainda - pronto pra
  uso futuro por outro sistema/plugin).
- `identity/audit/PresenceUpdateSnapshot.java` (novo) e `AuditTask.UpdatePresence`
  (nova variante) — caminho leve que só atualiza a tabela `presence`
  (estado atual), sem gerar linha em `item_events`.
- `identity/presence/SqliteBackedPresenceStore.java` — reescrito pra
  decidir, a cada observação auto-commitada, se ela é "meaningful" o
  bastante pra virar histórico permanente (`isMeaningfulChange`),
  comparando pelo **holder LÓGICO** (`HolderRef#sameOwner` - player UUID,
  entity UUID, ou world+coordenadas do container/bloco), não pelo tipo de
  holder: PLAYER João -> PLAYER Pedro é relevante mesmo sendo PLAYER-PLAYER,
  CHEST A -> CHEST B é relevante mesmo sendo CHEST-CHEST. Só o slot mudando
  dentro do MESMO dono lógico -> só atualiza `presence`, nunca histórico.
  Mudança de `PresenceState` também sempre vira histórico. Sem heartbeat
  periódico gerando evento - `presence.last_confirmed_at` já fica em dia a
  cada scan via o caminho leve, então não precisava de um mecanismo
  separado pra isso (removido para não gerar item_event sem necessidade).
- `identity/listeners/IdentityPlayerInventoryListener.java` — log de
  observação divergente rebaixado de INFO pra WARN (é um estado ambíguo
  real, não spam de rotina).
- Níveis de log revisados em todo o pacote `identity/`: INFO só pra
  startup/hidratação/shutdown; WARN pra `ORPHAN_PRESENCE_RECORD`, linha de
  presence corrompida/incompleta na hidratação, e fila da AuditQueue perto
  do limite; ERROR pra falha de escrita no SQLite. Nada em DEBUG ainda
  (não há necessidade nesta etapa) - nenhum log por item/slot em uso
  normal além do que já foi descrito.
- `items.status` continua só `ACTIVE` nesta versão (hardcoded no INSERT) -
  a coluna já existe e é TEXT livre, então `DESTROYED`/`CONSUMED`/
  `RESURRECTED_ITEM` cabem sem migração de schema quando o ConflictDetector
  (etapa 7) existir. Nada detecta isso ainda - documentado como decisão
  consciente, não implementado por instrução explícita de não expandir
  escopo agora.
- Confirmado nesta revisão: nenhum vestígio de `UniqueItemGuard`, Java 21
  em todo `build.gradle.kts` (toolchain + release), Shadow Plugin +
  sqlite-jdbc configurados, `item-integrity.yml` com todas as chaves
  usadas tendo default.

## Item Integrity System — etapas 1–3 (identidade de itens, novo)

Pacote novo `main.java.me.dniym.identity/`, config própria e separada em
`plugins/IllegalStack/item-integrity.yml` (não usa o sistema de
`Protections`/`config.yml` principal, por ser uma política estruturada
demais pro modelo boolean-flat deles).

- `identity/ItemOrigin.java`, `identity/ItemIdentity.java` — value objects.
  Id no formato `ZI-YYYYMMDDTHHmm-<world>-x<X>-y<Y>-z<Z>-<24 hex chars>`,
  gerado com `SecureRandom` (96 bits). Campos estruturados
  (`registered_at`, `origin`, `world`, `x/y/z`) gravados em PDC keys
  próprias (`zetra:*`) — nunca dependem de parsear a string do id de volta.
- `identity/TrackabilityPolicy.java` — regra `getMaxStackSize() == 1`, com
  pontos de extensão pra whitelist/blacklist futura. **Não rastreia itens
  empilháveis** (decisão explícita de escopo desta fase).
- `identity/IdentityService.java` — mecânico: gera/lê/escreve o id e os
  campos PDC. Não decide quando atribuir.
- `identity/MigrationService.java` — atribui `LEGACY_IMPORT` a item
  rastreável sem id, sem gerar alerta.
- `identity/presence/` — `HolderType`, `HolderRef` (sealed interface com
  `PlayerHolder`/`ItemEntityHolder`/`ContainerHolder`, cada um validado no
  construtor pra impedir estado estruturalmente inválido — ex: `PLAYER`
  sem `playerId`), `PresenceState`
  (`LIVE_CONFIRMED`/`OFFLINE_COMMITTED`/`PERSISTED_BLOCK`/
  `PERSISTED_CONTAINER`/`STALE`/`UNKNOWN`/`MISSING`), `PresenceRecord`,
  `PresenceObservation`, `PresenceStore` (interface) e
  `InMemoryPresenceStore` (implementação temporária desta fase — **não
  sobrevive a restart**, será trocada por uma implementação com SQLite na
  etapa 4 mantendo a mesma interface).
  - **Canonical nunca é sobrescrita automaticamente por um holder
    diferente.** `observe()` só auto-commita quando não há canonical ainda
    ou quando o novo holder é o MESMO dono da canonical (`HolderRef#sameOwner`,
    que ignora diferença de slot). Um holder diferente vira só uma
    observação registrada (`getLastDivergentObservation`), sem apagar a
    canonical — fica pronta pro `ConflictDetector` (etapa 7) decidir
    handoff vs conflito vs reconciliação. `commitHandoff()` existe pronto
    pra esse uso futuro, mas nada nesta etapa chama automaticamente pra
    holders diferentes.
- `identity/config/ItemIntegrityConfig.java` — carrega/cria
  `item-integrity.yml`, com as seções futuras (`sqlite`, `webhook`,
  `conflict-detector`, etc) já esqueletadas e comentadas no arquivo.
- `identity/listeners/IdentityPlayerInventoryListener.java` — observa
  inventário de jogador em join (+ migração), varredura periódica (mesmo
  `ItemScanTimer`) e quit (síncrono, sem agendar — mesma decisão do
  `BundleGuard`). **Não remove, não quarentena, não modifica item nenhum**
  além de gravar a identidade em si.
- `identity/ItemIntegritySystem.java` — composição raiz, instanciada uma
  vez no `onEnable()` do `IllegalStack.java`.

**Sem remoção automática nesta fase** (decisão explícita) — sem SQLite/
backup persistente ainda, não haveria como garantir a ordem
DETECT→SNAPSHOT→PERSIST→CONFIRM→DELETE que vocês definiram.


- `src/main/java/main/java/me/dniym/checks/BundleCheck.java`
  Lógica pura de extração segura de Bundle (sem conhecimento de eventos).
- `src/main/java/main/java/me/dniym/listeners/BundleListener.java`
  Listener que dispara a varredura em join, fechar inventário, craft,
  pickup, troca de item na mão, troca de mão, morte e logout.

## Arquivos modificados
- `src/main/java/main/java/me/dniym/enums/Protections.java`
  Duas novas entradas no enum, seguindo exatamente o padrão existente:
  - `BlockBundles` (id 200, default true) — liga/desliga a proteção.
  - `DropBundleItemsOnGround` (id 201, default true) — controla se o
    excedente que não coube no inventário é dropado no chão.
  Config gerado automaticamente pelo `writeConfig()` deles, nos caminhos:
  `Exploits.Other.BlockBundles` e `Exploits.Other.DropBundleItemsOnGround`.

- `src/main/java/main/java/me/dniym/listeners/BundleListener.java`
  Adicionado um scanner periódico (reaproveitando o intervalo já configurado
  em `ItemScanTimer`, o mesmo usado por `RemoveOverstackedItems`), porque os
  eventos de inventário sozinhos não cobrem todos os jeitos de mexer num
  bundle: segurar botão direito no bundle direto na hotbar (sem abrir
  nenhuma tela de inventário) esvazia o conteúdo aos poucos sem disparar
  `InventoryClickEvent`/`InventoryCloseEvent` nenhum. Sem o scanner, esse
  caminho passava batido pela proteção inteira.

- `src/main/java/main/java/me/dniym/enums/Msg.java`
  Adicionadas 3 mensagens novas seguindo o padrão existente (aparecem
  automaticamente no `messages.yml` gerado, customizáveis igual as outras):
  `BundleContentsExtracted`, `BundleOverflowDropped`, `BundleOverflowDiscarded`.
  `BundleCheck` agora loga por elas (`fListener.getLog().append(...)`) em vez
  de string fixa, então o evento `IllegalStackLogEvent` dispara corretamente
  e admins podem customizar a mensagem como qualquer outra do plugin.

- `src/main/java/main/java/me/dniym/listeners/BundleListener.java`
  **Fix crítico**: adicionado `player.updateInventory()` depois de qualquer
  processamento de bundle. Mutações de inventário feitas fora do fluxo
  normal de clique (join, quit, scanner periódico) não reenviam o pacote de
  inventário pro cliente sozinhas — o servidor já estava correto (confirmado
  pelos logs de teste), mas a tela do jogador continuava mostrando o bundle
  cheio até ele mexer em algo que forçasse um refresh natural. Isso é ainda
  mais sensível em jogadores Bedrock via Geyser/Floodgate.

- `src/main/java/main/java/me/dniym/checks/BundleCheck.java`
  Corrigido pra reconhecer as 16 variações coloridas de Bundle
  (`WHITE_BUNDLE`, `RED_BUNDLE`, etc), não só o `Material.BUNDLE` base. Antes
  disso, qualquer jogador que tingisse o bundle passava batido pela proteção
  inteira. Agora usa a tag oficial `Tag.ITEMS_BUNDLES`, que cobre todas as
  cores automaticamente (e qualquer variação nova que a Mojang adicionar no
  futuro), com fallback por sufixo de nome (`_BUNDLE`) só em servidores
  antigos demais para ter essa tag.

- `src/main/java/main/java/me/dniym/checks/BadPotionCheck.java`
  Corrige um bug real do upstream ([issue #199](https://github.com/dniym/IllegalStack/issues/199),
  ainda aberto): `PotionType.UNCRAFTABLE` foi removido a partir do Minecraft
  1.20.6, e o código original acessava esse campo diretamente, causando
  `NoSuchFieldError` toda vez que um jogador arremessa uma splash potion —
  derrubando a proteção `PreventInvalidPotions` inteira com uma exceção no
  console.

  Reescrito para usar a API atual (não-depreciada) `PotionMeta.hasBasePotionType()`
  / `getBasePotionType()`, em vez do caminho antigo `getBasePotionData()` /
  `PotionType.UNCRAFTABLE`, que na API 1.21.1+ (você está na 1.21.4) foi
  marcado `@Deprecated(forRemoval)` e passou a poder retornar `null` em vez
  de um `PotionData` com tipo `UNCRAFTABLE` — ou seja, o patch anterior
  (só evitar o `NoSuchFieldError`) ainda quebraria com `NullPointerException`
  no seu servidor especificamente. A detecção de qual API usar é feita uma
  única vez, via reflection (`getMethod` + catch de `NoSuchMethodException`),
  com fallback pro caminho antigo em servidores anteriores à 1.21 que não
  têm `hasBasePotionType()`.

## Bugs conhecidos do upstream que NÃO foram corrigidos aqui (avalie se valem a pena)
- **Issue #204** — `FishAttempt.isBlackListedSpot` quebra com
  `IllegalArgumentException: World unloaded` em servidores que criam/deletam
  mundos dinamicamente (ex: mundos de evento temporários). Só relevante se
  o ZetraMC fizer isso. Me avise se for o caso que eu aplico o patch.
- **Issue #201** — erro de acesso assíncrono a entidades com ProtocolLib
  instalado. Reportado sem stack trace legível publicamente, só um
  screenshot que não consigo acessar — não há informação suficiente pra
  aplicar uma correção seguramente. Fica registrado como conhecido, sem fix.

- `src/main/java/main/java/me/dniym/IllegalStack.java`
  No `onEnable()`, logo após o registro do `ProtectionListener`, adicionado:
  ```java
  if (Protections.BlockBundles.isEnabled()) {
      new main.java.me.dniym.listeners.BundleListener(this);
      LOGGER.info("BundleGuard: proteção contra Bundle habilitada (BlockBundles).");
  }
  ```

## Como compilar
```
./gradlew clean build
```
O jar final fica em `build/libs/IllegalStack.jar`. O build precisa baixar
dependências de repositórios Maven (PaperMC, ProtocolLib, Magic, CodeMC,
JitPack) — normal para esse projeto, nada foi adicionado aqui que exija
repositório novo.

## O que NÃO foi mudado
Nenhuma outra classe, check ou listener original foi tocado. A integração
inteira é aditiva: se `BlockBundles` estiver `false` no seu config, o
comportamento do plugin é idêntico ao original, sem nenhum overhead.

## Fontes da pesquisa que embasou o design (ver conversa anterior para os links)
- PaperMC/Paper#6550 (overstacked bundle dupe)
- MC-225381 (Mojira)
- Histórico de bugs de bundle em crafting no Mojira (MC-205482 e relacionados)
- BundleMeta / InventoryClickEvent (Spigot API docs)
- GeyserMC — Current Limitations
