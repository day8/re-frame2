#!/usr/bin/env bash
set -euo pipefail

# Conservative changed-surface classifier for PR CI tiering.
# Usage:
#   .github/scripts/report-changed-surfaces.sh [--all] [path ...]
#
# With explicit paths, classify those paths. Without paths, derive the changed
# file list from the GitHub Actions event's ACCEPTED BASE, or from HEAD^
# locally. See the base-resolution block below for what "accepted base" means
# on each event shape.

force_all=false
declare -a explicit_paths=()
for arg in "$@"; do
  case "$arg" in
    --all) force_all=true ;;
    *) explicit_paths+=("$arg") ;;
  esac
done

if [ "$force_all" = true ]; then
  files="__ALL__"
elif [ "${#explicit_paths[@]}" -gt 0 ]; then
  files="$(printf '%s\n' "${explicit_paths[@]}")"
elif [ "${GITHUB_EVENT_NAME:-}" = "pull_request" ] && [ -n "${GITHUB_BASE_REF:-}" ]; then
  # Fetch the base branch BY NAME, with no depth cap (rf2-om08). Two things
  # make that the right shape. First, this fetch is load-bearing even though
  # the sole caller checks out at full depth: a pull_request checkout fetches
  # `+<sha>:refs/remotes/pull/N/merge` and never creates
  # refs/remotes/origin/<base>, so this is what puts that name in scope for the
  # three-dot diff below — and it costs almost nothing, because the objects are
  # already local from that checkout.
  # Second, a `--depth` cap here would not merely fetch too little, it would
  # DESTROY history: `git fetch --depth=N` converts a COMPLETE clone into a
  # shallow one, grafting the base branch at N and discarding exactly what
  # test.yml's `fetch-depth: 0` just paid for — the checkout its own comment
  # calls "what makes the base resolvable without a fetch: it can be
  # arbitrarily deep". A merge base past that graft then dies `fatal: no merge
  # base` (measured: exit 128). That fails CLOSED — the diff below is
  # unswallowed under `set -euo pipefail`, so the classify step reds rather
  # than classifying an empty change set and skipping every gate — but a
  # spurious red on a PR with an unusually distant merge base is still worth
  # not having. docs.yml's `Detect docs surface` step (rf2-ihfw) and lint.yml's
  # lint-surface classifier (rf2-4sl9) fetch their base the same uncapped way,
  # for the same reasons. (A `--depth=1` fetch of a SPECIFIC SHA under a
  # deliberately shallow checkout is a different and legitimate shape — see
  # portability.yml, which checks out at depth 2.)
  git fetch --no-tags origin "${GITHUB_BASE_REF}" >/dev/null 2>&1 || true
  # rf2-vxgfnd.137 — `--no-renames` is load-bearing, not cosmetic. Git rename
  # detection collapses a rename to its DESTINATION path only, so a pure rename
  # OUT of a classified surface (e.g. implementation/core/** -> docs/**) would
  # report only the unclassified destination and leave every gate for the
  # DELETED endpoint false — a CI false-green (the deleted production code
  # runs no browser/JVM/node gate). Disabling rename detection makes Git emit
  # both endpoints (old path as a deletion, new path as an addition), so the
  # classifier below arms the gates for BOTH surfaces. Every rename is thus
  # classified as delete + add; ordinary add/modify/delete are unaffected.
  files="$(git diff --no-renames --name-only "origin/${GITHUB_BASE_REF}...HEAD")"
else
  # rf2-34yg — THE EVENT'S ACCEPTED BASE, not HEAD^. One push event produces
  # ONE workflow run, at the tip. On a MULTI-COMMIT push HEAD^ is merely the
  # push's own second-to-last commit, so commits 1..N-1 are invisible to this
  # classifier and never get a run of their own — a rebase-merged PR of N
  # commits has its trunk run classified on commit N alone. This is the
  # rf2-7hq4l defect, already named and fixed twice here:
  # .github/workflows/portability.yml resolves the accepted base from
  # `github.event.before` and passes it to scripts/check-ai-tracking-ratchet.sh
  # in `AI_RATCHET_BASE_REF`; .github/workflows/post-merge-workflow-sanity.yml
  # does the same inline. This is the same idiom, one script over.
  #
  # MEASURED, on two real merges (both re-run in the PR that added this):
  #   * PR #8159's 4-commit push — tip was a docs file, so the push armed
  #     NOTHING. Whole push arms 6. Run 31742435731 reported "All required
  #     checks passed" across 78 jobs with every surface-gated one skipped.
  #   * ebd92e12d2 touched expensive-tests.yml AND TESTING.md, both `mark_all`
  #     triggers, behind a tip arming 0. Tip-only 0; whole push 32. The
  #     full-matrix run that editing TESTING.md exists to FORCE never happened.
  # It creates no exposure relative to the PR gate — each of those commits was
  # fully classified against `base...HEAD` on its own PR. What it degrades is
  # the POST-MERGE net: the run that catches a semantic conflict between two
  # PRs green alone, and that honours `mark_all` when CI's own configuration
  # changes.
  #
  # THE CALLER SUPPLIES THE REF, this script only consumes it — portability.yml's
  # split, and for its stated reason: context values reach the script through
  # `env:` and are never interpolated into a script body, so the step is
  # injection-safe whatever the event carries. test.yml maps push ->
  # `github.event.before`; a pull_request never reaches here (the branch above
  # takes it, unchanged, and its `base...HEAD` was always correct).
  #
  # TWO-DOT, matching post-merge-workflow-sanity.yml and the ratchet's
  # base-tree comparison. On the ordinary push the base is an ancestor of HEAD
  # and two- and three-dot agree; on a FORCE-PUSH they do not, and two-dot is
  # the conservative one — it reports the discarded commits' paths as changed
  # too, arming more rather than fewer gates.
  #
  # FIRST PUSH TO A NEW REF sends the all-zeros sentinel; force-push sends a
  # tip that may no longer be reachable. Both are handled below, and neither
  # silently returns to HEAD^ semantics without saying so.
  base_ref="${CHANGED_SURFACES_BASE_REF:-}"
  if [ "$base_ref" = '0000000000000000000000000000000000000000' ]; then
    # The first push to a fresh ref has no "before". post-merge-workflow-
    # sanity.yml folds this to HEAD^ and portability.yml folds it to empty so
    # the ratchet's own HEAD^ default stands; both land in the same place, and
    # so does this. There is no earlier state to have missed, so HEAD^ loses
    # nothing here.
    base_ref=''
  fi

  if [ -z "$base_ref" ]; then
    files="$(git diff --no-renames --name-only HEAD^ HEAD 2>/dev/null || git diff --no-renames --name-only HEAD)"
  else
    # The `^{commit}` peel is REQUIRED, not decorative (rf2-uol6, learnt by the
    # ai/ ratchet): for a full 40-hex sha `git rev-parse --verify --quiet`
    # echoes the string back with exit 0 WITHOUT consulting the object store,
    # so an absent base would sail through and `git diff` would then fail into
    # a `|| true`, classifying an empty change set as "nothing to run". Peeling
    # forces the lookup. Guarded so `set -e` does not abort before the message.
    base_sha="$(git rev-parse --verify --quiet "${base_ref}^{commit}" 2>/dev/null)" || base_sha=''
    if [ -n "$base_sha" ]; then
      files="$(git diff --no-renames --name-only "$base_sha" HEAD)"
    else
      # UNRESOLVABLE BASE — mark_all, and say so loudly.
      #
      # The realistic cause is a FORCE-PUSH to main: `before` is then the
      # DISCARDED tip, reachable from no ref, so even test.yml's fetch-depth: 0
      # checkout does not hold it. (portability.yml fetches its base by name
      # because it checks out at depth 2; the sole caller here is already a
      # full clone, so a fetch would buy only this one unreachable case and is
      # deliberately not attempted.)
      #
      # WHY mark_all AND NOT HEAD^. The ratchet's rule is that a guard which
      # cannot see its base must not certify anything — but a RATCHET fails
      # closed by exiting red, whereas a CLASSIFIER's failure mode is a false
      # GREEN: skipping gates. Arming everything is this script's fail-closed,
      # and it is the only branch here that cannot under-arm. Falling back to
      # HEAD^ would reproduce the very defect above, silently. Erroring out
      # would red the trunk on a legitimate if abnormal event; a force-push to
      # main is also exactly when the full matrix is worth running.
      printf 'report-changed-surfaces.sh: base ref %s does not resolve to a commit.\n' \
        "$base_ref" >&2
      printf 'Classifying as --all: a classifier that cannot see its base must not\n' >&2
      printf 'skip gates. Usual cause: a force-push, whose previous tip is now\n' >&2
      printf 'unreachable. See rf2-34yg.\n' >&2
      files="__ALL__"
    fi
  fi
fi

implementation_jvm=false
cljs_node_test=false
adapter_diagnostic=false
cljs_browser=false
examples_compile=false
cljs_prod=false
bundle_isolation=false
reagent_slim_bundle=false
adapter_testbed_smokes=false
tools_jvm=false
# rf2-wq17m — two artefacts with a wired `:test` alias and a slot on
# scripts/test-jvm-tools.sh's roster, but no PR-time CI lane until now. They get
# their OWN outputs rather than joining `tools_jvm`: that output gates FOUR
# jvm-tools-* jobs (xray / story / story-mcp / mcp-base), none of which runs
# either artefact, so setting it would fire four unrelated probes and STILL skip
# the files these outputs exist to reach.
tools_jvm_machines_viz=false
tools_jvm_testbed_support=false
# rf2-odlm3 — the artefact's OWN CLJS lane
# (tools/machines-viz/shadow-cljs.edn `:machines-viz-node-test`), which runs the
# `-test`-suffixed suites the consolidated `:node-test` build compiles and never
# selects. Its own output for the same reason as the two above: no existing
# output gates a job that runs this build.
tools_cljs_machines_viz=false
# rf2-8m344 - the read-only viewer PAGE (public/viewer.html + the
# `:machines-viz-viewer` bundle). Its own output for the same reason as the
# per-artefact outputs above: no existing output gates a job that BUILDS
# this bundle, and until this one existed no job did.
machines_viz_viewer_page=false
template_expensive=false
mcp_conformance=false
mcp_live=false
story_xray_browser=false
# rf2-65ajl — the FULL Story feature-load gate (`npm run test:story-feature-load`),
# as distinct from the PR-SMOKE tier `story_xray_browser` gates. Its own output
# because the two tiers run DIFFERENT COMMANDS: the smoke job runs
# `test:xray-feature-gate:smoke` + `test:story-play-scripts`, neither of which
# loads the full-gate runners. Arming `story_xray_browser` for a change to
# tools/story/test/story_feature_load.cjs would therefore schedule a job that
# still never executes the changed file. See is_story_full_gate_path below.
story_full_gate=false
# rf2-9n2cv — the Story STATIC-EXPORT gate (`npm run test:story-static`), a
# third tier again distinct from the two above, and for the same reason: it is
# a different COMMAND. `test:story-static` is `node scripts/check-story-static.cjs`
# — it builds the static export, serves it under an ownership token and drives
# Chromium at the published artefact. Neither smoke command runs it and neither
# does the full feature-load gate, so arming either of those outputs for a
# change to the static gate's own teeth would schedule a job that never
# executes the changed file. See the arm on check-story-static.cjs below.
story_static_gate=false
tenant_switcher_smoke=false
skills_structural=false
playground=false
migration_hicasso_codemod=false
hicasso_controlled=false
# rf2-hic-015 — the Hicasso HMR gate (`npm run test:hicasso-hmr`), a tier of
# its own for the same reason `hicasso_controlled` is: it is a DIFFERENT
# COMMAND, and the only one in the repo that drives a real hot reload. No
# other job's closure reaches it — every other browser gate serves a COMPILED
# bundle over http-server, and a compiled bundle cannot hot-reload itself.
hicasso_hmr=false
migration_v1_codemod=false
# rf2-n8vp — the ssr-node package's OWN gate (`npm run test:ssr-node`), and its
# own output for the reason the two migration-codemod outputs have theirs: no
# job any other output gates would run it. The package is plain CommonJS on
# `node:` builtins — no shadow-cljs build lists it, no `deps.edn` puts it on a
# classpath, and it declares no npm dependency (its own absence.test.cjs asserts
# all three) — so `cljs_node_test`, `implementation_jvm` and `cljs_browser`
# would each fire a tier that cannot reach a line of it and STILL leave its 73
# rows unrun. It landed (PR #8028) classifying to nothing at all: the arm below
# is the half that makes the schedule real.
ssr_node=false

mark_all() {
  implementation_jvm=true
  cljs_node_test=true
  adapter_diagnostic=true
  cljs_browser=true
  examples_compile=true
  cljs_prod=true
  bundle_isolation=true
  reagent_slim_bundle=true
  adapter_testbed_smokes=true
  tools_jvm=true
  tools_jvm_machines_viz=true
  tools_jvm_testbed_support=true
  tools_cljs_machines_viz=true
  machines_viz_viewer_page=true
  template_expensive=true
  mcp_conformance=true
  mcp_live=true
  story_xray_browser=true
  story_full_gate=true
  story_static_gate=true
  tenant_switcher_smoke=true
  skills_structural=true
  playground=true
  migration_hicasso_codemod=true
  hicasso_controlled=true
  hicasso_hmr=true
  migration_v1_codemod=true
  ssr_node=true
}

