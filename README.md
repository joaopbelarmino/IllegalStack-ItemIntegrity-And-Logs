# IllegalStack ItemIntegrity And Logs

Private ZetraMC fork targeting Minecraft/Leaf 1.21.11 and Java 21.
The current baseline is version 3.0 without stackable-item UUID tracking or
cleanup. Equipment and shulker identity, presence tracking, conflict detection
and asynchronous SQLite audit remain available.

## Repository workflow

Canonical private repository: [IllegalStack ItemIntegrity And Logs](https://github.com/joaopbelarmino/IllegalStack-ItemIntegrity-And-Logs).

This repository is the primary source of truth. Keep it private. `main` is the
stable branch; use separate branches for larger changes and merge only after
validation. Never force-push `main`. See [AGENTS.md](AGENTS.md) for the complete
commit, push, security and validation rules.

Do not commit production configs, credentials, webhooks, logs or SQLite files.
Historical handoffs and beta reports describe older versions; current source
and [NO-STACK-REPORT.md](NO-STACK-REPORT.md) take precedence for stack removal.

## Upstream

A spigot based plugin dedicated to fixing glitches and exploits that have made it into final Minecraft releases.

## Links
(NOTE: CONCERNING CUSTOM OR UNRELEASED OR SPECIAL BUILDS OR JARS.  THE ONLY PLACE YOU SHOULD EVER DOWNLOAD THIS OR ANY OTHER PLUGIN IS DIRECTLY FROM THE AUTHORS LINKS.  NEVER TRUST SOMEONE THAT RANDOMLY MESSAGES YOU OR JOINS YOUR SERVER CLAIMING TO HAVE A NEW VERSION AS IT WILL LIKELY CONTAIN MALICIOUS CODE TO TAKE OVER OR DESTROY YOUR SERVER!)
- [Spigot](https://www.spigotmc.org/resources/dupe-fixes-illegal-stack-remover.44411/) <- OFFICIAL DOWNLOAD LINK
- [Wiki](https://github.com/dniym/IllegalStack/wiki/FAQ)
- [Discord](https://discord.gg/Gsx4QaT)

## Building this project

Use Java 21 and the included Gradle Wrapper:

```powershell
.\gradlew.bat clean build --no-daemon
```

On Linux: `bash ./gradlew clean build --no-daemon`.
The deployable shaded JAR is `build/libs/Illegalstack-zetramc-3.0.jar`.
The build runs tests and verifies the SQLite driver embedded in that JAR.

Original authorship and GPL licensing are preserved in [LICENSE](LICENSE).
