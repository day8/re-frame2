(ns re-frame.source-coord-prod-elision-test
  "Source-coord production elision. Under the disabled debug gate the public
  `rf/handler-meta` carries no coord keys, because Xray's Open-in-editor and
  re-frame-pair are dev-only. The always-on registration coordinate each stored
  descriptor carries keeps them for the error-emit record that off-box shippers
  read; that half is pinned by `re-frame.source-coord-jvm-test`, and the
  process-wide `[kind id]` index tooling reads by `re-frame.source-coords-test`.

  JVM-only (`_test.clj`) so the gate can be modelled with `with-redefs`; under
  `scripts/test-core-prod-gate.sh` the redef is a no-op over an already-false
  flag. The CLJS bundle-level absence is pinned by `scripts/check-elision.cjs`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest registry-meta-strips-coord-keys-under-disabled-debug-gate
  (with-redefs [rf.interop/debug-enabled? false]
    (rf/reg-event :rf2-3un2g/prod-elide-event (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta {:source :store :kind :event :id :rf2-3un2g/prod-elide-event})]
      (is (some? meta))
      (is (= {} (select-keys meta [:ns :file :line :column]))))))
