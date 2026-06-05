(ns re-frame.flows-output-marks-test
  "JVM coverage for Spec 015 §7 (Flows) data-classification on `reg-flow`
  (rf2-ouemt — senior-review finding).

  A `reg-flow` registration may carry output data-classification keys
  (`:sensitive?` / `:large?` whole-output, `:sensitive` / `:large`
  per-output-path). Pre-fix these were stashed in the frame-blind global
  `re-frame.marks` `{flow-id marks}` table whose only reader (`flow-marks`)
  was never wired into the central trace projection switch — so the contract
  was silently inert: a `{:sensitive [[:secret]]}` or `{:sensitive? true}`
  flow emitted its raw `:result` on `:rf.flow/computed` AND wrote the raw
  value to its app-db destination slot (visible to App-DB-Diff / pending-db
  egress / view render-arg egress).

  The fix makes flow output marks FIRST-CLASS through the SAME per-frame
  app-db elision registry the schema-first wire walker
  (`elision/elide-wire-value`) already reads: `reg-flow` translates the
  output-rooted marks into absolute declarations rooted at `(:path flow)`
  and installs them frame-aware. ONE walker then redacts BOTH the flow
  trace `:result` / `:before` slots AND the app-db destination slot.

  These tests pin the five acceptance cases the bead enumerates:
    1. per-path flow output redaction (`:sensitive` / `:large` sub-paths);
    2. whole-output `:sensitive? true`;
    3. `:large` whole-output markers;
    4. `:sensitive? false` opt-out (no whole-output mark installed);
    5. same-flow-id multi-frame registrations with DIFFERENT marks (frame
       isolation — the frame-blind table would conflate them)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(def ^:dynamic ^:private *captured* nil)

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (flows/reset-last-inputs!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-listener!
        ::flow-marks-recorder
        (fn [ev] (swap! captured conj ev)))
      (try
        (test-fn)
        (finally
          (trace/unregister-listener! ::flow-marks-recorder))))))

(use-fixtures :each reset-runtime)

(defn- by-op [op] (filterv #(= op (:operation %)) @*captured*))

(defn- sensitive-decls [frame-id]
  (elision/sensitive-declarations frame-id))

(defn- large-decls [frame-id]
  (elision/declarations frame-id))

;; ---------------------------------------------------------------------------
;; 1. Per-path flow output redaction — `:sensitive [[:secret]]`
;; ---------------------------------------------------------------------------

(deftest reg-flow-sensitive-subpath-redacts-result-and-app-db
  (testing "a flow declaring `:sensitive [[:secret]]` redacts the :secret
            sub-slot of its output on the :rf.flow/computed :result AND on
            the app-db destination egress, while a sibling slot rides raw"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id        :creds
                  :inputs    [[:n]]
                  :output    (fn [_] {:secret :S :public :P})
                  :path      [:derived :creds]
                  :sensitive [[:secret]]})
    ;; The absolute declaration was installed rooted at the flow's :path.
    (is (contains? (sensitive-decls :rf/default) [:derived :creds :secret])
        "an absolute sensitive declaration is installed at :path ++ [:secret]")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/computed)))]
      (is (= :creds (:flow-id tags)))
      (is (= privacy/redacted-sentinel (get-in (:result tags) [:secret]))
          ":secret is redacted on the :result trace slot")
      (is (= :P (get-in (:result tags) [:public]))
          ":public rides raw — only the declared sub-path redacts"))
    ;; App-db destination egress: the t2 pending-db stamp rides through
    ;; `project-db-tags` → `elide-wire-value`, reading the SAME registry.
    (let [t2 (last (filterv #(= :rf.event/db-pending-post-flow (:operation %))
                            @*captured*))
          stamped (-> t2 :tags :rf.event/db)]
      (is (some? t2) "a post-flow pending-db trace fired")
      (is (= privacy/redacted-sentinel (get-in stamped [:derived :creds :secret]))
          "the app-db destination :secret slot is redacted on db egress")
      (is (= :P (get-in stamped [:derived :creds :public]))
          ":public rides raw on the db egress too"))))

;; ---------------------------------------------------------------------------
;; 2. Whole-output `:sensitive? true`
;; ---------------------------------------------------------------------------

(deftest reg-flow-whole-output-sensitive-redacts-entire-result
  (testing "a flow declaring `:sensitive? true` redacts its WHOLE output on
            :result and at the app-db destination slot"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "header.payload.sig"})
                  :path       [:auth :token]
                  :sensitive? true})
    (is (contains? (sensitive-decls :rf/default) [:auth :token])
        "the whole-output sensitive declaration is installed at :path itself")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/computed)))]
      (is (= privacy/redacted-sentinel (:result tags))
          "the entire :result is redacted to the sentinel"))
    (let [t2 (last (filterv #(= :rf.event/db-pending-post-flow (:operation %))
                            @*captured*))
          stamped (-> t2 :tags :rf.event/db)]
      (is (= privacy/redacted-sentinel (get-in stamped [:auth :token]))
          "the whole app-db destination slot is redacted on db egress"))))

