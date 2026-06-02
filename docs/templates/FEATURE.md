# Feature: [Feature name]

> Last updated: [YYYY-MM-DD]

## Context

[Why this feature exists. What problem it solves. 2-3 sentences max.]

## How it works

[Technical explanation of how it works. Flows, components involved, interactions.]

### Key files

- `src/.../XxxController.java` — API entry point
- `src/.../XxxService.java` — business logic
- `src/.../XxxRepository.java` — data access

### Flow

```
[Simple text diagram of the flow if relevant]
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| [E.g., Stream API] | [Performance on large volumes] | [Classic loop] |

## Gotchas / Pitfalls

- [What's not obvious from reading the code]
- [Edge cases handled and how]
- [Things that break if you touch them without understanding]

## Tests

- `XxxServiceTest` — service unit tests
- `XxxIntegrationTest` — integration tests with DB

## Links

- Related ADR: [link to docs/decisions/ if applicable]
- Ticket: [Jira/Linear link if applicable]
