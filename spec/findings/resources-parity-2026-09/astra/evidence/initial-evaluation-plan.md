# Resources evaluation plan

Initial criteria recorded before experimental results on 2026-09-14. Preserve this version in `evidence/initial-evaluation-plan.md` before refining the protocol. Compare ordinary SPA outcomes; importance is not a numerical weight.

| ID | Job | Importance | Passing outcome |
|---|---|---|---|
| R01 | First useful read and next feature | Essential | A fresh consumer can follow public instructions to render data; additional reads and writes have a discoverable path. |
| R02 | Identity, cache reuse and deduplication | Essential | Same key shares one in-flight request; distinct params/scopes remain separate; fresh reads reuse data. |
| R03 | Demand, ownership and navigation | Essential | Work has a useful lifetime; another consumer retains its entry; departed identities cannot corrupt current projections. |
| R04 | Refresh and failure with existing data | Essential | Stale data remains usable and refresh failure is visible; default and matched freshness policies are distinguished. |
| R05 | Retry, focus, reconnect and polling | Important | Documented policies recover without accidental duplicate writes; transport and cache ownership are explicit. |
| R06 | Mutation results and cross-view consequences | Essential | Accepted writes update affected views, membership and counts; completion drives the next workflow step. |
| R07 | Optimistic writes under overlap | Essential | Fast feedback, truthful rejection and eventual authoritative convergence under controlled completion order. |
| R08 | Paged navigation and previous data | Important | Changing params preserves a useful view without representing old data as new. |
| R09 | Infinite feeds | Important | Load-more, terminal state and refresh obey one coherent page model; retention limitations are explicit. |
| R10 | Viewer and unresolved-session isolation | Essential | Alice's delayed result never appears in Bob's read; correct requests still work. |
| R11 | SSR and hydration | Important | Request isolation, projection and freshness hold across a real serialization boundary. |
| R12 | Persistence and offline writes | Important | A documented supported route or an explicit parity gap, including restart and conflict limits. |
| R13 | Push, cross-tab and entity coherence | Important | Existing integrations are credited; broad parity gaps are not hidden by Conduit's narrower needs. |
| R14 | Schema, editor and contract changes | Important | Parameter/key changes are discoverable and understandable with ordinary tools; runtime validation and static inference are distinct. |
| R15 | Diagnose, repair and preserve a regression | Essential | A real application mistake is observable, its repair passes, and reintroducing the same fault makes the assertion fail. |
| R16 | Reproduction and assistant authoring | Important | Public declarations, tools and fixtures support an actual repair or authoring task without hidden runtime assumptions. |

For every row retain separate contract, implementation, discoverability, observed behavior and comparative judgment. Evidence labels: executed, source-traced, documented, inferred, unverified. A passing internal test does not establish a browser journey or ergonomic advantage.

The initial executable set uses the Resources module's focused existing suites, Conduit's deterministic backend and a compact TanStack counterpart. Record source hashes and command exit codes. For key claims, add specific fault controls in isolated research fixtures. Establish the request ledger before asserting deduplication; control response ordering rather than waiting an arbitrary interval. Compare default policies, then equivalent explicit policies. Keep SSR, infinite-feed and classified-export checks at their actual tested layer.

Research runs may read shared source but must keep their fixture, output, ports and processes separate. No product changes or permanent benchmark infrastructure. Record setup failures as failures of the attempted route, not library-wide absence. Runtime and UI evidence will be marked separately in the final execution table.
