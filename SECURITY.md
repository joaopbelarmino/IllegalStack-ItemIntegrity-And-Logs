# Segurança

## Relatar uma vulnerabilidade
Não publique um método de dupe explorável, credenciais ou arquivos de jogadores em uma issue pública. Use a opção privada de relato de vulnerabilidade do GitHub quando disponível neste repositório. Se ela não estiver disponível, abra apenas um pedido genérico de canal privado, sem detalhes exploráveis.

Inclua versão/commit afetado, requisitos para reprodução e impacto estimado. Aguarde um canal privado para enviar o caso completo.

## Dados sensíveis
URLs de webhook permitem publicar no destino associado. Trate-as como credenciais. Antes de enviar logs ou screenshots, remova tokens, senhas, endereços IP e dados pessoais desnecessários.

Se uma credencial já apareceu em um commit, apagá-la no commit seguinte não resolve a exposição: revogue/rotacione a credencial e revise o histórico antes de publicar.

## Limites
Não há garantia de detectar todos os dupes. Não use uma suspeita como prova isolada para banir ou apagar itens. Faça backups consistentes e teste mudanças destrutivas fora da produção.