# rf2-k9ekz — predicate: does `$1` look like a Story/Xray runtime
# source file (CLJS/CLJC/JS/CSS extension under tools/{story,xray}/src/**
# or tools/{story,xray}/testbeds/**)? Returns 0 (yes) / 1 (no). The
# Story/Xray browser gate only fires on a runtime-relevant extension
# under one of those two trees — Markdown specs, EDN config, and JVM
# unit tests under tools/{story,xray}/{spec,test,bench}/** do not
# affect chrome and so do not fire the gate. Testbeds under
# tools/{story,xray}/testbeds/** legitimately drive the
# story-feature-load + xray-feature-gate Playwright runners (their
# variant graph IS what the gate exercises), so a runtime-extension
# change there does fire the gate.
#
# rf2-kttom — `.html` is on the list, and its absence was a real hole.
# The browser runners do not merely compile a testbed: each one COPIES the
# testbed's hand-written `index.html` into the served output dir and then
# navigates a browser to it (`stageTestbedHtml` in
# examples/scripts/serve-and-run-story-play-scripts.cjs and its
# feature-load sibling; the `:dev-http` roots in
# implementation/shadow-cljs.edn resolve the same file). So the served
# document IS a testbed source file — break its `<script src>`, its
# `#app` node or its charset and every play in that deck fails — yet an
# HTML-only change used to classify as no-surface and skip the gate
# entirely. It is a runtime path by the same test as the others: change
# it and the browser sees something different.
#
# rf2-uqf5q — with the SAME ONE named exception both sibling predicates
# below already carry: tools/story/src/re_frame/story/macros.clj. The
# extension guard here excludes `.clj`, which is right for a JVM consumer
# and wrong for the one CLJ namespace under either src tree that is a
# compile-time PRODUCER. `re-frame.story` delegates every public
# registration macro to it, so the CLJS a testbed's `(story/reg-story …)`
# / `(story/reg-variant …)` call site compiles to — and therefore what the
# Playwright deck actually renders — comes out of this file.
#
# MEASURED, NOT PREDICTED. story_xray_browser is this predicate's to arm,
# and it read false for macros.clj — so the browser-gates job skipped
# BOTH its tiers, the PR-smoke one and the full feature-load one, because
# story_full_gate arms only on the gate's own spec modules and was false
# too. Commit e1cbd089c4 (rf2-3xq1v) walked through exactly that hole: it
# routed re-frame.story.macros/coords-form through
# re-frame.source-coords/coords-form, which absolutises at
# macro-expansion time, changing the source-coord rendered in the Story
# pane. No browser gate ran, so nothing saw the pane it changed; it
# surfaced ~1.5 hours later only because two unrelated PRs happened to
# arm every surface, and it blocked both of them.
#
# Named rather than globbed, exactly as on the two predicates below: this
# is the only CLJ macro namespace either tool ships, and a second one
# should be a deliberate edit here rather than a silent widening of the
# browser matrix. An ordinary JVM `.clj` beside it keeps the general
# exclusion, and the testbed arms are untouched.
is_story_xray_runtime_path() {
  case "$1" in
    tools/story/src/re_frame/story/macros.clj)
      return 0 ;;
    tools/story/src/*|tools/xray/src/*|tools/story/testbeds/*|tools/xray/testbeds/*)
      case "$1" in
        *.cljs|*.cljc|*.js|*.cjs|*.css|*.scss|*.html)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    *)
      return 1 ;;
  esac
}

# rf2-f79t8 — predicate: does `$1` compile into the consolidated
# `:node-test` build? shadow-cljs.edn lists tools/{story,xray}/{src,test}
# as :source-paths, so a CLJS/CLJC change under those trees changes the
# node-test output and MUST fire the `cljs` job (which is gated on
# cljs_node_test). Markdown specs (tools/{story,xray}/spec/**), EDN
# config, and JVM-only `.clj` files there do NOT compile into node-test
# and so must not fire it — the same spec-md-exclusion philosophy the
# runtime-extension guard applies to the Playwright gate.
#
# rf2-eyyd2 — with ONE named exception. `tools/story/src/re_frame/story/
# macros.clj` is a JVM-only file that nonetheless changes node-test OUTPUT:
# it is the compile-time producer every `re-frame.story` registration macro
# delegates to, so the CLJS the consolidated build emits for a
# `(story/reg-variant …)` call site comes out of this file. The general
# `.clj` exclusion is right — an ordinary JVM unit test compiles into
# nothing — and this is the one CLJ namespace under either src tree that is
# a macro producer rather than a JVM consumer. Same reasoning arms it on the
# browser predicate below; see the long note there.
is_story_xray_node_test_path() {
  case "$1" in
    tools/story/src/re_frame/story/macros.clj)
      return 0 ;;
    tools/story/src/*|tools/xray/src/*|tools/story/test/*|tools/xray/test/*|tools/story/testbeds/*|tools/xray/testbeds/*)
      case "$1" in
        *.cljs|*.cljc)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    *)
      return 1 ;;
  esac
}

# rf2-1sd8h — predicate: can `$1` change what the `:browser-test` build
# (headless Chromium, `-dom-cljs-test$`) executes for Story/Xray? Returns
# 0 (yes) / 1 (no).
#
# WHY IT IS SEPARATE FROM THE NODE-TEST PREDICATE ABOVE. Both trees ride
# the SAME two builds — implementation/shadow-cljs.edn puts
# tools/{story,xray}/{src,test} on :source-paths, and the `:node-test`
# build's `cljs-test$` regex matches a `-dom-cljs-test` namespace just as
# the `:browser-test` build's `-dom-cljs-test$` does. So a Story DOM suite
# was already COMPILED at PR time — under Node, where it finds no
# `document` and SKIPS rather than mounting. The classifier fired only
# cljs_node_test for it, so the `cljs-browser` job — the one lane where
# these files can actually execute — reported SKIPPED while the PR's
# decisive regression test had run nowhere but the author's laptop
# (#7037). Same false-green shape rf2-vxgfnd.90 closed for
# implementation/ui and rf2-drpa3.70 for implementation/freehand, one tier
# out.
#
# Two shapes, deliberately not the whole tree:
#   * a DOM suite itself — tools/{story,xray}/test/**/*_dom_cljs_test.{cljs,cljc}.
#     The filename suffix and the declared namespace suffix are the same
#     token the `:browser-test` selector reads, so this arm cannot drift
#     from what the build selects.
#   * the runtime those suites mount — tools/{story,xray}/src/**.{cljs,cljc}.
#     The DOM suites are the designated boundary for Story's mounted
#     surfaces (presence flush, sub-override render, recorder DOM capture,
#     shell/test-mode/viewport/xray-embed) and Xray's (epoch panel rounding,
#     reactive data view, theme a11y, view walker); a src change reaches
#     them transitively, and the conservative direction on a browser gate
#     is to run it more, never to skip it.
#
# NOT the rest of the test tree: a `.clj` or a plain `.cljc` pure-data
# suite there cannot change what React puts on a page. NOT the testbeds
# either — they drive the Playwright gates (story_xray_browser), which the
# runtime-extension predicate above already owns, and no
# `-dom-cljs-test` namespace lives under them.
#
# rf2-eyyd2 — the two seams rf2-1sd8h left to the nightly matrix, closed
# because both turned out to be LIVE edges of the suites armed above, not
# hypotheticals.
#
#   * the SUPPORT HELPERS — tools/{story,xray}/test/**/test_helpers/**.
#     Take the require closure of the 14 live `*_dom_cljs_test` suites,
#     restricted to files under those two test trees, and it is exactly
#     three files, all of them in a `test_helpers/` directory:
#       - story .../test_helpers/e2e_multi_frame.cljs, required by
#         share_url_state_popstate_stale_override_dom_cljs_test;
#       - xray  .../test_helpers/e2e_multi_frame.cljs, required by
#         reactive_data_view_rows_dom_cljs_test AND by the story helper
#         above, which aliases it as `xray-e2e`;
#       - xray  .../test_helpers/host_fixtures/counter.cljs, required by
#         reactive_data_view_rows_dom_cljs_test.
#     Those helpers classified `cljs_browser=false`, so a breaking
#     helper-only PR ran the Node compile — where the importing DOM suites
#     find no `document` and self-skip — and skipped the one lane that
#     mounts them. The arm is the DIRECTORY rather than the three measured
#     files: `test_helpers/` is the established home for shared support in
#     both trees, so a DOM suite reaching for a fourth helper beside them
#     is armed on arrival instead of on the next audit. Extension-guarded
#     the same way as everything else here — a `.md` or `.edn` beside a
#     helper compiles into nothing.
#
#   * the MACRO PRODUCER — tools/story/src/re_frame/story/macros.clj, the
#     one `.clj` under either src tree. `re-frame.story` (a `.cljc` on both
#     builds' :source-paths) delegates EVERY public registration macro to
#     it — `expand-reg-story` / `gen-reg-call` at story.cljc:231-478 — so
#     its emitted forms are what four of the DOM suites compile when they
#     call `story/reg-story` / `story/reg-variant`. The extension guard on
#     the src arm below excludes `.clj`, so it classified false on BOTH
#     CLJS lanes: it is armed for the node one here too, since the same
#     expansion is what `:node-test` compiles. Named rather than globbed —
#     it is the only CLJ macro namespace either tool ships, and a second
#     one should be a deliberate edit here rather than a silent widening.
#
# Its NON-CLJS fan-out is unchanged and already correct: `tools/story/src/*`
# arms examples_compile, and the `tools/{story,xray}/*` case arms tools_jvm,
# mcp_conformance and template_expensive (the emitted-`:app` compile), for
# macros.clj as for any other src file.
is_story_xray_dom_test_path() {
  case "$1" in
    tools/story/test/*_dom_cljs_test.cljs|tools/story/test/*_dom_cljs_test.cljc)
      return 0 ;;
    tools/xray/test/*_dom_cljs_test.cljs|tools/xray/test/*_dom_cljs_test.cljc)
      return 0 ;;
    tools/story/test/*/test_helpers/*|tools/xray/test/*/test_helpers/*)
      case "$1" in
        *.cljs|*.cljc)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    tools/story/src/re_frame/story/macros.clj)
      return 0 ;;
    tools/story/src/*|tools/xray/src/*)
      case "$1" in
        *.cljs|*.cljc)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    *)
      return 1 ;;
  esac
}

# rf2-65ajl — predicate: is `$1` part of the FULL Story feature-load gate
# itself — the two Playwright spec modules `npm run test:story-feature-load`
# loads, or the run/serve orchestration that loads them? Returns 0 / 1.
#
# WHY IT IS NOT `story_xray_browser`. That output schedules the PR-SMOKE tier
# (rf2-wa3oo), whose two steps are `test:xray-feature-gate:smoke` and
# `test:story-play-scripts`. NEITHER command loads
# tools/story/test/story_feature_load.cjs or story_browser_scenarios.cjs — only
# `test:story-feature-load` does, and that command was nightly-only. So the gap
# had two independent halves and closing either alone left the hole open:
# the classifier did not arm on tools/story/test/** at all, AND the job the
# output schedules would not have run the changed file if it had. #7472 changed
# story_feature_load.cjs's COVERAGE_MATRIX, said in its body that CI would be
# the first end-to-end exercise of the demoted row, and merged with the job
# skipped (rf2-65ajl).
#
# The roster is the runner's own spec list plus the orchestration that stages
# and serves it:
#   * the two spec modules — `ALL_SPEC_FILES` in
#     examples/scripts/run-story-feature-load-tests.cjs. A test in
#     implementation/scripts/_changed-surfaces.test.cjs reads that array off the
#     runner and asserts every entry is armed here, so a third spec added to the
#     runner is armed on arrival rather than on the next audit.
#   * run-story-feature-load-tests.cjs — the runner: it selects the spec
#     modules, owns the per-spec timeout and the console/pageerror verdict.
#   * serve-and-run-story-feature-load-tests.cjs — the orchestrator: it compiles
#     the two Story testbeds, cleans + stages their HTML and serves them.
#   * story-feature-load-port.cjs — the dedicated port resolver both launchers
#     call before anything is compiled or served.
#
# Deliberately NOT the SHARED examples/scripts helpers (port-resolver.cjs,
# examples-staging.cjs, examples-asset-manifest.cjs). Each is already armed for
# `story_xray_browser`, and the PR-smoke tier RUNS `test:story-play-scripts`,
# which calls resolveStoryFeatureLoadPort + cleanStageDirs over the same staged
# output — so a break in a shared helper reds a job that already runs at PR
# time. Paying for the full gate on top would buy no new signal.
#
# Deliberately NOT the rest of tools/story/test/**: an ordinary JVM `.clj` or a
# CLJS unit suite there cannot change what the Playwright runner executes, and
# they have their own lanes (tools_jvm, cljs_node_test, cljs_browser).
is_story_full_gate_path() {
  case "$1" in
    tools/story/test/story_feature_load.cjs|tools/story/test/story_browser_scenarios.cjs)
      return 0 ;;
    examples/scripts/run-story-feature-load-tests.cjs|examples/scripts/serve-and-run-story-feature-load-tests.cjs|examples/scripts/story-feature-load-port.cjs)
      return 0 ;;
    *)
      return 1 ;;
  esac
}

# rf2-w9ip — predicate: is `$1` an INPUT to the in-bundle route-path census
# (`implementation/routing/test/re_frame/routing_path_census_test.clj`)?
# Returns 0 (yes) / 1 (no).
#
# THE FAIL-OPEN THIS CLOSES. The census is a JVM suite in the routing
# artefact, so the only job that runs it is `jvm-routing`, gated on
# `implementation_jvm`. Its inputs are the trees in its own `app-roots` —
# `examples`, `testbeds` and `implementation/hicasso/test` — and NONE of the
# three armed that output. Measured on main before this arm:
#
#   examples/reagent/todo/src/foo.cljs                      implementation_jvm=false
#   testbeds/foo.cljs                                       implementation_jvm=false
#   implementation/hicasso/test/.../examples/foo.cljs       implementation_jvm=false
#
# So the census could not fire on ANY edit it exists to police. A PR adding
# or renaming a route path reached main with the collision check never run —
# route paths are plain strings in the PROCESS-GLOBAL registrar and the
# shared node bundle loads a dozen applications into one process, so the
# first registration of a duplicate path wins every URL forever and the later
# app is unreachable for URL ingress. That breakage lands in a suite which has
# never heard of the new app, naming ITS routes; rf2-hic-025 cost twelve
# RealWorld assertions exactly that way. PR #8131 added two routes and would
# have been the same case — its worker ran the routing suite by hand and said
# so, which is not a mechanism.
#
# WHY A PREDICATE AND NOT AN ARM OF THE BIG `case`, the same reason rf2-65ajl
# gives above: a POSIX `case` takes the FIRST match, and all three roots
# already have arms there (`examples/*`, `implementation/hicasso/*`), so an
# arm would be shadowed. A predicate consulted for every file cannot be. It
# only ever SETS `implementation_jvm`, so it can narrow nothing.
#
# WHY `implementation_jvm` AND NOT AN OUTPUT OF ITS OWN. An output of its own
# would fire one job instead of twenty-two, which is the honest argument for
# it — but `scripts/test-fast-pr.sh` gates the local JVM tier on
# `implementation_jvm` too, so a second predicate would have to be taught to
# the spine as well and then held in step with this one. That is the very
# defect this bead reports (rf2-6ng7's class: a gate's inputs and its arming
# in two files with nothing holding them together), and buying one instance
# by manufacturing another is a bad trade. Reusing the existing output keeps
# CI and the local spine aligned BY CONSTRUCTION, which is the property
# TESTING.md's fast-spine section already claims.
#
# EXTENSIONS ARE THE CENSUS'S OWN. `source-files` filters `#"\.clj[sc]$"`, so
# a README, a `.cjs` harness or an `.edn` fixture under these trees is not an
# input and must not queue the JVM tier. Plain `.clj` is deliberately absent
# here for the same reason it is absent there.
#
# HELD IN STEP: `implementation/scripts/_changed-surfaces.test.cjs` reads
# `app-roots` out of the census source and asserts this predicate arms the
# lane for every root it finds. Add a root there and the assertion reds here
# until this list catches up — the roster is derived, not restated on trust.
is_route_path_census_input() {
  case "$1" in
    examples/*|testbeds/*|implementation/hicasso/test/*)
      case "$1" in
        *.cljs|*.cljc)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    *)
      return 1 ;;
  esac
}

if [ "$files" = "__ALL__" ]; then
  mark_all
else
  while IFS= read -r file; do
    [ -z "$file" ] && continue

    # rf2-gzavkm — compile every standalone example build only when the
    # changed surface can alter one of those builds. This is deliberately
    # separate from `cljs_browser`: core still gets its focused browser/node/
    # production gates at PR time, while the full example-build sweep moves
    # to the nightly safety net for core-only changes. Story/Xray and
    # machines-viz remain here because example builds load the Xray preload
    # and Story hosts, whose compiled closure includes machines-viz.
    #
    # rf2-in6c4 — the top-level testbed SOURCES joined this list when
    # check-examples-compile.cjs widened its derivation to `:testbeds/*`. Same
    # shape as the two widenings above, and the same reason: that gate is now
    # the only PR-time job that compiles those fifteen builds. Read the arm
    # below, not this list — `testbeds/*` is deliberately NOT here, because a
    # first-match `case` would then swallow the extension narrowing.
    case "$file" in
      # rf2-qxg24 — `implementation/security/*` is NOT here. It is a src-less
      # test partition (`:paths []`, `:deps {}`, no `src/` tree, no published
      # artefact), so nothing under it can appear in a compiled example
      # closure: the all-examples compiler has no edge to a test-only tree.
      # It was added to this arm by 5bb3cd9cd1 as a side effect — that commit
      # wanted `implementation_jvm` + `cljs_node_test` and reached for a
      # generic feature arm that happened to set five outputs — and the toll
      # was real: security-only commit d1fa5ff493 made the ~10-minute
      # all-examples job its critical path.
      examples/*|implementation/adapters/*|implementation/epoch/*|implementation/schemas/*|implementation/machines/*|implementation/routing/*|implementation/flows/*|implementation/http/*|implementation/ssr/*|implementation/ssr-ring/*|implementation/resources/*|implementation/deps.edn|implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json|implementation/scripts/check-examples-compile.cjs)
        examples_compile=true
        ;;
      # rf2-in6c4 — top-level testbed CLJS sources. The extensions are the
      # narrowing, and they are shadow's own: `testbeds/README.md`,
      # `testbeds/*/index.html` and `testbeds/spec-helpers.cjs` cannot change
      # what `shadow-cljs compile` produces, and this is a ~10-minute job. A
      # NEW testbed build still arms it whatever its files look like, because
      # declaring one edits `implementation/shadow-cljs.edn`, which is on the
      # roster above. (`.cljc` is listed for the same reason the route-path
      # census predicate lists it — a testbed is free to be reader-conditional
      # — and plain `.clj` is absent for the same reason it is absent there:
      # shadow does not compile it into a browser build. Re-measured at
      # rf2-in6c4's close: the tracked tree is 14 .md, 13 .html, 13 .cljs,
      # 2 .cjs, 1 .json and ZERO .clj, so an arm on that extension would fire
      # for no diff this repository can produce and compile nothing extra if
      # it did. Nesting was re-measured too — a POSIX `case` glob spans `/`,
      # so `testbeds/deep_machine/core.cljs` arms and `testbeds/README.md`,
      # `testbeds/spec-helpers.cjs` do not.)
      testbeds/*.cljs|testbeds/*.cljc)
        examples_compile=true
        ;;
      tools/story/src/*|tools/story/testbeds/*|tools/story/deps.edn|tools/xray/src/*|tools/xray/testbeds/*|tools/xray/deps.edn|tools/machines-viz/src/*|tools/machines-viz/deps.edn)
        examples_compile=true
        ;;
      # rf2-k8yl5f — the re-frame2-pair skill ships a dev-only preload
      # (skills/re-frame2-pair/preload/re_frame2_pair/{runtime.cljs,pure.cljc})
      # that shadow-cljs.edn adds as a :source-path
      # (../skills/re-frame2-pair/preload) and injects into ~28 :examples/*
      # dev builds via `:devtools :preloads [re-frame2-pair.runtime ...]`. A
      # `compile` (unlike `release`) HONOURS :devtools/preloads, so the
      # examples-compile coverage gate (npm run test:examples-compile →
      # shadow-cljs compile) actually compiles this preload for every build
      # that wires it — a preload-only change can therefore break that gate.
      # But the classifier previously armed ONLY skills_structural on this path
      # (the skills/re-frame2-pair/* case below), leaving examples_compile=false
      # so a preload break surfaced only in the unconditional nightly
      # examples-compile net (rf2-gzavkm follow-up), never at PR time. Arm
      # examples_compile so the PR-time gate recompiles the example dev builds
      # that inject it. (skills_structural still fires via the later
      # skills/re-frame2-pair/* case; this widens coverage, it does not narrow
      # it.)
      skills/re-frame2-pair/preload/*)
        examples_compile=true
        ;;
    esac

    # rf2-bbe91 (audit reopen of PR #8868) — the REVERSE edge into the MIG-23
    # SSR cold-start fixture. `reagent-migration-fixture-cold-start` is gated
    # solely on `skills_structural`, and before this dispatch that output was
    # armed only from the skill trees themselves. So the fixture fired on a
    # change to the RECIPE but never on a change to the SUBSTRATE it pins the
    # recipe against — a core adapter-lifecycle, SSR, Hicasso server-render or
    # Reagent adapter change skipped the only cross-artefact witness that the
    # documented cold start still works, which is the very regression the
    # fixture exists to catch. A skipped job is an accepted result, so the
    # aggregator could not notice.
    #
    # The roster is the fixture's own declared classpath —
    # `skills/reagent-migration/tests/fixture/deps.edn` resolves exactly these
    # four artefacts as `:local/root`, and the job's cache key hashes the same
    # four `deps.edn` files. `src/*` + `deps.edn` rather than the whole tree
    # because a `:local/root` contributes the artefact's `:paths` and its
    # dependency declaration and nothing else: an artefact's own `test/` tree is
    # not on this fixture's classpath and cannot change what it compiles.
    #
    # ITS OWN DISPATCH RATHER THAN FOUR ARMS OF THE BIG `case` BELOW, for the
    # reason rf2-65ajl gives just under this: a POSIX `case` takes the FIRST
    # match, and all four of these trees already have arms there
    # (`implementation/core/*`, `implementation/adapters/*`, the per-feature
    # fan-out that carries `implementation/ssr/*`, and `implementation/
    # hicasso/*`). An arm placed above them would SHADOW those arms and
    # silently narrow four artefacts' coverage to one output; placed below, it
    # would never match. A dispatch consulted for every file can do neither —
    # it only ever SETS `skills_structural`, so it cannot narrow anything.
    #
    # THE FAN-OUT IS DELIBERATE AND WIDER THAN THE ONE JOB, which is worth
    # stating because `skills_structural` gates three things. Firing it here
    # also schedules `re-frame2-pair-fixture-pure`, and that is CORRECT for
    # two of the four: `skills/re-frame2-pair/tests/fixture/deps.edn` declares
    # core and the Reagent adapter as `:local/root` too, so it carries the
    # identical unarmed reverse dependency and this closes it as well. For ssr
    # and hicasso it over-fires that job, and it over-fires the cheap Babashka
    # `skills-structural` job for all four. TESTING.md's routing rule prefers
    # exactly that trade ("when in doubt, over-classify"), and the alternative
    # — a 29th output existing only to split two fixture jobs apart — buys a
    # few runner-minutes for a permanent widening of the matrix.
    #
    # rf2-f9f3p — THE ROSTER IS THE UNION OF BOTH FIXTURES' CLASSPATHS, and
    # completing the second half is this bead. rf2-bbe91 armed the Pair fixture
    # for core and the Reagent adapter INCIDENTALLY, because the two fixtures
    # share those two roots and share this one output. But
    # `skills/re-frame2-pair/tests/fixture/deps.edn` resolves FIVE in-repo
    # artefacts, not two — the shipped preload `:require`s `re-frame.epoch`,
    # `re-frame.schemas` and `re-frame.machines` directly for its
    # epoch-history/`get-path`/`orient`/list-machines surfaces, which the
    # wad2fl front-porch shrink demoted off the `re-frame.core` facade — so
    # three fifths of that job's own in-repo classpath still classified
    # `skills_structural=false` (measured at rf2-bbe91's tip, for `src/*` and
    # `deps.edn` alike, against `implementation/core/src/*` and
    # `implementation/core/deps.edn` as passing controls). An owned-namespace
    # API or behaviour change in any of the three could therefore break
    # `re_frame2_pair.pure` or the runtime preload while the only job that
    # compiles and tests that shipped source was skipped — and a skipped
    # required job is an accepted result, so nothing went red.
    #
    # ONE DISPATCH FOR BOTH FIXTURES rather than a second `case` beside this
    # one, because they gate the identical output: two rosters setting one
    # variable is a distinction with no behavioural difference, and the union
    # is what `skills_structural=true` actually means here. The three added
    # roots also each already have an arm in the big `case` below (the
    # per-feature fan-out), so the shadowing argument above applies to them
    # unchanged — epoch keeps `mcp_conformance`/`mcp_live`, schemas keeps
    # `template_expensive`, machines keeps the machines-viz and `playground`
    # lanes. This dispatch only ever SETS the flag; it cannot narrow them.
    #
    # The over-fire trade is the same one, and no larger: ssr and hicasso
    # over-fire `re-frame2-pair-fixture-pure`, epoch/schemas/machines over-fire
    # `reagent-migration-fixture-cold-start`, and all seven over-fire the cheap
    # Babashka `skills-structural` job. Still no 29th output.
    case "$file" in
      implementation/core/src/*|implementation/core/deps.edn|implementation/ssr/src/*|implementation/ssr/deps.edn|implementation/hicasso/src/*|implementation/hicasso/deps.edn|implementation/adapters/reagent/src/*|implementation/adapters/reagent/deps.edn|implementation/epoch/src/*|implementation/epoch/deps.edn|implementation/schemas/src/*|implementation/schemas/deps.edn|implementation/machines/src/*|implementation/machines/deps.edn)
        skills_structural=true
        ;;
    esac

    # rf2-65ajl — its own dispatch rather than an arm of the big `case` below,
    # for the reason a POSIX `case` takes the FIRST match: the full gate's
    # roster straddles two trees that already have arms there
    # (`tools/story/*` and the examples/scripts launcher case), so an arm would
    # have to be duplicated in both and could be shadowed by a future one added
    # above it. A predicate consulted for every file cannot be shadowed. It only
    # ever SETS `story_full_gate`, so it cannot narrow anything; the one
    # deliberate narrowing this bead makes is in the examples/scripts launcher
    # arms below, and is spelled out there.
    if is_story_full_gate_path "$file"; then
      story_full_gate=true
    fi

    # rf2-w9ip — same shape, same reason (see the predicate's own note): the
    # route-path census reads three app trees that all have shadowing arms in
    # the `case` below, and it runs in `jvm-routing`, which only
    # `implementation_jvm` gates.
    if is_route_path_census_input "$file"; then
      implementation_jvm=true
    fi

    case "$file" in
      .github/workflows/test.yml|.github/workflows/expensive-tests.yml|.github/scripts/report-changed-surfaces.sh|TESTING.md)
        mark_all
        ;;
      scripts/test-core-prod-gate.sh|scripts/test-routing-prod-gate.sh|scripts/test-ssr-prod-gate.sh)
        # rf2-f8x2i / rf2-hnrwo — each of these scripts IS a production-gate
        # lane's roster: its `known_red` array decides which of that
        # artefact's namespaces the matching `jvm-*-prod-gate` job runs.
        # Editing one therefore changes what that job covers, and the one edit
        # that can silently REMOVE coverage (widening the exclusions) would
        # otherwise fire no job at all — the same shape of unwatched gate the
        # lanes exist to close. One flag covers all three: `implementation_jvm`
        # is what gates every `jvm-*-prod-gate` job.
        implementation_jvm=true
        ;;
      spec/api-manifest-metadata.edn|spec/api-manifest.edn|spec/API.md)
        # rf2-4ka7c2.1 — false-green fix. The CLJS-only adapter / Xray /
        # pair-MCP public surfaces cannot be `require`d on the JVM, so their
        # rows live in the sidecar (spec/api-manifest-metadata.edn) under
        # :cljs-only and the JVM generator carries them through VERBATIM into
        # spec/api-manifest.edn. The ONLY live runtime verifier for those rows
        # is the CLJS enumeration probe
        # (implementation/scripts/api-manifest/probe/, wired into the
        # consolidated :node-test build), which runs only when
        # cljs_node_test=true. A PR editing the sidecar / generated manifest /
        # API.md without touching implementation/, tools/, or shadow-cljs.edn
        # previously left cljs_node_test=false — so a stale/missing CLJS-only
        # row could ship with green CI (lint.yml runs only the JVM generator +
        # projection checks, not the CLJS probe). Fire cljs_node_test so the
        # probe reconciles the :cljs-only rows against the live CLJS public
        # vars on any sidecar/generated-manifest/API.md change.
        cljs_node_test=true
        ;;
      implementation/core/*)
        # rf2-8jz9t + rf2-k9ekz + rf2-t5slp — adapter_testbed_smokes
        # and story_xray_browser are NOT fired here. The Playwright
        # gates exist to catch surface-specific browser bugs (adapter
        # mount lifecycle, Story variant boot, Xray panel layout) —
        # none of which are core regressions. Core renames are caught
        # by node-test (consolidated CLJS unit + browser-test) which
        # exercises every public re-frame.core fn, and by the always-on
        # JVM core suite. A core rename that breaks an adapter mount or
        # Story variant boot silently is caught by the nightly cron +
        # post-merge gate (both run the full matrix on main).
        implementation_jvm=true
        cljs_node_test=true
        adapter_diagnostic=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        tools_jvm=true
        template_expensive=true
        mcp_conformance=true
        mcp_live=true
        # rf2-tzy13 — the docs/cljs live-cell SCI bundle BAKES IN re-frame2
        # core. The bundle is no longer committed (it is generated in CI and on
        # docs deploy), so there is no committed snapshot to go stale — but the
        # tools-playground job is now the ONLY PR-time proof that current core
        # source still compiles into a working SCI bundle and renders live under
        # headless Chromium. Fire it for any core change. (Before this ruling
        # the cost trade-off ran the other way: the job was scoped out of core
        # PRs precisely because a core change forced a bundle REBUILD + RECOMMIT,
        # which is exactly the write lock this bead retired.)
        playground=true
        # rf2-wq17m (audit reopen of #7005) — the DEPENDENCY side of the two
        # tools JVM lanes that PR created. Both artefacts resolve
        # `day8/re-frame2` by :local/root onto implementation/core, and both
        # carry a suite whose subject is CORE behaviour seen from the tool:
        #   * tools/testbed-support — open_in_editor_server_test.clj verifies
        #     the endpoint's delegation to `re-frame.source-coords`, which
        #     lives in core. Change the resolver and this is the suite that
        #     notices.
        #   * tools/machines-viz — engine_grammar_parity_test.cljc feeds
        #     representative definitions through BOTH the viz mirror and the
        #     engine, and core is on that classpath under the mirror.
        # `tools_jvm` above does NOT reach either job: it gates
        # jvm-tools-xray / -story / -story-mcp / -mcp-base. So a core change
        # armed four probes that do not run these artefacts and left both of
        # the artefacts' own lanes skipped — the same hole the PR closed on
        # the tools side, still open on the framework side.
        #
        # Two booleans on already-declared :local/root edges, not a
        # dependency-graph engine and not mark_all: every other reverse edge
        # in this file is spelled out the same way.
        tools_jvm_machines_viz=true
        tools_jvm_testbed_support=true
        tools_cljs_machines_viz=true
        # rf2-fk5jy — the migration/from-re-frame-v1 codemod's lane, a FOURTH
        # reverse edge of exactly the shape of the three above, and the newest.
        # That artefact's `:test` alias keeps re-frame2 off its classpath on
        # purpose, which is why this arm did not carry the edge when the lane
        # landed (rf2-0qzh) — the comment on the codemod's own arm still said
        # `:paths ["src"]`, deps clojure and rewrite-clj only, and it was true.
        # rf2-36u96 (PR #8857) then wired a SECOND step into the job,
        # `clojure -M:integration`, and that alias declares
        # `day8/re-frame2-core {:local/root "../../../implementation/core"}`:
        # it evaluates the codemod's emitted output against the REAL v2
        # `reg-event` contract at namespace load, with four negative controls
        # proving the harness observes registration rather than parsing. A
        # codemod whose output core REJECTS is precisely what that step exists
        # to catch — and core is the half of the pair that can change under it.
        #
        # So between #8857 and this line the lane was armed by the codemod
        # subtree alone: a core-only diff left it SKIPPED on exactly the commit
        # that could break it, while the rollup read green throughout.
        # TESTING.md's changed-surface section names that shape.
        #
        # SCOPED to this arm and no wider. implementation/core/deps.edn puts
        # only `:paths ["src"]` and two mvn coordinates on its BASE classpath;
        # its cross-artefact :local/root edges all sit under aliases, and a
        # :local/root dependency never activates its target's aliases. Core's
        # own tree is therefore the whole of what the :integration lane reaches,
        # so the per-feature arm above stays dark for it.
        #
        # ONE-WAY, like the three edges above it: the codemod's own arm does
        # NOT gain implementation_jvm in return. It is downstream of core, and
        # `_changed-surfaces.test.cjs` pins both directions.
        migration_v1_codemod=true
        ;;
      implementation/adapters/reagent-slim/*|examples/substrates/reagent_slim/counter/*|implementation/scripts/check-reagent-slim-bundle-isolation.cjs)
        # rf2-8cevm — the examples/ tree is test-free. counter_slim_and_fast
        # used to ship a paired spec.cjs but the bundle-isolation contract
        # at scripts/check-reagent-slim-bundle-isolation.cjs is the
        # canonical gate; adapter_testbed_smokes is no longer fired here.
        implementation_jvm=true
        cljs_node_test=true
        adapter_diagnostic=true
        cljs_browser=true
        cljs_prod=true
        reagent_slim_bundle=true
        # rf2-tzy13 — reagent-slim is the substrate baked into the docs/cljs SCI
        # bundle (re-frame.adapter.reagent-slim + reagent2.*), so an adapter
        # source change must prove the bundle still builds + renders. Scoped to
        # the adapter source only: the examples/ counter and the bundle-isolation
        # script above share this arm but are NOT baked in, and must not drag a
        # heavy JVM+Playwright job onto their PRs.
        case "$file" in
          implementation/adapters/reagent-slim/*) playground=true ;;
        esac
        ;;
      implementation/adapters/scripts/*)
        # The adapter-smoke harness (orchestrator + runner + shared
        # manifest) lives with the adapters it drives. A harness-script
        # edit drives the adapter-testbed-smokes job (it runs
        # `npm run test:adapter-smokes` over the shared ADAPTER_SMOKES
        # manifest) but does NOT change adapter
        # or substrate source, so it fires ONLY that gate — not the full
        # adapter-source fan-out the broad implementation/adapters/* case
        # below triggers. This mirrors the dedicated harness-script case
        # the examples tree used before the harness moved here.
        # (spec-helpers.cjs / examples-port.cjs / examples-staging.cjs
        # still live under examples/scripts/ and have their own cases
        # there, since the example dev runner and the Story launchers
        # share them.)
        adapter_testbed_smokes=true
        ;;
      implementation/adapters/*)
        # rf2-bxdk8 + rf2-cjp0i — the adapter-testbed-smokes gate is
        # scoped to the 2 adapter smokes at
        # implementation/adapters/<reagent|uix>/testbed/. Adapter
        # source changes are the canonical trigger; harness-script
        # changes are caught by the dedicated case above.
        implementation_jvm=true
        cljs_node_test=true
        adapter_diagnostic=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        adapter_testbed_smokes=true
        tools_jvm=true
        template_expensive=true
        mcp_conformance=true
        mcp_live=true
        ;;
      examples/scripts/spec-helpers.cjs)
        # rf2-bxdk8 + rf2-cjp0i — the shared Playwright assertion matchers.
        # The adapter-smoke harness moved to implementation/adapters/scripts/
        # (its own case above), but this helper stays under examples/scripts/
        # because THREE gate families require it: the adapter smokes (their
        # testbed spec.cjs files import the matchers), the Story/Xray
        # PR-smoke tier (serve-and-run-story-play-scripts.cjs and
        # tools/xray/testbeds/feature_matrix/scenarios.cjs — the module the
        # Xray feature gate loads — both require it; the full-gate roster's
        # is_story_full_gate_path comment above already assumed the shared
        # helpers were armed for story_xray_browser), and the tenant-switcher
        # smoke (testbeds/tenant_switcher/spec.cjs requires it). Before the
        # rf2-6ng7-class fix this case armed only the first pair, so a break
        # confined to the Story/Xray-only exports (navigate, reloadPage, …)
        # or the tenant spec's matchers merged green and was caught only by
        # the nightly. Fire every gate that actually loads the file.
        adapter_testbed_smokes=true
        story_xray_browser=true
        tenant_switcher_smoke=true
        ;;
      examples/scripts/examples-port.cjs)
        # rf2-y9o5e3 — the port resolver the adapter-smoke orchestrator's
        # main() calls before any compile/serve; a break there false-greens
        # the adapter smoke gate. Its consumers are the adapter-smoke
        # orchestration + dev runners only (no Story/Xray/tenant edge — the
        # Story launchers use story-feature-load-port.cjs), so unlike
        # spec-helpers.cjs above it stays scoped to the smoke pair. The rest
        # of examples/** is test-free per rf2-8cevm. (port-resolver.cjs is
        # shared with the Story launchers and is handled in its own case
        # below so it fires BOTH gates; examples-staging.cjs likewise.)
        adapter_testbed_smokes=true
        ;;
      examples/scripts/serve-and-run-story-feature-load-tests.cjs|examples/scripts/run-story-feature-load-tests.cjs)
        # rf2-65ajl — the full gate's own orchestrator + runner. Their gate is
        # `story_full_gate`, armed by is_story_full_gate_path above for every
        # file in this arm, so nothing is set here.
        #
        # The arm exists to STOP the walk, and that is load-bearing: a POSIX
        # `case` takes the first match, and with no arm of their own these two
        # fall through to the generic `examples/*` case below, which arms
        # cljs_node_test + cljs_browser. Neither compiles a line of CLJS — they
        # are Node launchers — so the fall-through would put two heavy jobs on
        # every edit to them, coverage they did not carry before and cannot use.
        # (`examples_compile` still fires, from the first case block at the top
        # of the loop, exactly as it did before.)
        :
        ;;
      examples/scripts/serve-and-run-story-play-scripts.cjs|examples/scripts/story-feature-load-port.cjs)
        # rf2-y9o5e3 — the Story CI-as-test launchers + their dedicated
        # port resolver under examples/scripts/ are the executable
        # orchestration for `npm run test:story-feature-load` and
        # `npm run test:story-play-scripts`. A break in one of these launchers
        # (compile step, server staging, port resolution, runner spawn)
        # can break the Story browser gate, so editing one must fire that
        # gate — closing the false-green hole where the launcher could
        # break and still avoid the gate it drives.
        #
        # rf2-65ajl — the arm was right about the principle and wrong about
        # which output carries it, for two of the four files it used to list.
        # `story_xray_browser` schedules the PR-SMOKE tier, whose two steps are
        # `test:xray-feature-gate:smoke` and `test:story-play-scripts`. These
        # two files ARE on the play-script path — serve-and-run-story-play-
        # scripts.cjs is the command, story-feature-load-port.cjs is the port
        # resolver it calls — so the smoke tier really does execute them and
        # they stay here. The other two, serve-and-run-story-feature-load-
        # tests.cjs and run-story-feature-load-tests.cjs, are reachable from
        # `npm run test:story-feature-load` and from nothing else: arming the
        # smoke tier for them scheduled a job running two commands that never
        # load them. They now arm `story_full_gate` (is_story_full_gate_path
        # above), which schedules the step that DOES run them, and they are off
        # this arm so a full-gate-only PR does not also pay for two smoke
        # compiles it cannot affect. story-feature-load-port.cjs arms both,
        # being on both paths.
        story_xray_browser=true
        ;;
      examples/scripts/port-resolver.cjs)
        # rf2-y9o5e3 — port-resolver.cjs is the shared free-port resolver
        # imported by BOTH examples-port.cjs (adapter smoke orchestrator)
        # and story-feature-load-port.cjs (Story launchers). A break here
        # affects every examples/scripts browser gate, so it fires the
        # adapter-testbed-smokes and story-xray-browser gates.
        adapter_testbed_smokes=true
        story_xray_browser=true
        ;;
      examples/scripts/examples-staging.cjs)
        # rf2-eqjxya — false-green fix, mirroring port-resolver.cjs above.
        # examples-staging.cjs is the SHARED staging/cleaning helper (it owns
        # stageShared, cleanStageDirs, stageExample) require'd by BOTH browser
        # gate families: the adapter-smoke orchestrator
        # serve-and-run-adapter-smokes.cjs (`npm run test:adapter-smokes`,
        # adapter_testbed_smokes) imports stageShared + cleanStageDirs, AND the
        # two Story launchers — serve-and-run-story-feature-load-tests.cjs
        # (`npm run test:story-feature-load`) and
        # serve-and-run-story-play-scripts.cjs (`npm run test:story-play-scripts`),
        # both under story_xray_browser — import cleanStageDirs. A regression in
        # the staging/cleaning code (e.g. cleanStageDirs or the _shared fan-out)
        # can break the files those gates serve before they run, yet a PR
        # touching only this helper used to fall through to the generic
        # examples/* case below (cljs_browser only), skipping both Playwright
        # gates it underpins — a CI false-green for this slice. Fire BOTH gates,
        # exactly like the shared port-resolver.cjs case, so editing the shared
        # helper runs the browser gates that depend on it. (The dev runner
        # serve-example.cjs also imports it but is not a CI gate, so no extra
        # fan-out is warranted.)
        adapter_testbed_smokes=true
        story_xray_browser=true
        ;;
      examples/scripts/examples-asset-manifest.cjs)
        # rf2-78th1g — false-green fix, mirroring the examples-staging.cjs
        # case above. examples-asset-manifest.cjs is the SINGLE side-effect-free
        # owner of every examples external-asset EXCEPTION (rf2-phpbo8): what
        # extra static asset each departing example stages and which _shared
        # asset it may omit. The staging helper examples-staging.cjs require's
        # its `stagedAssetsByBuild` projection to decide what to STAGE before
        # the browser gates run — and examples-staging.cjs itself fires BOTH
        # browser-gate families (adapter_testbed_smokes via the adapter-smoke
        # orchestrator, story_xray_browser via the two Story launchers). So a
        # regression in the manifest data or its projection can break the files
        # those gates serve, yet a PR touching only the manifest used to fall
        # through to the generic examples/* case below (cljs_browser +
        # cljs_node_test), skipping both Playwright gates it underpins — a CI
        # false-green for this slice. Fire BOTH gates, exactly like the shared
        # examples-staging.cjs case, so editing the manifest runs the browser
        # gates that depend on it. (The static asset scanner
        # check-examples-assets.cjs also require's `pageExemptions`, but it is
        # not a CI gate, so no extra fan-out is warranted.)
        adapter_testbed_smokes=true
        story_xray_browser=true
        ;;
      implementation/epoch/*)
        # rf2-ribu5a — false-green fix. Epoch is the ONLY per-feature
        # artefact wired into a LIVE MCP conformance gate: the
        # re-frame2-pair live fixture
        # (skills/re-frame2-pair/tests/fixture/deps.edn) resolves
        # `day8/re-frame2-epoch` as a :local/root, and the hermetic
        # `mcp-conformance-re-frame2-pair` job's INNER_TESTS include
        # `live-re-frame2-pair-redaction.cjs` — the egress-protection
        # regression net for the pull-mode epoch tools (trace-window /
        # watch-epochs) that asserts a declared-sensitive epoch is
        # WHOLE-DROPPED gate-OFF / shipped gate-ON across the MCP wire
        # (rf2-q4o83 / rf2-5613h). That job is gated on mcp_live='true'.
        # Folded into the generic per-feature bucket below, an
        # `implementation/epoch/src/...` change set NEITHER mcp_live NOR
        # mcp_conformance — so a PR that broke the epoch egress/redaction
        # contract merged GREEN at PR time (a conformance false-green on
        # a DATA-LEAK guard), surfacing only in the nightly cron. Split
        # out here so epoch source changes arm the live redaction gate.
        # All the generic per-feature gates still fire (epoch is not in
        # the scaffold, so no template_expensive; not baked into the SCI
        # bundle, so no playground).
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        mcp_conformance=true
        mcp_live=true
        ;;
      implementation/ssr-node/*)
        # rf2-n8vp — the ssr-node package's own lane, and ONE output.
        #
        # WHY IT SITS ABOVE ITS SIBLINGS rather than joining the per-feature arm
        # directly below. A POSIX `case` takes the FIRST match, and today
        # neither `implementation/ssr/*` nor `implementation/ssr-ring/*` matches
        # a path under `implementation/ssr-node/` — the literal `/` after `ssr`
        # sees to that, which is exactly why this package classified to NOTHING
        # on arrival rather than to something wrong. That is a fact about two
        # patterns, not a property anybody maintains: widen either to
        # `implementation/ssr*` and this tree silently joins a five-lane JVM/CLJS
        # fan-out that cannot execute a line of it. Placed first, this arm keeps
        # the narrow answer whatever happens below it.
        #
        # ONE output, and not one of the existing ones. The package is plain
        # CommonJS on `node:` builtins with no npm dependency, so
        # `implementation_jvm` / `cljs_node_test` / `cljs_browser` would each
        # queue a tier with no edge into it and still not run its suite. Nor does
        # it arm `examples_compile`: no example build lists it, and the first
        # case block at the top of the loop is deliberately left unmatched for
        # the same reason.
        #
        # The arm is the WHOLE package tree, not `src/**`. `test/**` carries the
        # nine suites and their twelve fixtures — the fixtures ARE inputs to the
        # allowlist, protocol and byte-fidelity rows — and README.md documents
        # the five bounded guarantees those rows witness. The suite is seconds
        # and needs no build, so narrowing buys nothing and would skip the very
        # files a change is most likely to be in.
        ssr_node=true
        ;;
      # rf2-qxg24 — `implementation/security/*` LEFT this arm for the src-less
      # tier below. This arm is the PRODUCTION per-feature fan-out: every other
      # tree in it publishes a Maven artefact whose source rides a shipped
      # bundle, which is what earns `cljs_browser` + `cljs_prod` +
      # `bundle_isolation`. The security partition publishes nothing. Its
      # namespaces are all `-security-cljs-test`, and `:browser-test` selects
      # only `*-dom-cljs-test`, so the browser lane cannot observe an edit to
      # it; the production and bundle-isolation builds do not require the test
      # tree at all. Its JVM job and its consolidated `:node-test` inclusion
      # are unchanged — those are the two lanes that DO run these tests, and
      # the tier below sets exactly them.
      implementation/schemas/*|implementation/machines/*|implementation/routing/*|implementation/flows/*|implementation/http/*|implementation/ssr/*|implementation/ssr-ring/*|implementation/resources/*|implementation/deps.edn)
        # rf2-8jz9t — adapter_testbed_smokes NOT fired here. Per-feature
        # artefact changes are covered by their own JVM + CLJS unit
        # suites (implementation_jvm, cljs_browser, cljs_prod) and by
        # bundle_isolation; the adapter smokes under
        # adapter-testbed-smokes only catch adapter-mount-specific bugs
        # (createRoot lifecycle, hydration, real concurrent scheduling).
        # Nightly + post-merge gate runs the full matrix.
        #
        # rf2-dxndhc — implementation/resources/* (the EP-0003
        # day8/re-frame2-resources artefact, on the root CLJS/test
        # classpath via implementation/deps.edn + shadow-cljs.edn) was
        # NOT routed here before: a PR touching only resources/* left
        # every output false, so the aggregator could pass with the
        # jvm-resources suite + the consolidated :node-test resources
        # surface both skipped. It is a published, src-carrying
        # per-feature artefact exactly like schemas/ssr/…, so it gets the
        # full per-feature treatment (implementation_jvm fires the
        # jvm-resources job added in test.yml; cljs_node_test/cljs_browser/
        # cljs_prod/bundle_isolation cover its CLJS surface + isolation).
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        # rf2-jdj17.1 — false-green fix. The template's generated `:app`
        # build compiles `day8/re-frame2-schemas` (events.cljs side-loads
        # re-frame.schemas + re-frame.schemas.malli; schema.cljs calls
        # rf/reg-app-schema). The ONLY PR-time gate that compiles the
        # emitted `:app` (emitted_test_run_test, RF2_TEMPLATE_RUN_EMITTED_TESTS)
        # fires only under template_expensive. So a PR breaking the
        # re-frame.schemas / reg-app-schema surface used to merge GREEN at
        # PR time and surface only in the nightly cron. Fire
        # template_expensive for an implementation/schemas/* change so
        # jvm-tools-template runs the emitted-app compile at PR time.
        # (The other per-feature artefacts here — machines/routing/flows/
        # http/ssr/ssr-ring — are NOT pulled into today's scaffold,
        # so they do not arm template_expensive; add them here if a future
        # scaffold flag wires one in. epoch has its own case above,
        # rf2-ribu5a.)
        case "$file" in
          implementation/schemas/*) template_expensive=true ;;
        esac
        # rf2-2h1yhk / rf2-tzy13 — the docs/cljs live-cell SCI bundle BAKES IN
        # the machines and flows artefacts (`re-frame.machines` +
        # `re-frame.flows` are :require'd at bundle init, and the bundle carries
        # the reserved :rf.machine/* lifecycle keywords). rf2-tzy13 retired the
        # COMMITTED bundle — it is generated in CI and on docs deploy now — so
        # this arm no longer guards a snapshot against staleness. What it guards
        # instead is composition: a reserved-keyword rename or a late-bind hook
        # change here can make the bundle fail to build, or build and then throw
        # at render, and tools-playground is the PR-time proof that it still
        # builds + renders live under headless Chromium. flows was omitted
        # before (rf2-nyjml finding) even though it is in the require graph and
        # in the digest roster; it fires now.
        case "$file" in
          implementation/machines/*|implementation/flows/*) playground=true ;;
        esac
        # rf2-wq17m (audit reopen of #7005) — the engine half of the
        # machines-viz parity ratchet. tools/machines-viz/deps.edn declares a
        # TEST-ONLY :local/root on implementation/machines precisely so
        # engine_grammar_parity_test.cljc can feed representative definitions
        # through BOTH the viz mirror and the engine and assert equal output:
        # catching ENGINE-side drift is the suite's reason to exist. Yet the
        # only lane that runs it is jvm-tools-machines-viz — neither CLJS
        # selector reaches the namespace (`-test`, not `cljs-test$` or
        # `-dom-cljs-test$`) — and an engine-only diff left that output false.
        # So a grammar change could merge with its designated drift gate
        # skipped. Scoped to the one artefact on the other end of the declared
        # edge; the sibling per-feature trees have no such consumer.
        case "$file" in
          implementation/machines/*)
            tools_jvm_machines_viz=true
            # rf2-odlm3 — the parity ratchet runs in BOTH runtimes now, so an
            # engine change must schedule both of its lanes.
            tools_cljs_machines_viz=true ;;
        esac
        ;;
      implementation/hicasso/*)
        # rf2-8a6s — the Hicasso view substrate artefact (rf2-hic-001).
        # The package landed with no case here at all, so a hicasso-ONLY
        # diff classified to NOTHING: every output false, every job
        # skipped, green. TESTING.md §Changed-surface classifier names
        # that exact shape — a new artefact directory needs a classifier
        # rule AND a workflow gate reading it, and either side missing is
        # a silent hole. This is the rule half; the `cljs` job's two
        # hicasso steps are the gate half.
        #
        # ONE output, deliberately, and NOT the four the retired
        # `implementation/freehand/*` case used to set. That case went with
        # its tree (rf2-0yp7w) and no freehand arm survives here. The
        # artefact's coverage today is exactly what `cljs_node_test`
        # schedules:
        #
        #   - the package smoke `re-frame.hicasso.smoke-cljs-test`, which
        #     rides the consolidated `:node-test` build (hicasso/src +
        #     hicasso/test are on the global :source-paths and the ns
        #     matches that build's `cljs-test$` regexp) in the `cljs` job;
        #   - the INVARIANTS GATE, `npm run test:hicasso-invariants`, a step
        #     of that same job — the optional-module reachability check (which
        #     since rf2-6c12m.1 also carries the no-bench-import row the
        #     retired freeze gate used to seal), the budget ledger, the facade
        #     inventory and the guide-samples check;
        #   - the modules compile (`test:hicasso-compile`), already an
        #     unconditional step there.
        #
        # NOT implementation_jvm — AND THE OLD REASON HAS EXPIRED, so it
        # is worth being exact about the new one. This row used to read
        # "every suite it owns is CLJS; its `:test` alias is a classpath
        # probe with `--probe`; it is deliberately absent from
        # scripts/test-jvm-implementation.sh's roster. Arm this the same
        # commit a JVM-runnable suite lands and the roster gains the row."
        # rf2-0yp7w re-homed the bench harness here, which brought the
        # `.cljc` equivalence pin for the canonical slot rule (rf2-ani6y) —
        # now `test/re_frame/hicasso/slot_cljs_test.cljc`, retargeted into
        # the package when the harness moved out again (rf2-6c12m.1) — so
        # the alias dropped `--probe` and took the test-count floor, and
        # rf2-ipx7h put the artefact ON that roster with a required
        # `jvm-hicasso` job.
        #
        # The row survives because that job is UNCONDITIONAL, so it needs
        # no arm, and because arming this root would be wrong twice over:
        # 22 OTHER jobs read `implementation_jvm`, so a hicasso-only diff
        # would schedule all of them to run one five-second one-namespace
        # lane; and the arm would still not cover the lane's own inputs —
        # `implementation/hicasso/deps.edn` and `test_kit/src/**` are on
        # its `:test` classpath and are matched by THIS case, which sets
        # no jvm output. `implementation/scripts/_changed-surfaces.test.cjs`
        # pins all three facts.
        #
        # NOT cljs_prod / bundle_isolation: no
        # `-elision-prod-test$` namespace and no example resolves the
        # artefact.
        cljs_node_test=true
        # rf2-8a6s — cljs_browser, AND THE CONDITION FOR IT HAS NOW BEEN
        # MET. This arm originally read "NOT cljs_browser … the package
        # owns no `-dom-cljs-test$` namespace, so arming it would
        # schedule a Playwright job that runs not one line of the changed
        # surface. Widen the moment a `*-dom-cljs-test` namespace lands."
        # Three have landed — kernel_commit_owns, roots_frames_hydration
        # and roots_frames_isolation, under implementation/hicasso/test/
        # (rf2-hic-010, rf2-hic-012) — so the narrowing expired and this
        # is the widening it asked for.
        #
        # The failure it closes wore a GREEN BADGE, which is why it is
        # worth spelling out. `:browser-test` selects `.*-dom-cljs-test$`
        # (it carried a leading `(?!re-frame\.freehand\.bench\.)` exclusion
        # until that tree went under rf2-0yp7w), so those three namespaces
        # are already IN the browser lane; the lane just
        # never ran on a diff that touched them. The consolidated node
        # build compiles the same namespaces, and each DOM row there
        # reports a STATED GREEN SKIP — so the surface reported success
        # having executed none of its DOM assertions. An unclassified
        # surface at least fails loudly the first time somebody looks;
        # this one did not.
        #
        # This ADDS to the node arm rather than replacing it. Both still
        # matter and they cover different things: `cljs_node_test` is the
        # only output that schedules the package smoke and the invariants
        # gate, neither of which the browser lane runs.
        cljs_browser=true
        # rf2-ga8m — and, since the three-engine controlled-input gate
        # landed (rf2-hic-016), `hicasso_controlled`. That gate compiles
        # the `:hicasso/testbed` build off THIS tree — the testbed app
        # and `hicasso/testbed/spec.cjs` both live under it — and drives
        # the package's element-path converge in Chromium, Firefox and
        # WebKit. It is the only lane that witnesses invariant I15's
        # caret and composition clauses, and the caret is the ONLY
        # observable in a real browser that separates this runtime from
        # plain React: React's own end-of-event restore repairs a
        # value-level misconduct inside the same discrete event, so the
        # value assertions in the `cljs_node_test` suites above stay
        # green under regressions these rows catch. A hicasso diff that
        # did not run it would be relying on the weaker witness.
        hicasso_controlled=true
        # rf2-hic-015 — and, since the HMR witness matrix landed (rf2-vsgq),
        # `hicasso_hmr`. That gate compiles the `:hicasso/hmr-testbed` build
        # off THIS tree — the testbed app under hicasso/testbed/
        # hicasso_hmr_testbed/, the page under hicasso/testbed/hmr/ and
        # `hicasso/testbed/hmr_spec.cjs` all live here — then starts a REAL
        # `shadow-cljs watch`, rewrites a marked source line and lets shadow
        # recompile, push the module and re-evaluate it.
        #
        # It is the only lane in the repo that witnesses the reload itself,
        # and the distinction it holds is the one the #7755 audit named: the
        # package's Node HMR suites call `collector/mint-view!` directly and
        # drive the commit and release seams by hand, so they can catch
        # neither drift between the `defview` macro and a real shadow reload
        # nor a renderer that fails to run old-generation cleanup on a type
        # replacement. Every runtime file a reload re-evaluates lives under
        # this arm, so a hicasso diff that skipped it would be resting the
        # whole HMR contract on the seam-driven witness.
        hicasso_hmr=true
        # rf2-erjv — migration_hicasso_codemod, the reverse edge into the
        # codemod's JVM lane. It landed here as the SECOND of two such edges,
        # the first being a classpath one on the then-live
        # `implementation/freehand/*` arm; rf2-r4j91 moved that one here as
        # well, so this arm carries BOTH — and the freehand arm itself went
        # with its tree under rf2-0yp7w.
        #
        # The classpath edge: the codemod's deps.edn puts
        # `../../../implementation/hicasso/src` on `:paths` so the tool and the
        # door share ONE slot rule (rf2-ani6y, repointed off the retiring
        # prototype by rf2-r4j91), and `shared_rule_test.clj` pins the tool's
        # resolver and `impl/slot.cljc`'s `prop-name` `identical?` — plus the
        # file's own path, so a rule loaded from anywhere else reds rather than
        # passing on a byte-identical twin.
        #
        # The source-text edge, which is why this arm existed first.
        # `shared_rule_test.clj`'s `the-callback-contracts-are-the-doors`
        # (rf2-vi11) reaches back up the tree with a relative `io/file` and
        # slurps `implementation/hicasso/src/re_frame/hicasso/impl/codec.cljs`,
        # because that roster is `.cljs` this JVM cannot load but CAN read —
        # then asserts the door's own `callback-contracts` set equals the one
        # the codemod PRINTS into the `defhost` sketch it invites a migrator to
        # paste. Add a fourth contract at the door, or rename one, and the
        # tool's advice goes wrong; that pin is the assertion that says so, and
        # before this line it lived in a lane a hicasso-only diff never ran.
        # The pin also asserts the file EXISTS where it looks, so a MOVE of
        # codec.cljs reds it rather than silently skipping — which is only
        # worth having if the move's own PR schedules the lane.
        #
        # COARSER than either edge, deliberately: both files sit inside this
        # tree, and an arm naming them alone would have to precede this one,
        # where it would shadow the outputs above and silently narrow the
        # package's own coverage. Over-classifying a seconds-long pure JVM
        # suite is the cheaper error, and TESTING.md says to prefer it.
        migration_hicasso_codemod=true
        ;;
      implementation/reply-conformance/*|implementation/derivation-conformance/*|implementation/event-conformance/*|implementation/security/*)
        # rf2-qxg24 — `implementation/security/*` JOINED this arm, closing a
        # contradiction the file carried with itself. The comment below already
        # named the security tier as the PRECEDENT for omitting the browser /
        # production / bundle gates, while two earlier, executable arms put
        # security in the production fan-out and the examples-compile roster
        # and thereby armed all four. Prose lost to code, silently, for as long
        # as nobody classified a security path and read the output. All four
        # source-less tiers now take the same route, which is what the prose
        # said all along.
        #
        # The four tiers are alike in the way that matters here: `:paths []`,
        # `:deps {}`, no `src/` tree, no Maven artefact — test surfaces on the
        # root test classpath and nothing else.
        #
        # rf2-dxndhc — the three EP cross-conformance tiers
        # (reply-conformance / derivation-conformance / event-conformance)
        # are src-less `.cljc` TEST surfaces on the root test classpath
        # (implementation/deps.edn + shadow-cljs.edn list them). They run
        # in BOTH runtimes: the always-on consolidated `:node-test` gate
        # (the `cljs` job, gated on cljs_node_test) runs the `:cljs` arms,
        # and each tier's own `:test` alias runs the SAME `.cljc`
        # namespaces under the JVM to exercise the `:clj` reader-conditional
        # arms (the JVM jobs added in test.yml, gated on implementation_jvm;
        # mirrors the security tier). Before rf2-dxndhc a PR touching only
        # one of these tiers left every output false, so the aggregator
        # could pass with both the JVM `:clj`-arm job AND the consolidated
        # node-test surface skipped. They ship NO production src (no Maven
        # artefact), so they do NOT widen any production bundle — hence no
        # cljs_browser / cljs_prod / bundle_isolation here (the security
        # tier, the precedent, is likewise off those).
        implementation_jvm=true
        cljs_node_test=true
        ;;
      implementation/spec-resource/*)
        # day8/re-frame2-spec-resource is the ONE build-time reader for
        # committed spec/ data — the api-manifest CLJS probe expands
        # through it, so a change here can break that macro's compile.
        #
        # implementation_jvm fires this artefact's own `:test` alias, which
        # is the deterministic control for the cold-load race the reader
        # exists to close (jvm-spec-resource). cljs_node_test fires
        # the consolidated `:node-test` build, where the reader is actually
        # exercised — that is the lane whose macro expansion reaches
        # shadow-cljs's recording read.
        #
        # cljs_browser is deliberately OFF (rf2-7b1ti). It was on for the
        # Freehand `-dom-cljs-test` fixture suites, which left with the
        # rf2-0yp7w retirement along with the second consumer and the
        # `jvm-freehand` lane this comment used to name — and no browser
        # namespace replaced them. `re-frame.build.spec-resource` is
        # macro-side `.clj`, so a CLJS build can reach it only through a
        # macro require, and the single such path is
        # `re-frame.api-manifest.cljs-publics`. That namespace has exactly
        # two consumers — `re-frame.api-manifest.cljs-manifest-probe-cljs-test`
        # and `day8.re-frame2-xray.panel-enum-guard-cljs-test` — both
        # plain `-cljs-test`, and nothing requires either of them. The
        # cljs_browser job compiles only the `:browser-test` build, whose
        # `:ns-regexp` is `.*-dom-cljs-test$`, so it selected no namespace
        # that expands through this reader: the lane cost a Chromium
        # install, compile and run on every spec-resource diff and could
        # not have gone red for one.
        #
        # The spec-resource block in
        # `implementation/scripts/_changed-surfaces.test.cjs` now asserts
        # all three outputs, cljs_browser included, so re-adding it here
        # reds that suite. Until rf2-7b1ti it asserted only the other two,
        # which is how the expired reason survived unnoticed — a change to
        # this arm's browser output moved nothing.
        #
        # No production bundle requires any of this (build-time only), so
        # cljs_prod / bundle_isolation stay off.
        implementation_jvm=true
        cljs_node_test=true
        ;;
      implementation/test-quiet/src/*|implementation/test-quiet/test/*|implementation/test-quiet/deps.edn)
        # rf2-am7grp — implementation/test-quiet is the test-runtime
        # quiet-reporter artefact (day8/re-frame2-test-quiet, rf2-try1x):
        # the JVM runner (re-frame.test-quiet.runner, runner.clj — the
        # `:main-opts` of EVERY per-artefact `:test` alias) AND the CLJS
        # shadow-node runner (re-frame.test-quiet.shadow-node, the
        # `:node-test` build's `:main` in implementation/shadow-cljs.edn).
        # Before this case the classifier had NO rule for test-quiet/**,
        # so a PR changing the quiet reporter implementation, its runners,
        # its deps.edn, or its contract tests left every output false — the
        # JVM quiet-runner contract (test_quiet_runner_contract_test.clj,
        # test_quiet_pin*_test.clj) and the CLJS node-test quiet reporter
        # contract (test_quiet_shadow_node_cljs_test.cljs, the green/red
        # fixture cljs tests) BOTH skipped, leaving js-harness-self-tests
        # (which only runs the JS script policy/helper tests, never the
        # quiet reporter's own :test or the consolidated :node-test pins)
        # as the sole — insufficient — verifier.
        #
        # Fire implementation_jvm so the per-artefact JVM `:test` aliases
        # (which route through the quiet runner) re-run the runner contract
        # at PR time, and cljs_node_test so the consolidated `:node-test`
        # build (whose `:main` IS the CLJS quiet runner) exercises the
        # shadow-node reporter + its contract tests. Scoped to src/test/
        # deps.edn only; no broadening onto unrelated docs/spec paths and
        # no reliance on js-harness-self-tests as the sole verifier.
        implementation_jvm=true
        cljs_node_test=true
        ;;
      spec/conformance/fixtures/*)
        # rf2-qmiiz — Fixtures under spec/conformance/fixtures/*.edn
        # are consumed by:
        #   - implementation/core/test/re_frame/conformance_test.clj
        #     (JVM core job, always-on)
        #   - implementation/core/test/re_frame/conformance_corpus_cljs_test.cljs
        #     (cljs job, always-on)
        #   - per-artefact _conformance_test.clj under
        #     implementation/{flows,ssr,machines,schemas,routing}/test/
        #     (each gated behind implementation_jvm='true')
        # A fixture-only PR (no impl/test change) would skip every
        # per-artefact _conformance_test.clj. Fire implementation_jvm
        # so the per-artefact corpus runners pick up new fixtures, and
        # fire the CLJS surfaces so the cross-platform corpus runner
        # in core does too. The CLJS corpus runner
        # (conformance_corpus_cljs_test.cljs) is in the consolidated
        # :node-test build, so cljs_node_test fires too.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        ;;
      # ─── PROSE THAT A test.yml SUITE PINS (rf2-61ar) ──────────────────────
      #
      # THE HOLE. A docs/spec-only diff classified to NOTHING: measured on
      # main, `report-changed-surfaces.sh docs/machines/parallel-states.md
      # docs/machines/concepts.md` printed all 32 outputs `false`. But a
      # growing family of suites inside test.yml jobs `slurp` repo PROSE and
      # assert on its text — guide-truth pins, terminology pins, schema
      # extracts, doc-example evaluators. Every one of them was armed by its
      # CODE surface and by nothing on the PROSE side, so a prose-only edit
      # could red them and merge green, with the red landing on `main` for the
      # next unrelated PR to discover. That is not hypothetical: PR #8068 (the
      # machines-guide rewrite, 13 files, docs-only) skipped `jvm-machines` —
      # the very lane pinning the pages it rewrote — and left five assertions
      # across four deftests red on main until an unrelated PR happened to arm
      # `implementation_jvm` (rf2-s41i, PR #8082, the prose+test half).
      #
      # THE MEASURED ROSTER — the prose each test.yml suite reads, and the job
      # that reads it. Established by walking every `.clj`/`.cljc` under
      # implementation/**/test and tools/**/test that both names a repo prose
      # path and calls a read primitive, then reading each one:
      #
      #   docs/machines/parallel-states.md   parallel_states_guide_truth_jvm_test
      #                                        .clj                  → jvm-machines
      #   docs/machines/concepts.md          transition_geometry_terminology_jvm_
      #                                        test.clj              → jvm-machines
      #   docs/api/re-frame.adapter.uix.md   scope_ensure_authority_test.clj
      #                                                              → jvm-core
      #   docs/api/re-frame.ssr.md           ssr_doc_example_projector_test.clj
      #                                                              → jvm-ssr
      #   docs/design/hicasso/product/       recipes/async_nav_doc_test.clj
      #     async-routing-recipes.md                                 → jvm-routing
      #   spec/000-Vision.md                 scope_ensure_authority_test.clj
      #   spec/012-Routing.md                        "
      #   spec/013-Flows.md                          "                → jvm-core
      #   spec/Tool-Pair.md                  spec_elision_registry_tense_
      #   spec/Security.md                     conformance_test.clj   → jvm-core
      #   spec/009-Instrumentation.md        error_catalogue_channel_conformance
      #                                        _test.clj (core), scope_ensure_
      #                                        authority_test.clj (core),
      #                                        destroyed_reason_channel_
      #                                        conformance_test.clj (machines),
      #                                        spawn_all_schema_extract.clj →
      #                                        spawn_all_authority_catalogue_
      #                                        test.clj (machines)
      #                                              → jvm-core + jvm-machines
      #   spec/005-StateMachines.md          transition_geometry_terminology_jvm_
      #                                        test.clj, destroyed_reason_channel
      #                                        _conformance_test.clj → jvm-machines
      #   spec/Cross-Spec-Interactions.md    destroyed_reason_channel_conformance
      #                                        _test.clj             → jvm-machines
      #   spec/Pattern-FormAction.md         ssr_doc_example_form_action_test.clj
      #                                                              → jvm-ssr
      #   spec/Spec-Schemas.md               FOUR suites in THREE artefacts — see
      #                                        its own arm below.
      #
      # WHY `implementation_jvm` AND NOT SOMETHING NARROWER. There is nothing
      # narrower. `implementation_jvm` is the single output every one of the 22
      # per-artefact JVM jobs gates on; a per-lane arm would mean 22 new outputs
      # and 22 `if:` widenings in test.yml for a saving the spec roster above
      # would not even collect (its pins already span jvm-core, jvm-machines,
      # jvm-epoch, jvm-ssr and jvm-routing). The precedent is directly
      # overhead: prose a suite reads arms the JVM tier. So the
      # narrowing this bead buys is bought on the PATH axis instead: prose a
      # suite reads arms the JVM tier, and prose nothing reads still arms
      # nothing, exactly as today.
      #
      # NONE of these arms `cljs_browser`, `cljs_prod` or any Playwright
      # output. Markdown cannot change what React puts on a page — the same
      # line rf2-drpa3.70 drew for `implementation/freehand/*.md`. The one
      # exception is `spec/Spec-Schemas.md`, and it is a COMPILE-TIME edge
      # rather than a runtime one; its own arm below says why.
      docs/machines/*.md)
        # The two pinned pages are `concepts.md` (the §Self-transitions
        # terminology `transition_geometry_terminology_jvm_test.clj` holds to
        # spec/005) and `parallel-states.md` (the guide-truth walk). The arm is
        # the TREE rather than those two names, and deliberately:
        #   * the incident was a 13-file tree-wide rewrite, which is how a
        #     guide is actually edited — page by page is the exception;
        #   * `concepts.md` and `parallel-states.md` are the terminology spine
        #     every other page in the guide restates, so a rewrite that moves a
        #     definition between pages is the shape most likely to red them;
        #   * unlike `docs/api/**` below, this tree has NO other gate — it
        #     reaches docs.yml, which stages it into the site and executes not
        #     one line of the suites that read it. Under-arming here is what
        #     cost the red, and TESTING.md's rule for exactly this doubt is
        #     "when in doubt, over-classify".
        # `.md` rather than `*`: both pins read Markdown, and the tree carries
        # no other file today. An image dropped beside a page should not queue
        # a JVM tier.
        implementation_jvm=true
        ;;
      docs/api/re-frame.adapter.uix.md|docs/api/re-frame.ssr.md)
        # Named files, NOT `docs/api/*` — the one place in this block where the
        # narrow answer is the right one, for a reason the other trees lack:
        # `docs/api/**` is ALREADY on lint.yml's `paths:` filter, so all 25
        # pages carry the api-manifest `doc-api-check` projection gate. The
        # tree is not an unwatched surface; the hole is two pages that two
        # test.yml JVM suites additionally name as literals —
        # `scope_ensure_authority_test.clj` (jvm-core) reads the uix adapter
        # page's scope/ensure row, and `ssr_doc_example_projector_test.clj`
        # (jvm-ssr) EVALUATES the projector example off the ssr page. Arming 25
        # pages' worth of edits into a 22-job JVM tier when 23 of them have no
        # JVM pin at all is the "coarse rules clutter the matrix" TESTING.md
        # warns about. Add the page here when a new JVM suite names one.
        implementation_jvm=true
        ;;
      docs/design/hicasso/product/async-routing-recipes.md)
        # One named file, and emphatically not its tree: `docs/design/**` is 159
        # files of working design records, deliberately excluded from the site
        # build (`exclude_docs` in mkdocs.yml) and validated only by
        # scripts/check_doc_slugs.py + check_provenance_pins.py. Exactly one of
        # them is read by a test.yml suite — `recipes/async_nav_doc_test.clj`
        # (jvm-routing) reads this page's recipe forms and evaluates them — so
        # exactly one is armed.
        implementation_jvm=true
        ;;
      spec/Spec-Schemas.md)
        # The widest single miss in the roster.
        #
        # FOUR suites in THREE artefacts extract schema forms from this file:
        #   error_catalogue_channel_conformance_test.clj      (core)
        #   epoch_silence_contract_test.clj                   (epoch)
        #   destroyed_reason_channel_conformance_test.clj     (machines)
        #   spawn_all_schema_extract.clj                      (machines)
        # — jvm-core, jvm-epoch and jvm-machines, all on
        # `implementation_jvm`.
        #
        # It armed `cljs_node_test` as well until 2026-08-21, and no longer
        # does. That arm rested ENTIRELY on one compile-time edge:
        # `observation_schema_extract.clj` was a JVM MACRO namespace and
        # `observation_port_cljs_test.cljc` pulled it in through
        # `:require-macros`, so the ObservationOnChangeFailedTags schema was
        # inlined into the consolidated `:node-test` build. Both namespaces
        # went with the internal observation port (rf2-63t1i), and the schema
        # went with them, so nothing extracts from this Markdown at
        # macro-expansion time any more — every remaining reader is a JVM suite
        # that slurps it at RUN time. (The Freehand corpus arm was the other
        # case built on the same reasoning; it retired with the tree that read
        # it — rf2-0yp7w.6. This file now arms no CLJS output at all.)
        #
        # Must precede the `spec/*` catch-all below: a POSIX `case` takes the
        # FIRST match. Both set `implementation_jvm`, but this arm is kept
        # explicit because its roster is the documentation.
        implementation_jvm=true
        ;;
      spec/*)
        # The catch-all, and WHOLESALE on purpose.
        #
        # Eleven spec documents are pinned today by suites in six artefacts
        # (the roster above), and every one of them arrived as its own bead —
        # rf2-qyvyes, rf2-4go8s, rf2-qgsp2o, rf2-vxgfnd.97.3 and friends. A
        # named list of eleven would therefore be a list that is wrong again by
        # the next bead, and being wrong is precisely this hole: an unarmed
        # normative document whose pin runs in no lane. `spec/` is the
        # artefact of this repo — the implementation is downstream of it — and
        # it is edited prose-only routinely, which is the exact diff shape that
        # classified to nothing.
        #
        # The cost is explicit and accepted: a typo fix in an unpinned spec
        # page now queues the JVM tier. That is TESTING.md's "when in doubt,
        # over-classify", and the tier is 22 cached `clojure -M:test` jobs — no
        # Chromium, no `:advanced` compile, no Playwright.
        #
        # It is a CATCH-ALL, so its POSITION is load-bearing twice over. A
        # POSIX `case` takes the first match and `*` spans `/`, so this arm
        # would swallow every narrower `spec/` case if it preceded them. The
        # three that must stay ahead of it, and do:
        #   spec/api-manifest{,-metadata}.edn + spec/API.md  (cljs_node_test)
        #   spec/conformance/fixtures/*
        #   spec/Spec-Schemas.md                             (immediately above)
        # Two more used to sit here. The Freehand corpus arm
        # (spec/conformance/freehand/fixtures/* + conformance-index.md) retired
        # with the tree that read it, and the S{3,4,5}-view-conformance-profile
        # arm retired with the jvm-ui job whose drift guards it armed — both
        # rf2-0yp7w. The profile docs themselves survive and now fall to this
        # catch-all, which arms implementation_jvm: over-classification, the
        # safe direction.
        # A new narrower `spec/` arm goes ABOVE this one or it is dead code.
        implementation_jvm=true
        ;;
      implementation/scripts/serve-and-run-reagent-slim-smoke.cjs|implementation/scripts/_reagent-slim-smoke-policy.test.cjs)
        # rf2-5v0dg7 — false-green fix, mirroring the xray-feature-gate
        # launcher case below. serve-and-run-reagent-slim-smoke.cjs IS the
        # executable orchestration for `npm run test:reagent-slim:smoke`,
        # the command the cljs-reagent-slim-bundle-isolation PR job now runs
        # (.github/workflows/test.yml). A break in this launcher (compile,
        # staging, port resolution, ownership-token readiness, the browser
        # drive) can break the slim client-runtime smoke gate — yet the
        # generic implementation/scripts/* case below routes only to the
        # always-on JS/CLJS surfaces and NEVER to reagent_slim_bundle, so a
        # PR editing this launcher (or its policy test) could break the very
        # gate it drives while avoiding it (a false-green hole). Fire
        # reagent_slim_bundle so editing the launcher runs the gate it
        # orchestrates. The remaining static-script surfaces this file shares
        # with the generic case (cljs_node_test / cljs_browser / cljs_prod /
        # bundle_isolation) stay armed too — this case widens coverage, it
        # does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        ;;
      implementation/scripts/serve-and-run-xray-feature-gate.cjs)
        # rf2-rcepku — false-green fix, mirroring rf2-y9o5e3 for the
        # examples/scripts launchers. This launcher IS the executable
        # orchestration for `npm run test:xray-feature-gate:smoke`, the
        # command the story-xray-browser PR job runs (.github/workflows/
        # test.yml). A break in this launcher (compile step, surface
        # staging, port resolution, server staging, the external-base-URL
        # readiness probe) can break the Xray PR-smoke gate — yet the
        # generic implementation/scripts/* case below routes only to the
        # always-on JS/CLJS surfaces and NEVER to story_xray_browser, so a
        # PR editing this launcher could break the very gate it drives
        # while avoiding it (a false-green hole). Fire story_xray_browser
        # so editing the launcher runs the gate it orchestrates. The
        # remaining static-script surfaces this file shares with the
        # generic case (cljs_node_test / cljs_browser / cljs_prod /
        # bundle_isolation / reagent_slim_bundle) stay armed too — this
        # case widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        story_xray_browser=true
        ;;
      implementation/scripts/serve-and-run-tenant-switcher-testbed.cjs)
        # rf2-h5e3v7 — false-green fix, mirroring the xray-feature-gate +
        # reagent-slim-smoke launcher cases above. This launcher IS the
        # executable orchestration for `npm run test:testbed-tenant-switcher`,
        # the command the tenant-switcher-testbed-smoke PR job runs
        # (.github/workflows/test.yml). It is the ONLY Playwright smoke that
        # exercises the tenant-switcher browser scenario (compile / index.html
        # staging / ownership-token server readiness / pageerror handling). A
        # break in this launcher — or in the colocated testbed it drives —
        # could ship green, since the generic implementation/scripts/* case
        # below routes only to the always-on JS/CLJS surfaces and never to
        # tenant_switcher_smoke. Fire tenant_switcher_smoke so editing the
        # launcher runs the gate it orchestrates. The remaining static-script
        # surfaces this file shares with the generic case (cljs_node_test /
        # cljs_browser / cljs_prod / bundle_isolation / reagent_slim_bundle)
        # stay armed too — this case widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        tenant_switcher_smoke=true
        ;;
      implementation/scripts/serve-and-run-hicasso-controlled-testbed.cjs)
        # rf2-ga8m — self-protection, mirroring the launcher cases above.
        # This file IS the three-engine controlled-input gate: it compiles
        # `:hicasso/testbed`, serves it, drives `hicasso/testbed/spec.cjs`
        # once per engine, and owns the two pieces of verdict logic that
        # make the run mean anything — the 50-check-per-engine floor, and
        # the cross-engine comparator that reds an unlisted divergence in
        # a RECORDED row. Both are exactly the sort of teeth a diff can
        # soften. The generic `implementation/scripts/*` case below never
        # arms `hicasso_controlled`, so without this case a PR could edit
        # the gate's own floor while avoiding the job that runs it. The
        # static-script surfaces it shares with the generic case stay
        # armed too; this case widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        hicasso_controlled=true
        ;;
      implementation/scripts/serve-and-run-hicasso-hmr-testbed.cjs)
        # rf2-hic-015 — self-protection, exactly as for the controlled-input
        # launcher above. This file IS the HMR gate: it starts the
        # `shadow-cljs watch`, owns the HOT-LINE rewriter that makes a save a
        # save at all, and owns the two pieces of verdict logic that make the
        # run mean anything — `REQUIRED_SECTIONS` / `REQUIRED_RECORDS` (the
        # structural coverage floor) and the cross-engine comparator that reds
        # an unlisted divergence in a RECORDED row.
        #
        # The rewriter is the sharper half. A gate whose `save()` silently
        # stopped editing the file would report a perfectly green run in which
        # nothing ever reloaded — so softening it is a change that must run
        # the gate. The generic `implementation/scripts/*` case below never
        # arms `hicasso_hmr`, so without this case a PR could edit the gate's
        # own teeth while avoiding the job that runs them. The static-script
        # surfaces it shares with the generic case stay armed too; this case
        # widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        hicasso_hmr=true
        ;;
      implementation/scripts/check-story-static.cjs|implementation/scripts/story-build.cjs)
        # rf2-9n2cv — self-protection, the same shape as the two freehand
        # checker arms that used to sit above (both retired with their tree
        # under rf2-0yp7w) and for the same reason. `npm run test:story-static`
        # IS `node scripts/check-story-static.cjs`: the mounted-shell
        # assertions, the first-visit-overlay suppression check, the
        # ownership-token verification and the non-vacuity floor all live in
        # that one file. The generic `implementation/scripts/*` case below
        # arms cljs_node_test + cljs_browser + cljs_prod + bundle_isolation +
        # reagent_slim_bundle, and NOT ONE of those schedules a job that runs
        # the command the file defines — so a PR could soften an assertion or
        # drop a check in the static-export gate's own teeth and merge with
        # the gate unexercised.
        #
        # story-build.cjs is in the roster because it is this gate's build
        # orchestration, the exact analogue of the run/serve launchers
        # rf2-65ajl armed for the feature-load gate: check-story-static.cjs
        # spawns it by name (`path.join(__dirname, 'story-build.cjs')`) to
        # produce the export it then serves and asserts against. It is also
        # `npm run story:build`, and NO workflow runs that script directly —
        # grep the tree: its only CI execution path anywhere is through
        # check-story-static.cjs. So without this arm it is doubly untraced.
        # A test below reads the spawn list off check-story-static.cjs rather
        # than trusting this roster, so a second spawned sibling is armed on
        # arrival rather than on the next audit.
        #
        # Deliberately NOT the shared harness helpers this gate requires
        # (lib/local-browser-harness.cjs, lib/browser-test-report.cjs). Each is
        # required by run-browser-tests.cjs,
        # serve-and-run-xray-feature-gate.cjs and serve-and-run-reagent-slim-
        # smoke.cjs — all PR-time gates — and each carries its own dedicated
        # policy test in the fast spine. A break in one already reds a job that
        # runs at PR time, so paying for the static gate on top buys no signal.
        #
        # This arm WIDENS and never narrows: it re-sets every output the
        # generic case below would have set, so there is no fall-through to
        # worry about and no tier is lost. That is why it can be a plain `case`
        # arm here, where rf2-65ajl needed a predicate dispatched in the loop
        # body — its roster straddled two trees that already had arms and could
        # be shadowed; this roster is two files in one tree, sitting directly
        # above the only other arm that matches them.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        story_static_gate=true
        ;;
      implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json|implementation/scripts/*)
        # rf2-8jz9t + rf2-bxdk8 + rf2-cjp0i + rf2-k9ekz + rf2-t5slp —
        # adapter_testbed_smokes and story_xray_browser are NOT fired
        # here. The Playwright gates are triggered ONLY by direct
        # source-tree changes (adapter source for adapter-testbed-
        # smokes; tools/{story,xray}/{src,testbeds}/** for the Story/
        # Xray browser gate). A shadow-cljs.edn or implementation/
        # scripts/ change that breaks the build is caught by the
        # nightly cron + post-merge gate (both run the full matrix on
        # main). shadow-cljs.edn + package-lock.json directly determine
        # the :node-test build, so cljs_node_test fires here too.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        # rf2-6yuzo4 — template npm-pin lockstep. The template's hooks.clj
        # pins :shadow-version + :react-version, and version_lockstep_test
        # asserts those emitted pins match implementation/package.json's
        # react / react-dom / shadow-cljs entries (the source of truth).
        # The emitted-app smoke (emitted_test_run_test, the ONLY PR-time
        # gate that compiles + runs the generated project) symlinks
        # implementation/node_modules — populated from
        # implementation/package-lock.json — into the scaffold so React
        # resolves. So a PR that bumps those package.json pins, or the
        # lockfile the smoke links against, must run jvm-tools-template:
        # otherwise the template keeps emitting a stale pin (or links a
        # drifted node_modules) and merges GREEN while breaking the
        # lockstep contract + the advertised smoke-tested combination.
        # Scoped to package.json + the lockfile; shadow-cljs.edn and
        # implementation/scripts/* don't carry the emitted npm pins, so
        # they stay off template_expensive (the nightly full matrix
        # covers any build-config drift they can introduce).
        case "$file" in
          implementation/package.json|implementation/package-lock.json)
            template_expensive=true ;;
        esac
        # rf2-ga8m — the three-engine controlled-input gate is DEFINED by
        # this trio in the same way, and scoped identically. shadow-cljs.edn
        # declares the `:hicasso/testbed` build the gate compiles; package.json
        # carries the `test:hicasso-controlled` script AND the `playwright`
        # pin, which for this gate is not an ordinary dependency bump — the
        # pin IS the three engine revisions under test, so bumping it changes
        # the subject of every caret and composition witness. The lockfile
        # fixes those revisions. `implementation/scripts/*` stays off: the one
        # script that drives this gate has its own case above.
        case "$file" in
          implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json)
            hicasso_controlled=true ;;
        esac
        # rf2-hic-015 — the HMR gate is DEFINED by the same trio, and one of
        # the three carries a fact no other gate depends on. shadow-cljs.edn
        # declares the `:hicasso/hmr-testbed` build AND its `:dev-http` on
        # port 8061 — this gate is served by shadow's own dev server rather
        # than by http-server precisely so the document and the devtools
        # websocket share an origin, so an edit to either half can stop the
        # reload arriving. package.json carries the `test:hicasso-hmr` script
        # and the `playwright` pin, which here as for the controlled gate IS
        # the three engine revisions under test; the lockfile fixes them.
        # `implementation/scripts/*` stays off, exactly as above: the one
        # script that drives this gate has its own case.
        case "$file" in
          implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json)
            hicasso_hmr=true ;;
        esac
        # rf2-8m344 — the `:machines-viz-viewer` build is DECLARED here, in
        # implementation/shadow-cljs.edn, while the page it emits a bundle for
        # lives under tools/machines-viz/public/. That split is exactly how the
        # build ended up compiled by no workflow, no npm script and no gate
        # while README.md and spec/API.md documented `shadow-cljs release
        # machines-viz-viewer` as the way a consumer self-hosts the page. A
        # rename of the module or the output-dir here silently breaks that
        # recipe, so this file arms the page gate.
        case "$file" in
          implementation/shadow-cljs.edn)
            machines_viz_viewer_page=true ;;
        esac
        # rf2-n8vp — package.json ALONE, and the narrowest scoping in this arm.
        # `test:ssr-node` is defined there and nowhere else, so an edit that
        # renamed or emptied it would otherwise stop the gate running with no
        # job going red. shadow-cljs.edn is off it because no build id reaches
        # this package; package-lock.json is off it because the package declares
        # no npm dependency and the job does no `npm ci`, so a lockfile change
        # cannot alter what it executes; `implementation/scripts/*` is off it
        # because the gate's whole body lives under implementation/ssr-node/,
        # which has its own arm above.
        case "$file" in
          implementation/package.json)
            ssr_node=true ;;
        esac
        ;;
      examples/*)
        # rf2-bxdk8 + rf2-cjp0i + rf2-8cevm — examples/** is test-free.
        # adapter_testbed_smokes is NOT fired by generic examples/**
        # paths; only the orchestrator scripts under examples/scripts/
        # (matched above) fire it. The cljs_browser gate covers
        # CLJS-source regressions touched by examples/.
        #
        # rf2-8ckcf2 — false-green fix (ROOT CAUSE of the #5353 main-red).
        # examples/ carries no tests of its own, BUT example PRODUCTION
        # source sits on the consolidated :node-test classpath
        # (implementation/shadow-cljs.edn lists ../examples/core,
        # ../examples/real-apps, ../examples/capabilities/*, … as
        # :source-paths), and framework/adapter test namespaces may
        # `:require` it. The reagent-adapter test
        # re-frame.realworld-resources-cljs-test
        # (implementation/adapters/reagent/test/re_frame/realworld_resources_cljs_test.cljs
        # — its ns matches the :node-test build's `cljs-test$` ns-regexp)
        # `:require`s realworld-resources.core + realworld-resources.scope
        # from examples/real-apps/realworld_resources/. So an examples-only
        # change can break a :node-test test, yet firing only cljs_browser
        # left the `cljs` job (gated on cljs_node_test) SKIPPED: PR #5353
        # (an examples/real-apps editor fix) merged green and turned main
        # red undetected. Fire cljs_node_test on ANY examples change so the
        # consolidated node-test suite re-runs the coupled tests. Broad
        # (whole examples/ tree) rather than examples/real-apps-only — the
        # classifier is deliberately conservative, other example-coupled
        # tests may exist or be added, node-test is the fast default gate,
        # and an examples change that no test `:require`s simply recompiles
        # nothing new (harmless). (examples/scripts/*.cjs orchestration
        # helpers are matched by their own earlier cases and stay scoped to
        # the browser gates — they are not CLJS source any node-test
        # `:require`s.)
        #
        # rf2-6ckzl — the SAME false-green, one tier out, for the two gates whose
        # bundles are compiled FROM this tree. `cljs_prod` and `bundle_isolation`
        # were both left false here, and both gate jobs that build `:examples/*`
        # entries:
        #
        #   * cljs-bundle-isolation (bundle_isolation) releases
        #     :examples/counter, :examples/counter-uix, :examples/login,
        #     :examples/login-uix and :examples/realworld-resources, then greps
        #     the five bundles for tools/* symbols.
        #   * cljs-perf-bundle (cljs_prod, rf2-eegpw) releases :examples/counter
        #     and :examples/counter-perf — both counter.core/run — and greps the
        #     perf-OFF bundle for the ABSENCE and the perf-ON twin for the
        #     PRESENCE of the mark-and-measure call sites.
        #
        # So a PR touching only examples/counter/** recompiled the exact entry
        # both gates read and ran NEITHER. The isolation gate would not have
        # noticed a counter change that pulled in a tools/* symbol; the perf gate
        # would not have noticed one that stopped reaching a mark-and-measure
        # call site, which is what makes its perf-ON positive control non-vacuous.
        #
        # Armed for the WHOLE tree rather than for the seven example directories
        # those two commands name today. Both rosters are npm-script argument
        # lists in implementation/package.json, free to gain an entry without
        # touching this file, and a narrow list here would go stale in silence —
        # the failure mode this case already carries a paragraph about (rf2-8ckcf2
        # widened it to cljs_node_test on exactly that reasoning). The cost was
        # measured rather than assumed: a docs-only PR pays NOTHING (it never
        # reaches this case), and an examples-only PR pays four extra jobs —
        # 161s + 127s + 91s + 90s of runner time on run 30980305020 — which run
        # in PARALLEL beside the 778s `:node-test` and 644s examples-compile jobs
        # such a PR already queues, so its wall clock does not move.
        cljs_browser=true
        cljs_node_test=true
        cljs_prod=true
        bundle_isolation=true
        ;;
      testbeds/tenant_switcher/*)
        # rf2-h5e3v7 — the tenant-switcher testbed is the ONE top-level
        # testbed that legitimately keeps its own colocated Playwright
        # spec.cjs (a cross-cutting framework smoke per CLAUDE.md
        # "framework testbeds carry their own non-adapter spec.cjs"). Its
        # runner serve-and-run-tenant-switcher-testbed.cjs drives
        # testbeds/tenant_switcher/spec.cjs against the compiled
        # :testbeds/tenant-switcher build + staged index.html. So a change
        # to the testbed's core.cljs / spec.cjs / index.html must fire the
        # tenant-switcher-testbed-smoke gate (else the runner's only live
        # browser coverage can be avoided). cljs_browser stays lit too for
        # the transitive CLJS-source coverage every other testbed gets.
        cljs_browser=true
        tenant_switcher_smoke=true
        ;;
      testbeds/spec-helpers.cjs)
        # rf2-6ng7 class — the shared Playwright helper require'd ONLY by
        # tools/xray/testbeds/feature_matrix/scenarios.cjs, the module BOTH
        # Xray feature-gate tiers load (implementation/scripts/
        # serve-and-run-xray-feature-gate.cjs; the PR-smoke tier runs
        # `test:xray-feature-gate:smoke` under story_xray_browser). The
        # generic testbeds/* fall-through below arms only cljs_browser — a
        # CLJS compile-and-test lane that never loads a .cjs — so an edit
        # breaking this helper red-ded no armed PR job and was caught only
        # by the nightly full gate. Arm the tier that actually executes it;
        # cljs_browser is deliberately NOT set (no CLJS source is reachable
        # from this file — same stop-the-walk reasoning as the Story
        # launcher arm above).
        story_xray_browser=true
        ;;
      testbeds/*)
        # rf2-7vsfm + rf2-t5slp — Top-level testbeds/* surfaces are
        # retained as Xray observation targets but no longer have a
        # paired Playwright spec.cjs; all framework + top-level testbed
        # specs migrated to CLJS/JVM unit tests under the four
        # rf2-tglku waves and the split-out `framework-testbeds` gate
        # was retired (rf2-t5slp). cljs_browser stays lit for CLJS-
        # source regressions in shared core/feature artefacts that the
        # testbed compiles transitively pull in.
        cljs_browser=true
        ;;
      tools/template/*)
        # rf2-os0c1 + rf2-40vmd — tools/template is a deps-new template
        # that scaffolds new projects (migrated from clj-new in rf2-dolpf
        # §2); it does not share runtime with xray/story/story-mcp/
        # mcp-base. The template_expensive gate fires jvm-tools-template
        # (its only PR-time job); tools_jvm would unnecessarily fire the
        # four sibling jvm-tools-* probes.
        template_expensive=true
        ;;
      tools/story/*|tools/xray/*)
        # rf2-os0c1 + rf2-k9ekz + rf2-t5slp + rf2-f79t8 — Story / Xray
        # changes legitimately fan out to tools_jvm (per-artefact JVM
        # unit tests + sibling story-mcp consumer) and mcp_conformance
        # (the MCP wrappers consume these artefacts).
        #
        # rf2-f79t8 — spec-md guard. A pure documentation change under
        # tools/{story,xray}/spec/**.md cannot affect any runtime, any
        # JVM unit test, or any MCP wire surface, so it must NOT fan out
        # to the JVM/MCP probes (jvm-tools-{xray,story,story-mcp},
        # node-test-tools-story-mcp, mcp-conformance-*). It is covered by
        # docs.yml + the nightly full matrix. Mirrors the
        # runtime-extension guard already applied to story_xray_browser
        # below (rf2-k9ekz). All NON-spec-md changes (src/test .clj/.cljs,
        # deps.edn, README, EDN, …) still fire the probes conservatively.
        case "$file" in
          # rf2-6ng7 — THE XRAY HALF OF THAT GUARD WAS FALSE. Its premise is
          # that a spec-md change "cannot affect any JVM unit test"; two
          # suites under `tools/xray/test/` refute it by reading the spec
          # markdown as their expected value:
          #
          #   coverage_matrix_metadata_test.clj  slurps
          #     tools/xray/spec/017-Test-Coverage-Matrix.md and
          #     019-Cross-Cutting-Insight.md, reconciling the feature-matrix
          #     scenario names against the spec's rows      → jvm-tools-xray
          #   panel_enum_spec_refs.clj           slurps
          #     tools/xray/spec/007-UX-IA.md and
          #     008-Embedding-Contract.md at MACRO-EXPANSION time, emitting
          #     the spec's `mount-<panel>!` set into
          #     panel_enum_guard_cljs_test.cljs, which the consolidated
          #     :node-test build compiles                   → cljs
          #
          # Both were armed by their CODE surface and by nothing on the spec
          # side, so an Xray spec edit that renamed a matrix row or a panel
          # left the reconciling suite unrun and the red landed on main for
          # the next unrelated PR — the rf2-61ar incident shape.
          #
          # THE TREE, NOT THE FOUR NAMES, and deliberately: naming them here
          # would be a second copy of a roster that already lives in the two
          # suites, free to drift the moment a fifth spec file is read or
          # content moves between files. `mcp_conformance` and
          # `template_expensive` stay OFF — markdown cannot change an MCP wire
          # surface or the generated app's compile, and no suite in either
          # lane reads these files.
          tools/xray/spec/*.md)
            tools_jvm=true
            cljs_node_test=true
            ;;
          tools/story/spec/*.md)
            : # spec doc only — no runtime/JVM/MCP/CLJS/template fan-out.
              # Story's spec markdown has no counterpart reader: the one
              # consumer, api-manifest's story_spec_check, runs in lint.yml's
              # api-manifest job, which `tools/*` already arms (rf2-6ng7).
            ;;
          *)
            tools_jvm=true
            mcp_conformance=true
            # rf2-jdj17.1 — false-green fix. The template's generated
            # `:app` build compiles against Story + Xray: the with-story
            # scaffold's core requires re-frame.story and calls
            # story/mount-shell! / unmount-shell!, and EVERY scaffold wires
            # day8.re-frame2-xray.preload via :devtools/preloads in
            # shadow-cljs.edn. The ONLY PR-time gate that compiles the
            # emitted `:app` (emitted_test_run_test,
            # RF2_TEMPLATE_RUN_EMITTED_TESTS) fires only under
            # template_expensive — so a PR breaking the re-frame.story
            # shell API or renaming/removing the xray preload ns used to
            # merge GREEN at PR time and surface only in the nightly cron
            # (generated apps then fail their first `shadow-cljs watch app`
            # / `release app`). Fire template_expensive for any non-spec-md
            # Story/Xray change so jvm-tools-template runs the emitted-app
            # compile at PR time. Scoped exactly like tools_jvm/mcp above:
            # a pure spec-md change can't break the generated `:app`
            # compile, so it stays excluded.
            template_expensive=true
            ;;
        esac
        # story_xray_browser is narrowed (rf2-k9ekz): it fires ONLY when
        # the changed path is under tools/{story,xray}/{src,testbeds}/**
        # AND the file has a runtime extension — plus the one named
        # macros.clj exception (rf2-uqf5q). Read the predicate for the
        # authoritative extension list rather than a copy here; this
        # summary has already drifted once (it predated `.html`,
        # rf2-kttom). Markdown specs, JVM unit tests under
        # tools/{story,xray}/test/**, deps.edn, README.md, and *.txt do
        # NOT fire it. The split-out framework-testbeds gate (formerly
        # rf2-9grp6) was retired in rf2-t5slp.
        if is_story_xray_runtime_path "$file"; then
          story_xray_browser=true
        fi
        # rf2-f79t8 — the consolidated :node-test build lists
        # tools/{story,xray}/{src,test} as :source-paths (shadow-cljs.edn),
        # so a CLJS/CLJC change under those trees changes node-test output
        # and must fire the `cljs` job (gated on cljs_node_test). A
        # markdown / EDN / JVM-only `.clj` change does not compile into
        # node-test and so does not fire it.
        if is_story_xray_node_test_path "$file"; then
          cljs_node_test=true
        fi
        # rf2-1sd8h — and the browser half. `cljs_node_test` alone
        # COMPILES a `-dom-cljs-test` namespace under Node, where it finds
        # no `document` and self-skips; `cljs_browser` is what schedules
        # the headless-Chromium lane in which it actually mounts. See the
        # predicate's own comment for why this is a separate, narrower arm
        # rather than a widening of the node-test one.
        if is_story_xray_dom_test_path "$file"; then
          cljs_browser=true
        fi
        ;;
      tools/machines-viz/*)
        # rf2-z0cw6s — tools/machines-viz ships day8/re-frame2-machines-viz
        # (the MachineChart component + read-only viewer + Mermaid/SCXML/
        # PNG/SVG/share-URL export surfaces, rf2-o9arp). It is a CLJS-only
        # tool (no JVM unit tests, no MCP wrapper): its src+test are listed
        # as :source-paths of the consolidated :node-test AND :browser-test
        # builds (implementation/shadow-cljs.edn). So a CLJS/CLJC change
        # must fire BOTH the `cljs` (node-test) job and the `cljs-browser`
        # job — the latter runs the `*-dom-cljs-test` export/redaction +
        # chart DOM suites under headless Chromium (where EP-0015 image-
        # export egress is verified). No mcp_conformance /
        # template_expensive fan-out: machines-viz is not consumed by an MCP
        # wrapper and is not part of the deps-new template's generated app.
        #
        # rf2-as6bg — this arm used to claim machines-viz "has no JVM suite".
        # It does: a wired `:test` alias, on `scripts/test-jvm-tools.sh`'s
        # roster, 632 tests / 2537 assertions. 29 of its 31 suites are
        # `*_cljs_test.*` and ride the two CLJS gates above, but
        # `engine_grammar_parity_test.cljc` (the engine<->viz grammar drift
        # ratchet) and `mermaid_public_smoke_test.cljc` match neither
        # `cljs-test$` (`:node-test`) nor `-dom-cljs-test$` (`:browser-test`),
        # so they run in the JVM lane ONLY.
        #
        # rf2-wq17m — that lane now has a CI job, `jvm-tools-machines-viz`,
        # gated on the dedicated output below. `tools_jvm` is still deliberately
        # NOT set: it gates four jvm-tools-* jobs (xray / story / story-mcp /
        # mcp-base), none of which runs this artefact, so it would fire four
        # unrelated probes and still skip these two files.
        #
        # spec-md guard (mirrors story/xray above): a pure documentation
        # change under tools/machines-viz/spec/**.md cannot affect any
        # compiled output, so it fires nothing runtime — covered by
        # docs.yml + the nightly full matrix.
        case "$file" in
          tools/machines-viz/spec/*.md)
            : # spec doc only — no runtime/CLJS fan-out
            ;;
          *)
            # Every non-spec-md change (src/test .cljs/.cljc, deps.edn,
            # public/viewer.html, README, EDN) conservatively fires the
            # node-test + browser gates. The node-test build picks up the
            # *_cljs_test suites; the browser build picks up the
            # *-dom-cljs-test export/chart-DOM suites. The JVM lane joins them
            # on the same conservative footing (rf2-wq17m): `deps.edn` moves the
            # test classpath and the parity suite mirrors engine grammar, so
            # narrowing to `src/**` would skip the very inputs it watches. The
            # whole suite is seconds.
            cljs_node_test=true
            cljs_browser=true
            tools_jvm_machines_viz=true
            # rf2-odlm3 — and the artefact's OWN CLJS lane. The two suites the
            # comment above calls out as JVM-only, `engine_grammar_parity_test`
            # and `mermaid_public_smoke_test`, are `.cljc`: dual-runtime by
            # construction, and the parity ratchet carries `:cljs` reader arms.
            # The consolidated `:node-test` bundle does not contain them at all
            # (its `cljs-test$` selector never reaches the namespaces), so the
            # `cljs` job above is not their lane however green it is.
            # `:machines-viz-node-test` is, and this output is what schedules it.
            tools_cljs_machines_viz=true
            # rf2-8m344 - and the PAGE gate. The viewer entry lives on this
            # artefact's `page/` root, the HTML on its `public/` root, and the
            # bundle recompiles against `src/` + `deps.edn`, so every non-spec-md
            # change here can break the self-hosting recipe the README documents.
            machines_viz_viewer_page=true
            ;;
        esac
        ;;
      tools/testbed-support/*)
        # rf2-as6bg — this tree had NO arm at all, so a testbed-support-only
        # PR classified as zero changed surfaces and ran zero gates. Not just
        # the JVM suite the bijection gate found (rf2-4hc9p): its three CLJS
        # suites were skipped too. `implementation/shadow-cljs.edn` says the
        # slice "additionally rides the always-on `:node-test` gate, so the
        # slice is covered on every PR" — that was true of the BUILD's
        # source-paths and false of CI, because the `cljs` job is gated on
        # cljs_node_test, which nothing here set.
        #
        # src+test are :source-paths of the consolidated :node-test AND
        # :browser-test builds, exactly like machines-viz above, so the same
        # two gates own them: `config_cljs_test.cljs` +
        # `story_host_cljs_test.cljs` under node, and
        # `story_host_dom_cljs_test.cljs` (matching `-dom-cljs-test$`) under
        # headless Chromium for its real React-root handoff assertions.
        #
        # The `.clj` half — `open_in_editor_server_test.clj`, 32 tests / 153
        # assertions, which no CLJS build can load — now has a lane too
        # (rf2-wq17m): `jvm-tools-testbed-support`, gated on the dedicated
        # output below. `tools_jvm` stays deliberately unset for the same reason
        # as machines-viz above: none of the four jobs `tools_jvm` gates (xray /
        # story / story-mcp / mcp-base) runs this artefact, so it would fire four
        # unrelated probes and still skip the file.
        cljs_node_test=true
        cljs_browser=true
        tools_jvm_testbed_support=true
        ;;
      tools/story-mcp/*)
        # rf2-os0c1 — MCP wrappers don't run in a browser; story-xray-browser
        # exercises the Story/Xray CLJS runtimes via Playwright and is
        # noise for an MCP-wrapper-only diff. tools_jvm + mcp_conformance
        # cover the actual JVM probes (jvm-tools-story-mcp / wire-vocab) and
        # node integration tests.
        tools_jvm=true
        mcp_conformance=true
        ;;
      tools/re-frame2-pair-mcp/*|tools/mcp-base/*)
        # rf2-os0c1 — mcp-base is .cljc shared by every MCP server (rf2-vw4sq),
        # and re-frame2-pair-mcp ships as a Node binary plus a JVM mcp-base consumer.
        # tools_jvm picks up jvm-tools-mcp-base; mcp_conformance fires the
        # node + wire-vocab gates; mcp_live fires the re-frame2-pair live conformance.
        tools_jvm=true
        mcp_conformance=true
        mcp_live=true
        ;;
      tools/mcp-conformance/*)
        # rf2-os0c1 — mcp-conformance is JS test scripts plus the JVM
        # wire-vocab subdir. The wire-vocab JVM tests already run under
        # mcp-conformance-wire-vocab, which is gated by mcp_conformance.
        # Setting tools_jvm here would needlessly fire four unrelated
        # jvm-tools-* probes (xray/story/story-mcp/mcp-base).
        mcp_conformance=true
        mcp_live=true
        ;;
      skills/re-frame2/references/tooling/story-mcp-loop.md)
        # rf2-jbwraa — code↔skill drift ratchet routing. The
        # tools/story-mcp JVM test `skill-leaf-tool-names-match-registry`
        # (tools_test.clj) PINS this leaf's prose catalogue against the
        # live story-mcp tool registry (slurps ../../skills/re-frame2/
        # references/tooling/story-mcp-loop.md). That gate fires only under
        # `jvm-tools-story-mcp` (gated by tools_jvm) — which previously ran
        # ONLY when tools/story-mcp/** changed, NOT when this cross-checked
        # leaf changed. So a leaf edit that desynced the ratchet merged
        # green and left main latently RED (rf2-r2xswa/#3637). Route a
        # change to this specific leaf to tools_jvm + mcp_conformance,
        # mirroring the tools/story-mcp/* case, so the ratchet runs at PR
        # time. Scoped to the single pinned path — no broader skills/
        # re-frame2/** fan-out.
        tools_jvm=true
        mcp_conformance=true
        ;;
      skills/re-frame2-pair/tests/fixture/*)
        skills_structural=true
        mcp_conformance=true
        mcp_live=true
        ;;
      skills/re-frame2-pair/preload/*)
        # rf2-11yjq — the shipped re-frame2-pair preload
        # (skills/re-frame2-pair/preload/re_frame2_pair/{runtime.cljs,pure.cljc},
        # and any nested source) is dev-only RUNTIME whose stateful wrapper has
        # TWO owning behavioral gates:
        #   - cljs-browser (gated on cljs_browser) discovers
        #     re-frame.pair-dispatch-and-settle-dom-cljs-test, which imports
        #     re-frame2-pair.runtime and drives its real React/epoch settle
        #     behavior under headless Chromium.
        #   - mcp-conformance-re-frame2-pair (gated on mcp_live) boots the
        #     hermetic fixture with THIS exact preload and exercises live Pair
        #     operations across the MCP bridge.
        # rf2-k8yl5f armed examples_compile (the earlier case block) so the
        # preload COMPILES into the ~28 example dev builds that inject it, and
        # the generic skills/re-frame2-pair/* case below armed skills_structural
        # (source-shape + pure-core node fixture). But NEITHER of those executes
        # the stateful runtime wrapper or its live wire behavior — so a
        # preload-only regression (runtime.cljs / pure.cljc / a nested preload
        # path) merged with BOTH owning behavioral gates SKIPPED, caught only by
        # the nightly net (a PR-time false-green). Arm cljs_browser + mcp_live so
        # the two runtime consumers run at PR time. skills_structural is set here
        # too because this more-specific case shadows the generic
        # skills/re-frame2-pair/* case below (widening coverage, not narrowing
        # it); examples_compile still fires via the earlier case block. Scoped to
        # the preload subtree only — non-preload skill docs/references keep their
        # structural-only classification and never arm these expensive gates.
        skills_structural=true
        cljs_browser=true
        mcp_live=true
        ;;
      skills/re-frame2-pair/*|skills/shared/*)
        skills_structural=true
        ;;
      skills/re-frame2-setup/*)
        # rf2-agi57x — the re-frame2-setup skill carries a structural drift
        # guard (tests/setup_drift_test.clj) gated under skills-structural.
        skills_structural=true
        # rf2-6ng7 — the skill's REFERENCE snippets are compiled, not merely
        # linted. `setup-skill-scaffold-compiles-test` in
        # tools/template/test/day8/re_frame2_template/emitted_test_run_test.clj
        # materialises a scaffold for each documented substrate SOLELY from
        # the fenced blocks in this directory (first-counter.md /
        # shared-dataflow.md + entry-namespace.md + shadow-cljs.md), then runs
        # `clojure -M:shadow compile app` over it against the in-repo source.
        # That suite runs in `jvm-tools-template`, which `template_expensive`
        # gates — and this tree armed only `skills_structural`, so the one
        # gate that compiles the skill's hand-written greenfield counter never
        # fired on an edit to it. The structural guard cannot stand in: it
        # checks shape, not that the snippets still compile.
        #
        # `references/` only. The skill's SKILL.md, its tests/ and its other
        # trees are not materialised into the scaffold, and an expensive
        # emitted-app compile has no business queueing for them.
        case "$file" in
          skills/re-frame2-setup/references/*)
            template_expensive=true
            ;;
        esac
        ;;
      skills/re-frame2-pair-retro/*)
        # rf2-g1m2q — this tree armed NOTHING AT ALL, not merely
        # skills_structural=false: the four arms above were the whole of the
        # `skills_structural` surface and the main case has no default arm, so
        # a diff confined here classified to zero of 28 outputs.
        #
        # IT CANNOT INHERIT THE PAIR ARM, and that is the whole reason it needs
        # its own. `skills/re-frame2-pair/*` requires a `/` immediately after
        # `pair`; this tree has `-retro` there, so the pattern never matches and
        # the near-identical prefix reads as though it were already covered.
        #
        # rf2-qad4l wired `skills/re-frame2-pair-retro/tests/*_test.clj` into the
        # `skills-structural` job as its own step (a bb loop mirroring the
        # setup-skill one), gated on this output — so until this arm existed the
        # step fired only on a change to one of the OTHER armed trees or on an
        # --all run, never on a change to the tests it gates. That is the
        # surface-armed gate skipped on the very push that breaks it.
        #
        # ONE output, structural only. The tree is prose plus a Babashka
        # command-contract test that loads no re-frame2 runtime, drives no live
        # Pair op and compiles into no example build, so the expensive lanes its
        # `skills/re-frame2-pair/*` neighbour arms have nothing to do here.
        skills_structural=true
        ;;
      skills/reagent-migration/*)
        # rf2-g1m2q — the same silent hole in the second of the two trees the
        # measurement covered, and the same zero-output classification.
        #
        # rf2-vpdrf / rf2-bbe91 wired `skills/reagent-migration/tests/fixture/`
        # into `reagent-migration-fixture-cold-start` — a MIG-23 SSR cold-start
        # :node-test build — gated on this output. The
        # fixture is the skill's executable half: it pins the migrated output
        # against the real substrate, so an unclassified edit to it merged with
        # its own suite unrun.
        #
        # ONE output, structural only, and the fixture is why that is not an
        # oversight: it resolves its own deps and builds its own :node-test, so
        # `reagent-migration-fixture-cold-start` (which skills_structural gates)
        # is the job that runs it. Arming `cljs_node_test` or `examples_compile`
        # instead would schedule the implementation's own heavy lanes for a diff
        # that cannot reach them, and still not run this fixture.
        skills_structural=true
        ;;
      docs/tools/playground/*|docs/cljs/playground.js|docs/cljs/playground.css|scripts/playground-sci-input-digest.mjs)
        # rf2-ee38b.22 — the docs/cljs live-cell playground (CM6 + Scittle
        # bootstrap + the re-frame2 SCI bundle). The tools-playground job
        # rebuilds both bundles, runs the headless-Chromium smoke against
        # them, and `git diff --exit-code`s the committed
        # docs/cljs/playground.{js,css} so a stale vendored bootstrap bundle
        # fails the PR.
        #
        # rf2-tzy13 — docs/cljs/playground-rf2.js is GONE from this arm: the
        # SCI bundle is no longer tracked (it is generated in CI and on docs
        # deploy, and is .gitignored), so the path can never appear in a diff.
        # scripts/playground-sci-input-digest.mjs joins the arm instead — it
        # computes the provenance digest copy-bundle.mjs stamps into the
        # generated bundle, so an edit to it must exercise the build (rf2-nyjml
        # finding: the digest + copy-bundle scripts were not themselves
        # classifier inputs; copy-bundle.mjs is already covered under
        # docs/tools/playground/*).
        playground=true
        ;;
      migration/reagent-to-hicasso/codemod/*)
        # rf2-2rtt6.143 — the Reagent `[:>]` → Hicasso codemod (the FIXER).
        # It shipped with no case here at all, so a codemod-only diff
        # classified to NOTHING: 22 tests / 158 assertions, including the
        # golden corpus that IS this tool's spec, running in no lane anywhere.
        # `migration/**` reaches docs.yml, which stages the tree into the site
        # and executes not one line of it. Same silent hole rf2-8a6s closed for
        # `implementation/hicasso/*` and rf2-4hc9p / rf2-as6bg closed on the
        # tools side; TESTING.md's Changed-surface classifier section names the
        # shape and the two-sided fix.
        #
        # ONE output, and its own rather than an existing one: the artefact is
        # a standalone JVM tool that reads consumer SOURCE TEXT through
        # rewrite-clj and loads no re-frame2 runtime, so none of the jobs any
        # other output gates would run it. Arming `implementation_jvm` instead
        # would fire the whole implementation JVM tier for a diff that cannot
        # touch it, and still not run this suite.
        migration_hicasso_codemod=true
        ;;
      migration/from-re-frame-v1/codemod/*)
        # rf2-0qzh — the v1 `reg-event-db/-fx/-ctx` → `reg-event` codemod
        # (EP-0018 Slice E), and the identical hole one tree over from the
        # case above. Measured on main: this artefact's deps.edn carried a
        # working `:test` alias — 45 tests, 158 assertions — and the string
        # `from-re-frame-v1` appeared ZERO times in this file, in
        # .github/workflows/test.yml and in scripts/test-jvm-tools.sh. So a
        # codemod-only diff classified to nothing and went green having run
        # none of its own tests. `migration/**` does reach docs.yml, but that
        # workflow stages the tree into the site and executes not one line of
        # it. TESTING.md calls an unclassified surface a silent hole.
        #
        # ONE output, and its own, for the same reason its sibling has one:
        # the artefact is a standalone JVM tool that rewrites consumer SOURCE
        # TEXT through rewrite-clj and never loads, requires or executes
        # re-frame2, so no job any other output gates would run it. Arming
        # `implementation_jvm` would fire the whole implementation JVM tier
        # for a diff that cannot touch it and STILL not run this suite.
        #
        # A separate output from `migration_hicasso_codemod` rather than a
        # shared `migration_codemods` one: the two artefacts have no
        # dependency on each other, and sharing would make each one's diff
        # pay for the other's lane forever. Unlike its sibling this one has
        # no cross-tree edge to mirror — `:paths ["src"]`, deps clojure and
        # rewrite-clj only — so the arm is exactly the artefact's own tree.
        # The arm is the codemod SUBTREE, not `migration/from-re-frame-v1/*`:
        # that parent also holds five hand-written migration guides, and a
        # prose edit has no business queueing a JVM lane.
        migration_v1_codemod=true
        ;;
      bench/*)
        # rf2-6c12m.1 — the Hicasso bench lane, a hand-run shadow-cljs project
        # OFF every per-PR lane by ruling: its suites exercise LOCAL COPIES of
        # the runtime, so running them per PR could not catch a regression in
        # the shipped one, and its 82 MB of committed run records are
        # evidence, not inputs. A bench-only diff is therefore CLASSIFIED to
        # no gate rather than left unclassified — this arm sets nothing so the
        # silence is stated. TESTING.md §Changed-surface classifier calls an
        # unclassified surface a silent hole; this is the other thing, a
        # surface whose gate is `npm run check` from bench/hicasso/, which
        # the bench README requires before publishing a change there. Two
        # unconditional per-PR readers still reach the tree with no gate of
        # their own: `scripts/check_readme_links.py --ci` validates
        # bench/hicasso/README.md, and `core/test/re_frame/bench/
        # lane_cache_wiring.test.cjs` scans the drivers as TEXT for the
        # cache-clear rule. `implementation/scripts/_changed-surfaces.test.cjs`
        # pins every output false here.
        ;;
    esac
  done <<< "$files"
fi

emit() {
  local key="$1"
  local value="$2"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s=%s\n' "$key" "$value" >> "$GITHUB_OUTPUT"
  else
    printf '%s=%s\n' "$key" "$value"
  fi
}

emit implementation_jvm "$implementation_jvm"
emit cljs_node_test "$cljs_node_test"
emit adapter_diagnostic "$adapter_diagnostic"
emit cljs_browser "$cljs_browser"
emit examples_compile "$examples_compile"
emit cljs_prod "$cljs_prod"
emit bundle_isolation "$bundle_isolation"
emit reagent_slim_bundle "$reagent_slim_bundle"
emit adapter_testbed_smokes "$adapter_testbed_smokes"
emit tools_jvm "$tools_jvm"
emit tools_jvm_machines_viz "$tools_jvm_machines_viz"
emit tools_jvm_testbed_support "$tools_jvm_testbed_support"
emit tools_cljs_machines_viz "$tools_cljs_machines_viz"
emit machines_viz_viewer_page "$machines_viz_viewer_page"
emit template_expensive "$template_expensive"
emit mcp_conformance "$mcp_conformance"
emit mcp_live "$mcp_live"
emit story_xray_browser "$story_xray_browser"
emit story_full_gate "$story_full_gate"
emit story_static_gate "$story_static_gate"
emit tenant_switcher_smoke "$tenant_switcher_smoke"
emit skills_structural "$skills_structural"
emit playground "$playground"
emit migration_hicasso_codemod "$migration_hicasso_codemod"
emit hicasso_controlled "$hicasso_controlled"
emit hicasso_hmr "$hicasso_hmr"
emit migration_v1_codemod "$migration_v1_codemod"
emit ssr_node "$ssr_node"
