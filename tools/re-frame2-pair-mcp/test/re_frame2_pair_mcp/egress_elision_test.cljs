(ns re-frame2-pair-mcp.egress-elision-test
  "Regression tests for the app-db egress-redaction fixes (rf2-6wvh5 +
  rf2-f1ose) — same privacy class, shared fix.

  ## The bugs

  Two pull-mode tools shipped raw slices of a live app's state off-box,
  bypassing the redaction that snapshot / get-path / the `:epoch`
  subscribe drain already route through:

    - rf2-6wvh5: `trace-window` / `watch-epochs` egress whole epoch
      records carrying `:db-before` / `:db-after` (and `:trigger-event`
      / `:trace-events`) app-db snapshots — a declared-sensitive slot
      rode off-box verbatim because the pull-mode ring was never routed
      through the framework's `re-frame.core/projected-record` egress
      projection (the single normative off-box-egress emission site for
      epoch records; core.cljc names the per-slot hand-walk an
      anti-pattern \"one missed `mapv projected-record` away from a
      leak\").
    - rf2-f1ose: `list-subscriptions :include-values` ships each sub's
      current `:value` (deref) raw — a value over a declared-sensitive
      app-db slot leaked the same way. A sub-cache value is NOT an
      epoch record, so its egress routes through the same
      `re-frame.core/elide-wire-value` walker `snapshot`'s `:sub-cache`
      slice uses (the two read the same reactive cache source).

  ## What these tests pin

  The redaction runs SERVER-SIDE inside the eval form the tool ships
  over nREPL — `projected-record` / `elide-wire-value` read the live
  `[:rf.runtime/elision]` runtime-db registry, which only exists app-side. A unit
  test can't run them (no live app), so — mirroring the discipline
  `subscribe_test.cljs` uses for `drain-form` — these tests assert the
  FORM-LEVEL contract: with the `--allow-sensitive-reads` gate OFF (the
  published-build default), the epoch eval forms map the egress page
  through `re-frame.core/projected-record`, and the list-subscriptions
  eval form wraps each sub `:value` through `re-frame.core/elide-wire-value`
  with `:rf.size/include-sensitive? false`; with the gate ON and
  `:include-sensitive true`, the records ship raw (no projection) and the
  sub-value walker threads `:rf.size/include-sensitive? true`.

  Plus an end-to-end shape check via the stub harness: an
  already-redacted record (what the live projection would produce)
  survives the client-side wire-pipeline with its `:rf/redacted`
  sentinel intact.

  Live end-to-end coverage (a real shadow-cljs runtime running the
  projection) is the cross-server conformance harness's job; the
  framework conformance file `epoch_mcp_egress_conformance_test.clj`
  pins `projected-record`'s semantics directly."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [re-frame.mcp-base.egress :as base-egress]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]
            [re-frame2-pair-mcp.tools.list-subscriptions :as ls]
            [re-frame2-pair-mcp.tools.snapshot :as snap]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

;; ---------------------------------------------------------------------------
;; Gate state is process-global (an atom in raw-state.cljs). Reset to the
;; published-build default (OFF) after every test so ordering can't leak.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:before (fn []
             ;; The signal-runtime! cache is process-global; clear it so a
             ;; test's `configure-raw-state!` round-trip is never skipped by
             ;; a prior test having already signalled the build (rf2-8fin7.2
             ;; — snapshot / record / watch-until now issue signal-runtime!).
             (raw-state/reset-runtime-signal-cache!))
   :after  (fn []
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))})

;; ---------------------------------------------------------------------------
;; Form-capturing stub. The probe form (substring `__re_frame2_pair_runtime`)
;; resolves truthy so `ensure-runtime!` passes; `configure-raw-state!` (the
;; raw-state signal) resolves nil; every other eval form is the tool's
;; SLICE form — captured into `forms` and answered with `canned`.
;; ---------------------------------------------------------------------------

