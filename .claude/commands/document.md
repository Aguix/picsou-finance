Write or update the technical note for the feature I just worked on.

Follow this process strictly:

1. Identify the feature from the recent changes (git diff or files I modified in this session)
2. Read the template at docs/templates/FEATURE.md
3. Check if a note already exists in docs/features/ for this feature
   - If yes: read it, then UPDATE it with the new changes
   - If no: CREATE a new one from the template
4. Fill every section based on the ACTUAL code — not assumptions:
   - Context: why this feature exists
   - How it works: flow, components, key files
   - Technical choices: what was chosen and why, rejected alternatives
   - Gotchas: non-obvious behaviors, edge cases, things that break if touched carelessly
   - Tests: which test files cover this feature
5. Update docs/INDEX.md — add or update the feature entry in the table
6. If any significant technical decision was made during this work, ask me if I want to create an ADR for it

Keep it concise. No filler. Only useful content that will help future sessions understand this feature without reading all the code.
