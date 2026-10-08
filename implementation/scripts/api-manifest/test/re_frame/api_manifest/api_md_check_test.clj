(ns re-frame.api-manifest.api-md-check-test
  "Tests for the spec/API.md projection check's parser, its qualifier
  resolution and kind grading, and its non-vacuous floor.

  The manifest carries the SAME bare var `adapter` for several namespaces at
  different tiers. A qualified row therefore resolves strictly against the
  `[namespace var]` index — a bare-name match would let `rf.adapter.uix/adapter`
  drift to an unknown qualifier and still pass. Bare rows keep by-name
  latitude and the bare-name allowlist."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.api-md-check :as rf.api-manifest.api-md-check]))

(deftest reconcile-grades-qualified-and-bare-rows
  (doseq [[api-row allow expected]
          [;; Qualified: neither another namespace's :adapter-tier `adapter`
           ;; nor the bare-name allowlist rescues an unknown qualifier.
           [{:var "adapter" :qualifier "bogus-adapter" :tier :adapter
             :line 185 :raw "bogus-adapter/adapter"}
            #{"adapter"}
            [{:kind :missing :var "adapter" :raw "bogus-adapter/adapter"
              :line 185 :api-tier :adapter}]]
           [{:var "adapter" :qualifier "re-frame.ssr" :tier :adapter
             :line 314 :raw "re-frame.ssr/adapter"}
            #{}
            [{:kind :tier-mismatch :var "adapter" :raw "re-frame.ssr/adapter"
              :line 314 :api-tier :adapter :manifest-tiers #{:implementation}}]]
           ;; Bare: resolves by name; an unmanifested name is flagged unless
           ;; allowlisted. A row with no :kind is not graded on kind.
           [{:var "reg-event" :tier :front-porch :line 1 :raw "reg-event"} #{} []]
           [{:var "story-view" :tier :tooling :line 2 :raw "story-view"}
            #{}
            [{:kind :missing :var "story-view" :raw "story-view" :line 2 :api-tier :tooling}]]
           [{:var "story-view" :tier :tooling :line 2 :raw "story-view"} #{"story-view"} []]
           [{:var "reg-event" :tier :tooling :line 3 :raw "reg-event"}
            #{}
            [{:kind :tier-mismatch :var "reg-event" :raw "reg-event" :line 3
              :api-tier :tooling :manifest-tiers #{:front-porch}}]]
           ;; Kind is graded once name and tier resolve.
           [{:var "reg-event" :tier :front-porch :kind :fn :line 4 :raw "reg-event"}
            #{}
            [{:kind :kind-mismatch :var "reg-event" :raw "reg-event" :line 4
              :api-kind :fn :manifest-kinds #{:macro}}]]
           [{:var "reg-event" :tier :front-porch :kind :macro :line 5 :raw "reg-event"} #{} []]]]
    (is (= expected
           (rf.api-manifest.api-md-check/reconcile
             {:rows               [{:namespace "re-frame.adapter.reagent" :var "adapter"
                                    :tier :adapter :kind :var}
                                   {:namespace "re-frame.ssr" :var "adapter"
                                    :tier :implementation :kind :fn}
                                   {:namespace "re-frame.core" :var "reg-event"
                                    :tier :front-porch :kind :macro}]
              :api-rows           [api-row]
              :known-unmanifested allow
              :aliases            rf.api-manifest.api-md-check/adapter-aliases}))
        (:raw api-row))))

(deftest parse-var-rows-reads-the-leading-kind-marker
  ;; The M/Fn cell is graded by its leading marker; an unknown spelling
  ;; (`Macro`) drops the row silently, which is the collapse the floor catches.
  (is (= [[3 "reg-event" :macro] [4 "adapter" :var] [5 "frame-root" :fn]]
         (map (juxt :line :var :kind)
              (rf.api-manifest.api-md-check/parse-var-rows
                [[1 "| API | M/Fn | Signature | Status | Tier | Spec |"]
                 [2 "|---|---|---|---|---|---|"]
                 [3 "| `reg-event` | M/Fn (CLJS) | sig | v1 | front-porch | 001 |"]
                 [4 "| `adapter` | Var (map) | sig | v1 | adapter | 006 |"]
                 [5 "| `frame-root` | Fn (Reagent component) | sig | v1 | front-porch | 002 |"]
                 [6 "| `render!` | Macro | sig | v1 | advanced | 006 |"]])))))

(deftest live-api-md-names-qualified-var-rows
  ;; Without qualified rows in the live table, the strict qualifier
  ;; resolution would guard nothing.
  (is (seq (filter :qualifier (rf.api-manifest.api-md-check/parse-api-md-var-rows)))))

(deftest extraction-floor-trips-only-on-a-collapse
  (is (some? (rf.api-manifest.api-md-check/floor-violation 49)))
  (is (nil? (rf.api-manifest.api-md-check/floor-violation 50)))
  ;; The floor guards a near-total collapse, never ordinary retirement churn,
  ;; so it must sit well below the live count.
  (is (nil? (rf.api-manifest.api-md-check/floor-violation
              (long (* 0.9 (count (rf.api-manifest.api-md-check/parse-api-md-var-rows))))))))
