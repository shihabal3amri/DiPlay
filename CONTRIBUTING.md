# Contributing to DiPlay

Thanks for helping. One idea per pull request keeps review fast.

**Quick path:** search → branch from `main` → code & test → commit → open PR → wait for CI.

Discuss in Chinese or English. Use **English** for commits, PR titles, and PR descriptions.

DiPlay targets **BYD** cars. Other brands are unsupported (see the README).

---

## 1. Before you code

1. Search [issues](https://github.com/shihabal3amri/DiPlay/issues) and [open PRs](https://github.com/shihabal3amri/DiPlay/pulls). Prefer an existing thread over a duplicate.
2. Bugs: [Connection problem](https://github.com/shihabal3amri/DiPlay/issues/new?template=connection.yml) (logs required) or [Other bug](https://github.com/shihabal3amri/DiPlay/issues/new?template=bug.yml).
3. Test while parked. Strip passwords and personal data before uploading diagnostics ([SECURITY.md](SECURITY.md), [docs/PRIVACY.md](docs/PRIVACY.md)).

| Need | Where |
|------|--------|
| Build (`mobile`) | [docs/BUILD.md](docs/BUILD.md) |
| Settings / strings | [AGENTS.md](AGENTS.md) |
| Manual checks | [docs/TESTING.md](docs/TESTING.md) |
| CI (tests / lint / build) | [`.github/workflows/android.yml`](.github/workflows/android.yml) |

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

---

## 2. Create a branch

From latest `main` (never commit on `main`):

```sh
git fetch origin && git checkout main && git pull origin main
git checkout -b fix/short-description
```

Pattern: `type/what-you-change` — e.g. `feat/settings-search`, `fix/wifi-reconnect-412`, `docs/contributing`.  
Prefixes: `feat/`, `fix/`, `docs/`, `chore/`, `refactor/`, `test/`, `contrib/`.

---

## 3. Commit

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```text
type: short description

Optional body: why this change exists. Fixes #123
```

| Type | When |
|------|------|
| `feat` | new capability |
| `fix` | bug fix |
| `docs` | docs only |
| `refactor` | no intended behavior change |
| `test` | tests only |
| `chore` | housekeeping |
| `build` / `ci` | build or GitHub Actions |
| `perf` / `style` / `revert` | perf, formatting-only, or revert |

Examples: `feat: add inline settings search` · `fix(wifi): pause scans during Wi-Fi Direct` · `docs: explain pull requests`

Keep the description short, lowercase, no trailing period. Optional scope: `fix(audio): …`. One logical change per commit. Never commit secrets or unreviewed diagnostics.

---

## 4. Open a pull request

```sh
git push -u origin HEAD
```

Open against **`main`** (GitHub **Compare & pull request**, or `gh pr create`).

PR title must use the same Conventional Commits form — [`.github/workflows/pr.yml`](.github/workflows/pr.yml) checks it.

Fill the template (Background, Summary, How to verify, Risk notes, checklist). Mark unused checklist items **N/A**; do not claim vehicle tests you did not run.

Wait for CI to pass, fix on the same branch, and reply on that PR. Maintainers merge when ready.
