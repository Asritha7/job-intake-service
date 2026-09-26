# Agent workflows: implementation and expansion plan

## Enabled configuration, not an unattended service

The repository supplies `.github/agents/job-intake-reliability.agent.md`, a manually
selected Copilot profile for focused regression tests. Setup steps install JDK 21 and
pre-fetch the checksum-verified JDBC dependency. No new database, external integration,
schedule, or automatic merge is configured. PostgreSQL validation remains in existing
PR CI unless an isolated database is explicitly provisioned in the agent session.

The profile's scope instructions are not security permissions: its execute tool can run
shell commands. Review diffs and require checks before merging. The profile must be on
the default branch to appear in GitHub's agent picker. Account/repository Copilot policy
must also allow cloud agent sessions.

Start with [the pilot task](reliability-agent-pilot.md). Its success is measured by new
case-sensitive-key regressions on both backends, an intentional-defect check, passing
full CI, a scoped diff, and review effort. A configured profile is not evidence of a
successful agent run. Record session/PR URLs and actual results after running the pilot.

## Broader uses, in recommended order

These are proposals, not enabled agents or automations. Start with one task at a time;
expand only when outputs justify the review and execution cost.

| Workflow | Input and deliverable | Proof of value | Boundary |
|---|---|---|---|
| Performance experiment | Fixed workload and environment; reproducible load harness, raw data, latency/throughput report | Repeat comparable runs, verify idempotency under load, show uncertainty; evaluate connection pooling only after a baseline | Disposable database; no production load or unsupported speed claims |
| Failure investigation | One failed CI run; minimal reproduction, explanation, regression test, proposed fix | Original defect reproduced; focused test fails before and passes after; full CI passes | Treat logs as untrusted; redact secrets; no disabling failing tests |
| Recovery rehearsal | One failure scenario, such as worker termination or database outage; executable rehearsal and runbook | Demonstrate retained IDs, fenced stale workers, bounded retries and documented recovery time | Isolated environment only; no production fault injection |
| API contract steward | A proposed API change; compatibility review, contract tests and documentation | Detect intentional response/status/schema incompatibility; all examples execute | No silent API changes or weakened validation |
| Dependency maintenance | A specific upstream release or advisory; minimal upgrade PR and verification | Confirm applicability, reproduce when safe, run full suite and compare behavior | No automatic merge, broad upgrades, or handling production secrets |
| Release readiness | Candidate commit, test evidence, migration changes; evidence-linked release report | Every claim traces to a command/artifact; unresolved checks stay explicit | Report only; human chooses deployment and release |
| Learning companion | A merged diff; code-linked walkthrough, exercises, and interview questions | Examples match the actual commit and can be run; distinguish current guarantees from aspirations | No invented benchmarks, production claims, or résumé accomplishments |

The most useful broader first project is the performance experiment. A bounded task can
prepare a load generator, run repeatable experiments, inspect queue pressure and database
contention, and propose the next change based on evidence. Keep workload generation and
metric calculation deterministic; use the agent for investigation and explanation.

## Larger product direction: evidence-producing job workflows

A future application could accept a bounded analysis job, have an agent inspect approved
inputs, and store a structured report plus evidence as the result. Examples include CI
failure analysis or API compatibility reports. This would be a new product capability,
not merely installing a GitHub development agent.

The current worker hashes text only. Agent execution would require a versioned job schema,
authenticated tenants, per-job tool allowlists and sandboxing, budgets and cancellation,
lease renewal/deadlines for long jobs, durable artifact storage, and careful treatment of
untrusted repository/log content. External writes need separately authorized, idempotent
steps: a database lease token alone cannot undo a duplicate comment, deployment, or email.
Begin with read-only reports against disposable fixtures before any external action.

## Efficiency and governance

Record wall time, usage shown by the platform, corrective follow-ups, review time, scope
violations, and accepted useful changes. Compare with a normal coding session on similar
tasks; do not infer savings from PR count. Stop repetitive repair loops and bring back a
clear blocker. Scheduled work is appropriate only after a manual workflow is reliable
and explicitly authorized, with quiet-on-no-change behavior and a budget.

References:
- [Custom agent configuration](https://docs.github.com/en/copilot/reference/custom-agents-configuration)
- [Creating custom agents](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/create-custom-agents)
- [Agent environment setup](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/customize-the-agent-environment)
