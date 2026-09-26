# First GitHub pilot: case-sensitive idempotency keys

Select job-intake-reliability and assign this task:

Add focused regressions proving that `Order-A` and `order-a`, with identical payloads,
create distinct jobs and each replays to its own original ID. Exercise both the memory
HTTP backend and real PostgreSQL using the existing integration test classes. Inspect
existing coverage and avoid duplicating assertions already present. Preserve existing
full-capacity replay/conflict tests.

Modify only the relevant test files, plus a short evidence document if helpful. Do not
change production logic, migrations, dependencies, or CI. Demonstrate in a disposable
copy that normalizing keys to lowercase causes the new test to fail for the intended
reason; restore/omit that mutation from the final diff. Run ./test.sh and the real
PostgreSQL suite where available. Open one draft PR with commands, results, limitations,
and a beginner-readable explanation. Do not merge.

Acceptance gate:
- Both backends distinguish differently cased keys and preserve each replay ID.
- A lowercasing defect is caught by the new regression, not merely a compiler failure.
- Existing tests remain intact and full PostgreSQL PR CI passes on the final commit.
- Diff stays in scope, without new dependencies or intentional defects.
- Review the diff and evidence before merge; agent instructions are not access controls.

Efficiency evaluation: record session duration, usage shown by GitHub, changed lines,
number of corrective follow-ups, and whether any scope violations occurred. Target one
small PR and no more than one corrective follow-up. These are pilot targets, not measured
performance guarantees. No scheduled runs or multiple agents are needed.

Prerequisites: verify the account's paid Copilot entitlement and repository agent setting,
merge the profile through a reviewed setup PR, and select it in the Agents dropdown.
Confirm JDK 21 and dependency download access in the agent environment; arrange isolated
PostgreSQL there or explicitly leave that validation to existing PR CI. Do not assume
the Actions service container is automatically available inside an agent session.
