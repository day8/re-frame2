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
  `[:rf/runtime :elision]` registry, which only exists app-side. A unit
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
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]
            [re-frame2-pair-mcp.tools.list-subscriptions :as ls]))

;; ---------------------------------------------------------------------------
;; Gate state is process-global (an atom in raw-state.cljs). Reset to the
;; published-build default (OFF) after every test so ordering can't leak.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:after (fn [] (raw-state/set-allow-raw-state! false))})

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
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

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
                       (is (str/includes? form "mapv re-frame.core/projected-record")
                           "every record in the page is projected")
                       (done)))))))))

(deftest trace-window-gate-on-include-sensitive-ships-raw
  (testing "gate ON + include-sensitive true: NO projection — records ship raw (operator opted in)"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (tw/trace-window-tool nil (tu/args->js {:ms 1000 :include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (not (str/includes? form "projected-record"))
                           "gate-on + include-sensitive true bypasses projection — raw egress")
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
                       (is (str/includes? form "mapv re-frame.core/projected-record")
                           "gate-off MUST route the egress page through projected-record")
                       (done)))))))))

(deftest watch-epochs-gate-on-include-sensitive-ships-raw
  (testing "gate ON + include-sensitive true: NO projection — raw records ship"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms epoch-canned
              (fn [] (we/watch-epochs-tool nil (tu/args->js {:include-sensitive true}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (not (str/includes? form "projected-record"))
                           "gate-on + include-sensitive true ⇒ raw passes")
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

(deftest list-subscriptions-gate-on-elision-false-ships-bare
  (testing "gate ON + include-values + elision false: NO walker wrap — raw values ship"
    (async done
      (raw-state/set-allow-raw-state! true)
      (let [forms (atom [])]
        (-> (with-capture! forms sub-cache-canned
              (fn [] (ls/list-subscriptions-tool
                       nil (tu/args->js {:include-values true :elision false}))))
            (.then (fn [_]
                     (let [form (slice-form forms)]
                       (is (not (str/includes? form "re-frame.core/elide-wire-value"))
                           "gate-on + elision false bypasses the walker — raw value egress")
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
