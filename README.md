# IllegalStack ItemIntegrity And Logs

### Identidade de itens, evidências e investigação de duplicações

**Arquitetura e direção da fork por jaozinm, com programação assistida por IA.**
Construído sobre o trabalho dos autores e colaboradores do [IllegalStack original](https://github.com/dniym/IllegalStack).

[Como funciona](docs/ARQUITETURA.md) · [Instalação e testes](docs/OPERACAO.md) · [Estado das versões](docs/STATUS.md) · [Contribuir](CONTRIBUTING.md) · [Segurança](SECURITY.md)

## Por que compartilhar este projeto?

Depois de anos usando plugins gratuitos e aprendendo com o trabalho de outras pessoas na comunidade Minecraft, quis devolver um pouco dessa ajuda. Esta fork nasceu de problemas reais enfrentados na administração de servidores Minecraft e da vontade de construir uma ferramenta útil para outros servidores.

A proposta de abrir o código é permitir que mais pessoas entendam as decisões, revisem os riscos, reproduzam problemas e contribuam com melhorias. Não é uma promessa de proteção perfeita: é um projeto que deve evoluir com transparência, testes e responsabilidade.

A assistência de IA faz parte do processo de programação e revisão. A definição dos objetivos, as decisões de arquitetura e os testes relatados são conduzidos por jaozinm. Isso não substitui revisão humana, nem transfere a autoria do IllegalStack original para esta fork.

## O que existe aqui?

| Camada                    | Responsabilidade                                                                                        |
| ------------------------- | ------------------------------------------------------------------------------------------------------- |
| Proteções do IllegalStack | Verificações e bloqueios herdados do projeto original, controlados por sua própria configuração.        |
| Item Integrity            | Identidade persistente, rastreamento de custódia, revalidação de conflitos e registro de evidências.    |
| Auditoria de inventários  | Busca e consulta de playerdata e containers. **Beta em branch separada**, ainda não integrada à `main`. |

> **Leia antes de instalar:** a `main` é a base 3.0 sem rastreamento de empilháveis. Correções mais recentes e auditoria estão em branches de desenvolvimento. O número “3.0” sozinho não identifica todas as diferenças: registre também o commit utilizado. Veja o [estado exato das branches](docs/STATUS.md).

## Como funciona o Item Integrity?

Cada item elegível recebe uma identidade interna, como `ZI-...`, armazenada no **Persistent Data Container (PDC)**. Esse identificador não precisa aparecer no nome ou na lore.

O sistema acompanha onde o item está, quem o mantém e qual foi sua última presença confirmada. Transferências legítimas devem preservar o ID:

```text
Inventário → baú → inventário
Shulker como item → bloco colocado → drop → inventário
```

Quando aparecem observações incompatíveis do mesmo ID, elas precisam ser confrontadas com o estado físico atual. Uma observação antiga em SQLite não prova que existe outra cópia agora.

### Por que essa abordagem ajuda?

Muitos processos de duplicação copiam os dados do item junto com ele. Se duas instâncias preservarem o mesmo ID, o sistema pode relacioná-las e investigar a coexistência física. Isso é mais informativo do que considerar apenas material, quantidade ou nome.

**Não detecta todos os dupes.** Se outro plugin apagar ou reconstruir a identidade, se o caminho ainda não estiver coberto, ou se só houver dados antigos disponíveis, a conclusão pode ser limitada ou ambígua. Um ID é um marcador de identidade, não uma assinatura impossível de falsificar.

## MONITOR, DELETE e segurança

* **MONITOR:** registra e comunica os conflitos avaliados pelo Item Integrity, sem executar a remoção desse detector.
* **DELETE:** permite ação destrutiva apenas nos caminhos habilitados e sujeitos às verificações da implementação. Exige validação específica no servidor antes de uso.
* **FAIL_OPEN:** diante de incerteza ou falha na persistência/revalidação, a prioridade é não apagar um item legítimo.

**Recomendação para esta publicação: mantenha MONITOR.** Há correções relevantes de persistência dos resultados em branches posteriores à base da `main`.

MONITOR não desativa as proteções tradicionais do IllegalStack. A migração também pode atribuir IDs a itens elegíveis ainda não identificados. Portanto, MONITOR não significa que o plugin inteiro é somente leitura.

## Compatibilidade

| Ambiente                     | Situação                                                                                                                                                                            |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **LeafMC 1.21.11 + Java 21** | Alvo principal de desenvolvimento e testes de lifecycle.                                                                                                                            |
| Paper e outras forks         | Sem garantia de estabilidade ou cobertura equivalente.                                                                                                                              |
| Minecraft 1.21.4 a 1.21.11   | Outras versões dessa faixa podem compartilhar comportamentos, mas **a compatibilidade desta build não foi validada**. APIs específicas podem impedir funcionamento ou carregamento. |
| Folia                        | Não considere a fork inteira validada apenas pela declaração herdada em `plugin.yml`; a auditoria beta não é habilitada nesse ambiente.                                             |

Os testes no Leaf não significam que toda branch nova já passou por testes em produção. Build e testes automatizados também não provam ausência de falsos positivos ou impacto em TPS.

## Instalação e configuração

1. Faça backup do servidor e do banco antes de trocar a build.
2. Escolha o commit/branch de forma consciente e compile com Java 21.
3. Instale apenas o JAR sombreado e reinicie o servidor. Evite `/reload`.
4. Revise as configurações geradas e comece pelo MONITOR.
5. Reproduza transferências legítimas e duplicações controladas em servidor de teste.

| Arquivo                             | Uso                                                             |
| ----------------------------------- | --------------------------------------------------------------- |
| `config.yml`                        | Proteções tradicionais do IllegalStack.                         |
| `item-integrity.yml`                | Identidade, detector, SQLite, limites e webhooks.               |
| `item-integrity.db`                 | Persistência do Item Integrity. Não edite como texto.           |
| `item-integrity-cases.log`          | Casos classificados pelo detector.                              |
| `item-integrity-possible-cases.log` | Observações e casos possíveis que exigem interpretação.         |
| `item-audit.yml` / `item-audit.db`  | Módulo de auditoria, somente nas branches beta correspondentes. |

Não há rastreamento de UUID para itens empilháveis nem rotina de limpeza desse antigo subsistema. Arquivos de configuração antigos não reativam código removido.

## Comandos úteis

| Comando                   | Finalidade                                                                              |
| ------------------------- | --------------------------------------------------------------------------------------- |
| `/istack inspect`         | Inspecionar a identidade e a presença do item da mão principal.                         |
| `/istack inspect block`   | Consultar a identidade de um bloco rastreado, como shulker colocada.                    |
| `/istack lookup <itemId>` | Consultar identidade, presença e observações disponíveis.                               |
| `/istack metrics`         | Consultar métricas disponíveis na build.                                                |
| `/istack restart sql`     | Solicitar manutenção/compactação do SQLite; não é comando para apagar ou reiniciar IDs. |

Inspeção exige `illegalstack.itemintegrity.inspect`, além da permissão de acesso ao comando quando aplicável. Comandos de auditoria, incluindo o alias `/stack`, pertencem às branches beta; consulte a documentação da branch escolhida.

## Compilar

```powershell
.\gradlew.bat clean build --no-daemon
```

```bash
bash ./gradlew clean build --no-daemon
```

O artefato instalável é o **JAR sombreado** gerado em `build/libs/`.
Não confunda com o JAR simples sem dependências. Prefira compilar o código ou usar artefatos oficiais deste repositório; não instale arquivos enviados por desconhecidos.

## Performance e limites

A arquitetura prioriza eventos, cache em RAM, trabalho parcelado e escrita SQLite assíncrona. A confirmação de um caso deve investigar os holders envolvidos, não varrer todos os chunks.

Isso **não significa custo zero**. Inventários complexos, serialização, plugins externos e volume de eventos influenciam o resultado. Não existe uma quantidade de jogadores ou um processador que garantam TPS. Relatos úteis incluem perfil Spark, versão exata e caminho reproduzível.

## Créditos e licença

* **jaozinm:** arquitetura da fork, direção do projeto, requisitos e testes.
* **Programação assistida por IA:** apoio à implementação, análise e revisão, com limitações explicitamente documentadas.
* **dNiym, Loving11ish e colaboradores:** base IllegalStack e seu histórico de desenvolvimento.
* **Comunidade:** ferramentas, relatos, revisões e projetos que ajudam a melhorar o ecossistema.

A [GNU GPL v3 presente no repositório](LICENSE) e os créditos originais são preservados. Dependências mantêm suas próprias licenças; veja [as atribuições](docs/ATRIBUICOES.md). Esta fork não é uma versão oficial do projeto original e não oferece garantia de proteção absoluta.
