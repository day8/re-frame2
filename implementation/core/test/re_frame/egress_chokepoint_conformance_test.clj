(ns re-frame.egress-chokepoint-conformance-test
  "rf2-mrtis6 — the conformance PIN that makes the egress-redaction
  CHOKE-POINT real, not documentary, for the ALWAYS-ON (production-surviving)
  union-record fan-out.

  ## What this pins

  Security.md §The privacy / classification surface is NORMATIVE:

    > Projection is centralized at trust boundaries via `project-egress`
    > (the record-level primitive) over `elide-wire-value` (the low-level
    > walker) … per-tool reimplementation of the projection is prohibited.
    > Sinks consume already-projected records only.

  The choke-point ALREADY EXISTS — `re-frame.projection/project-egress`
  (projection.cljc). The EP-0015 §9 frame-owned observability route
  (`route-error!` / `route-error-record!`) already funnels error records
  through it. The risk this ratchet governs is the cmxiss-shaped
  `forgot the always-on half` bug family at the EGRESS boundary: a NEW site
  that ships a payload-bearing always-on union record to the corpus-wide
  `register-error-listener!` registry WITHOUT routing the untrusted slots
  through `project-egress` first.

  The two always-on union-record fan-out chokepoints are
  `re-frame.error-emit/dispatch-error-record!` and
  `…/dispatch-frame-teardown-report!` (error_emit.cljc). Their corpus-listener
  leg (`((:fan-out registry) record)`) ships the record UNCHANGED — the
  off-box-shipper (Sentry / Datadog) API, deliberately NOT privacy-gated for
  the host `:exception` residual. Safety therefore rests on every CALLER
  either (a) carrying ONLY structural identifiers / a host-exception residual,
  or (b) routing the untrusted payload-bearing slots through `project-egress`
  BEFORE the fan-out. There is otherwise ZERO framework enforcement.

  ## The ratchet (modelled exactly on error_catalogue_channel_conformance_test)

  SOURCE-SCAN every artefact `src/` tree — the corpus defined once in
  `re-frame.impl-source-corpus` and shared with the error-catalogue test —
  for the namespaces that CALL either chokepoint, and assert each calling
  namespace is EITHER:

    - the `error-emit` chokepoint namespace ITSELF (it defines the fns and its
      `dispatch-frame-teardown-report!` → `dispatch-error-record!` internal
      call routes the frame leg through `route-error-record!` → `project-egress`
      by construction); OR
    - a namespace that ALSO references `project-egress` (it routes its
      untrusted slots through the choke-point before the fan-out — the
      ssr/hydrate.cljc B5 model); OR
    - on the explicit `structural-only-allow-list` — a caller VETTED to carry
      ONLY value-free structural slots (a fixed diagnostic `:reason` string, a
      frame id, a DOM element id, a recovery enum), so its raw fan-out is safe.

  A NEW caller that is none of these fails CI with a missing-routing
  diagnostic — exactly how the error-catalogue test fails on a new
  uncatalogued emit site. `allow-list-stays-honest` keeps the allow-list from
  rotting: an entry that stops calling the chokepoint, or that starts routing
  through `project-egress`, must be dropped in the same PR.

  ## Scope boundary (NEEDS-MIKE ruling, recorded)

  RECORD-LEVEL slots only. Per Security.md §Out-of-scope (\"the framework does
  NOT walk exception messages or ex-data maps automatically\") this gate
  asserts the always-on record carries no raw app-db / event slice AT THE
  RECORD LEVEL; it treats a host `:exception` / its ex-data as an opaque host
  residual (the existing posture — the taint-tracking non-goal). The
  no-secrets-in-ex-data discipline stays a review/lint rule, NOT a structural
  guarantee. The teardown report's `:hook-failures[].exception` ex-data and
  the SSR `:exception` legs are therefore the documented exception residual,
  not a ratchet failure.

  CONSERVATIVE by design (same posture as the error-catalogue scan): the scan
  reads the dominant direct idiom (a namespace that calls the chokepoint fn /
  the published `:error-emit/dispatch-error-record` hook). It under-reports via
  variable-arg indirection rather than false-positiving — acceptable for a
  ratchet whose job is to catch NEW direct fan-out sites.

  JVM-only (`.clj`, NOT `*-cljs-test`): it `slurp`s repo source files, which
  only the JVM `clojure -M:test` runner can do."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [re-frame.impl-source-corpus :as rf.impl-source-corpus]))

;; ---------------------------------------------------------------------------
;; Source roots + file enumeration — `re-frame.impl-source-corpus`, shared with
;; error_catalogue_channel_conformance_test (the cmxiss enforcement precedent).
;;
;; This file used to hold a COPY of that test's enumeration, under a comment
;; claiming it was "reused verbatim". It was not reused, it was duplicated, and
;; the duplicate carried the same depth-1 defect: artefact roots enumerated as
;; `.listFiles(implementation/)` mapped to `<child>/src` never reached the four
;; adapter artefacts one level deeper, so this ratchet ran on adapter PRs and
;; walked past 14 production files (rf2-2cu7f). Sharing the definition is what
;; makes one fix fix both.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; The always-on union-record fan-out chokepoints + the routing primitive
;; ---------------------------------------------------------------------------

(def ^:private chokepoint-call-re
  "Matches a CALL to either always-on union-record fan-out chokepoint —
  `dispatch-error-record!` or `dispatch-frame-teardown-report!`. The fn may be
  ns-qualified (`error-emit/dispatch-error-record!`) or bare (a late-bound
  local rebinding, as ssr/hydrate / ssr/boot / ssr-ring do:
  `(when-let [dispatch-error-record! (late-bind/get-fn …)] (dispatch-error-
  record! record))`). Anchored on `(` so a docstring/comment MENTION of the fn
  name (no preceding paren) does NOT count as a call — only a genuine call form
  pins a namespace as a caller."
  #"\(\s*(?:[a-zA-Z0-9_.-]+/)?(dispatch-error-record!|dispatch-frame-teardown-report!)")

(def ^:private chokepoint-def-ns
  "The namespace that DEFINES the chokepoint fns — `re-frame.error-emit`. Its
  own `dispatch-frame-teardown-report!` → `dispatch-error-record!` internal
  call, and the fan-out itself, route the frame leg through
  `route-error-record!` → `project-egress` by construction (EP-0015 §9). It is
  the choke-point owner, not a bypassing caller."
  're-frame.error-emit)

(def ^:private routing-marker-re
  "A namespace ROUTES through the choke-point when its source references
  `project-egress` (directly, or via the `projection/project-egress` /
  `re-frame.projection` alias). A caller that projects its untrusted
  payload-bearing slots before the fan-out (the ssr/hydrate.cljc B5 model)
  matches here and is NOT required on the structural-only allow-list."
  #"project-egress")

(def ^:private ns-decl-re
  "Match a source file's leading `(ns <fully.qualified.name>` declaration and
  capture the namespace symbol. A text scan rather than `read-string` because
  `.cljc` files carry reader conditionals (`#?(:cljs …)`) that plain
  `read-string` rejects (`Conditional read not allowed`). Anchored on `(ns` at
  a line start (after optional leading whitespace) so a `(ns …)` MENTION inside
  a docstring/comment does not win over the real declaration; `(?m)` makes `^`
  match each line. The name class allows the `.`/`-`/digit chars a re-frame ns
  uses."
  #"(?m)^\s*\(ns\s+([a-zA-Z][a-zA-Z0-9_.*+!?<>=-]*)")

(defn- ns-form-symbol
  "Extract the namespace symbol from a source file's leading `(ns …)`
  declaration via the text scan (reader-conditional-safe). Returns nil when no
  `(ns …)` form is found."
  [src]
  (when-let [[_ ns-name] (re-find ns-decl-re src)]
    (symbol ns-name)))

(defn- chokepoint-caller-namespaces
  "Scan every non-test source file and return a map
  `{<ns-symbol> {:file <path> :routes? <bool>}}` for each namespace that CALLS
  a fan-out chokepoint. `:routes?` is whether the same file references
  `project-egress` (it routes its untrusted slots through the choke-point).
  Pure text scan — no classpath load — so it sees every artefact regardless of
  the test classpath."
  []
  (reduce
    (fn [acc f]
      (let [src (slurp f)]
        (if (re-find chokepoint-call-re src)
          (if-let [ns-sym (ns-form-symbol src)]
            (assoc acc ns-sym
                   {:file    (str/replace (.getPath f) "\\" "/")
                    :routes? (boolean (re-find routing-marker-re src))})
            acc)
          acc)))
    {}
    (rf.impl-source-corpus/non-test-source-files)))

;; ---------------------------------------------------------------------------
;; The self-honest structural-only allow-list (rf2-mrtis6 census B1-B7)
;; ---------------------------------------------------------------------------

(def ^:private structural-only-allow-list
  "Namespaces that CALL a fan-out chokepoint with a record VETTED to carry ONLY
  value-free structural slots — so the raw corpus-leg fan-out is safe WITHOUT
  routing the record through `project-egress`. Each entry is the census caller
  (B-row) + the one-line reason its record is structural-only. A NEW caller not
  on this list and not routing through `project-egress` fails the coverage test;
  `allow-list-stays-honest` fails if a listed entry stops calling the chokepoint
  or starts routing (forcing the co-edit).

    - re-frame.ssr.boot (census B3) — `dispatch-malformed-hydration-frameless!`
      ships a FRAMELESS (`:frame nil`) `:rf.error/malformed-hydration-payload`
      record carrying `:where` (a quoted symbol), `:failing-id :rf/hydrate`,
      `:element-id` (a DOM element id — a structural locator, not app data),
      `:reason` (a framework-authored sentence), `:recovery :no-recovery`. No
      slot lifts a value OUT of the untrusted payload — the parse FAILED, so
      there is no parsed value to carry; only the structural fact that it failed.

    - re-frame.ssr.error-projector (census B4) — `emit-always-on-error!` ships
      the `:rf.error/sanitised-on-projection` FALLBACK record (the public
      boundary fell back to the locked generic-500). Structural status fact;
      carries no app value (the whole point of the fallback is that projection
      failed, so the unprojected payload is NOT carried forward — re-entry guard,
      RULED rf2-hhutya).

    - re-frame.ssr.ring.lifecycle (census B6) — `emit-always-on-error!` ships
      the ring-host lifecycle error record (`:rf.error/ssr-ring-error-view-
      failed` and siblings): structural host-lifecycle facts + a host
      `:exception` residual (the documented exception non-goal — Security.md
      §Out-of-scope), no app-db / event slice.

    - re-frame.router (rf2-fcbrjo) — `handle-depth-exceeded!` ships the
      `:rf.error/drain-depth-exceeded` halt record. VETTED structural-only: it
      carries `:depth` / `:queue-size` (ints), `:last-event-id` /
      `:tail-event-ids` (the cycle-evidence ring) / `:dropped-event-ids` (event
      ID KEYWORDS only — the halt path deliberately extracts `(first event)`, so
      NO event args ride), `:rollback?` (bool), `:recovery :no-recovery`, and
      `:time`. No slot lifts a value out of an event / app-db slice, and there
      is no exception residual (a depth halt is a control-flow limit, not a
      thrown error), so the raw corpus-leg fan-out is value-free. The rich human
      `:reason` prose + full `:last-event` vector ride the DCE'd dev trace ONLY,
      never this record.

      rf2-mwv4e adds a SECOND record from the same namespace —
      `emit-boundary-rejection-record!` ships the `:rf.schema/at-boundary`
      refusal (`:rf.error/schema-validation-failure`, `:source :boundary`) so an
      opt-in production security gate is observable to the person who opted in;
      before it, a refused untrusted payload skipped its handler, emitted
      nothing on either axis, and had its `:events` record report `:outcome
      :ok`. VETTED structural-only, and on THIS record that is a stricter
      guarantee than a scrub rather than a weaker one. A validation failure's
      natural detail is THE VALUE THAT FAILED, which at a system boundary is
      attacker-controlled or user-private by definition and can carry secrets in
      keys the declared schema never anticipated — so no schema-aware redactor
      can be trusted to have seen them, and the sibling treatment on
      `re-frame.routing.url-change` / `re-frame.ssr.response` (scrub the one
      untrusted slot, because that slot IS the observability payload) does not
      transfer. Every payload-derived slot is therefore OMITTED OUTRIGHT: no
      event vector, no `:value`, no `:received`, no `:explain`, no schema form,
      and no human `:reason` — the last deliberately, since the dev trace's
      `:reason` interpolates the offending value and would reintroduce the
      `ssr/hydrate` prose hazard. What remains is a CLOSED key set of framework
      keywords and structural identifiers: `:error`, `:where :event`, `:source
      :boundary`, `:event-id` / `:failing-id` / `:schema-id` (all the event id —
      a registered handler keyword, not caller data), `:frame`, `:recovery
      :no-recovery`, `:time`. Nothing lifts a value out of the event vector or an
      app-db slice, and there is no exception residual (a refusal is a gate
      decision, not a throw). The key set is pinned CLOSED by
      `re-frame.always-on-validation-production-test`, which also asserts the
      ABSENCE of every payload-bearing key by name. The rich diagnosis
      (`:value` / `:received` / `:explain` / interpolated `:reason`) rides the
      DCE'd dev trace in `re-frame.spec` ONLY, exactly as the depth-halt prose
      does — so this namespace's two records share one discipline.

  NOTE the census B5 site `re-frame.ssr.hydrate` is DELIBERATELY ABSENT: it
  lifts the untrusted `:payload-frame-id` out of the deserialised payload, so it
  is NOT structural-only — it ROUTES through `project-egress` (the B5 fix) and
  is governed by the routing arm, not this list. If it ever stopped routing it
  would fail the coverage test, which is the point."
  '#{re-frame.ssr.boot
     re-frame.ssr.error-projector
     re-frame.ssr.ring.lifecycle
     re-frame.router

     ;; rf2-ov56u / ruling rf2-rqje9 — `re-frame.routing.url-change`'s
     ;; `emit-route-miss-error!` ships the URL-driven `:rf.error/no-such-handler`
     ;; `:kind :route` miss on the always-on axis, so an unroutable SSR request
     ;; answers 404 rather than a soft-404 200 under `-Dre-frame.debug=false`.
     ;; VETTED structural-only: every slot but one is a fixed framework keyword
     ;; or a structural id — `:error`, `:kind :route`,
     ;; `:recovery :replaced-with-default`, `:frame`, `:time`, and `:reason`,
     ;; which is only ever `:match-error` (the `match-url-fail-closed`
     ;; discriminator) or `:malformed-url`. No prose and so no interpolation of
     ;; an untrusted value into it (the ssr/hydrate hazard), no exception
     ;; residual, and no slot lifts a value out of an event vector or an app-db
     ;; slice.
     ;;
     ;; The one non-enum slot, `:url`, is the navigation LOCATOR, scrubbed by
     ;; `privacy.url/redact-url-tag` inside `route-miss-tags` — BEFORE either
     ;; axis sees it — so query VALUES and the whole opaque `#fragment` are
     ;; already the `rf/redacted` sentinel on the record that fans out. It sits
     ;; on THIS list rather than the routing arm because `project-egress` is the
     ;; wrong instrument here, not the costlier one: it projects tree slots
     ;; against the FRAME'S CLASSIFICATION registry, and a route MISS has no
     ;; matched route → no `:params` / `:query` schema to path-target — which is
     ;; precisely why the blanket `redact-url-carriers` policy exists. Under a
     ;; live frame no declared path covers `:url`, so `project-egress` would ride
     ;; it through untouched; under a frameless one it would blanket-redact the
     ;; structured path the ruling deliberately keeps. The carrier scrub is the
     ;; stronger guarantee on this path.
     re-frame.routing.url-change

     ;; rf2-6jqa8 — `re-frame.ssr.response`'s `dispatch-safe-redirect-record!`
     ;; ships the three `:rf.error/safe-redirect-*` rejections on the always-on
     ;; axis, so an attempted open redirect / `javascript:` scheme reaches an
     ;; off-box shipper in a production build instead of being silently
     ;; no-op'd. (The CRLF / NUL gate on the SAME fx already rode
     ;; `:rf.error/fx-handler-exception` and was always-on — two halves of one
     ;; security surface with opposite production observability.)
     ;; VETTED structural-only: every slot but one is a fixed framework keyword
     ;; or a parsed URL component — `:error`, `:recovery :no-recovery`,
     ;; `:frame`, `:time`, `:scheme` and `:host` (parsed off the location, and
     ;; on this path they ARE the security signal), `:reason` (a framework enum
     ;; plus one framework-authored string on the parse-failure arm), and
     ;; `:allowlist`, which is the CALL'S OWN policy input rather than caller
     ;; data. No prose interpolating an untrusted value (the ssr/hydrate
     ;; hazard), no exception residual, and no slot lifts a value out of an
     ;; event vector or an app-db slice. The key set is pinned CLOSED by
     ;; `re-frame.ssr-safe-redirect-production-test`.
     ;;
     ;; The one non-enum slot, `:location`, is BY CONSTRUCTION caller-untrusted
     ;; — that is the entire reason `:rf.server/safe-redirect` exists as the
     ;; sibling of the caller-trusted `:rf.server/redirect` — and it is
     ;; scrubbed by `privacy.url/redact-url-tag` inside `safe-redirect-tags`,
     ;; BEFORE either axis sees it, so query VALUES and the whole opaque
     ;; `#fragment` are already the `rf/redacted` sentinel on the record that
     ;; fans out. It sits on THIS list rather than the routing arm for the same
     ;; reason `re-frame.routing.url-change` does: `project-egress` projects
     ;; tree slots against the FRAME'S CLASSIFICATION registry, and a REJECTED
     ;; redirect has no matched route and no schema to path-target. Under a
     ;; live frame no declared path covers `:location`, so `project-egress`
     ;; would ride it through untouched; under a frameless one it would
     ;; blanket-redact the scheme and host that are the whole point of the
     ;; record. The carrier scrub is the stronger guarantee on this path.
     re-frame.ssr.response})

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest source-scan-finds-the-chokepoint-callers
  (testing "Sanity: the source scan reaches the artefact src trees and finds
            the known always-on fan-out chokepoint callers. A zero / tiny
            result means the src roots did not resolve from the test CWD (a
            path-layout change) — fail loudly rather than vacuously passing the
            coverage invariant below with an empty caller set.

            `(seq roots)` alone is NOT that guard, and rf2-2cu7f is the proof:
            for as long as this scan enumerated roots at depth 1 it resolved 16
            of them, walked 377 files, reached not one of the four nested
            adapter artefacts, and passed here. A floor on whether the walk
            found ANYTHING says nothing about whether it found EVERYTHING, so
            the coverage claim is the cross-check — this corpus against the
            independently-shaped one the repo's other source-scanning lints
            use."
    (let [roots   rf.impl-source-corpus/src-roots
          callers (chokepoint-caller-namespaces)
          {:keys [missing extra]} (rf.impl-source-corpus/corpus-cross-check)]
      (is (seq roots)
          "at least one artefact src root resolved from the JVM test CWD")
      (is (empty? missing)
          (str "the scan MISSED production source that the path-shaped "
               "enumeration of implementation/**/src/ finds — an artefact root "
               "the walk does not reach, which is exactly how the adapters went "
               "unscanned (rf2-2cu7f). Missing: " (pr-str (vec missing))))
      (is (empty? extra)
          (str "the scan reached source OUTSIDE implementation/**/src/: "
               (pr-str (vec extra))))
      ;; The chokepoint definer + at least the SSR caller family must be found.
      (is (contains? callers chokepoint-def-ns)
          (str chokepoint-def-ns " (the chokepoint definer) is among the "
               "scanned callers"))
      (is (>= (count callers) 4)
          (str "source scan found the known fan-out chokepoint callers "
               "(>= 4 namespaces: the definer + the SSR family), not a broken "
               "/ empty scan; found " (count callers) ": "
               (pr-str (sort (keys callers)))))
      ;; Anchor on the B5 routing site — proves the routing arm is live.
      (is (get-in callers ['re-frame.ssr.hydrate :routes?])
          "re-frame.ssr.hydrate (census B5) routes through project-egress"))))

(deftest every-chokepoint-caller-routes-or-is-allow-listed
  (testing "rf2-mrtis6 (the enforcement spine): every namespace that CALLS an
            always-on union-record fan-out chokepoint
            (`dispatch-error-record!` / `dispatch-frame-teardown-report!`)
            either (a) IS the `error-emit` chokepoint definer (routes the frame
            leg via route-error-record! → project-egress), (b) routes its
            untrusted slots through `project-egress` in the same namespace, or
            (c) is on the explicit `structural-only-allow-list` (a record vetted
            value-free). A NEW caller that does none of these ships a
            payload-bearing always-on record to corpus listeners UNREDACTED —
            the egress-boundary analogue of the cmxiss `forgot the always-on
            half` bug — and fails HERE with a missing-routing diagnostic."
    (let [callers   (chokepoint-caller-namespaces)
          unhandled (->> callers
                         (remove (fn [[ns-sym {:keys [routes?]}]]
                                   (or (= ns-sym chokepoint-def-ns)
                                       routes?
                                       (contains? structural-only-allow-list
                                                  ns-sym))))
                         (map (fn [[ns-sym info]] [ns-sym (:file info)]))
                         (into {}))]
      (is (empty? unhandled)
          (str "always-on union-record fan-out callers that NEITHER route "
               "their untrusted slots through `project-egress` NOR are on the "
               "structural-only-allow-list (rf2-mrtis6 — they ship a "
               "payload-bearing record to corpus listeners raw): "
               (pr-str unhandled)
               " — either route the untrusted slots through `project-egress` "
               "before the fan-out (the ssr/hydrate B5 model), or, if the "
               "record is VETTED value-free (structural ids + a host exception "
               "residual only), add the namespace to structural-only-allow-list "
               "with a one-line rationale.")))))

(deftest allow-list-stays-honest
  (testing "rf2-mrtis6: every namespace on the structural-only-allow-list must
            STILL call a fan-out chokepoint AND must STILL NOT route through
            `project-egress`. This keeps the allow-list from rotting: an entry
            that stops calling the chokepoint (the caller was deleted /
            refactored) must be dropped, and an entry that NOW routes through
            `project-egress` (it graduated to projecting its slots) must ALSO be
            dropped so the routing arm — not this list — governs it. Both
            drifts fail here, forcing the co-edit."
    (let [callers          (chokepoint-caller-namespaces)
          no-longer-caller (set/difference structural-only-allow-list
                                           (set (keys callers)))
          now-routes       (->> structural-only-allow-list
                                (filter (fn [ns-sym]
                                          (get-in callers [ns-sym :routes?])))
                                set)]
      (is (empty? no-longer-caller)
          (str "structural-only-allow-list entries that no longer call a "
               "fan-out chokepoint (drop them, rf2-mrtis6): "
               (pr-str (sort no-longer-caller))))
      (is (empty? now-routes)
          (str "structural-only-allow-list entries that NOW route through "
               "`project-egress` (drop them so the routing arm governs them, "
               "rf2-mrtis6): " (pr-str (sort now-routes)))))))
