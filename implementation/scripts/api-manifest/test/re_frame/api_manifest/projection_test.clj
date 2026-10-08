(ns re-frame.api-manifest.projection-test
  "Tests for the shared projection scaffolding: the non-vacuous floor that
  refuses a green over a collapsed extraction, and the keyword-drift guards
  the check-mains fold into their verdict."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

(deftest report-with-floor-goes-red-on-vacuous-empty
  ;; Rows: no problems but a sub-floor count (the vacuous green), a clean
  ;; count above the floor, and real drift above the floor.
  (is (= [false true false]
         (map #(apply rf.api-manifest.projection/report-with-floor! "skills/" %)
              [[0 100 []]
               [505 100 []]
               [505 100 [{:file "skills/x.md" :line 1 :raw "rf/gone" :detail "no row"}]]]))))

(deftest require-markdown-files-throws-on-missing-dir
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"expected directory is missing"
        (rf.api-manifest.projection/require-markdown-files
          "skills/"
          (rf.api-manifest.projection/repo-file
            (str "definitely-missing-surface-" (System/currentTimeMillis)))))))

(deftest keyword-drift-flags-bare-stale-mention
  (is (= [["docs/core/x.md" 10 ":rf.world/inputs"]]
         (map (juxt :file :line :raw)
              (rf.api-manifest.projection/ep0017-keyword-drift-problems
                "docs/core/x.md"
                [[10 "Supply recordable facts under :rf.world/inputs on dispatch."]
                 [11 "Supplying :rf.world/inputs rides the generic warning naming :rf.cofx."]])))))

(deftest ep0011-flags-retired-spellings-without-a-marker
  ;; `:stale-key` is matched as a substring and bare `:work-id` by a regex; a
  ;; marker on the same line approves either.
  (is (= [[7 ":stale-key"] [8 ":work-id"]]
         (map (juxt :line :raw)
              (rf.api-manifest.projection/ep0011-reply-vocab-drift-problems
                "spec/API.md"
                [[7 "Suppress on a `:stale-key` `[:resource k gen]` head."]
                 [8 "The reply map carries a `:work-id` attempt identity."]
                 [9 "Stale suppression keys on :work/id; the separate :stale-key is dropped."]])))))

(deftest ep0015-flags-retired-profile-form
  ;; Only the retired keyword FORM is drift: the bare adjectives on-box and
  ;; trusted-local remain current trust-boundary vocabulary.
  (is (= [[9 ":rf.egress/on-box-hidden-sensitive"]]
         (map (juxt :line :raw)
              (rf.api-manifest.projection/ep0015-privacy-vocab-drift-problems
                "docs/core/x.md"
                [[9 "Set the profile to :rf.egress/on-box-hidden-sensitive for dev."]
                 [10 ":rf.egress/on-box-hidden-sensitive was renamed to :rf.egress/local-redacted."]
                 [11 "A trusted-local operator on-box may opt into raw."]])))))
