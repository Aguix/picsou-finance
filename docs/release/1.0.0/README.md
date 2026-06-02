# Picsou 1.0.0 — Release documentation suite

This folder contains the IEEE-style documentation set for Picsou 1.0.0.
Each document targets a different audience:

| Doc | Audience | Answers |
|-----|----------|---------|
| [SRS](./SRS.md) — Software Requirements Specification | Product, contributors | **What** Picsou must do |
| [SDD](./SDD.md) — Software Design Description | Architects, contributors | **How** the architecture realises it |
| [SDS](./SDS.md) — Software Detailed Specification | Implementers | Component-level interfaces, REST contract |
| [STP](./STP.md) — Software Test Plan | Release engineers | How a build is verified |
| [User Manual](./USER_MANUAL.md) | End-users | Day-to-day usage of every feature |

These documents consolidate content from the working `docs/features/`,
`docs/decisions/`, and `docs/conventions/` folders into a stable
release deliverable. Update them when Picsou ships a new
major/minor version; do not edit them between releases.
