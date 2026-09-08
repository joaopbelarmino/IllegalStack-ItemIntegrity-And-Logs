# Project workflow

- Canonical private remote: https://github.com/joaopbelarmino/NomeDoPlugin.git
- The repository name follows the owner's latest explicit instruction.
- This Git repository is the primary source of truth for IllegalStack ZetraMC
  Item Integrity. Work here, not in old ZIP extractions or copied releases.
- The GitHub repository must remain PRIVATE. Verify the owner, destination and
  private visibility before the first push and whenever the remote changes.
- main is the stable branch. Never force-push, rewrite or delete main.
- Before larger changes create a descriptive feature/* or fix/* branch from an
  up-to-date main. Preserve unrelated user edits. Commit clear, scoped changes
  and push the working branch. Merge into main only after required validation
  passes; then push main normally. If gameplay validation is still pending,
  keep the change on its branch and explicitly report that limitation.
- For every requested change, inspect the diff, run focused tests plus the real
  Gradle build when code/build files change, commit with a clear message and
  push to the verified private remote. Report the commit and push result.
- If authentication, permissions or network prevents a push, report the blocker
  honestly. A local commit is not a GitHub backup. Never invent a remote URL.
- Do not publish secrets, webhook URLs with tokens, passwords, .env files,
  private keys, API credentials, server configs, logs, databases or dumps.
  Review staged contents before every commit; .gitignore is not a secret scan.
  Authenticate via approved connectors or the OS credential manager. Do not
  embed credentials in remotes, scripts, documentation or command output.
- Preserve LICENSE and upstream attribution. Do not copy external code without
  checking its license and obtaining approval where required.

# Runtime and validation

- Target: Minecraft/Leaf 1.21.11, Java 21, Gradle Wrapper 8.10.2.
- Windows build: .\gradlew.bat clean build --no-daemon
- Linux build: bash ./gradlew clean build --no-daemon
- Output: build/libs/Illegalstack-zetramc-3.0.jar. Do not commit build outputs.
- UUID tracking and cleanup for stackable items were removed. Do not reintroduce
  them without an explicit new request. Equipment/shulker identity is separate.
- Preserve RAM hot state, asynchronous SQLite writer, physical revalidation and
  FAIL_OPEN. No synchronous SQL, async Bukkit calls or global container scans.
- A successful build is not proof of production TPS or DELETE safety. Report
  manual tests and load testing that were not performed.
