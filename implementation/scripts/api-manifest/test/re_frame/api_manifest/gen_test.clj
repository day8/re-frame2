(ns re-frame.api-manifest.gen-test
  "Tests for the manifest generator's row-level refusals — one row per
  [namespace var], no `:tier :implementation` facade export, and both
  facade-audit axes (`:justification`, `:action`) on every facade row — and
  for the committed manifest's coverage of its rosters.

  The refusal tests drive `build-manifest` with the REAL sidecar plus one
  planted fault, so every earlier check passes and the exact ex-data names
  the refusal that fired."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]))

(def ^:private capture-frame ["re-frame.core" "capture-frame"])

(defn- refusal
  "The ex-data `build-manifest` throws for `sidecar`, or nil when it builds."
  [sidecar]
  (try (rf.api-manifest.gen/build-manifest sidecar)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest build-manifest-throws-on-duplicate
  ;; A duplicated `:cljs-only` sidecar row, here with a conflicting tier.
  (let [sidecar (rf.api-manifest.gen/read-sidecar)
        row     (first (:cljs-only sidecar))]
    (is (= [[[(:namespace row) (:var row)] 2]]
           (:duplicates (refusal (update sidecar :cljs-only conj (assoc row :tier :tooling))))))))

(deftest build-manifest-throws-on-cljs-only-row-colliding-with-jvm-row
  ;; Uniqueness is checked over the JVM and `:cljs-only` rows together, so a
  ;; JVM-loadable var hand-rowed under `:cljs-only` never ships as two rows.
  (let [sidecar (rf.api-manifest.gen/read-sidecar)]
    (is (= [[capture-frame 2]]
           (:duplicates
             (refusal (update sidecar :cljs-only conj
                              (assoc (get-in sidecar [:classification capture-frame])
                                     :namespace (first capture-frame)
                                     :var (second capture-frame)
                                     :kind :fn
                                     :facade? true))))))))

(deftest implementation-facade-rows-flags-only-the-contradiction
  ;; `:internal-public` is a supported embed seam, not an internal tier, so a
  ;; facade row there is not a contradiction.
  (is (= [["re-frame.core" "make-capture-frame"]]
         (rf.api-manifest.gen/implementation-facade-rows
           [{:namespace "re-frame.core" :var "make-capture-frame"
             :tier :implementation :facade? true}
            {:namespace "re-frame.core" :var "frame-provider"
             :tier :internal-public :facade? true}]))))

(deftest build-manifest-throws-on-implementation-facade-row
  (is (= [capture-frame]
         (:implementation-facade
           (refusal (assoc-in (rf.api-manifest.gen/read-sidecar)
                              [:classification capture-frame :tier] :implementation))))))

(deftest unjustified-facade-rows-treats-blank-as-missing
  (is (= [["re-frame.core" "blank"] ["re-frame.core" "not-a-string"]]
         (rf.api-manifest.gen/unjustified-facade-rows
           [{:namespace "re-frame.core" :var "blank" :facade? true
             :action :keep :justification "   \n  "}
            {:namespace "re-frame.core" :var "not-a-string" :facade? true
             :action :keep :justification :keep}]))))

(deftest build-manifest-throws-on-unjustified-facade-row
  (is (= [capture-frame]
         (:unjustified-facade
           (refusal (update-in (rf.api-manifest.gen/read-sidecar)
                               [:classification capture-frame] dissoc :justification))))))

(deftest build-manifest-throws-on-missing-or-unknown-action
  ;; An absent :action is the shape a new, unclassified facade export has.
  (let [sidecar (rf.api-manifest.gen/read-sidecar)]
    (is (= [(conj capture-frame :defer)]
           (:bad-action-facade
             (refusal (assoc-in sidecar [:classification capture-frame :action] :defer)))))
    (is (= [(conj capture-frame nil)]
           (:bad-action-facade
             (refusal (update-in sidecar [:classification capture-frame] dissoc :action)))))))

(deftest live-manifest-axes-are-facade-scoped
  ;; `build-manifest` does not refuse an axis on an ordinary row, where it
  ;; would read as a facade classification nobody made.
  (is (empty? (filter #(or (contains? % :action) (contains? % :justification))
                      (remove :facade? (:vars (rf.api-manifest.gen/read-committed-manifest)))))))

(deftest xray-facade-rows-are-cljs-only-and-carry-the-flag-per-row
  ;; The Xray facade cannot be introspected on the JVM, so its rows carry
  ;; `:facade? true` in the sidecar rather than through `facade-namespaces`.
  (is (every? :facade? (filter #(= "day8.re-frame2-xray.core" (:namespace %))
                               (:cljs-only (rf.api-manifest.gen/read-sidecar))))))

(deftest live-manifest-has-facade-rows-in-every-enrolled-facade
  ;; `--check` is green whenever the regenerated and committed manifests
  ;; agree, so it cannot see `facade?` narrowing to fewer namespaces.
  (let [facade-nses (set (map :namespace (filter :facade? (:vars (rf.api-manifest.gen/read-committed-manifest)))))]
    (doseq [ns-sym rf.api-manifest.gen/facade-namespaces]
      (is (contains? facade-nses (name ns-sym))
          (str "no :facade? true row for enrolled facade " ns-sym)))))

(deftest every-jvm-namespace-contributes-rows
  ;; `--check` is green whenever the two manifests agree, including when both
  ;; lack a rostered namespace whose publics all went `^:no-doc` or behind a
  ;; reader conditional.
  (let [rowed (set (map :namespace (:vars (rf.api-manifest.gen/read-committed-manifest))))]
    (doseq [ns-sym rf.api-manifest.gen/jvm-namespaces]
      (is (contains? rowed (name ns-sym))
          (str ns-sym " is in jvm-namespaces but contributes no committed manifest row")))))