(defn- with-capture!
  "Run `body-fn` with a stub `cljs-eval-value` that captures every
  non-probe / non-signal form into the `forms` atom and answers the
  slice form with `canned`. Returns the Promise from `body-fn`."
  [forms canned body-fn]
  (let [orig nrepl/cljs-eval-value
        answer (fn [form-str]
                 (cond
                   (str/includes? form-str "__re_frame2_pair_runtime")
                   (js/Promise.resolve true)

                   (str/includes? form-str "configure-raw-state!")
                   (js/Promise.resolve nil)

                   :else
                   (do (swap! forms conj form-str)
                       (js/Promise.resolve canned))))
        stub (fn
               ([_conn _build-id form-str]       (answer form-str))
               ([_conn _build-id form-str _opts] (answer form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- slice-form
  "The first captured non-probe/non-signal form — the slice eval the
  tool ships to read + redact the egress payload."
  [forms]
  (first @forms))

;; The slice form for the epoch tools — runtime returns a ready map for
;; trace-window's let-body / watch-epochs' epochs-since wrapper. Shape is
;; irrelevant to the form-contract assertions (we read the captured form,
;; not the response); we hand back an empty-ish epoch result so the tool
;; resolves cleanly.
(def ^:private epoch-canned
  {:epochs        []
   :matches       []
   :id-aged-out?  false
   :requested-id  nil
   :head-id       nil
   :next-id       nil
   :history-count 0
   :since-count   0
   :remaining     0})

;; ===========================================================================
;; rf2-6wvh5 — trace-window epoch :db-* egress
;; ===========================================================================

(deftest trace-window-gate-off-projects-records
  (testing "gate OFF (default): the slice form maps the egress page through projected-record"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (tw/trace-window-tool nil (tu/args->js {:ms 1000}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (some? form) "the tool shipped a slice eval form")
                       (is (str/includes? form "re-frame.core/projected-record")
                           "gate-off MUST route the egress page through projected-record")
                       (is (str/includes? form "mapv (fn [r#] (re-frame.core/projected-record")
                           "every record in the page is projected via a fn literal threading the egress opts")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — Pair-MCP is an off-box tool wire; the page projects under :rf.egress/off-box-tool")
                       (done)))))))))

(deftest trace-window-gate-on-include-sensitive-routes-through-projection
  (testing "gate ON + include-sensitive true: STILL projected, threading :include-sensitive? true under off-box-tool (rf2-m9duxl, rf2-nmjcll)"
    ;; rf2-m9duxl — `:include-sensitive true` is NOT a raw bypass. It routes
    ;; THROUGH `projected-record` as the `:include-sensitive? true` egress opt
    ;; (app-db sensitive axis only), composed OVER the off-box-tool profile
    ;; floor (rf2-nmjcll). fx-args / runtime-db / large slots / `:redact-fn`
    ;; stay fail-closed because we do NOT pass their opts.
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (tw/trace-window-tool nil (tu/args->js {:ms 1000 :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "re-frame.core/projected-record")
                           "include-sensitive STILL routes through projected-record — never a raw bypass")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — the off-box-tool boundary is named even on the sensitive opt-in path")
                       (is (str/includes? form ":include-sensitive? true")
                           "the app-db sensitive axis is threaded INTO the projection (composed over the off-box-tool floor)")
                       (is (not (str/includes? form ":include-fx-args?"))
                           "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                       (is (not (str/includes? form ":include-runtime-db?"))
                           "runtime-db axis is NOT lifted by include-sensitive (orthogonal)")
                       (is (not (str/includes? form ":include-large?"))
                           "large axis is NOT lifted by include-sensitive (orthogonal)")
                       (done)))))))))

(deftest trace-window-gate-on-default-still-projects
  (testing "gate ON but include-sensitive omitted (default false): records STILL projected"
    ;; The opt-in is two-key: launch flag AND per-call include-sensitive.
    ;; The flag alone does not flip the per-call default — a forgetful
    ;; caller still gets redaction.
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (tw/trace-window-tool nil (tu/args->js {:ms 1000}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "re-frame.core/projected-record")
                           "gate-on alone (no per-call opt-in) still projects — fail-safe default")
                       (done)))))))))

;; ===========================================================================
;; rf2-6wvh5 — watch-epochs epoch record egress
;; ===========================================================================

