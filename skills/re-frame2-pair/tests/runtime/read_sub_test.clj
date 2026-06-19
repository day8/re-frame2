;;;; tests/runtime/read_sub_test.clj
;;;;
;;;; Babashka-runnable verification of `read-sub!` + `validate-sub-id` in
;;;; preload/re_frame2_pair/runtime.cljs (rf2-3bu3d.7) — the validated
;;;; one-shot subscription read the MCP `read-sub` tool wraps.
;;;;
;;;; ## What rf2-3bu3d.7 adds
;;;;
;;;;   Reading a subscription value — the #1 read on any re-frame2 app —
;;;;   via raw eval-cljs `@(rf/subscribe [:foo])` is UNVALIDATED: a typo'd
;;;;   sub-id silently subscribes to a non-existent sub and returns
;;;;   nil/garbage. `read-sub!` closes that mistake class with the SAME
;;;;   no-silent-swallow discipline `dispatch-consequence!` applies on the
;;;;   write side:
;;;;     - VALIDATE the sub-id against the live `:sub` registrar FIRST
;;;;       (`validate-sub-id`); an unknown id returns :reason :unknown-id
;;;;       + :nearest matches WITHOUT subscribing (never a silent nil);
;;;;     - resolve the operating frame the same way every read op does;
;;;;       no frame -> :ambiguous-frame (never silently reads :rf/default);
;;;;     - subscribe + deref ONCE inside a try; a throw -> :sub-error (a
;;;;       structured error, not a bare nil);
;;;;     - a non-vector / empty shape -> :not-a-sub-vector.
;;;;
;;;; This file mirrors `read-sub!` + `validate-sub-id` + `validate-registered`
;;;; + the frame resolver against a stubbed `:sub` registry + sub-deref and
;;;; asserts the contract (the CLJS preload can't run under bb — it needs
;;;; the live re-frame2 registry).
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs §Call-time id
;;;; validation (`validate-sub-id`) and §Subscriptions (`read-sub!`).
;;;;
;;;; Run:  bb tests/runtime/read_sub_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns read-sub-test
  (:require [clojure.test :refer [deftest is run-tests testing use-fixtures]]
            [runtime-support :as rt]))

;; ---------------------------------------------------------------------------
;; PART 1 — Structural pins: the named fns exist in the real runtime source
;; and carry the no-silent-swallow slots. Mirrors the dispatch_consequence
;; structural-pin style so a refactor that drops the validation can't ship
;; green. Shared locate+parse+walk scaffold lives in
;; tests/runtime/_support.clj (rf2-yrpt90).
;; ---------------------------------------------------------------------------

