#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved (rf2-g2mxd).  A relative
# invocation resolves `${BASH_SOURCE[0]}` against the shell's actual cwd, which
# a backgrounded `cd <worktree> && sh scripts/…` does not reliably set — so a
# gate can grade another worktree's diff and look entirely normal doing it.
# See scripts/test-fast-pr.sh for the incident; invoke backgrounded gates by
# ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

artefacts=(
  implementation/core
  implementation/adapters/reagent
  implementation/adapters/reagent-slim
  implementation/adapters/uix
  implementation/adapters/test-react
  implementation/schemas
  implementation/machines
  implementation/routing
  implementation/flows
  implementation/http
  implementation/ssr
  implementation/ssr-ring
  implementation/epoch
  # rf2-dxndhc — the resources artefact (day8/re-frame2-resources,
  # EP-0003) ships its own JVM `:test` alias (the resource lifecycle FSM,
  # the work-ledger substrate, managed-HTTP lowering, invalidation/GC/
  # owners, mutations, and the routing + SSR/hydration integrations under
  # test-only :local/root deps). It was omitted from this local rigorous
  # sweep even though every sibling per-feature artefact is listed; a
  # resources-only change therefore skipped the JVM tier locally. Added
  # here alongside the matching jvm-resources PR-CI job (test.yml).
  implementation/resources
  # The build-time reader for committed spec/ data
  # (day8/re-frame2-spec-resource). Its `:test` alias is the DETERMINISTIC
  # control for the cold-load race that reader exists to close: it holds
  # the interned-but-unbound window open and drives two independent
  # resolver sites into it, so the racy shape fails on an assertion rather
  # than on thread scheduling. This is the one suite in the repo whose
  # green is evidence about that defect — the surrounding lanes were all
  # green while it shipped, twice.
  implementation/spec-resource
  # rf2-gj2ae — the adversarial-property security tier is `.cljc` and
  # advertises a cross-runtime contract (e.g. `re-frame.security.gen`'s
  # JVM `:clj` `long`-multiply vs the CLJS `Math.imul` arm, pinned by
  # `gen-parity-security-cljs-test`). Its own `:test` alias
  # (implementation/security/deps.edn) runs the SAME `.cljc` namespaces
  # under the JVM so a divergence between the two reader-conditional arms
  # goes RED here — previously only the CLJS side (`npm run test:security`
  # + the always-on `:node-test` gate) ever exercised the tier.
  implementation/security
  # rf2-wbh1ln + rf2-5ha70w — the EP-0011 cross-family reply-VOCABULARY-
  # consistency tier is `.cljc` and runs in BOTH runtimes (it round-trips
  # work-id tuples through the reader-conditional EDN reader: `:clj`
  # `read-string` vs `:cljs` `cljs.reader/read-string`). Its own `:test`
  # alias (implementation/reply-conformance/deps.edn) runs the SAME `.cljc`
  # namespaces under the JVM so the `:clj` arm is exercised — previously
  # only the CLJS side (the always-on `:node-test` gate) ever ran the tier.
  implementation/reply-conformance
  # rf2-jn7frs — the EP-0014 derivation/process-ALGEBRA conformance tier
  # is `.cljc` and runs in BOTH runtimes. Its own `:test` alias
  # (implementation/derivation-conformance/deps.edn) runs the SAME `.cljc`
  # namespaces under the JVM so the `:clj`-side composition (the pure-data
  # algebra views, the whole-value law over JVM data) is exercised —
  # previously only the CLJS side (the always-on `:node-test` gate) ever
  # ran the tier. The suite supplies an EXPLICIT contributor map on BOTH
  # hosts, so it deliberately does NOT exercise JVM `default-contributors`
  # auto-resolution; that path is core's, pinned by
  # `default-contributors-resolves-every-jvm-sibling` and
  # `default-contributors-wires-the-machine-selector-targets-surface` in
  # implementation/core/test/re_frame/derivation_graph_test.clj. It
  # proves the four EP-0014 laws (lowering / storage+eval+lifecycle
  # classification / graph edges / whole-value) across all five families
  # — subscriptions, flows, resources, route facts, machines — through
  # the graph composer.
  implementation/derivation-conformance
  # rf2-xhfxcs.6 — the EP-0018 one-form event-MODEL conformance tier is
  # `.cljc` and runs in BOTH runtimes. Its own `:test` alias
  # (implementation/event-conformance/deps.edn) runs the SAME `.cljc`
  # namespaces under the JVM so the `:clj`-only arms are exercised: the
  # `^:no-doc`-meta facade probe (which reads `(meta (var …))` over the
  # public `re-frame.core` vars — JVM-only, since CLJS has no runtime
  # vars) and the `:clj` host throw type (`clojure.lang.ExceptionInfo`)
  # for the retired-name removal errors — previously only the CLJS side
  # (the always-on `:node-test` gate) ever ran the tier. It locks the
  # one-form event PUBLIC contract: `reg-event` as the single form with
  # reg-event-fx semantics, the three retired names as throwing stubs
  # raising their exact hard errors (production-survivable), the single
  # `:rf/event-handler` wrapper (no `:event/kind`), and frame-scoped event
  # routing (an image-loaded frame routes to its image, an image-less frame
  # to the global registrar). The EP-0013 multi-realm substrate was retired
  # under EP-0023/EP-0024 (rf2-afdlyr / rf2-tu2vr7) — no realm-routing here.
  implementation/event-conformance
  # rf2-jg1ag2 — the test-runtime quiet-reporter artefact
  # (day8/re-frame2-test-quiet, rf2-try1x) ships its own JVM `:test`
  # alias whose test tree is the ADVERSARIAL CONTRACT suite for the JVM
  # runner itself (test_quiet_runner_contract_test.clj — ~24 subprocess
  # deftests — plus test_quiet_pin_test.clj / ..._pin_passing_test.clj).
  # It pins the JVM RED/ERROR/exit-code paths, the discovery-banner
  # overdrop guards, nested-run banner+tally, the test-ns-hook fallback,
  # and the central stderr buffer + red-replay. Every OTHER artefact's
  # `:test` alias merely ROUTES THROUGH the quiet runner (green
  # happy-path), so before this line the contract suite ran in NO
  # automated gate — a regression in the JVM red/error/banner/stderr
  # behaviour shipped green. Listed last because it relaunches a fresh
  # JVM per deftest (slowest artefact), matching the dedicated
  # `jvm-test-quiet` PR-CI job (test.yml).
  implementation/test-quiet
  # rf2-ipx7h — the Hicasso view substrate, and the ONE thing it runs on the
  # JVM: `re-frame.hicasso.slot-cljs-test`, the `.cljc` equivalence pin for
  # the canonical slot rule (rf2-ani6y), retargeted onto the package's own
  # `impl/slot.cljc` when the bench tree left (rf2-6c12m.1). Measured here:
  # 3 tests, 92 assertions, ~5s.
  #
  # THE PIN IS THE WHOLE REASON THE LANE EXISTS. `impl/slot.cljc` has exactly
  # one definition of `prop-name`, and the two ways one definition still answers
  # two things — a `#?(:clj …:cljs …)` reader conditional inside it, and a
  # host-differing primitive like the JVM's locale-sensitive `str/upper-case` —
  # are invisible to any single host. So the same corpus is asserted twice
  # against that one implementation: once by `npm run test:cljs` in Node, once
  # by `clojure -M:test` here. A lane that runs only one arm does not merely
  # halve the coverage, it deletes the mechanism.
  #
  # Every OTHER suite the artefact owns is CLJS — the runtime is React — so this
  # is a one-namespace lane and is expected to stay small. The `:test` alias
  # carries NO `--probe`: it takes the runner's test-count floor, so if the pin
  # ever stops being discovered the lane reds instead of passing empty.
  #
  # This entry landed WITH the `jvm-hicasso` job in `.github/workflows/test.yml`
  # (unconditional, in `all-required-passed`'s `needs:`), because
  # `check_jvm_lane_rosters.py` R1/R2 refuse either half alone. It replaces a
  # "NOT HERE, ON PURPOSE" note that justified the exclusion by naming a JVM
  # suite `re-frame.hicasso.lint-export-test`: `140620d291` added that deftest,
  # `dd9f31bbc4` replaced it with `scripts/check_lint_export.py` and restored
  # `--probe`, and the note was left describing a world that no longer existed.
  # The lint export is still gated by `lint.yml`'s required `clj-kondo` job and
  # by `npm run test:hicasso-lint`; it is not a JVM suite and never was one.
  implementation/hicasso
)

for artefact in "${artefacts[@]}"; do
  printf '==> JVM %s\n' "$artefact"
  if ! (cd "$repo_root/$artefact" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$artefact" "$artefact" >&2
    exit 1
  fi
done

printf 'PASS implementation JVM artefacts\n'
