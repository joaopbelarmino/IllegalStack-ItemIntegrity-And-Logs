# Estado das versões

A main continua na base de código 3.0 sem o antigo subsistema de UUID de stacks. A atualização de apresentação não incorpora automaticamente as mudanças abaixo.

| Branch | Conteúdo | Situação |
| --- | --- | --- |
| main | Base 3.0 e documentação pública | Recomenda-se MONITOR; não representa todas as correções beta. |
| perf/identity-reads-and-webhook-delivery | Leitura de PDC e entrega de webhooks | Desenvolvimento separado da main. |
| feature/ender-slot-revalidation | Ender Chest, eventos de slot e correções de resultados de DELETE | Ainda separada da main. |
| feature/inventory-audit | Primeira auditoria de playerdata e containers | Beta histórica, substituída pelas correções seguintes. |
| fix/audit-evidence-and-transfers | Evidência, indexação e transferência em memória | Não usar como garantia de segurança contra crash. |
| fix/audit-bounded-index-and-freshness | Correções adicionais de NBT, atualização online e filas | Beta mais recente; transferência administrativa bloqueada. |

Na revisão eb1d574 da última branch: 77 testes passaram e o build verificou o SQLite embarcado. Isso não representa um teste de desempenho em servidor nem aprovação para produção.

O alias /stack e as consultas de auditoria pertencem às branches de auditoria. O banco item-audit.db é separado do item-integrity.db.

Não misture arquivos de várias branches manualmente. Para comparar, use os pull requests e o histórico Git. Relatórios de builds antigos de stacks foram retirados da árvore atual porque descreviam um subsistema removido; o histórico foi preservado.

## Pendente
- Recuperação transacional da transferência administrativa entre playerdata e chunks.
- Regressões reais da auditoria beta em Leaf, incluindo containers entre chunks e carga elevada.
- Validação explícita antes de habilitar DELETE.
- Matriz de compatibilidade com outras versões/plataformas.
