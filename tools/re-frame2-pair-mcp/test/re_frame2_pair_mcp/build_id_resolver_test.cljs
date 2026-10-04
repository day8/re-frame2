(ns re-frame2-pair-mcp.build-id-resolver-test
  "Forgiving suffix→canonical build-id resolution + round-trippable
  running-build guidance.

  ## What this guards

  shadow build ids are namespaced keywords (`:examples/machine-epochs`).
  A short tail an operator naturally reaches for —
  `snapshot {:build \"machine-epochs\"}` — must resolve to the unique
  running build that ends in that tail rather than being rejected
  `:build-not-running`. Four properties:

    1. Read ops forgive a suffix the same way discover-app does — a
       suffix form that names a unique running build resolves across the
       read/action ops.
    2. The `:running-builds` error list is round-trippable — it names the
       valid set in a form the operator can paste straight back.
    3. Colon-prepend is idempotent (`:examples/…` stays `:examples/…`,
       never `::examples/…`). Carried by `fresh-keyword`'s
       colon-tolerance, pinned in build_id_cache_test.
    4. The selected build sticks — a discover-app'd build transfers to
       the next read op via the sticky `:resolved-build-id`; pinned by
       dx-papercuts-test and sticky-build-invoke-test.

  The mechanism: ONE shared forgiving resolver
  (`probe/canonicalize-build!` + `probe/match-running-build`) used across
  EVERY op via the conn `:build-alias` cache that `wire/arg-build`
  consults; a unique suffix match resolves to the canonical running id,
  ambiguous/no match falls through to the diagnostic ladder UNCHANGED.
  Every running-build guidance string is rendered in the round-trippable
  `:build` arg form."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil :build-alias {})
    conn))

(defn- with-running!
  "Stub `probe/running-builds` to resolve to `running-vec`, restoring in
  `.finally`."
  [running-vec body-fn]
  (let [orig probe/running-builds]
    (set! probe/running-builds (fn [_conn] (js/Promise.resolve running-vec)))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! probe/running-builds orig))))))

;; ---------------------------------------------------------------------------
;; Pure match rule.
;; ---------------------------------------------------------------------------

(deftest match-running-build-resolution-rule
  ;; The suffix rule (rule 2) is documented as applying ONLY to a BARE
  ;; (no-namespace) requested id. A fully-namespaced request that doesn't
  ;; exactly match must fall through UNCHANGED — never redirected by
  ;; bare-name suffix match to a same-named build in a different
  ;; namespace, even when that other build is the sole suffix match.
  (doseq [[requested running expected note]
          [[:examples/machine-epochs [:examples/machine-epochs :testbeds/panel-gallery]
            :examples/machine-epochs
            "an exact keyword match resolves to itself"]
           [:machine-epochs [:testbeds/panel-gallery :examples/machine-epochs]
            :examples/machine-epochs
            "a unique name-suffix resolves to the canonical running id"]
           [:machine-epochs [:examples/machine-epochs :other/machine-epochs]
            :machine-epochs
            "two builds sharing the tail stay ambiguous — return the requested id unchanged"]
           [:typo [:examples/machine-epochs]
            :typo
            "no match returns the requested id so the diagnostic ladder fires"]
           [:examples/step-deck [:other/step-deck]
            :examples/step-deck
            "a namespaced request with no exact match falls through unchanged, NOT redirected to :other/step-deck"]
           [:step-deck [:other/step-deck]
            :other/step-deck
            "a genuinely bare request still suffix-matches the unique running build"]]]
    (is (= expected (probe/match-running-build requested running)) note)))

;; ---------------------------------------------------------------------------
;; canonicalize-build! — the async resolver + alias caching.
;; ---------------------------------------------------------------------------

(deftest canonicalize-suffix-resolves-and-caches
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)
          orig  probe/running-builds]
      (set! probe/running-builds
            (fn [_] (swap! calls inc) (js/Promise.resolve [:examples/machine-epochs])))
      (-> (probe/canonicalize-build! conn :machine-epochs)
          (.then (fn [c]
                   (is (= :examples/machine-epochs c) "suffix resolves to canonical")
                   (is (= :examples/machine-epochs (get-in @conn [:build-alias :machine-epochs]))
                       "the resolution is cached on the conn")
                   ;; second call must NOT re-fetch running-builds.
                   (probe/canonicalize-build! conn :machine-epochs)))
          (.then (fn [c]
                   (is (= :examples/machine-epochs c))
                   (is (= 1 @calls) "the cached alias means no second active-builds round-trip")))
          (.finally (fn [] (set! probe/running-builds orig)))
          (.then (fn [_] (done)))))))