(deftest watch-epochs-gate-off-projects-records
  (testing "gate OFF (default): watch-epochs maps the matched-page through projected-record"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (we/watch-epochs-tool nil (tu/args->js {:pred {:event-id :auth/sign-in}}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "mapv (fn [r#] (re-frame.core/projected-record")
                           "gate-off MUST route the egress page through projected-record (fn literal threading opts)")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — watch-epochs is an off-box tool wire; the page projects under :rf.egress/off-box-tool")
                       (done)))))))))

(deftest watch-epochs-gate-on-include-sensitive-routes-through-projection
  (testing "gate ON + include-sensitive true: STILL projected, threading :include-sensitive? true under off-box-tool (rf2-m9duxl, rf2-nmjcll)"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (we/watch-epochs-tool nil (tu/args->js {:include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "re-frame.core/projected-record")
                           "include-sensitive STILL routes through projected-record — never a raw bypass")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — the off-box-tool boundary is named even on the sensitive opt-in path")
                       (is (str/includes? form ":include-sensitive? true")
                           "the app-db sensitive axis is threaded INTO the projection (composed over the off-box-tool floor)")
                       (is (not (str/includes? form ":include-fx-args?"))
                           "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                       (is (not (str/includes? form ":include-runtime-db?"))
                           "runtime-db axis is NOT lifted by include-sensitive (orthogonal)")
                       (is (not (str/includes? form ":include-large?"))
                           "large axis is NOT lifted by include-sensitive (orthogonal)")
                       (done)))))))))

(deftest watch-epochs-gate-on-default-still-projects
  (testing "gate ON but include-sensitive omitted (default false): records STILL projected"
    ;; rf2-ywn27.2 — the two-key opt-in fail-safe, the watch-epochs SIBLING
    ;; of `trace-window-gate-on-default-still-projects` /
    ;; `snapshot-epochs-gate-on-default-still-projects`. The opt-in is
    ;; TWO-KEY (epoch_egress.cljs:35-50): the launch flag
    ;; --allow-sensitive-reads ALONE does NOT flip the per-call default.
    ;; watch_epochs.cljs:61-63 computes `incl? (if (raw-state-allowed?)
    ;; (parse-bool-arg ... :include-sensitive) false)` — gate-ON + omitted
    ;; arg ⇒ parse-bool-arg false ⇒ incl? false ⇒ project? true. A
    ;; regression that flipped the per-call default to true under the gate
    ;; (e.g. `incl? (raw-state-allowed?)`, the plausible "the flag IS the
    ;; opt-in" misreading) would leak raw epoch state on EVERY poll once an
    ;; operator launched with the flag for ONE deliberate read. Pre-this
    ;; the watch-epochs gate-ON coverage only asserted the
    ;; include-sensitive:true case, so the flip shipped GREEN here while
    ;; trace-window's identical regression WOULD be caught — asymmetric.
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (we/watch-epochs-tool nil (tu/args->js {}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "mapv (fn [r#] (re-frame.core/projected-record")
                           "gate-on alone (no per-call opt-in) still projects — fail-safe default")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — still the off-box-tool boundary under the gate without the opt-in")
                       (done)))))))))

;; ===========================================================================
;; rf2-f1ose — list-subscriptions :include-values sub :value egress
;; ===========================================================================

(def ^:private sub-cache-canned
  {:ok?   true
   :frame :rf/default
   :count 1
   :subs  [{:query-v ["auth-token"] :value "raw-from-runtime" :ref-count 1}]})

(deftest list-subscriptions-gate-off-elides-values
  (testing "gate OFF + include-values: the slice form wraps each sub :value through elide-wire-value, redacting"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms sub-cache-canned
              (fn [] (ls/list-subscriptions-tool nil (tu/args->js {:include-values true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "re-frame.core/elide-wire-value")
                           "gate-off + include-values MUST route each sub :value through the walker")
                       (is (str/includes? form ":value")
                           "the per-sub :value slot is the elision target")
                       (is (str/includes? form ":rf.size/include-sensitive? false")
                           "gate-off forces include-sensitive? false ⇒ sensitive sub values redact")
                       (done)))))))))

