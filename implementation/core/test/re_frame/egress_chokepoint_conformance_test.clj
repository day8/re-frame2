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

  SOURCE-SCAN every artefact `src/` tree (reusing `impl-src-roots` /
  `non-test-source-files` from the error-catalogue test) for the namespaces
  that CALL either chokepoint, and assert each calling namespace is EITHER:

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
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Source roots + file enumeration (reused verbatim from
;; error_catalogue_channel_conformance_test — the cmxiss enforcement precedent)
;; ---------------------------------------------------------------------------

(def ^:private impl-src-roots
  "Every artefact's non-test source root, resolved from the JVM test CWD
  (`implementation/core/` per rf2-0hxm → repo root is `../../`). Falls back to
  the pre-split `../implementation/...` layout for a transitional REPL run from
  `implementation/`. Only existing dirs are kept."
  (let [bases ["../../implementation" "../implementation" "../.."]]
    (->> bases
         (mapcat (fn [base]
                   (let [d (io/file base)]
                     (when (.isDirectory d)
                       (->> (.listFiles d)
                            (filter #(.isDirectory %))
                            (map #(io/file % "src")))))))
         (filter #(.isDirectory %))
         distinct
         vec)))

(def ^:private source-file-exts
  #{".clj" ".cljc" ".cljs"})

(defn- non-test-source-files
  "Every `.clj` / `.cljc` / `.cljs` file under the artefact src roots. src roots
  carry only production source; we guard with a `/test/` path check anyway."
  []
  (->> impl-src-roots
       (mapcat (fn [root] (file-seq root)))
       (filter #(.isFile %))
       (filter (fn [f]
                 (let [n (.getName f)]
                   (some #(str/ends-with? n %) source-file-exts))))
       (remove (fn [f]
                 (let [p (str/replace (.getPath f) "\\" "/")]
                   (or (str/includes? p "/test/")
                       (str/ends-with? (.getName f) "_test.clj")
                       (str/ends-with? (.getName f) "_test.cljc")
                       (str/ends-with? (.getName f) "_test.cljs")))))))

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
    (non-test-source-files)))

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

  NOTE the census B5 site `re-frame.ssr.hydrate` is DELIBERATELY ABSENT: it
  lifts the untrusted `:payload-frame-id` out of the deserialised payload, so it
  is NOT structural-only — it ROUTES through `project-egress` (the B5 fix) and
  is governed by the routing arm, not this list. If it ever stopped routing it
  would fail the coverage test, which is the point."
  '#{re-frame.ssr.boot
     re-frame.ssr.error-projector
     re-frame.ssr.ring.lifecycle})

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest source-scan-finds-the-chokepoint-callers
  (testing "Sanity: the source scan reaches the artefact src trees and finds
            the known always-on fan-out chokepoint callers. A zero / tiny
            result means the src roots did not resolve from the test CWD (a
            path-layout change) — fail loudly rather than vacuously passing the
            coverage invariant below with an empty caller set."
    (let [roots   impl-src-roots
          callers (chokepoint-caller-namespaces)]
      (is (seq roots)
          "at least one artefact src root resolved from the JVM test CWD")
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