(deftest canonicalize-exact-probed-skips-round-trip
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)
          orig  probe/running-builds]
      (swap! conn update :probed-builds conj :examples/machine-epochs)
      (set! probe/running-builds
            (fn [_] (swap! calls inc) (js/Promise.resolve [:examples/machine-epochs])))
      (-> (probe/canonicalize-build! conn :examples/machine-epochs)
          (.then (fn [c]
                   (is (= :examples/machine-epochs c))
                   (is (= 0 @calls)
                       "an already-probed exact build needs no active-builds round-trip")))
          (.finally (fn [] (set! probe/running-builds orig)))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Property 1 — arg-build applies the alias so EVERY op forgives the suffix.
;; ---------------------------------------------------------------------------

(deftest arg-build-applies-the-suffix-alias
  ;; After canonicalize-build! has recorded the alias (the pipeline's
  ;; first step), every op's `arg-build conn args` — explicit suffix arg
  ;; included — resolves to the canonical running id.
  (async done
    (let [conn (fresh-conn)]
      (-> (with-running! [:examples/machine-epochs]
            (fn [] (probe/canonicalize-build! conn :machine-epochs)))
          (.then
            (fn [_]
              (is (= :examples/machine-epochs
                     (wire/arg-build conn (tu/args->js {:build "machine-epochs"})))
                  "an explicit suffix :build arg resolves to the canonical running id across ops")
              (is (= :examples/machine-epochs
                     (wire/arg-build conn (tu/args->js {:build ":machine-epochs"})))
                  "colon form of the suffix resolves identically")
              (done)))))))

;; ---------------------------------------------------------------------------
;; Property 2 — round-trippable running-build guidance.
;; ---------------------------------------------------------------------------

(deftest build-not-running-error-list-is-round-trippable
  ;; End-to-end through the diagnostic ladder: the :build-not-running
  ;; envelope's running-build guidance must be copy-paste-round-trippable.
  (async done
    (let [conn      (fresh-conn)
          orig-cljs nrepl/cljs-eval-value
          orig-jvm  nrepl/jvm-eval
          ;; Marker probe false (so the ladder runs); JVM reachable; the build
          ;; the operator targeted (:genuinely-absent) is NOT in active-builds.
          cljs-stub (fn ([_ _ _] (js/Promise.resolve false))
                      ([_ _ _ _] (js/Promise.resolve false)))
          jvm-stub  (fn ([_ form] (js/Promise.resolve
                                    (if (re-find #"active-builds" form)
                                      {:value "[:examples/machine-epochs]"}
                                      {:value "1"})))
                      ([_ form _] (js/Promise.resolve
                                    (if (re-find #"active-builds" form)
                                      {:value "[:examples/machine-epochs]"}
                                      {:value "1"}))))]
      (set! nrepl/cljs-eval-value cljs-stub)
      (set! nrepl/jvm-eval jvm-stub)
      (-> (probe/ensure-runtime! conn :genuinely-absent)
          (.then (fn [_] (is false "must reject for a non-running build")))
          (.catch (fn [err]
                    (let [data (ex-data err)]
                      (is (= :build-not-running (:reason data)))
                      (is (= [":examples/machine-epochs"] (:running-builds-arg-forms data))
                          "the error carries the round-trippable arg forms")
                      ;; The hint string names the build in paste-ready form.
                      (is (str/includes? (:hint data) ":examples/machine-epochs")
                          "the hint prints the colon form, not a bare short name")
                      ;; Round-trip: the listed form resolves back.
                      (is (= :examples/machine-epochs
                             (wire/arg-build (fresh-conn)
                                             (tu/args->js {:build (first (:running-builds-arg-forms data))})))
                          "the listed form pastes straight back into :build"))))
          (.finally (fn []
                      (tu/restore-eval! cljs-stub orig-cljs)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)))
          (.then (fn [_] (done)))))))
