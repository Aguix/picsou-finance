I need to record a technical decision.

Follow this process:

1. Ask me what decision was made (if I didn't already specify it as an argument: $ARGUMENTS)
2. Read the template at docs/templates/DECISION.md
3. Check docs/decisions/ for any existing ADR on the same topic
   - If one exists: ask me if this supersedes it
4. Create the ADR file as docs/decisions/YYYY-MM-DD-[topic-in-kebab-case].md
5. Fill it based on our conversation:
   - Context: why this came up
   - Decision: what was decided
   - Alternatives: ask me what else was considered if I didn't say
   - Reasoning: why this option won
   - Trade-offs: what we sacrifice
6. Update docs/INDEX.md — add the decision entry in the table
7. If it supersedes an existing ADR, update the old one's status to ❌ Superseded

Use today's date. Keep it concise.