(deftest list-subscriptions-no-include-values-no-elision-wrap
  (testing "include-values FALSE (default): query-vectors only — no :value ships, so no walker wrap"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms {:ok? true :frame :rf/default :count 0 :subs []}
              (fn [] (ls/list-subscriptions-tool nil (tu/args->js {}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (not (str/includes? form "re-frame.core/elide-wire-value"))
                           "no values egress ⇒ no elision wrap (the cheap what's-subscribed read)")
                       (is (str/includes? form "sub-cache-info")
                           "still routes through the reactive sub-cache reader")
                       (done)))))))))

(deftest list-subscriptions-gate-on-include-sensitive-passes-raw
  (testing "gate ON + include-values + include-sensitive true: walker passes declared-sensitive values raw"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms sub-cache-canned
              (fn [] (ls/list-subscriptions-tool
                       nil (tu/args->js {:include-values true :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form ":rf.size/include-sensitive? true")
                           "gate-on + include-sensitive true ⇒ declared-sensitive sub values pass raw")
                       (done)))))))))

(deftest list-subscriptions-gate-on-elision-false-still-redacts-sensitive
  ;; rf2-t55hxg.13 — fail-CLOSED. Pre-fix this asserted that gate-ON +
  ;; `:elision false` bypassed the walker entirely (raw value egress),
  ;; which let a caller pull declared-sensitive sub values off-box WITHOUT
  ;; the per-call `:include-sensitive true` opt-in — collapsing EP-0015's
  ;; two-key sensitive gate. The contract is now: a BARE `:elision false`
  ;; (no sensitive opt-in) STILL walks — large content passes
  ;; (`include-large? true`) but a declared-sensitive value redacts.
  (testing "gate ON + include-values + elision false (no sensitive opt-in): STILL walks, sensitive redacts"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms sub-cache-canned
              (fn [] (ls/list-subscriptions-tool
                       nil (tu/args->js {:include-values true :elision false}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "re-frame.core/elide-wire-value")
                           "bare :elision false MUST still walk — no sensitive bypass")
                       (is (str/includes? form ":rf.size/include-sensitive? false")
                           "sensitive sub values redact (the caller didn't opt in)")
                       (is (str/includes? form ":rf.size/include-large? true")
                           ":elision false overlays include-large? true — large content passes")
                       (done)))))))))

(deftest list-subscriptions-gate-on-full-raw-opt-in-ships-bare
  ;; rf2-t55hxg.13 — the ONLY combination that skips the walker is the
  ;; deliberate full-raw local opt-in: `:elision false` AND
  ;; `:include-sensitive true` (the operator's `:rf.egress/local-raw` act).
  (testing "gate ON + include-values + elision false + include-sensitive true: full-raw opt-in skips the walker"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms sub-cache-canned
              (fn [] (ls/list-subscriptions-tool
                       nil (tu/args->js {:include-values true :elision false :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (not (str/includes? form "re-frame.core/elide-wire-value"))
                           "full-raw opt-in (elision false + include-sensitive true) ships raw")
                       (done)))))))))

;; ===========================================================================
;; End-to-end shape — an already-redacted record (what the live walker
;; produces) survives the client wire-pipeline with the sentinel intact.
;; ===========================================================================

(deftest trace-window-preserves-redacted-sentinel-through-pipeline
  (testing "a :db-after carrying :rf/redacted (post-walker) survives diff-encode/dedup to the wire"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [;; What the live walker would hand back: the sensitive slot
            ;; is already the :rf/redacted sentinel.
            redacted-rec {:epoch-id    :e1
                          :event-id    :auth/sign-in
                          :committed-at 100
                          :db-before   {:auth {:password :rf/redacted}}
                          :db-after    {:auth {:password :rf/redacted}}}
            canned       (assoc epoch-canned
                                :epochs [redacted-rec]
                                :head-id :e1
                                :history-count 1)
            forms        (atom [])]
        (-> (with-capture! forms canned
              ;; :epochs-mode full so :db-after isn't diff-collapsed —
              ;; we want to read the sentinel straight off the wire.
              (fn [] (tw/trace-window-tool
                       nil (tu/args->js {:ms 60000 :epochs-mode "full" :dedup false}))))
            (.then (fn [result]
                     (let [edn    (tu/extract-edn result)
                           epoch  (first (:epochs edn))]
                       (is (true? (:ok? edn)))
                       (is (= 1 (:count edn)))
                       (is (= :rf/redacted (get-in epoch [:db-after :auth :password]))
                           "the redacted sentinel rides the wire — no raw secret")
                       (is (= :rf/redacted (get-in epoch [:db-before :auth :password])))
                       (done)))))))))

