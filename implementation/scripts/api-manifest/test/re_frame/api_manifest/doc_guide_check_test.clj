(ns re-frame.api-manifest.doc-guide-check-test
  "Tests for the docs/core projection check's FILE-SCOPED removed-name
  allowlist. A removed name is silenced only in its approved migration
  file(s); a bare global allowlist would let a live `(rf/inject-cofx …)` in
  any teaching chapter pass."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.doc-guide-check :as rf.api-manifest.doc-guide-check]))

(deftest reconcile-silences-a-removed-name-only-in-its-approved-files
  (is (= [["docs/core/interceptors.md" 50]
          ["docs/core/concepts/effects-and-coeffects.md" 51]
          ["docs/core/concepts/x.md" 52]]
         (map (juxt :file :line)
              (rf.api-manifest.doc-guide-check/reconcile
                {:core-vars    #{}
                 :scoped-allow {"inject-cofx"  #{"docs/core/25-from-re-frame-v1.md"
                                                 "docs/core/concepts/effects-and-coeffects.md"}
                                "reg-event-db" #{"docs/core/25-from-re-frame-v1.md"}}
                 :references
                 [;; approved file: silenced
                  {:var "inject-cofx" :line 12 :raw "rf/inject-cofx"
                   :file "docs/core/25-from-re-frame-v1.md"}
                  ;; live teaching file
                  {:var "inject-cofx" :line 50 :raw "rf/inject-cofx"
                   :file "docs/core/interceptors.md"}
                  ;; approved for inject-cofx, not for reg-event-db
                  {:var "reg-event-db" :line 51 :raw "rf/reg-event-db"
                   :file "docs/core/concepts/effects-and-coeffects.md"}
                  ;; neither a core var nor allowlisted
                  {:var "totally-gone" :line 52 :raw "rf/totally-gone"
                   :file "docs/core/concepts/x.md"}]})))))
