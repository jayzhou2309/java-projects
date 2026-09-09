# Project working instructions

## Documentation required with code changes

- Whenever adding or changing code, update a relevant Markdown document in `Documentations/` or create a new progress document in the same change.
- Record the date, purpose, affected files, implementation status, and validation performed (including checks not run).
- List every method added or changed, its class, inputs and outputs, and the function it serves. For changes without methods, describe the affected configuration, schema, or behavior instead.
- Separate implemented behavior from proposals and pending work. Never mark untested behavior as verified.
- Keep `Documentations/MultiAgentPlan.MD` progress aligned with completed milestones.

## Product direction

The governing product specification is `Documentations/PRD_Stock_Recommendation_Engine_v3.md`. Base all new development on that document. `src/main/java/project/ragdemo/PRD.MD` is superseded historical context. Follow `Documentations/MultiAgentPlan.MD` for delivery progress; the plan is not evidence that proposed components are implemented. Recommend-only is the default; execution remains a separate, opt-in, human-approved capability, initially paper/demo only. A product specification is not authorization to submit real trades.