;; ===========================================================================
;; rf2-8fin7.1 — snapshot :epochs slice raw :db-* egress
;; ===========================================================================
;;
;; The snapshot eval form elided ONLY :app-db / :sub-cache; the :epochs
;; slice rode raw. The client scrub merely DROPS whole sensitive epochs, so
;; a schema-declared-sensitive SLOT inside a NON-sensitive epoch's
;; :db-before / :db-after leaked off-box in :full mode under the gate-OFF
;; default. The fix routes the :epochs slice through projected-record (the
;; same projection trace-window / watch-epochs use), gated by the
;; include-sensitive two-key opt-in — parity with rf2-6wvh5.

;; The snapshot eval form returns {:value <snap> :elided-count N
;; :tool-frames-excluded []}. The slice content is irrelevant to the
;; form-contract assertions (we read the captured form, not the response);
;; hand back an empty per-frame snap so the client pipeline resolves
;; cleanly.
(def ^:private snapshot-canned
  {:value                 {:rf/default {:app-db {} :epochs []}}
   :elided-count          0
   :tool-frames-excluded  []})

(deftest snapshot-epochs-gate-off-projects-records
  (testing "gate OFF (default): the snapshot :epochs slice is mapped through projected-record"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms snapshot-canned
              ;; mode "full" expands the :epochs slice — the leak path.
              (fn [] (snap/snapshot-tool nil (tu/args->js {:frames "[:rf/default]"
                                                           :include "[:epochs]"
                                                           :mode "full"}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (some? form) "the tool shipped a snapshot eval form")
                       (is (str/includes? form "mapv (fn [r#] (re-frame.core/projected-record")
                           "gate-off MUST route the :epochs slice through projected-record (fn literal threading opts)")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — the snapshot :epochs slice projects under :rf.egress/off-box-tool")
                       (is (str/includes? form ":epochs")
                           "the :epochs slot is the projection target")
                       (done)))))))))

(deftest snapshot-epochs-gate-on-include-sensitive-routes-through-projection
  (testing "gate ON + include-sensitive true: :epochs STILL projected, threading :include-sensitive? true under off-box-tool (rf2-m9duxl, rf2-nmjcll)"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms snapshot-canned
              (fn [] (snap/snapshot-tool nil (tu/args->js {:frames "[:rf/default]"
                                                           :include "[:epochs]"
                                                           :mode "full"
                                                           :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "re-frame.core/projected-record")
                           "include-sensitive STILL routes the :epochs slice through projected-record")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — the off-box-tool boundary is named even on the sensitive opt-in path")
                       (is (str/includes? form ":include-sensitive? true")
                           "the app-db sensitive axis is threaded INTO the projection (composed over the off-box-tool floor)")
                       (is (not (str/includes? form ":include-fx-args?"))
                           "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                       (is (not (str/includes? form ":include-runtime-db?"))
                           "runtime-db axis is NOT lifted by include-sensitive (orthogonal)")
                       (done)))))))))

(deftest snapshot-epochs-gate-on-default-still-projects
  (testing "gate ON but include-sensitive omitted (default false): :epochs STILL projected"
    ;; Two-key opt-in: the launch flag alone does not flip the per-call
    ;; default — a forgetful caller still gets redaction.
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms snapshot-canned
              (fn [] (snap/snapshot-tool nil (tu/args->js {:frames "[:rf/default]"
                                                           :include "[:epochs]"
                                                           :mode "full"}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "mapv (fn [r#] (re-frame.core/projected-record")
                           "gate-on alone (no per-call opt-in) still projects :epochs — fail-safe default")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — still the off-box-tool boundary under the gate without the opt-in")
                       (done)))))))))

