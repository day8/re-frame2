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
  # rf2-vxgfnd.1 — the re-frame.ui compiled-view substrate (epic
  # rf2-vxgfnd). S1a skeleton: the :test alias runs the classpath probe
  # (re-frame.ui.skeleton-cljs-test's :clj arm) so the artefact's JVM
  # side is exercised from day one; the compiler-slice suites (S1b+,
  # where the .cljc compiler genuinely runs on the JVM) grow here.
  implementation/ui
  # rf2-drpa3.15 — the Freehand view substrate (EP-0036). F1a skeleton:
  # the :test alias runs the classpath probe
  # (re-frame.freehand.skeleton-cljs-test's :clj arm) so the artefact's
  # JVM side is exercised from day one — the F1 spine needs a JVM tree
  # emitter alongside the React one, so the JVM arm is load-bearing here
  # from the first commit rather than bolted on later.
  implementation/freehand
  # rf2-ftmh0 — the CI teeth for examples/ui/minimal-counter. The required
  # `ui-scaffold-smoke` job runs this artefact's `clojure -M:test` with
  # RF2_UI_SCAFFOLD_COMPILE=1, but it sat on NO roster, so a scaffold-only
  # change armed nothing locally. Listed WITHOUT the env var on purpose: the
  # structural tier (7 tests, ~3s, no Node needed) is what belongs in a local
  # sweep, while the install+compile proof and the per-file omission matrix
  # — which shell out to `npm install` + `npx shadow-cljs compile app` — stay
  # CI's job. Set RF2_UI_SCAFFOLD_COMPILE=1 to arm those locally too.
  implementation/freehand/scaffold-smoke
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
  # namespaces under the JVM so the `:clj`-side composition (JVM
  # `default-contributors` auto-resolution, the pure-data algebra views,
  # the whole-value law over JVM data) is exercised — previously only the
  # CLJS side (the always-on `:node-test` gate) ever ran the tier. It
  # proves the four EP-0014 laws (lowering / storage+eval+lifecycle
  # classification / graph edges / whole-value) across all five families
  # through the graph composer.
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
  # rf2-hic-022 — the Hicasso artefact was CORRECTLY absent from this roster
  # until its lint export landed: the runtime requires React, so every suite
  # it owned was CLJS on the Node lane, and its `:test` alias was a `--probe`
  # classpath check with the test-count floor waived. That premise no longer
  # holds. `re-frame.hicasso.lint-export-test` runs clj-kondo IN PROCESS over
  # the artefact's own `resources/clj-kondo.exports/` tree — no React, no DOM,
  # no CLJS compile — and asserts that each of the six checks fires on its
  # positive fixture and that correct code stays silent. It is pure JVM work
  # and this is the only roster that can reach it. Listed after test-quiet
  # because it resolves a clj-kondo jar on first run.
  #
  # NOTE (rf2-hic-022): there is no `jvm-hicasso` job in `.github/workflows/
  # test.yml`, so PR CI does NOT run this suite — `.github/workflows/**` is
  # hot-zone and sequential, and adding one is a separate scheduling decision.
  # What CI *does* gate is the export itself: `.clj-kondo/config.edn` points
  # `:config-paths` at it, so the required `clj-kondo` job in `lint.yml` loads
  # these hooks over the whole lint surface and reds if they break or start
  # firing on real code.
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
