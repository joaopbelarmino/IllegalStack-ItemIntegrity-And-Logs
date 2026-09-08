# Stack Integrity beta / SQLite enxuto

Alvo: Leaf/Paper 1.21.11, Java 21. Nao ha codigo externo copiado.

## Configuracao

`plugins/IllegalStack/AntDupe-Stack-Config.yml` e independente de
`item-integrity.yml`. Todos os controles de empilhaveis estao no novo arquivo:
lista `materials`, habilitacao, budgets, memoria, revalidacao, pontes de merge,
deduplicacao e dois endpoints de webhook. Materiais invalidos sao ignorados com
aviso no startup. Alteracoes exigem restart. Equipamentos e shulkers continuam
usando sua configuracao e identidade atuais.

A beta vem com `enabled: false` e suporta apenas `MONITOR`. Ative em teste
antes de usar em producao. Nao ha remocao de duplicatas, mesmo que `mode` seja
editado para DELETE. A manutencao SQLite funciona independentemente disso.

## Identidade e operacoes

- PDC binario `zetra:stack_identity`, mostrado como `ZS-<UUID>`.
- UUID atual, UUID da operacao, quantidade emitida e ate tres ancestrais.
- De 38 a 86 bytes de payload, sem lore/nome/tooltip e sem alterar `zetra:item_id`.
- Movimentacao inteira e reducao simples conservam o UUID.
- Divisao testemunhada exige uma origem fisica, equivalencia dos itens e soma
  exata das quantidades. As saidas recebem identidades diferentes.
- Juncoes explicitas conservam quantidade e aposentam as origens em RAM.
- Uma origem aposentada nao pode autorizar outra operacao durante sua retencao.
- Historico comum entre duas partes nao e duplicacao.
- Duas presencas atuais passam por nova leitura fisica apos o delay. Quantidade
  simultanea acima da emissao, ou origem aposentada coexistindo com descendente de uma
  operacao conhecida, pode produzir CONFIRMED_DUPLICATE / WOULD_REMOVE.
- Mesmo UUID em duas pilhas cuja soma cabe na emissao e AMBIGUOUS_STACK_OPERATION:
  pode ser uma divisao nao testemunhada. Nao e promovido a confirmado pelo tempo.
- Quantidade excessiva em uma unica pilha ou UUID compartilhado entre materiais
  diferentes tambem fica ambiguo: nao prova sozinho duas copias simultaneas.
- Casos usam snapshot, CaseFileLogger, os logs confirmados/possiveis existentes,
  AuditTask, writeAndConfirm e o notifier compartilhado. Webhooks de stacks tem
  endpoints proprios. Nenhuma chamada Bukkit acontece nos callbacks SQL/HTTP.

## Cobertura implementada e limites

Observacao: scanner parcelado existente dos jogadores, abertura/fechamento de
containers fisicos, cliques/drag, drop/pickup, hopper, spawn de drops ja marcados,
merge vanilla de identidades iguais, troca de maos e colocacao de blocos.

Pontes de merge entre IDs diferentes: clique esquerdo/direito e shift-click em
inventarios de armazenamento fisicos; hopper para armazenamento fisico. Hopper
e cancelado primeiro e a ponte roda depois da restauracao vanilla da origem,
revalidando os IDs dos dois slots. Nao interfere em GUI virtual de loja/plugin.

Limitacoes que PRECISAM ser consideradas antes de habilitar:

- Ainda nao ha ponte geral para double-click, merge de drops com IDs diferentes,
  pickups em inventario cheio quando so ha espaco em pilhas de outro UUID,
  slots especiais de maquinas, receitas/crafter, fundicao ou plugins externos.
  Nessas situacoes, PDC diferente pode impedir a juncao vanilla.
- Drops novos de mineracao/farms nao recebem ID enquanto estao soltos; recebem
  identidade quando observados num inventario fisico. Isso preserva o merge
  vanilla dos drops novos e evita fragmentar automaticamente toda farm.
- Nao ha identidade por bloco mineral colocado nem continuidade garantida
  atraves de colocar/quebrar, crafting, fundicao ou mudanca de material.
  Esses caminhos podem sair da cobertura de ancestralidade.
- Um plugin que remove/reconstroi PDC pode perder a identidade. Inventarios
  virtuais nao sao prova de duplicacao e nao autorizam rotacao de IDs.
- Ancestralidade recente em RAM expira (default 600 s), e se perde no restart.
  O PDC do item permanece; a protecao retrospectiva e limitada pela retencao.
- Saturacao de memoria/fila aborta novas transformacoes; nao recicla IDs de
  origens suspeitas. Ausencia de evidencia nunca autoriza DELETE.
