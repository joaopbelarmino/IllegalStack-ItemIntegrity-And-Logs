# Como o sistema funciona

## Identidade não é quantidade
O Item Integrity acompanha objetos elegíveis individualmente. A identidade contém o ID e os dados do registro original, como origem, horário e localização. Mover o objeto não deve gerar outro registro de nascimento.

O PDC acompanha o ItemStack. Para um objeto mudar de representação, como shulker colocada, a identidade precisa ser transferida explicitamente entre item e TileState. A persistência automática do servidor não deve ser presumida para todos os eventos.

## Presença e custódia
Uma presença relaciona ID, tipo de holder, localização lógica, slot, estado e revisão. A presença canônica é a referência conhecida do objeto. Uma observação divergente é informação a investigar, não autorização imediata para remover.

Movimentos conhecidos usam handoffs. Dois slots diferentes do mesmo jogador podem conter cópias reais; já uma observação genérica do jogador e uma observação de seu offhand podem descrever a mesma instância.

Inventários de plugins exigem cautela: uma GUI pode representar um item que continua no armazenamento real. Sem evidência suficiente, o resultado deve permanecer ambíguo.

## Da anomalia à decisão
O desenho do detector segue esta sequência:

1. Observar um ID em locais aparentemente incompatíveis.
2. Revalidar os holders físicos relevantes após uma pequena janela.
3. Reconciliar movimentos legítimos ou manter a observação ambígua.
4. Quando houver evidência suficiente, registrar o caso e sua decisão.
5. Nos caminhos destrutivos habilitados, persistir o snapshot antes da ação e revalidar novamente.

A implementação varia entre as branches. A correção da gravação do resultado final de DELETE está em uma branch posterior à base da main; consulte [STATUS.md](STATUS.md). Não use este desenho como certificação de toda build histórica.

## SQLite e logs
O estado em RAM atende às observações frequentes. Filas encaminham trabalho ao escritor SQLite, evitando consulta por item na thread principal. Casos, estado atual e retenção têm objetivos diferentes: compactar histórico não deve recriar IDs.

Logs possíveis não são uma lista de jogadores culpados. Containers indisponíveis, metadata reconstruída por outro plugin e informações antigas podem gerar contexto incompleto.

## Auditoria beta, separada do detector
A auditoria usa seu próprio banco e serve para localizar itens e examinar evidências. Ela não substitui a revalidação física do Item Integrity.

Na branch beta mais recente, IDs repetidos entre snapshots de momentos diferentes não ganham pontuação de duplicação só por coincidirem. O índice online é atualizado por eventos agrupados; conteúdo NBT excessivo produz um índice parcial sinalizado. O snapshot original do container é preservado.

A transferência para inventário administrativo está bloqueada nessa beta até existir recuperação contra crash validada entre playerdata e chunks. Ter um log persistido não torna esses arquivos atomicamente consistentes.

## O que isto não resolve sozinho
Não há rastreamento geral de stacks, promessa de cobrir todo exploit, integração universal com lojas ou prevenção completa de crash dupe. A verificação depende da identidade preservada e da cobertura dos caminhos reais.
