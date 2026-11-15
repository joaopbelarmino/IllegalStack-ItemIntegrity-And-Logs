# Comandos principais

## Item Integrity

| Comando | Uso |
| --- | --- |
| `/istack inspect` | Inspeciona o item da mão principal, sem criar ID. |
| `/istack inspect block` | Inspeciona o bloco rastreado que você está olhando. |
| `/istack lookup <itemId>` | Consulta identidade, presença e observações disponíveis. |
| `/istack metrics` | Exibe as métricas disponíveis na build. |
| `/istack restart sql` | Solicita manutenção/compactação do SQLite do Item Integrity. |

Inspeção: `illegalstack.itemintegrity.inspect`. Manutenção: `illegalstack.admin`.
O acesso ao comando também pode exigir `illegalstack.help`.

## Auditoria beta

**Estes comandos não existem na base atual da main.** Estão nas branches de auditoria. Nelas, `/stack` é um alias de `/istack`.

| Comando | Uso |
| --- | --- |
| `/stack search all diamond` | Busca material em todas as fontes indexadas. |
| `/stack search playerdata diamond` | Busca no inventário e Ender Chest indexados dos jogadores, com total por jogador. |
| `/stack search inv diamond` | Busca apenas no índice de inventários. |
| `/stack search end diamond` | Busca apenas no índice de Ender Chests. |
| `/stack search bau diamond` | Busca nos containers indexados. |
| `/stack search all diamond 2` | Segunda página de resultados. |
| `/stack search all serial:ZI-EXEMPLO` | Busca um ID exato. Substitua pelo ID real completo. |
| `/stack search suspeito` | Lista suspeitas do índice; não prova duplicação física. |
| `/stack search suspeito playerdata` | Filtra suspeitas de jogadores. |
| `/stack search suspeito bau` | Filtra suspeitas de containers. |
| `/stack view inv <nick|uuid>` | Abre a visualização de inventário. |
| `/stack view end <nick|uuid>` | Abre a visualização de Ender Chest. |
| `/stack view bau here` | Consulta o container próximo para o qual você olha. |
| `/stack view bau <uuid>` | Consulta o container pelo UUID do índice. |
| `/stack view bau <mundo> <x> <y> <z>` | Consulta a posição carregada, sem forçar chunk. |
| `/stack reindex playerdata` | Solicita reindexação dos arquivos de jogadores offline. |
| `/stack reindex playerdata <nick|uuid>` | Solicita reindexação do playerdata de um jogador offline. |
| `/stack backup playerdata <nick|uuid>` | Faz backup manual do arquivo playerdata existente; não força um save do estado online. |
| `/stack audit status` | Mostra estado da auditoria, filas e progresso. |
| `/stack audit reload` | Recarrega a configuração do módulo de auditoria. |

Permissões: `illegalstack.audit.search`, `illegalstack.audit.view`, `illegalstack.audit.reindex` e `illegalstack.audit.admin`, de acordo com a ação.

Visualizações exigem uso em jogo. Nicks dependem do cache local: use UUID quando o nick não for encontrado. A busca por serial atualmente não aplica os filtros de fonte como a busca por material; prefira `all` para essa consulta.

Na beta mais recente, a transferência para o administrador está bloqueada. Snapshots históricos são somente evidência, nunca uma fonte para gerar ou entregar itens.
