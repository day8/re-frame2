(ns re-frame2-pair-mcp.build-id-resolver-test
  "Forgiving suffix→canonical build-id resolution + round-trippable
  running-build guidance (rf2-qda59).

  ## The bug (live-reproduced 2026-06-02 on the machine-epochs deck)

  shadow build ids are namespaced keywords (`:examples/machine-epochs`).
  `discover-app {:build \"examples/machine-epochs\"}` connected fine, but
  the read ops were STRICT: `snapshot {:build \"machine-epochs\"}` (the
  short tail an operator naturally reaches for) was rejected
  `:build-not-running` — even though `machine-epochs` named the build
  right there in the error's own `:running-builds` list. Four symptoms:

    1. discover-app forgives but read ops are strict — a suffix form that
       names a unique running build is rejected by the read/action ops.
    2. The `:running-builds` error list was not round-trippable — it
       named the valid set in a form the operator couldn't paste back.
    3. Colon-prepend was non-idempotent (`:examples/…` ⇒ `::examples/…`).
       (Fixed upstream by #2821's `fresh-keyword` colon-tolerance; pinned
       here as a regression guard.)
    4. The selected build did not stick — a discover-app'd build did not
       transfer to the next read op. (Fixed by #2821's sticky
       `:resolved-build-id`; pinned here.)

  The fix (rf2-qda59): ONE shared forgiving resolver
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

(deftest match-running-build-exact-wins
  (is (= :examples/machine-epochs
         (probe/match-running-build :examples/machine-epochs
                                    [:examples/machine-epochs :testbeds/panel-gallery]))
      "an exact keyword match resolves to itself"))

(deftest match-running-build-unique-suffix
  (is (= :examples/machine-epochs
         (probe/match-running-build :machine-epochs
                                    [:testbeds/panel-gallery :examples/machine-epochs]))
      "a unique name-suffix resolves to the canonical running id"))

(deftest match-running-build-ambiguous-suffix-falls-through
  (is (= :machine-epochs
         (probe/match-running-build :machine-epochs
                                    [:examples/machine-epochs :other/machine-epochs]))
      "two builds sharing the tail stay ambiguous — return the requested id unchanged"))

(deftest match-running-build-no-match-falls-through
  (is (= :typo
         (probe/match-running-build :typo [:examples/machine-epochs]))
      "no match returns the requested id so the diagnostic ladder fires"))

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
;; Symptom 1 — arg-build applies the alias so EVERY op forgives the suffix.
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

(deftest arg-build-leaves-un-aliased-ids-unchanged
  ;; A typo / not-yet-resolved id passes through verbatim so the
  ;; diagnostic ladder still fires for it.
  (let [conn (fresh-conn)]
    (is (= :nope (wire/arg-build conn (tu/args->js {:build "nope"})))
        "no alias recorded → id passes through unchanged")))

;; ---------------------------------------------------------------------------
;; Symptom 2 — round-trippable running-build guidance.
;; ---------------------------------------------------------------------------

(deftest running-build-arg-forms-are-round-trippable
  ;; Each rendered form, fed back as a :build arg, resolves to the same
  ;; keyword — the copy-paste-from-error contract.
  (let [conn    (fresh-conn)
        running [:examples/machine-epochs :testbeds/panel-gallery]
        forms   (probe/running-build-arg-forms running)]
    (is (= [":examples/machine-epochs" ":testbeds/panel-gallery"] forms)
        "rendered in the EDN keyword form (colon-tolerant, paste-ready)")
    (doseq [[kw form] (map vector running forms)]
      (is (= kw (wire/arg-build conn (tu/args->js {:build form})))
          (str form " round-trips back to " kw))
      ;; And the colon-stripped form an agent might serialise also works.
      (is (= kw (wire/arg-build conn (tu/args->js {:build (subs form 1)})))
          (str "the bare form of " form " round-trips too")))))

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

;; ---------------------------------------------------------------------------
;; Symptom 3 — colon idempotence (regression guard; fixed by #2821).
;; ---------------------------------------------------------------------------

(deftest colon-prepend-is-idempotent
  (let [conn (fresh-conn)]
    (is (= :examples/machine-epochs
           (wire/arg-build conn (tu/args->js {:build ":examples/machine-epochs"})))
        "a leading colon is stripped, never double-prepended into ::examples/…")
    (is (= (wire/arg-build conn (tu/args->js {:build "examples/machine-epochs"}))
           (wire/arg-build conn (tu/args->js {:build ":examples/machine-epochs"})))
        "bare and colon forms are indistinguishable post-coercion")))

;; ---------------------------------------------------------------------------
;; Symptom 4 — a stuck build transfers to subsequent ops (regression guard).
;; ---------------------------------------------------------------------------

(deftest stuck-build-transfers-to-subsequent-ops
  (let [conn     (fresh-conn)
        no-build (tu/args->js {})]
    ;; Simulate an explicit :build on a prior op having been stuck.
    (wire/stick-build! conn (tu/args->js {:build "examples/machine-epochs"}))
    (is (= :examples/machine-epochs (wire/arg-build conn no-build))
        "a build stuck on a prior op defaults the next no-:build op")))

(deftest stuck-suffix-build-canonicalised-by-step
  ;; A suffix form stuck on a prior op, then canonicalized by the pipeline
  ;; step, becomes the canonical sticky default for follow-up ops.
  (async done
    (let [conn (fresh-conn)]
      ;; stick-build! stores the fresh-keyword'd suffix form.
      (wire/stick-build! conn (tu/args->js {:build "machine-epochs"}))
      (is (= :machine-epochs (:resolved-build-id @conn)) "sanity: suffix stuck verbatim")
      (-> (with-running! [:examples/machine-epochs]
            (fn []
              (-> (probe/canonicalize-build! conn :machine-epochs)
                  (.then (fn [canonical]
                           ;; the step rewrites :resolved-build-id when one was stuck
                           (when (some? (:resolved-build-id @conn))
                             (swap! conn assoc :resolved-build-id canonical)))))))
          (.then (fn [_]
                   (is (= :examples/machine-epochs (wire/arg-build conn (tu/args->js {})))
                       "the canonical id is the sticky default for follow-up no-:build ops")
                   (done)))))))