- Esta implementacao e para Leaf/Paper convencional. Stacks nao iniciam em Folia.

## Performance e tarefas

Nenhum scanner global de chunks/containers. Nenhuma nova tarefa periodica de
scanner. O scanner de jogadores existente enfileira observacoes de stacks.
Uma tarefa pontual consome a fila no tick seguinte; so se reagenda se houver
backlog. Default 256 unidades de trabalho/tick, com parada entre operacoes
apos 2 ms; uma operacao atomica individual pode ultrapassar esse tempo.
O budget e compartilhado com capturas nos eventos (minimo configuravel 128).
Snapshots de operacoes consideram apenas os inventarios envolvidos.

Revalidacoes sao pontuais (default 3 ticks), direcionadas ao ID e aos locators
conhecidos, sem carregar chunks. O limite da fila e 512; presencas, 50.000;
origens aposentadas, 20.000. Essas estruturas crescem com jogadores ativos,
slots observados e taxa de divisao/juncao, ate os limites configurados.
`/istack stack stats` mostra backlog, contadores, tempo acumulado e saturacao.
Nao e uma medicao de MSPT do servidor. Nao foi feito benchmark com 150 CCU.

## SQLite

- `items` e `presence` preservados, inclusive identidades offline e terminais.
- Escritas novas de rotina vao para `item_event_tail`: ultima presenca
  comprometida, anterior quando o holder/estado mudou e ultima divergencia.
- `item_events` legado e reduzido durante `/istack restart sql`; seu ultimo
  evento por ID e importado ao tail sem substituir observacoes mais recentes.
- `item_event_rollups` antigos sao removidos; nao sao recriados por movimentacao.
- Possiveis continuam agregados por incidente e expiram com
  `sqlite.possible-retention-days: 7`, desde sua ultima ocorrencia persistida.
- `sqlite.history-retention-days` legado deixa de controlar provas/historico.
- Confirmados, WOULD_REMOVE antigos, REMOVED, RESURRECTED_ITEM e tentativas
  DELETE_ABORTED preservam detalhes/snapshot existentes. Snapshots que ja foram
  eliminados por versoes anteriores nao podem ser recuperados pela migracao.
- A manutencao automatica continua no writer, a cada 360 minutos por padrao.
  Migracao volumosa de `item_events` legado e feita pelo comando administrativo.
- Atualizacoes consecutivas de presenca sao coalescidas no lote. Uma revisao
  antiga nao substitui outra mais recente com o mesmo timestamp.
- Fila de writeAndConfirm limitada a 512; saturacao/falha retorna false.
- Operacoes normais de stacks nao criam linhas permanentes por UUID intermediario.
- sqlite-jdbc permanece embutido em `org.sqlite`, sem relocation: JNI referencia
  esses nomes nativos. `verifySqliteArtifact` abre uma conexao e executa SQL
  usando somente o JAR final; esse teste agora e obrigatorio no build.

O comando preserva seu nome e permissao `illegalstack.admin`, informa inicio,
fim, tamanho antes/depois e linhas do tail. A copia compactada passa por
quick_check e validacao de contagens de items/presence/tail/casos/resumos antes
da troca. Confirmados ficam intactos. A limpeza de historico autorizada nao e
reversivel: mantenha backup consistente antes da manutencao. Espaco livre para
a copia compactada e para WAL continua necessario.

## Teste manual obrigatorio

1. Ativar somente no servidor de teste; manter MONITOR.
2. `/istack stack` na mao: verificar UUID e emissao.
3. Dividir 64 em 32+32: novos UUIDs, mesma operacao/pai, nenhuma confirmacao.
4. Reunir com clique, clique direito e shift: quantidade total intacta.
5. Repetir em bau simples/duplo, shulker fisica, dispenser e hopper; verificar
   especialmente transferencias de uma unidade e origem esvaziada.
6. Testar eventos cancelados por protecao e acao Creative: nenhum desaparecimento
   ou normalizacao de copias por rotacao de IDs.
7. Duplicar 64 X em Creative: duas copias fisicas devem gerar um caso agregado.
8. Duplicar e imediatamente dividir uma ou ambas as copias; conferir genealogia
   e que a quantidade extra nao e legitimada por novos IDs.
9. Drop/pickup, troca de slots/offhand, restart e plugins da economia: registrar
   as limitacoes acima, especialmente pilhas que deixam de se juntar.
10. Executar `/istack restart sql` duas vezes: resultado idempotente; confirmar
    identidades, presencas e snapshots antigos; medir db e WAL no host.

Permissoes de leitura: `illegalstack.itemintegrity.inspect` para `/istack stack`,
`/istack stack stats` e `/istack inspect`. Console pode consultar os stats.
