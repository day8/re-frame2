(ns re-frame2-pair-mcp.build-id-resolver-test
  "Forgiving suffix-to-canonical build-id resolution, and round-trippable
  running-build guidance.

  A bare tail such as `machine-epochs` resolves to the unique running
  build ending in it (`:examples/machine-epochs`) through one shared
  resolver (`probe/canonicalize-build!` + `probe/match-running-build`),
  which records the alias on the conn for `wire/arg-build` to apply on
  every op. An ambiguous or unmatched id falls through unchanged to the
  diagnostic ladder, whose running-build list is rendered in the
  paste-back `:build` form. Colon tolerance is pinned in
  build_id_cache_test; the sticky transfer to the next op in
  dx-papercuts-test and sticky-build-invoke-test."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil :build-alias {})
    conn))

(deftest match-running-build-resolution-rule
  ;; The suffix rule applies only to a bare requested id: a namespaced id
  ;; that is not an exact match is never redirected to another namespace.
  (doseq [[requested running expected note]
          [[:examples/machine-epochs [:examples/machine-epochs :testbeds/panel-gallery]
            :examples/machine-epochs
            "an exact keyword match resolves to itself"]
           [:machine-epochs [:testbeds/panel-gallery :examples/machine-epochs]
            :examples/machine-epochs
            "a unique name-suffix resolves to the canonical running id"]
           [:machine-epochs [:examples/machine-epochs :other/machine-epochs]
            :machine-epochs
            "two builds sharing the tail stay ambiguous"]
           [:typo [:examples/machine-epochs]
            :typo
            "no match returns the requested id so the diagnostic ladder fires"]
           [:examples/step-deck [:other/step-deck]
            :examples/step-deck
            "a namespaced request with no exact match falls through unchanged"]]]
    (is (= expected (probe/match-running-build requested running)) note)))

(deftest canonicalize-suffix-resolves-and-caches
  ;; The second resolution of the same id is served from the conn's alias
  ;; cache, with no second active-builds round-trip.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)
          orig  probe/running-builds]
      (set! probe/running-builds
            (fn [_] (swap! calls inc) (js/Promise.resolve [:examples/machine-epochs])))
      (-> (probe/canonicalize-build! conn :machine-epochs)
          (.then (fn [c1]
                   (.then (probe/canonicalize-build! conn :machine-epochs)
                          (fn [c2] [c1 c2]))))
          (.then (fn [resolved]
                   (is (= [[:examples/machine-epochs :examples/machine-epochs] 1]
                          [resolved @calls]))))
          (.finally (fn [] (set! probe/running-builds orig)))
          (.then (fn [_] (done)))))))

(deftest arg-build-applies-the-suffix-alias
  ;; Once the pipeline's first step recorded the alias, an explicit suffix
  ;; `:build` arg resolves to the canonical running id on every op.
  (async done
    (let [conn (fresh-conn)
          orig probe/running-builds]
      (set! probe/running-builds (fn [_conn] (js/Promise.resolve [:examples/machine-epochs])))
      (-> (probe/canonicalize-build! conn :machine-epochs)
          (.then (fn [_]
                   (is (= :examples/machine-epochs
                          (wire/arg-build conn (tu/args->js {:build "machine-epochs"}))))))
          (.finally (fn [] (set! probe/running-builds orig)))
          (.then (fn [_] (done)))))))

(deftest build-not-running-error-list-is-round-trippable
  ;; Through the diagnostic ladder: the targeted build is absent from
  ;; active-builds, and the error lists the running ones in the exact
  ;; string form the `:build` arg accepts back.
  (async done
    (let [conn      (fresh-conn)
          orig-cljs nrepl/cljs-eval-value
          orig-jvm  nrepl/jvm-eval
          cljs-stub (fn ([_ _ _] (js/Promise.resolve false))
                      ([_ _ _ _] (js/Promise.resolve false)))
          answer    (fn [form] (js/Promise.resolve
                                 (if (re-find #"active-builds" form)
                                   {:value "[:examples/machine-epochs]"}
                                   {:value "1"})))
          jvm-stub  (fn ([_ form] (answer form))
                      ([_ form _] (answer form)))]
      (set! nrepl/cljs-eval-value cljs-stub)
      (set! nrepl/jvm-eval jvm-stub)
      (-> (probe/ensure-runtime! conn :genuinely-absent)
          (.then (fn [_] (is false "must reject for a non-running build")))
          (.catch (fn [err]
                    (is (= {:reason                   :build-not-running
                            :running-builds-arg-forms [":examples/machine-epochs"]}
                           (select-keys (ex-data err) [:reason :running-builds-arg-forms])))))
          (.finally (fn []
                      (tu/restore-eval! cljs-stub orig-cljs)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)))
          (.then (fn [_] (done)))))))
