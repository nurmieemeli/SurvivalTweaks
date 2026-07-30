## Outcome

Describe the player-visible or operator-visible result.

## Changes

- Describe the focused implementation.

## Verification

- [ ] `mvn --batch-mode --no-transfer-progress clean verify`
- [ ] `pwsh ./.github/scripts/paper-smoke.ps1`
- [ ] Tested relevant behavior on Paper 26.2

## Compatibility and performance

Describe event priority, thread-safety, persistence, hot-path, migration, and
plugin-compatibility implications. Write "None" where applicable.

## Resources

- [ ] English and Finnish locale keys match in order and placeholders
- [ ] New permissions and commands are declared in `plugin.yml`
- [ ] Configuration and operational documentation are updated
- [ ] No generated files, local IDE state, server binaries, logs, or secrets are included

## Related issue

Closes #