(def ^:private defn-named rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(deftest defines-read-sub!
  (is (some? (defn-named 'read-sub!))
      "runtime.cljs must define `read-sub!` — the validated one-shot subscription read (rf2-3bu3d.7)."))

(deftest defines-validate-sub-id
  (is (some? (defn-named 'validate-sub-id))
      "runtime.cljs must define `validate-sub-id` — the call-time sub-id registry check (rf2-3bu3d.7)."))

(deftest validate-sub-id-checks-the-sub-registrar
  (let [form (defn-named 'validate-sub-id)]
    (is (form-contains? #(= :sub %) form)
        "validate-sub-id MUST validate against the :sub registrar.")
    (is (form-contains? #(= :query-v %) form)
        "validate-sub-id MUST echo the resolved :query-v.")))

(deftest read-sub!-carries-no-silent-swallow-slots
  (let [form (defn-named 'read-sub!)]
    (is (some? form))
    (doseq [slot [:unknown-id :ambiguous-frame :sub-error :not-a-sub-vector]]
      ;; :unknown-id is produced by validate-registered (called via
      ;; validate-sub-id); :ambiguous-frame is now produced via the shared
      ;; enriched builder `ambiguous-frame-error` (rf2-n58jxo) rather than an
      ;; inline keyword; the others are literal in read-sub!.
      (is (or (form-contains? #(= slot %) form)
              (and (= slot :unknown-id)
                   (form-contains? #(= 'validate-sub-id %) form))
              (and (= slot :ambiguous-frame)
                   (form-contains? #(= 'ambiguous-frame-error %) form)))
          (str "read-sub! MUST surface " slot
               " (no silent nil — no-silent-swallow parity with dispatch).")))))

;; ---------------------------------------------------------------------------
;; PART 2 — Functional mirror: re-implement the validation + frame resolver
;; + read-sub! against a stubbed :sub registry and sub-deref, and assert the
;; behaviour. KEEP IN SYNC.
;; ---------------------------------------------------------------------------

(def sub-registry (atom #{}))      ;; set of registered sub-ids
(def frame-registry (atom #{}))    ;; set of frame-ids
(def selected-frame (atom nil))
(def sub-values (atom {}))         ;; [frame-id query-v] -> value (or ::throw)

(defn register-sub! [id] (swap! sub-registry conj id) id)
(defn register-frame! [fid] (swap! frame-registry conj fid) fid)
(defn select-frame! [fid] (reset! selected-frame fid))
(defn set-sub-value! [fid query-v v] (swap! sub-values assoc [fid query-v] v))

(defn frame-ids [] @frame-registry)

;; Mirror of `current-frame`. KEEP IN SYNC.
(defn current-frame
  ([] (current-frame nil))
  ([override]
   (or override
       @selected-frame
       (let [fids (frame-ids)]
         (when (= 1 (count fids)) (first fids))))))

;; Mirror of `nearest-ids` (edit-distance is overkill for the bb mirror —
;; a substring/prefix proximity is enough to pin the :nearest slot is
;; populated). KEEP THE CONTRACT (returns up to 3 candidate ids), not the
;; exact ranking.
(defn nearest-ids [id known]
  (->> known
       (sort-by (fn [k] (let [a (pr-str id) b (pr-str k)]
                          (Math/abs (- (count a) (count b))))))
       (take 3)
       vec))

;; Mirror of `validate-registered`. KEEP IN SYNC.
(defn validate-registered [kind id known]
  (if (some #(= id %) known)
    {:ok? true :kind kind :id id}
    {:ok? false :reason :unknown-id :kind kind :id id
     :nearest (nearest-ids id known)
     :known-count (count known)}))

;; Mirror of `validate-sub-id`. KEEP IN SYNC.
(defn validate-sub-id [query-v]
  (let [id (when (sequential? query-v) (first query-v))]
    (assoc (validate-registered :sub id @sub-registry) :query-v query-v)))

;; Stand-in for `@(rf/subscribe frame-id query-v)`.
(defn subscribe-deref [fid query-v]
  (let [v (get @sub-values [fid query-v])]
    (if (= ::throw v)
      (throw (ex-info "boom" {}))
      v)))

;; Mirror of `ambiguous-frame-error` (rf2-n58jxo). The full runtime helper
;; carries realm context; this read-sub mirror uses the simpler frame model
;; (no realms) so it surfaces the load-bearing slots: operation, the query,
;; the available frames, the current pin. KEEP THE SLOT CONTRACT IN SYNC.
(defn ambiguous-frame-error [operation extra]
  (merge {:ok? false :reason :ambiguous-frame :operation operation
          :available-frames (vec (frame-ids))
          :selected-frame @selected-frame}
         extra))

;; Mirror of `read-sub!`. KEEP IN SYNC.
(defn read-sub!
  ([query-v] (read-sub! query-v (current-frame)))
  ([query-v frame-id]
   (cond
     (or (not (sequential? query-v)) (empty? query-v))
     {:ok? false :reason :not-a-sub-vector :query-v query-v}

     :else
     (let [v (validate-sub-id query-v)]
       (cond
         (not (:ok? v)) (assoc v :subscribed? false)

         (nil? frame-id)
         (ambiguous-frame-error :read-sub {:query query-v :query-v query-v})

         :else
         (try
           {:ok? true :query-v query-v :frame frame-id
            :value (subscribe-deref frame-id query-v)}
           (catch Exception e
             {:ok? false :reason :sub-error :query-v query-v
              :frame frame-id :message (.getMessage e)})))))))

(defn reset-state! []
  (reset! sub-registry #{})
  (reset! frame-registry #{})
  (reset! selected-frame nil)
  (reset! sub-values {}))

(use-fixtures :each (fn [t] (reset-state!) (t) (reset-state!)))

(deftest hit-returns-the-deref-value
  (register-frame! :rf/default)
  (register-sub! :current-user)
  (set-sub-value! :rf/default [:current-user] {:id 42 :name "Ada"})
  (let [r (read-sub! [:current-user])]
    (is (true? (:ok? r)))
    (is (= [:current-user] (:query-v r)))
    (is (= :rf/default (:frame r)))
    (is (= {:id 42 :name "Ada"} (:value r)))))

(deftest unknown-sub-id-validated-not-subscribed
  ;; The headline rf2-3bu3d.7 case: a typo'd sub-id must NOT silently
  ;; subscribe + return nil. Validation short-circuits with :unknown-id +
  ;; :nearest, :subscribed? false.
  (register-frame! :rf/default)
  (register-sub! :current-user)
  (let [r (read-sub! [:current-userr])]
    (is (false? (:ok? r)))
    (is (= :unknown-id (:reason r)))
    (is (= [:current-userr] (:query-v r)) "the resolved query-v is echoed on the miss")
    (is (false? (:subscribed? r)) "no subscribe happened")
    (is (some #(= :current-user %) (:nearest r))
        ":nearest carries the real sub for a corrective retry")))

(deftest not-a-sub-vector-rejected
  (register-frame! :rf/default)
  (is (= :not-a-sub-vector (:reason (read-sub! :current-user))) "bare keyword")
  (is (= :not-a-sub-vector (:reason (read-sub! []))) "empty vector"))

(deftest ambiguous-frame-refused
  ;; Two frames + no selection ⇒ refuse rather than silently read
  ;; :rf/default. (Validation must pass first, so register the sub.)
  (register-frame! :rf/default)
  (register-frame! :rf/other)
  (register-sub! :cart/total)
  (let [r (read-sub! [:cart/total])]
    (is (false? (:ok? r)))
    (is (= :ambiguous-frame (:reason r)))
    ;; rf2-n58jxo — enriched diagnostics on the refusal.
    (is (= :read-sub (:operation r)))
    (is (= [:cart/total] (:query r)))
    (is (= #{:rf/default :rf/other} (set (:available-frames r)))
        "available-frames enumerates the candidate frames")))

(deftest sub-error-is-structured-not-nil
  ;; A sub handler that throws while computing returns :sub-error with the
  ;; message — never a bare nil.
  (register-frame! :rf/default)
  (register-sub! :boom)
  (set-sub-value! :rf/default [:boom] ::throw)
  (let [r (read-sub! [:boom])]
    (is (false? (:ok? r)))
    (is (= :sub-error (:reason r)))
    (is (= "boom" (:message r)))))

(deftest explicit-frame-and-pin-steer-the-read
  (register-frame! :rf/default)
  (register-frame! :rf/other)
  (register-sub! :thing)
  (set-sub-value! :rf/other [:thing] 99)
  (testing "explicit frame arg wins"
    (is (= 99 (:value (read-sub! [:thing] :rf/other)))))
  (testing "select-frame! pin steers a no-arg read"
    (select-frame! :rf/other)
    (is (= :rf/other (:frame (read-sub! [:thing]))))))

(deftest nil-value-hit-is-still-ok
  ;; A sub that legitimately derefs to nil is :ok? true with :value nil —
  ;; distinct from the unknown-id / sub-error error paths.
  (register-frame! :rf/default)
  (register-sub! :maybe)
  (set-sub-value! :rf/default [:maybe] nil)
  (let [r (read-sub! [:maybe])]
    (is (true? (:ok? r)))
    (is (nil? (:value r)) "a genuine nil deref is a successful read, not a swallowed error")))

(let [{:keys [fail error]} (run-tests 'read-sub-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
