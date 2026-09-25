#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved.  A relative
# invocation resolves `${BASH_SOURCE[0]}` against the shell's actual cwd, which
# a backgrounded `cd <worktree> && sh scripts/…` does not reliably set — so a
# gate can grade another worktree's diff and look entirely normal doing it.
# Invoke backgrounded gates by ABSOLUTE path.
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
  # The resources artefact (day8/re-frame2-resources,
  # EP-0003) ships its own JVM `:test` alias (the resource lifecycle FSM,
  # the work-ledger substrate, managed-HTTP lowering, invalidation/GC/
  # owners, mutations, and the routing + SSR/hydration integrations under
  # test-only :local/root deps). Listed like every sibling per-feature
  # artefact, so a resources-only change runs the JVM tier locally; it
  # pairs with the jvm-resources PR-CI job (test.yml).
  implementation/resources
  # The build-time reader for committed spec/ data
  # (day8/re-frame2-spec-resource). Its `:test` alias is the DETERMINISTIC
  # control for the cold-load race that reader exists to close: it holds
  # the interned-but-unbound window open and drives two independent
  # resolver sites into it, so the racy shape fails on an assertion rather
  # than on thread scheduling. This is the one suite in the repo whose
  # green is evidence about that defect — the surrounding lanes stay green
  # with the race present.
  implementation/spec-resource
  # The adversarial-property security tier is `.cljc` and
  # advertises a cross-runtime contract (e.g. `re-frame.security.gen`'s
  # JVM `:clj` `long`-multiply vs the CLJS `Math.imul` arm, pinned by
  # `gen-parity-security-cljs-test`). Its own `:test` alias
  # (implementation/security/deps.edn) runs the SAME `.cljc` namespaces
  # under the JVM so a divergence between the two reader-conditional arms
  # goes RED here — the CLJS side (`npm run test:security` + the always-on
  # `:node-test` gate) exercises only the `:cljs` arm.
  implementation/security
  # The EP-0011 cross-family reply-VOCABULARY-
  # consistency tier is `.cljc` and runs in BOTH runtimes (it round-trips
  # work-id tuples through the reader-conditional EDN reader: `:clj`
  # `read-string` vs `:cljs` `cljs.reader/read-string`). Its own `:test`
  # alias (implementation/reply-conformance/deps.edn) runs the SAME `.cljc`
  # namespaces under the JVM so the `:clj` arm is exercised — the CLJS side
  # (the always-on `:node-test` gate) runs only the `:cljs` arm.
  implementation/reply-conformance
  # The EP-0014 derivation/process-ALGEBRA conformance tier
  # is `.cljc` and runs in BOTH runtimes. Its own `:test` alias
  # (implementation/derivation-conformance/deps.edn) runs the SAME `.cljc`
  # namespaces under the JVM so the `:clj`-side composition (the pure-data
  # algebra views, the whole-value law over JVM data) is exercised — the
  # CLJS side (the always-on `:node-test` gate) runs only the `:cljs` arm.
  # The suite supplies an EXPLICIT contributor map on BOTH
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
  # The EP-0018 one-form event-MODEL conformance tier is
  # `.cljc` and runs in BOTH runtimes. Its own `:test` alias
  # (implementation/event-conformance/deps.edn) runs the SAME `.cljc`
  # namespaces under the JVM so the `:clj`-only arms are exercised: the
  # `^:no-doc`-meta facade probe (which reads `(meta (var …))` over the
  # public `re-frame.core` vars — JVM-only, since CLJS has no runtime
  # vars) and the `:clj` host throw type (`clojure.lang.ExceptionInfo`)
  # for the retired-name removal errors — the CLJS side (the always-on
  # `:node-test` gate) runs only the `:cljs` arm. It locks the
  # one-form event PUBLIC contract: `reg-event` as the single form with
  # reg-event-fx semantics, the three retired names as throwing stubs
  # raising their exact hard errors (production-survivable), the single
  # `:rf/event-handler` wrapper (no `:event/kind`), and frame-scoped event
  # routing (an image-loaded frame routes to its image, an image-less frame
  # to the global registrar). There is no realm-routing: EP-0023/EP-0024
  # retire the EP-0013 multi-realm substrate.
  implementation/event-conformance
  # The test-runtime quiet-reporter artefact
  # (day8/re-frame2-test-quiet) ships its own JVM `:test`
  # alias whose test tree is the ADVERSARIAL CONTRACT suite for the JVM
  # runner itself (test_quiet_runner_contract_test.clj — subprocess
  # deftests — plus test_quiet_pin_test.clj / ..._pin_passing_test.clj).
  # It pins the JVM RED/ERROR/exit-code paths, the discovery-banner
  # overdrop guards, nested-run banner+tally, the test-ns-hook fallback,
  # and the central stderr buffer + red-replay. Every OTHER artefact's
  # `:test` alias merely ROUTES THROUGH the quiet runner (green
  # happy-path), so without this line and its CI twin a regression in the
  # JVM red/error/banner/stderr behaviour would ship green. Listed last
  # because it relaunches a fresh JVM per deftest (slowest artefact),
  # matching the dedicated `jvm-test-quiet` PR-CI job (test.yml).
  implementation/test-quiet
  # The Fresco view substrate, and the ONE thing it runs on the
  # JVM: `re-frame.fresco.slot-cljs-test`, the `.cljc` equivalence pin for
  # the canonical slot rule, against the package's own `impl/slot.cljc`.
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
  # This entry pairs with the `jvm-fresco` job in `.github/workflows/test.yml`
  # (unconditional, in `all-required-passed`'s `needs:`), because
  # `check_jvm_lane_rosters.py` R1/R2 refuse either half alone. The fresco
  # lint export is not a JVM suite: it is gated by `lint.yml`'s required
  # `clj-kondo` job and by `npm run test:fresco-lint`.
  implementation/fresco
)

for artefact in "${artefacts[@]}"; do
  printf '==> JVM %s\n' "$artefact"
  if ! (cd "$repo_root/$artefact" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$artefact" "$artefact" >&2
    exit 1
  fi
done

printf 'PASS implementation JVM artefacts\n'