(deftest snapshot-epochs-projection-independent-of-elision-toggle
  (testing "gate ON + elision false STILL projects :epochs AND still walks :app-db/:sub-cache (rf2-t55hxg.13)"
    ;; The :epochs projection is gated by include-sensitive (incl?), NOT by
    ;; the large-elision toggle (elision?). Turning elision off must not
    ;; re-open the epoch leak.
    ;;
    ;; rf2-t55hxg.13 — and a BARE `:elision false` (no sensitive opt-in)
    ;; must ALSO keep walking the `:app-db` / `:sub-cache` slices
    ;; (fail-closed): large content passes but a declared-sensitive slot
    ;; redacts. Pre-fix `:elision false` suppressed the per-slot walker,
    ;; leaking sensitive `:app-db` / `:sub-cache` slots off-box.
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms snapshot-canned
              (fn [] (snap/snapshot-tool nil (tu/args->js {:frames "[:rf/default]"
                                                           :include "[:app-db :sub-cache :epochs]"
                                                           :mode "full"
                                                           :elision false}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form "mapv (fn [r#] (re-frame.core/projected-record")
                           "elision false (incl? still false) MUST still project :epochs")
                       (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                           "rf2-nmjcll — :epochs still projects under the off-box-tool boundary with elision off")
                       (is (str/includes? form "re-frame.core/elide-wire-value")
                           "bare :elision false MUST still walk :app-db/:sub-cache — no sensitive bypass")
                       (is (str/includes? form ":rf.size/include-sensitive? false")
                           "sensitive :app-db/:sub-cache slots redact (caller didn't opt in)")
                       (is (str/includes? form ":rf.size/include-large? true")
                           ":elision false overlays include-large? true — large content passes")
                       (done)))))))))

(deftest snapshot-full-raw-opt-in-skips-slice-walker
  (testing "gate ON + elision false + include-sensitive true: full-raw opt-in skips the :app-db/:sub-cache walker"
    ;; rf2-t55hxg.13 — the ONLY combination that skips the per-slot walker
    ;; is the deliberate full-raw local opt-in.
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms snapshot-canned
              (fn [] (snap/snapshot-tool nil (tu/args->js {:frames "[:rf/default]"
                                                           :include "[:app-db :sub-cache]"
                                                           :mode "full"
                                                           :elision false
                                                           :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (not (str/includes? form "re-frame.core/elide-wire-value"))
                           "full-raw opt-in (elision false + include-sensitive true) ships :app-db/:sub-cache raw")
                       (done)))))))))

;; ===========================================================================
;; rf2-8fin7.2 — record / watch-until raw :app-db & :sub signal egress
;; ===========================================================================
;;
;; record (read back via read-recording) and watch-until sample {:app-db
;; [path]} / {:sub [query-v]} signals and ship the SAMPLED VALUES back to
;; the model. Before the fix neither routed the value through
;; elide-wire-value / a raw-state gate, so a declared-sensitive slot leaked
;; off-box under the gate-OFF default. The fix threads an elision-opts map
;; (gate + per-call posture) into the runtime sampler via :elide-opts
;; (record) / the 3rd sample-signals arg (watch-until), and issues
;; signal-runtime! before sampling — parity with get-path / read-sub /
;; snapshot.

(def ^:private record-canned
  {:ok? true :recording-id "rec-x" :signals [{:app-db [:auth :token]}]
   :frame :rf/default :stop {:ms 30000}})

(deftest record-gate-off-threads-elision-opts
  (testing "gate OFF (default): start-recording! carries :elide-opts with include-sensitive? false"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms record-canned
              (fn [] (record/record-tool nil (tu/args->js {:signals "[{:app-db [:auth :token]}]"
                                                           :stop "{:ms 1000}"}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (some? form) "the tool shipped a start-recording! form")
                       (is (str/includes? form ":elide-opts")
                           "gate-off MUST thread :elide-opts so the sampler redacts off-box egress")
                       (is (str/includes? form ":rf.size/include-sensitive? false")
                           "gate-off forces include-sensitive? false ⇒ sensitive samples redact")
                       (done)))))))))

