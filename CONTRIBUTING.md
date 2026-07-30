# Contributing to SurvivalTweaks

Thank you for helping improve SurvivalTweaks. The project favors focused,
vanilla-friendly quality-of-life changes that remain safe on a busy Paper
server.

## Before starting

- Search existing issues before opening a duplicate.
- Use the bug or feature issue form and include the requested context.
- For a substantial behavior change, open an issue before investing in an
  implementation.
- Keep changes narrowly scoped; unrelated cleanup belongs in a separate pull
  request.

## Development setup

1. Fork and clone the repository.
2. Open the repository or `pom.xml` in IntelliJ IDEA.
3. Select a Java 25 SDK.
4. Import the Maven project and run `mvn clean verify`.

SurvivalTweaks targets Paper 26.2. Do not commit IntelliJ workspace files,
compiler output, test servers, logs, dumps, or downloaded server binaries.

## Project expectations

- Preserve the vanilla survival loop and avoid required client mods or resource
  packs.
- Keep Bukkit world and entity access on the server thread.
- Keep background persistence ordered, immutable at the async boundary, and
  atomically written.
- Bound recurring and block-heavy work so it can resume across ticks.
- Add or update tests for behavior changes.
- Add every player-facing message to both `messages_en.yml` and
  `messages_fi.yml`, in the same key order, with matching MiniMessage
  placeholders.
- Add new permission nodes and command metadata to `plugin.yml`.
- Document new settings and operational behavior in the README or operations
  guide.

## Validation

Before submitting a pull request, run:

```shell
mvn --batch-mode --no-transfer-progress clean verify
pwsh ./.github/scripts/paper-smoke.ps1
```

The first command runs unit, integration, locale, listener, and permission
contract tests. The second starts the checksum-pinned Paper build, reloads the
plugin, runs diagnostics, and verifies a clean shutdown.

## Pull requests

- Complete the pull request template.
- Explain the player-visible result and performance implications.
- Include reproduction and verification details for fixes.
- Do not bump the project version or create release tags in ordinary pull
  requests; maintainers handle releases.
- Update your branch when CI exposes a conflict or regression.

By contributing, you agree that your work is licensed under the repository's
[MIT License](LICENSE) and that project interaction follows the
[Code of Conduct](CODE_OF_CONDUCT.md).