;; ---------------------------------------------------------------------------
;; 3. `:large` markers (whole-output and per-path)
;; ---------------------------------------------------------------------------

(deftest reg-flow-large-whole-output-marks-result
  (testing "a flow declaring `:large? true` substitutes the
            :rf.size/large-elided marker for its whole output on :result"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id     :blob
                  :inputs [[:n]]
                  :output (fn [_] {:bytes "BIG"})
                  :path   [:derived :blob]
                  :large? true})
    (is (contains? (large-decls :rf/default) [:derived :blob])
        "the whole-output large declaration is installed at :path itself")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/computed)))]
      (is (elision/marker? (:result tags))
          ":result is replaced by the large-elided marker")
      (let [marker (:rf.size/large-elided (:result tags))]
        (is (= [:derived :blob] (:path marker))
            "marker carries the flow's output path")
        ;; Flow output marks are installed into the SAME per-frame elision
        ;; registry the schema-first wire walker (`elide-wire-value`) reads,
        ;; so the marker is produced by `elision/->marker` and carries its
        ;; `:reason :schema` stamp. That uniformity is the point — one
        ;; walker, one marker shape, regardless of whether the declaration
        ;; was schema-sourced or reg-flow-sourced.
        (is (= :schema (:reason marker))
            "marker rides the unified walker's :reason :schema stamp")))))

(deftest reg-flow-large-subpath-marks-only-that-slot
  (testing "a flow declaring `:large [[:big]]` marks only the :big sub-slot"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id     :payload
                  :inputs [[:n]]
                  :output (fn [_] {:big {:k "BIG"} :small 1})
                  :path   [:out]
                  :large  [[:big]]})
    (is (contains? (large-decls :rf/default) [:out :big])
        "the large declaration is installed at :path ++ [:big]")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/computed)))]
      (is (elision/marker? (get-in (:result tags) [:big]))
          ":big is replaced by the large-elided marker")
      (is (= 1 (get-in (:result tags) [:small]))
          ":small rides raw — only the declared sub-path is marked"))))

;; ---------------------------------------------------------------------------
;; 4. `:sensitive? false` opt-out — no whole-output mark installed
;; ---------------------------------------------------------------------------

(deftest reg-flow-sensitive-false-installs-no-whole-output-mark
  (testing "a flow declaring `:sensitive? false` installs NO whole-output
            declaration — its result rides raw (flows carry no upstream
            propagation, so the explicit-false override is the absence of a
            mark); per-path declarations on the same flow still apply"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id         :mixed
                  :inputs     [[:n]]
                  :output     (fn [_] {:hashed :H :raw :R})
                  :path       [:safe]
                  :sensitive? false
                  :sensitive  [[:hashed]]})
    ;; No whole-output declaration at :path itself.
    (is (not (contains? (sensitive-decls :rf/default) [:safe]))
        ":sensitive? false installs no whole-output declaration at :path")
    ;; The per-path declaration still applies.
    (is (contains? (sensitive-decls :rf/default) [:safe :hashed])
        "the per-path :sensitive [[:hashed]] declaration is still installed")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [result (:result (:tags (last (by-op :rf.flow/computed))))]
      (is (map? result)
          ":result is NOT wholesale-redacted (sensitive? false opt-out)")
      (is (= privacy/redacted-sentinel (get-in result [:hashed]))
          ":hashed sub-slot is still redacted by the per-path declaration")
      (is (= :R (get-in result [:raw]))
          ":raw rides through — neither whole-output nor per-path marked"))))

(deftest reg-flow-no-marks-leaves-registry-untouched
  (testing "a plain flow (no classification keys) installs no declarations —
            the no-marks common case stays zero-overhead"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id     :plain
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    (is (empty? (sensitive-decls :rf/default))
        "no sensitive declarations for a no-marks flow")
    (is (empty? (large-decls :rf/default))
        "no large declarations for a no-marks flow")))

;; ---------------------------------------------------------------------------
;; 5. Same-flow-id multi-frame registrations with DIFFERENT marks
;;
;; Spec 013 lets the SAME flow-id carry different definitions — hence
;; different marks — in different frames. The frame-blind `{flow-id marks}`
;; table the old code used would conflate them (last-writer-wins). The
;; frame-aware elision-registry install keeps them isolated by construction.
;; ---------------------------------------------------------------------------

