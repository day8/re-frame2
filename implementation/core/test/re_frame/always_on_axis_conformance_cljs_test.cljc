(ns re-frame.always-on-axis-conformance-cljs-test
  "EP-0008 / rf2-sgz1zq — the always-on EXERCISE leg of the channel
  conformance pin. The companion catalogue-side leg lives in
  `re-frame.error-catalogue-channel-conformance-test` (JVM; parses the
  Spec 009 catalogue markdown).

  Per Spec 009 §Error event catalogue: every `always-on` category MUST be
  exercised through the `register-error-listener!` error-emit substrate in
  at least one test, \"so promotion is real, not documentary.\" This file
  is that exercise — DATA-DRIVEN over the full always-on set so it stays
  green as categories are added (add the category to the catalogue +
  `always-on-categories` below and it is automatically exercised here).

  ## What this proves (and what it deliberately does NOT)

  The bead asks that every always-on category be \"exercised through
  `dispatch-on-error!` (the always-on axis) in at least one test.\" The
  always-on axis IS `re-frame.error-emit/dispatch-on-error!` (and the
  bounded-report sibling `dispatch-frame-teardown-report!` for the
  frame-teardown row, per Spec 009 §Channel-promotion catalogue rows). So
  this test drives EACH always-on category THROUGH that substrate fn and
  asserts the `register-error-listener!` registry fans the record out
  carrying that exact category.

  This is the CONTRACT-level pin: it proves the always-on axis carries
  every always-on category end-to-end (the fan-out, the category slot, the
  production-survivable surface). It is intentionally NOT a re-derivation
  of each real emit SITE — those per-site integration tests already exist
  and are cross-referenced below (`on-error-test`, the per-category
  `*_always_on_cljs_test` suites, the machines / flows artefact tests).
  Re-driving every emit site here would duplicate that coverage and pull
  the optional machines / flows / routing artefacts into the core test ns
  for no added contract assurance. The axis is the thing this conformance
  bead pins; the literal `always-on-categories` is held honest against the
  catalogue by the JVM companion's
  `parsed-always-on-set-equals-the-exercise-literal` test.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. `error-emit` is plain CLJC; no
  DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; The always-on set — the SINGLE literal this leg iterates.
;;
;; This set is PINNED against the Spec 009 catalogue's `always-on` rows by
;; the JVM companion test
;; (`re-frame.error-catalogue-channel-conformance-test/parsed-always-on-set-
;; equals-the-exercise-literal`). Adding a category here without graduating
;; it in the catalogue (or vice versa) fails that test. So this literal is
;; not a fragile hand-maintained duplicate: the catalogue is authoritative
;; and the companion enforces equality.
;; ---------------------------------------------------------------------------

(def always-on-categories
  "Every `:rf.error/*` category Spec 009 §Error event catalogue marks
  `always-on` — the production-survivable error-emit axis (surface #4).
  Pinned == the parsed catalogue always-on set by the JVM companion."
  #{:rf.error/handler-exception
    :rf.error/coeffect-exception
    :rf.error/interceptor-exception
    :rf.error/machine-action-exception
    :rf.error/fx-handler-exception
    :rf.error/sub-exception
    :rf.error/no-such-sub
    :rf.error/sub-input-fn-exception
    :rf.error/sub-input-fn-bad-return
    :rf.error/no-such-handler
    :rf.error/no-frame-context
    ;; rf2-9kpigo: the bad-frame-provider-arg reject graduated `always-on` in
    ;; the Spec 009 catalogue. A public `frame-provider` whose `:frame` is
    ;; non-nil but not a keyword is a bad public provider argument; it fires in
    ;; production too (a corrupt provider config is a correctness contract,
    ;; like `:rf.error/no-frame-context`), so it rides the always-on axis via
    ;; `frame/emit-bad-frame-provider-arg!` → `dispatch-on-error!`. This leg
    ;; drives it through the `:else` per-event axis to prove the listener
    ;; fan-out; the JVM companion keeps the literal == the catalogue's
    ;; always-on set.
    :rf.error/bad-frame-provider-arg
    :rf.error/override-fallthrough
    :rf.error/reserved-fx-override
    :rf.error/no-such-fx
    :rf.error/frame-destroyed
    :rf.error/write-after-destroy
    :rf.error/flow-eval-exception
    ;; The two-arg report fn (not dispatch-on-error!) carries this row —
    ;; the bounded single-report idiom (Spec 009 §Channel-promotion
    ;; catalogue rows). Exercised through its own report path below.
    :rf.error/frame-teardown-failed
    :rf.error/on-destroy-handler-exception
    ;; EP-0008 (rf2-hhutya): the seven promoted SSR error categories. These
    ;; ride the GENERAL non-event always-on helper
    ;; `error-emit/dispatch-error-record!` (the union-record sibling of the
    ;; teardown report), NOT the event-centric `dispatch-on-error!` — an SSR
    ;; render / writer / head / projector / hydration-parse failure is not a
    ;; dispatched-event failure. (`:rf.error/malformed-hydration-payload`
    ;; covers BOTH the hydrate-handler path AND the pre-frame FRAMELESS
    ;; parse sub-path — one catalogue category, two emit sites.) Driven
    ;; through `dispatch-error-record!` below.
    :rf.error/ssr-render-failed
    :rf.error/ssr-streaming-writer-failed
    :rf.error/malformed-hydration-payload
    :rf.error/ssr-head-resolution-failed
    :rf.error/sanitised-on-projection
    :rf.error/ssr-ring-error-view-failed
    ;; rf2-nv3mua: the SSR hydration frame-id mismatch graduated `always-on`
    ;; in the Spec 009 catalogue. The `:rf/hydrate` HANDLER guards the direct-
    ;; `dispatch-sync` split path (the boot helper `hydrate!` validates +
    ;; throws pre-dispatch — diagnostic — but a direct dispatch bypasses it):
    ;; a present-and-different payload `:rf/frame-id` against the dispatch
    ;; target fails CLOSED and emits this NON-EVENT union record via the
    ;; `:error-emit/dispatch-error-record` hook (the `malformed-hydration-
    ;; payload` sibling) so an off-box shipper sees the wrong-frame rejection
    ;; under `goog.DEBUG=false`. Driven through `dispatch-error-record!` below.
    :rf.error/hydration-frame-id-mismatch
    ;; EP-0017 (rf2-alc1lf): the cofx error family graduated `always-on` in
    ;; the Spec 009 catalogue by the `:rf.cofx` contract spec commit
    ;; (2536b2d98). Each is a causal-token / coeffect-contract validation that
    ;; fires in production too (an out-of-contract durable value is corrupt
    ;; state), so it rides the always-on axis. The emit SITES land across
    ;; slices A.2/A.3/B; this leg
    ;; drives every always-on category through `dispatch-on-error!` to prove
    ;; the listener fan-out, so the literal pins the catalogue's always-on set
    ;; regardless of which slice wires up the emit site. The JVM companion
    ;; (`parsed-always-on-set-equals-the-exercise-literal`) keeps this set ==
    ;; the parsed catalogue.
    :rf.error/unregistered-cofx
    :rf.error/missing-required-cofx
    :rf.error/cofx-value-invalid
    :rf.error/inject-cofx-removed
    ;; EP-0018 (rf2-xhfxcs): the retired event-registration names graduated
    ;; `always-on` in the Spec 009 catalogue by Slice A. `reg-event-db` /
    ;; `reg-event-fx` were removed (no alias, EP-0007 rule 2) and public
    ;; `reg-event-ctx` demoted to a framework-internal primitive; each call
    ;; is now a hard error that fires in production too (a correctness
    ;; contract — naming `reg-event` / `reg-interceptor` as the replacement),
    ;; so the row rides the always-on axis. The emit SITES land across the
    ;; EP-0018 slices; this leg drives every always-on category through
    ;; `dispatch-on-error!` to prove the listener fan-out, so the literal
    ;; pins the catalogue's always-on set regardless of which slice wires up
    ;; the emit site. The JVM companion
    ;; (`parsed-always-on-set-equals-the-exercise-literal`) keeps this set ==
    ;; the parsed catalogue.
    :rf.error/reg-event-db-removed
    :rf.error/reg-event-fx-removed
    :rf.error/reg-event-ctx-removed
    ;; rf2-ywv74m: the machine fail-closed spawn reject graduated `always-on`
    ;; in the Spec 009 catalogue. A runtime `:rf.machine/spawn` (or `:spawn-all`
    ;; per-child) naming an UNREGISTERED machine TYPE (no inline `:definition`)
    ;; is rejected fail-closed and emits this NON-EVENT union record via the
    ;; `:error-emit/dispatch-error-record` hook so an off-box shipper sees a
    ;; refused spawn under `goog.DEBUG=false`. The emit SITE lives in the
    ;; `machines` artefact (`machines/lifecycle_fx/spawn.cljc`); this leg drives
    ;; the category through `dispatch-on-error!` (the `:else` axis below) to
    ;; prove the listener fan-out, so the literal pins the catalogue's always-on
    ;; set regardless of the artefact wiring. The JVM companion
    ;; (`parsed-always-on-set-equals-the-exercise-literal`) keeps this set ==
    ;; the parsed catalogue.
    :rf.error/machine-spawn-unregistered-type
    ;; rf2-fcbrjo: the drain-depth halt graduated `always-on` in the Spec 009
    ;; catalogue. A runaway / infinite dispatch cascade hitting `:drain-depth`
    ;; is production-reachable + data-dependent, so before promotion it went
    ;; SILENT under `goog.DEBUG=false` (the dev trace is DCE'd). It now fans a
    ;; STRUCTURAL-ONLY NON-EVENT union record (ids / counts / the cycle-evidence
    ;; ring `:tail-event-ids`) via the `:error-emit/dispatch-error-record` hook
    ;; so an off-box shipper sees the halt in production. The emit SITE lives in
    ;; `re-frame.router/handle-depth-exceeded!`; this leg drives the category
    ;; through `dispatch-error-record!` (the `record-categories` branch below)
    ;; to prove the listener fan-out.
    :rf.error/drain-depth-exceeded})

;; The frame-teardown report is the ONE always-on category that rides the
;; bounded `dispatch-frame-teardown-report!` sibling (Spec 009: one record
;; per destroy carrying a `:hook-failures` vector, NOT one per item).
(def ^:private report-categories
  #{:rf.error/frame-teardown-failed})

;; EP-0008 (rf2-hhutya): the promoted SSR categories ride the GENERAL
;; non-event always-on helper `dispatch-error-record!` (the union-record
;; path the teardown report also rides under the hood). The data-driven
;; loop routes them to that fn with a minimal union record; every remaining
;; category goes through the event-centric `dispatch-on-error!`.
(def ^:private record-categories
  #{:rf.error/ssr-render-failed
    :rf.error/ssr-streaming-writer-failed
    :rf.error/malformed-hydration-payload
    :rf.error/ssr-head-resolution-failed
    :rf.error/sanitised-on-projection
    :rf.error/ssr-ring-error-view-failed
    ;; rf2-nv3mua: the direct-dispatch frame-id-mismatch handler guard rides
    ;; the same non-event union-record helper as the malformed-payload guard.
    :rf.error/hydration-frame-id-mismatch
    ;; rf2-fcbrjo: the drain-depth halt rides the same non-event union-record
    ;; helper (structural-only: ids / counts / the cycle-evidence ring).
    :rf.error/drain-depth-exceeded})

;; ---------------------------------------------------------------------------
;; Fixture — fresh registrar + plain-atom adapter per test; the always-on
;; error-listener registry (a `defonce` atom) cleared so a listener from one
;; test cannot leak into the next.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Per-category driver: push ONE record for `cat` through the always-on
;; axis. The teardown-report row uses its bounded-report fn; every other
;; category uses `dispatch-on-error!` (the per-event always-on surface).
;; ---------------------------------------------------------------------------

(defn- drive-category!
  "Surface one always-on record for `cat` through the always-on substrate.
  Returns nil. The exact payload is not the contract under test here (the
  per-site integration tests pin payload shapes); this drives the category
  through the axis so the listener fan-out can be asserted."
  [cat]
  (cond
    (contains? report-categories cat)
    ;; The bounded teardown report — a non-empty :hook-failures vector so
    ;; the report fn does not short-circuit (it no-ops on empty).
    (error-emit/dispatch-frame-teardown-report!
      :conformance/frame
      [{:hook :ssr/on-frame-destroyed
        :exception (ex-info "teardown hook threw" {})
        :where :safe-call-hook!}]
      0)

    (contains? record-categories cat)
    ;; EP-0008 (rf2-hhutya): the general non-event union-record helper. A
    ;; minimal union record `{:error :frame :time + flat keys}` — the exact
    ;; per-site payloads (`:exception` / `:phase` / `:reason` / …) are
    ;; pinned by the per-site SSR integration tests; here we just drive the
    ;; category THROUGH the axis so the listener fan-out can be asserted.
    (error-emit/dispatch-error-record!
      {:error     cat
       :frame     :conformance/frame
       :time      0
       :exception (ex-info "conformance" {})
       :recovery  :no-recovery})

    :else
    ;; The per-event always-on axis. Arity:
    ;; [error-kw event event-id frame-id exception elapsed-ms time].
    (error-emit/dispatch-on-error!
      cat
      [:conformance/event]
      :conformance/event
      :conformance/frame
      (ex-info "conformance" {})
      0
      0)))

;; ===========================================================================
;; The conformance pin: EVERY always-on category fans out through the
;; corpus-wide `register-error-listener!` substrate.
;; ===========================================================================

(deftest every-always-on-category-fans-out-through-the-error-listener
  (testing "Per Spec 009 §Error event catalogue (the rf2-sgz1zq pin):
            every catalogued `always-on` category is exercised through the
            `register-error-listener!` substrate — proving promotion is
            real, not documentary. Data-driven over the full
            `always-on-categories` set (pinned == the catalogue's
            always-on rows by the JVM companion), so it stays green as
            categories are added: each new always-on category is driven
            through the axis the moment it joins the set."
    (let [seen (atom #{})]
      (rf/register-listener! :errors
        :conformance/recorder
        (fn [record] (swap! seen conj (:error record))))
      (doseq [cat always-on-categories]
        (drive-category! cat))
      ;; Every always-on category must have appeared on the listener as the
      ;; record's :error slot. A category that did NOT fan out (a substrate
      ;; that dropped it, a report fn that short-circuited) is a gap.
      (let [missing (set/difference always-on-categories @seen)]
        (is (empty? missing)
            (str "always-on categories that did NOT fan out through "
                 "register-error-listener!: " (pr-str (sort missing)))))
      (is (= always-on-categories @seen)
          "the listener received EXACTLY the always-on set (no extras, no
           gaps) — the always-on axis carries every always-on category"))))

(deftest each-category-fans-out-exactly-once-and-carries-its-category
  (testing "Per Spec 009: a single drive of category `cat` through the
            always-on axis produces EXACTLY ONE listener record whose
            `:error` slot is `cat`. Pins the 1:1 fan-out (no duplicate
            emission, no category mislabelling) for every always-on
            category individually — the data-driven per-category
            counterpart to the aggregate test above."
    (doseq [cat always-on-categories]
      (error-emit/clear-error-listeners!)
      (let [seen (atom [])]
        (rf/register-listener! :errors
          :conformance/recorder
          (fn [record] (swap! seen conj record)))
        (drive-category! cat)
        (let [for-cat (filter #(= cat (:error %)) @seen)]
          (is (= 1 (count for-cat))
              (str cat ": exactly one always-on record fanned out"))
          (is (= 1 (count @seen))
              (str cat ": only that one record fanned out (no stray "
                   "emission)")))))))

(deftest report-category-rides-the-bounded-report-not-per-event-axis
  (testing "Per Spec 009 §Channel-promotion catalogue rows: the
            `:rf.error/frame-teardown-failed` row is the bounded
            single-report idiom — it rides `dispatch-frame-teardown-report!`
            (one record per destroy with a `:hook-failures` vector), NOT
            the per-event `dispatch-on-error!`. Pins that the report fn is
            a no-op on an empty `:hook-failures` (no flood) yet fans the
            bounded record out when failures are present."
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :conformance/recorder
        (fn [record] (swap! seen conj record)))
      ;; Empty failures → no record (the no-flood contract).
      (error-emit/dispatch-frame-teardown-report! :conformance/frame [] 0)
      (is (empty? @seen) "empty :hook-failures → no always-on report")
      ;; Non-empty → one bounded record carrying the failures vector.
      (error-emit/dispatch-frame-teardown-report!
        :conformance/frame
        [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {})
          :where :safe-call-hook!}]
        0)
      (is (= 1 (count @seen)) "non-empty :hook-failures → one bounded report")
      (let [r (first @seen)]
        (is (= :rf.error/frame-teardown-failed (:error r)))
        (is (vector? (:hook-failures r))
            "the bounded report carries the per-hook detail as a vector")))))

;; ===========================================================================
;; Late-bind hooks the substrate publishes (so other artefacts can reach
;; the always-on axis without static-requiring error-emit — load-cycle
;; avoidance). Pinned here because they are part of the always-on axis's
;; addressable surface (Spec 009 §What IS available in production).
;; ===========================================================================

(deftest always-on-late-bind-hooks-are-published
  (testing "Per EP-0008 (rf2-500ech / rf2-ini4wr): `error-emit` publishes
            its always-on entry points as late-bind hooks at ns-load, so
            substrate / frame layers that cannot static-require it (load
            cycle) still reach the always-on axis in production. Both the
            per-event and the bounded-report hooks must be present."
    (is (some? (late-bind/get-fn :error-emit/dispatch-on-error))
        ":error-emit/dispatch-on-error hook is published")
    (is (some? (late-bind/get-fn :error-emit/dispatch-frame-teardown-report))
        ":error-emit/dispatch-frame-teardown-report hook is published")))

;; ===========================================================================
;; rf2-2bzwr7 — the corpus-wide listener facade exports are CLASSIFIED as
;; ADVANCED non-off-box-raw integration APIs, NOT off-box shipper surfaces.
;;
;; EP-0015 §9 makes the frame-owned `:observability` sink + `project-egress`
;; the normal off-box egress path; the corpus-wide listener registries deliver
;; an UNPROJECTED record (raw owner-local data can leave a frame) and so must
;; not present themselves as the off-box shipper API. This is the
;; REGRESSION/LINT coverage the bead asks for: a facade `:doc` that re-points
;; off-box shippers at the raw global listener path goes RED here. The match is
;; on the FACADE var metadata so it tracks the actual exported surface, not a
;; copy of the prose.
;; ===========================================================================

#?(:clj
   (deftest corpus-wide-listener-facade-docs-classified-advanced-not-off-box
     (testing "rf2-2bzwr7 / EP-0015 §9 — the unified `register-listener!`
               facade verb documents its `:events` / `:errors` corpus-wide
               streams as ADVANCED integration hooks that point off-box
               shippers at the frame-owned `:observability` sink, and do NOT
               present as the off-box shipper API (raw owner-local data can
               leave a frame through the unprojected corpus listener).
               rf2-ikjmkm: the four `register-(event|error)-listener!` pairs
               were collapsed into this stream-parameterized verb."
       (let [v   #'rf/register-listener!
             doc (:doc (meta v))]
         (is (string? doc) (str v " carries a docstring"))
         ;; POSITIVE: classified advanced + cross-frame, and points at the
         ;; frame-owned sink as the normal off-box path.
         (is (re-find #"(?i)advanced" doc)
             (str v ": docstring classifies the corpus-wide streams as ADVANCED"))
         (is (re-find #":observability" doc)
             (str v ": docstring points off-box shippers at the frame-owned "
                  ":observability sink (EP-0015 §9)"))
         (is (re-find #"(?i)EP-0015" doc)
             (str v ": docstring cites EP-0015 (the frame-owned egress model)"))
         ;; NEGATIVE: the prior off-box-shipper framing — naming Sentry /
         ;; Honeybadger / Rollbar as THE consumers of THIS surface, or
         ;; calling it "for off-box observability shippers" — must be gone.
         ;; The frame sink is the off-box surface; this listener is the
         ;; advanced fallback only.
         (is (not (re-find #"(?i)for off-box observability shippers" doc))
             (str v ": docstring no longer pitches the raw listener AS the "
                  "off-box shipper API (EP-0015 §9 — the frame sink is)"))))))
