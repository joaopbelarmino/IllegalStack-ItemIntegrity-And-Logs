# Operação e testes

## Antes de instalar
Use Java 21 e LeafMC 1.21.11 como ambiente de referência. Selecione a branch e anote o commit. As proteções herdadas têm configuração própria e podem agir mesmo com o Item Integrity em MONITOR.

Faça backup consistente com o servidor parado ou pelo procedimento de backup validado da sua hospedagem. Um arquivo SQLite em WAL pode depender dos arquivos auxiliares; não copie apenas o .db de um servidor escrevendo sem garantir consistência.

## Configuração
O plugin gera os arquivos necessários. Revise o arquivo gerado pela build utilizada em vez de copiar configurações de relatórios antigos.

Comece com:
```yaml
conflict-detector:
  mode: MONITOR
```

Esse fragmento pertence ao item-integrity.yml; não substitui o arquivo inteiro.
A migração de itens sem identidade pode estar habilitada. Ela atribui IDs a itens elegíveis, embora o detector esteja em MONITOR.

Webhooks são opcionais. A URL contém credenciais: nunca a publique em issues, prints, exemplos ou commits. Use destinos separados para confirmados e possíveis quando suportados pela build.

## Testes mínimos
- Item na mão: anotar o ID com /istack inspect.
- Mover entre slots e offhand; verificar que o ID permanece.
- Guardar em baú e retirar; repetir com hopper entre containers.
- Colocar shulker, consultar /istack inspect block, reiniciar e quebrar.
- Testar drop, pickup e explosão em ambiente isolado.
- Reproduzir duas instâncias do mesmo ID e conferir os dois holders físicos.
- Testar loja, leilão e shulker aberta na mão, se esses plugins existirem.
- Na branch com Ender Chest, incluir esse caminho na regressão.
- Na auditoria beta, testar minecart, leitura offline, NBT profundo e filas sob carga.

Nenhuma suspeita deve virar punição automática. Primeiro diferencie coexistência real de transferência, cópia de GUI e informação desatualizada.

## Compactação
/istack restart sql solicita manutenção da base do Item Integrity. Aguarde a mensagem final e confirme o resultado. O tamanho pode crescer durante operações intermediárias. Não reinicie para tentar acelerar.

Se aparecer SQLITE_FULL, verifique espaço, quota e armazenamento temporário. Se aparecer SQLITE_CORRUPT, preserve uma cópia consistente e investigue: compactação não é promessa de reparo. Não apague IDs nem bancos para silenciar alertas.

## Diagnóstico
Informe commit, Java, build do Leaf, modo do detector, plugins que participam do caminho e passos reproduzíveis. Inclua um trecho mínimo do log, com credenciais e dados pessoais removidos. Para TPS, prefira um perfil com o problema acontecendo; porcentagens isoladas não explicam a causa.
