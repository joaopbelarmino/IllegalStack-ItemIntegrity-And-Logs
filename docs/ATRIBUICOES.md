# Autoria e dependências

## Fork ZetraMC
Arquitetura e direção por **jaozinm**, com programação assistida por IA. Esta atribuição se refere às mudanças e decisões da fork, não à autoria integral do IllegalStack ou de bibliotecas utilizadas.

## Projeto original
Base: [dniym/IllegalStack](https://github.com/dniym/IllegalStack).
Créditos aos autores dNiym, Loving11ish e demais colaboradores. A licença GPL v3 existente em [LICENSE](../LICENSE) foi mantida sem alteração.

O uso de referências externas de arquitetura não significa que implementações desses projetos foram incorporadas. Cada dependência ou trecho eventualmente incorporado precisa ser avaliado individualmente.

## Bibliotecas
- [sqlite-jdbc / Xerial](https://github.com/xerial/sqlite-jdbc): driver SQLite incorporado ao JAR; consulte a [licença Apache 2.0 do projeto](https://github.com/xerial/sqlite-jdbc/blob/master/LICENSE).
- [SQLite](https://sqlite.org/copyright.html): engine utilizada pelo driver; consulte sua declaração de domínio público.
- Parser NBT io.github.canary-prism:querz-nbt, nas branches de auditoria: licença Apache 2.0; a branch mantém o texto em META-INF/LICENSE-querz-nbt.txt.
- Leaf/Paper e APIs de integração são dependências de compilação conforme o build.gradle.kts da branch. Isso não transfere a autoria nem a licença desses projetos.

As versões exatas variam entre branches. Consulte o arquivo de build e os avisos incluídos no artefato correspondente. Não remova licenças, avisos ou créditos ao redistribuir. Esta página é um mapa de atribuições, não substitui os textos das licenças.