(deftest record-gate-on-include-sensitive-passes-raw
  (testing "gate ON + include-sensitive true: :elide-opts threads include-sensitive? true (raw samples)"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms record-canned
              (fn [] (record/record-tool nil (tu/args->js {:signals "[{:app-db [:auth :token]}]"
                                                           :stop "{:ms 1000}"
                                                           :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form ":rf.size/include-sensitive? true")
                           "gate-on + include-sensitive true ⇒ declared-sensitive samples pass raw")
                       (done)))))))))

(deftest watch-until-gate-off-threads-elision-opts
  (testing "gate OFF (default): the poll form threads elision-opts into sample-signals, include-sensitive? false"
    (async done
      (raw-state/set-allow-raw-state! false)
      (let [forms (atom [])]
        (-> (with-capture! forms {:held? true :sample {0 :rf/redacted} :t 1}
              (fn [] (watch-until/watch-until-tool
                       nil (tu/args->js {:signals "[{:app-db [:auth :token]}]"
                                         :pred #js {:signal 0 :changed true}}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (some? form) "the tool shipped a poll form")
                       (is (str/includes? form "re-frame2-pair.runtime/sample-signals")
                           "the poll samples server-side")
                       (is (str/includes? form ":rf.size/include-sensitive? false")
                           "gate-off forces include-sensitive? false ⇒ sensitive :sample values redact")
                       (done)))))))))

(deftest watch-until-gate-on-include-sensitive-passes-raw
  (testing "gate ON + include-sensitive true: the poll form threads include-sensitive? true (raw :sample)"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms {:held? true :sample {0 "secret-xyz"} :t 1}
              (fn [] (watch-until/watch-until-tool
                       nil (tu/args->js {:signals "[{:app-db [:auth :token]}]"
                                         :pred #js {:signal 0 :changed true}
                                         :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (str/includes? form ":rf.size/include-sensitive? true")
                           "gate-on + include-sensitive true ⇒ declared-sensitive :sample values pass raw")
                       (done)))))))))

;; ===========================================================================
;; rf2-nmjcll — epoch egress names the :rf.egress/off-box-tool profile
;; ===========================================================================
;;
;; Pair-MCP is an OFF-BOX TOOL WIRE (epoch records cross to an external
;; agent). Per Tool-Pair.md §Named-egress profile adoption (EP-0015 §10) the
;; epoch egress MUST name `:rf.egress/off-box-tool`, NOT lean on
;; `projected-record`'s `:rf.egress/off-box-observability` default (same
;; redact/elide floor, but OMITS the structural `:rf.size/include-digests?`
;; indicators a tool needs to reason about an elided slot's shape). The fix
;; lives in `egress-opts-edn`, so every epoch egress caller that threads
;; through it (trace-window / watch-epochs / snapshot :epochs / dispatch
;; :trace / :settle / subscribe epoch-topic drain) inherits the profile.
;;
;; These unit tests pin the helper directly — they PARSE the emitted EDN
;; (not just substring it) so the assertion is the actual data shape, and
;; cross-check that the named off-box-tool boundary resolves (via the shared
;; cross-MCP mirror) to the fail-closed-sensitive / digests-on floor the
;; off-box wire requires.

(defn- parse-opts
  "Read `egress-opts-edn`'s rendered EDN string back into a data map."
  [incl?]
  (reader/read-string (egress/egress-opts-edn incl?)))

(deftest egress-opts-default-path-names-off-box-tool
  (testing "rf2-nmjcll — the DEFAULT (no sensitive opt-in) egress opts name :rf.egress/off-box-tool"
    (let [opts (parse-opts false)]
      (is (= :rf.egress/off-box-tool (:rf.egress/profile opts))
          "the default epoch egress path names the off-box-tool boundary — NOT the projected-record observability default")
      ;; The default path carries ONLY the profile — no app-db sensitive
      ;; opt-in, and none of the orthogonal raw axes.
      (is (= {:rf.egress/profile :rf.egress/off-box-tool} opts)
          "default path = bare off-box-tool profile, no legacy :include-* overrides")
      (is (not (contains? opts :include-sensitive?))
          "the default path never opts the app-db sensitive axis back in"))))