(deftest same-flow-id-different-frames-different-marks-isolated
  (testing "the same flow-id registered against two frames with DIFFERENT
            output marks redacts independently per frame"
    (rf/reg-frame :left  {:doc "left"})
    (rf/reg-frame :right {:doc "right"})
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    ;; :left — whole output sensitive.
    (rf/reg-flow {:id         :shared
                  :inputs     [[:n]]
                  :output     (fn [_] {:v :LEFT})
                  :path       [:out]
                  :sensitive? true}
                 {:frame :left})
    ;; :right — same id, NO marks (rides raw).
    (rf/reg-flow {:id     :shared
                  :inputs [[:n]]
                  :output (fn [_] {:v :RIGHT})
                  :path   [:out]}
                 {:frame :right})
    ;; Registry isolation: :left carries the declaration, :right does not.
    (is (contains? (sensitive-decls :left) [:out])
        ":left frame carries the whole-output sensitive declaration")
    (is (empty? (sensitive-decls :right))
        ":right frame carries NO declaration — the marks did not bleed across")
    (reset! *captured* [])
    (rf/dispatch-sync [:init] {:frame :left})
    (rf/dispatch-sync [:init] {:frame :right})
    (let [left-tags  (:tags (last (filterv #(and (= :rf.flow/computed (:operation %))
                                                 (= :left (-> % :tags :frame)))
                                           @*captured*)))
          right-tags (:tags (last (filterv #(and (= :rf.flow/computed (:operation %))
                                                 (= :right (-> % :tags :frame)))
                                           @*captured*)))]
      (is (= privacy/redacted-sentinel (:result left-tags))
          ":left's result is redacted (its frame declared :sensitive? true)")
      (is (= {:v :RIGHT} (:result right-tags))
          ":right's result rides raw — its frame declared no marks"))))

;; ---------------------------------------------------------------------------
;; 6. Lifecycle: clear-flow drops the flow's output declarations
;; ---------------------------------------------------------------------------

(deftest clear-flow-drops-output-marks
  (testing "clear-flow removes the flow's :source :flow elision declarations"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:auth :token]
                  :sensitive? true})
    (is (contains? (sensitive-decls :rf/default) [:auth :token])
        "declaration present after reg-flow")
    (rf/clear-flow :token)
    (is (not (contains? (sensitive-decls :rf/default) [:auth :token]))
        "declaration dropped after clear-flow")))

(deftest clear-flow-preserves-other-sources
  (testing "clear-flow drops only :source :flow entries — schema- and
            add-marks-sourced declarations on adjacent paths survive"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    ;; An add-marks-sourced sensitive path the flow does not own.
    (rf/add-marks :rf/default {[:user :ssn] :sensitive})
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:auth :token]
                  :sensitive? true})
    (rf/clear-flow :token)
    (is (not (contains? (sensitive-decls :rf/default) [:auth :token]))
        "the flow-sourced declaration is gone")
    (is (contains? (sensitive-decls :rf/default) [:user :ssn])
        "the add-marks-sourced declaration survives clear-flow")))

;; ---------------------------------------------------------------------------
;; 7. Lifecycle: re-registration that changes :path moves the declaration
;; ---------------------------------------------------------------------------

(deftest reg-flow-path-change-moves-output-marks
  (testing "re-registering a flow with a NEW :path drops the OLD path's
            flow-sourced declaration and installs it at the new path"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:old :token]
                  :sensitive? true})
    (is (contains? (sensitive-decls :rf/default) [:old :token])
        "declaration at the original path")
    ;; Re-register the SAME id on the SAME frame with a NEW path.
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:new :token]
                  :sensitive? true})
    (is (not (contains? (sensitive-decls :rf/default) [:old :token]))
        "the OLD path's flow declaration is dropped on path-change")
    (is (contains? (sensitive-decls :rf/default) [:new :token])
        "the declaration is installed at the NEW path")))

(deftest reg-flow-re-register-with-fewer-marks-replaces-cleanly
  (testing "re-registering with a REDUCED mark set replaces the prior flow
            declarations wholesale (no stale leftovers)"
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id        :creds
                  :inputs    [[:n]]
                  :output    (fn [_] {:a 1 :b 2})
                  :path      [:out]
                  :sensitive [[:a] [:b]]})
    (is (contains? (sensitive-decls :rf/default) [:out :a]))
    (is (contains? (sensitive-decls :rf/default) [:out :b]))
    ;; Re-register dropping :b from the sensitive set.
    (rf/reg-flow {:id        :creds
                  :inputs    [[:n]]
                  :output    (fn [_] {:a 1 :b 2})
                  :path      [:out]
                  :sensitive [[:a]]})
    (is (contains? (sensitive-decls :rf/default) [:out :a])
        ":a's declaration survives the re-registration")
    (is (not (contains? (sensitive-decls :rf/default) [:out :b]))
        ":b's stale declaration is cleared — re-registration replaces in full")))
