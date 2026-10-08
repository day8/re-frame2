(ns re-frame.api-manifest.xray-spec-check-test
  "Tests for the Xray API-spec projection check. Each reference shape
  resolves against its own index: a fully-qualified
  `day8.re-frame2-xray.*/<var>` symbol strictly by [namespace var] (every
  panel namespace carries a `Panel`, so a bare-name match would pass a stale
  panel namespace), a bare `mount-*!` by Xray var name, and an `(rf/<var>`
  facade call by any manifest var name."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]
            [re-frame.api-manifest.xray-spec-check :as rf.api-manifest.xray-spec-check]))

(defn- problems
  [refs]
  (map (juxt :line :raw)
       (rf.api-manifest.xray-spec-check/reconcile
         (merge {:rows            [{:namespace "day8.re-frame2-xray.panels.trace" :var "Panel"}
                                   {:namespace "day8.re-frame2-xray.panels" :var "mount-trace!"}]
                 :qualified-refs  [] :bare-refs [] :facade-refs []
                 :qualified-allow #{} :bare-allow #{} :facade-allow #{}
                 :rel             "tools/xray/spec/API.md"}
                refs))))

(deftest reconcile-resolves-each-reference-shape-on-its-own-index
  (is (= [[43 "day8.re-frame2-xray.panels.gone/Panel"]]
         (problems {:qualified-refs  [{:ns "day8.re-frame2-xray.panels.views" :var "Panel" :line 42
                                       :raw "day8.re-frame2-xray.panels.views/Panel"}
                                      {:ns "day8.re-frame2-xray.panels.gone" :var "Panel" :line 43
                                       :raw "day8.re-frame2-xray.panels.gone/Panel"}]
                    :qualified-allow #{["day8.re-frame2-xray.panels.views" "Panel"]}}))
      "qualified: neither the shared bare var nor another namespace's allowlist entry rescues a stale namespace")
  (is (= [[2 "mount-gone!"]]
         (problems {:bare-refs  [{:var "mount-trace!" :line 1 :raw "mount-trace!"}
                                 {:var "mount-gone!" :line 2 :raw "mount-gone!"}
                                 {:var "mount-old!" :line 3 :raw "mount-old!"}]
                    :bare-allow #{"mount-old!"}}))
      "bare mount-*!: resolves by Xray var name, else flagged unless allowlisted")
  (is (= [[502 "rf/trace-buffer-BOGUSPLANT"]]
         (problems {:facade-refs  [{:var "trace-buffer-BOGUSPLANT" :line 502
                                    :raw "rf/trace-buffer-BOGUSPLANT"}
                                   {:var "sub-cache" :line 512 :raw "rf/sub-cache"}]
                    :facade-allow #{"sub-cache"}}))
      "facade (rf/<var>: an unmanifested name is flagged unless allowlisted"))

(deftest live-spec-names-qualified-and-facade-references
  ;; The check's floor is an aggregate over all three shapes, so one
  ;; extractor collapsing to zero would leave it green; these are the
  ;; per-shape floors.
  (let [lines (rf.api-manifest.projection/numbered-lines
                (rf.api-manifest.projection/repo-file "tools" "xray" "spec" "API.md"))]
    (is (seq (rf.api-manifest.projection/qualified-symbol-references "day8.re-frame2-xray." lines)))
    (is (seq (rf.api-manifest.projection/alias-call-references "rf" lines)))))