(deftest egress-opts-include-sensitive-composes-over-off-box-tool
  (testing "rf2-nmjcll — the sensitive opt-in path STILL names off-box-tool, with :include-sensitive? true on top"
    (let [opts (parse-opts true)]
      (is (= :rf.egress/off-box-tool (:rf.egress/profile opts))
          "the trusted-local sensitive opt-in is STILL the off-box-tool boundary (it is never the observability default)")
      (is (true? (:include-sensitive? opts))
          "the app-db sensitive axis is threaded as the legacy override ON TOP of the off-box-tool floor")
      ;; ONLY the app-db sensitive axis is lifted — the orthogonal axes stay
      ;; at the profile floor (fail-closed).
      (is (not (contains? opts :include-large?))
          "include-sensitive never lifts the large axis (orthogonal — stays at the off-box-tool floor)")
      (is (not (contains? opts :include-fx-args?))
          "include-sensitive never lifts fx-args (orthogonal)")
      (is (not (contains? opts :include-runtime-db?))
          "include-sensitive never lifts the runtime-db partition (orthogonal)"))))

(deftest off-box-tool-floor-redacts-sensitive-and-carries-digests
  ;; The behavioural pin: WHAT the named off-box-tool boundary buys at the
  ;; wire. Resolved through the shared cross-MCP mirror (the same source the
  ;; direct-read surfaces use), the off-box-tool profile keeps a
  ;; declared-sensitive app-db slot REDACTED off-box (it does NOT ship raw)
  ;; AND turns the structural digests ON — the contract the prior
  ;; observability-default epoch egress silently dropped.
  (testing "rf2-nmjcll — off-box-tool: sensitive stays redacted (fail-closed) + digests on"
    (let [floor (base-egress/profile-size-opts :rf.egress/off-box-tool)]
      (is (false? (:rf.size/include-sensitive? floor))
          "off-box-tool fails closed on sensitive — a declared-sensitive epoch field redacts off-box, NOT shipped raw")
      (is (false? (:rf.size/include-large? floor))
          "off-box-tool elides large slots off-box")
      (is (true? (:rf.size/include-digests? floor))
          "off-box-tool turns structural digests ON — the indicator the prior observability default omitted")
      ;; The difference the fix makes, spelled out: off-box-tool == the
      ;; observability default PLUS the digests the tool wire needs.
      (let [obs (base-egress/profile-size-opts :rf.egress/off-box-observability)]
        (is (false? (:rf.size/include-digests? obs))
            "the projected-record default (off-box-observability) OMITS digests — the gap rf2-nmjcll closes")
        (is (= (dissoc floor :rf.size/include-digests?)
               (dissoc obs :rf.size/include-digests?))
            "off-box-tool and the observability default share the redact/elide floor; digests are the only delta")))))

(deftest project-page-src-threads-off-box-tool-on-both-paths
  (testing "rf2-nmjcll — project-page-src emits the off-box-tool profile in the page fn literal (both paths)"
    (let [default-src   (egress/project-page-src "page" false)
          sensitive-src (egress/project-page-src "page" true)]
      (is (str/includes? default-src "mapv (fn [r#] (re-frame.core/projected-record")
          "default path projects each record via a fn literal threading the opts")
      (is (str/includes? default-src ":rf.egress/profile :rf.egress/off-box-tool")
          "default page projection names off-box-tool")
      (is (not (str/includes? default-src "mapv re-frame.core/projected-record page)"))
          "the bare 1-arity reference (which could not name the profile) is gone")
      (is (str/includes? sensitive-src ":rf.egress/profile :rf.egress/off-box-tool")
          "sensitive page projection ALSO names off-box-tool")
      (is (str/includes? sensitive-src ":include-sensitive? true")
          "sensitive path threads the app-db sensitive axis over the off-box-tool floor"))))
