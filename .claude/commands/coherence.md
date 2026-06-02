Audit the project documentation for coherence, staleness, and completeness.

This is a READ-ONLY audit — do not edit any files. Report findings, then ask me which ones to fix.

## Phase 1: Discover documentation structure

1. Read the root `CLAUDE.md` to understand the project structure, stack, and doc layout
2. Find all sub-module `CLAUDE.md` files (e.g. `backend/CLAUDE.md`, `frontend/CLAUDE.md`, etc.)
3. Find all documentation directories referenced in the root CLAUDE.md (e.g. `docs/`, `docs/conventions/`, `docs/features/`, `docs/decisions/`)
4. Find any documentation index file (e.g. `docs/INDEX.md`, `docs/README.md`)

## Phase 2: CLAUDE.md files vs reality

For EACH `CLAUDE.md` file found:

1. **Commands check**: verify every command listed actually works with the project's package manager (check lock files: `bun.lock` → bun, `package-lock.json` → npm, `pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn). Flag mismatches (e.g. `npm run dev` when the project uses bun).
2. **Directory structure check**: if the file documents a directory tree, run `ls` on the actual directory and compare. Flag missing/extra/renamed directories.
3. **File references check**: for every file path mentioned (e.g. `lib/api.ts`, `router.tsx`), verify it exists. Flag any dead reference.
4. **Link check**: for every markdown link (`[text](path)`), verify the target file exists. Flag broken links.
5. **Version check**: if stack versions are mentioned, cross-check against `package.json`, `pom.xml`, `Cargo.toml`, `go.mod`, `pyproject.toml`, or equivalent. Flag version drift.

## Phase 3: Convention/reference docs vs codebase

For EACH documentation file found in convention or reference directories:

1. **Structural claims**: if the doc describes a directory structure, package layout, or file organization — verify against the actual codebase.
2. **Dependency claims**: if the doc lists libraries, frameworks, or tools — verify they exist in the dependency manifest.
3. **Pattern claims**: if the doc says "we use X pattern" or "all Y are in Z directory" — spot-check with a quick grep/glob that the pattern holds.
4. **Don'ts/anti-patterns section**: check if the doc has one. Note its absence (not an error, just a suggestion).

## Phase 4: Documentation index completeness

If a documentation index file exists:

1. For each entry listed, verify the linked file exists
2. Check for orphan files: documents in `docs/` subdirectories that are NOT listed in the index
3. Check "Last updated" dates if present — flag any doc older than 30 days that covers code modified recently (cross-reference with `git log --since="30 days ago" --name-only`)

## Phase 5: Environment variable completeness

If a `.env.example`, `.env.template`, or similar file exists:

1. Grep all environment variable references in configuration files (e.g. `application.yml`, `docker-compose.yml`, `.env` imports in code)
2. Grep all env var reads in source code (`process.env.`, `import.meta.env.`, `System.getenv`, `os.environ`, `${VAR}`, `@Value`, etc.)
3. Flag any env var used in code but missing from the example/template file

## Phase 6: Cross-file consistency

1. For each pair of docs that cover the same topic (e.g. a CLAUDE.md section and a convention doc it links to), check they don't contradict each other
2. Flag any case where two docs describe the same thing differently (different file paths, different patterns, different commands)

## Report format

Present findings as a table:

| Severity | File | Issue |
|----------|------|-------|
| STALE | file.md | Description of what's outdated |
| MISSING | file.md | Description of what's absent |
| CONFLICT | file1.md vs file2.md | Description of contradiction |
| DRIFT | file.md | Version/name mismatch with code |
| OK | — | "All checks passed" (only if truly clean) |

Sort by severity: CONFLICT > STALE > DRIFT > MISSING > OK.

Then ask: "Which issues should I fix now?"
