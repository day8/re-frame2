(ns re-frame.flows-output-marks-test
  "JVM coverage for Spec 015 §7 (Flows) data-classification on `reg-flow`
  (rf2-ouemt — senior-review finding; rf2-ihfz9o — input→output PROPAGATION).

  A `reg-flow` registration may carry output data-classification keys
  (`:rf.egress/output-sensitivity` derived-output sensitivity enum + `:large?`
  whole-output size, `:sensitive` / `:large` per-output-path). Pre-fix these
  were stashed in the frame-blind global
  `re-frame.marks` `{flow-id marks}` table whose only reader (`flow-marks`)
  was never wired into the central trace projection switch — so the contract
  was silently inert: a `{:sensitive [[:secret]]}` or `{:rf.egress/output-sensitivity :rf.egress/sensitive}`
  flow emitted its raw `:result` on `:rf.flow/computed` AND wrote the raw
  value to its app-db destination slot (visible to App-DB-Diff / pending-db
  egress / view render-arg egress).

  The fix (rf2-ouemt) makes flow output marks FIRST-CLASS through the SAME
  per-frame app-db elision registry the schema-first wire walker
  (`elision/elide-wire-value`) already reads: `reg-flow` translates the
  output-rooted marks into absolute declarations rooted at `(:path flow)`
  and installs them frame-aware. ONE walker then redacts BOTH the flow
  trace `:result` / `:before` slots AND the app-db destination slot.

  rf2-ihfz9o (Mike RULED (a) PROPAGATE, 2026-06-09) closes the privacy gap
  the prior impl carried: a flow OUTPUT now INHERITS the data-classification
  of its INPUT paths (Spec 015:313 + the 015:568 conformance fixture). A flow
  reading a SENSITIVE app-db (or runtime-db-qualified, rf2-4eisfr) input
  emits a sensitive output BY DEFAULT (fail-closed — taint by default,
  declassify explicitly) unless the author opts out with `:rf.egress/output-sensitivity :rf.egress/public`.
  `:large` is asymmetric and is NOT auto-propagated (a flow usually shrinks a
  large input). `:rf.egress/output-sensitivity :rf.egress/public` is now a REAL declassify — it suppresses
  the propagated mark (previously a no-op).

  These tests pin the acceptance cases the bead enumerates:
    1. per-path flow output redaction (`:sensitive` / `:large` sub-paths);
    2. whole-output `:rf.egress/output-sensitivity :rf.egress/sensitive`;
    3. `:large` whole-output markers;
    4. `:rf.egress/output-sensitivity :rf.egress/public` declassify (the explicit opt-out);
    5. same-flow-id multi-frame registrations with DIFFERENT marks (frame
       isolation — the frame-blind table would conflate them);
    6. lifecycle (clear-flow / path-change drop & move declarations);
    7. PROPAGATION (rf2-ihfz9o) — default sensitive inheritance, explicit
       `:rf.egress/output-sensitivity :rf.egress/sensitive` over a sensitive input, explicit `:rf.egress/output-sensitivity :rf.egress/public`
       declassify of a sensitive input, per-output-path coexisting with
       propagation, BOTH `:sensitive` AND `:large` axes, runtime-db-qualified
       input propagation, flow→flow DAG propagation, and the t2
       `:rf.event/db-pending-post-flow` redaction the 015:568 fixture pins."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            ;; EP-0015 (rf2-mngp4o): `add-marks` / `set-marks` are no longer
            ;; on the `re-frame.core` façade; these tests drive the internal
            ;; `re-frame.marks` helpers directly.
            [re-frame.marks :as marks]
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
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002 (rf2-5q7um6): reg-flow is context-required frame-local — an
  ;; ambient call under no scope raises :rf.error/no-frame-context. Pin
  ;; :rf/default (an ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (let [captured (atom [])]
    (binding [*captured*           captured
              frame/*current-frame* :rf/default]
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
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
;; 2. Whole-output `:rf.egress/output-sensitivity :rf.egress/sensitive`
;; ---------------------------------------------------------------------------

(deftest reg-flow-whole-output-sensitive-redacts-entire-result
  (testing "a flow declaring `:rf.egress/output-sensitivity :rf.egress/sensitive` redacts its WHOLE output on
            :result and at the app-db destination slot"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "header.payload.sig"})
                  :path       [:auth :token]
                  :rf.egress/output-sensitivity :rf.egress/sensitive})
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
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
        ;; registry the wire walker (`elide-wire-value`) reads, so the
        ;; marker is produced by `elision/->marker` and carries the
        ;; declaration's `:source` as its `:reason`. reg-flow-sourced
        ;; declarations are stamped `{:source :flow}` (registry.cljc), so
        ;; the marker rides `:reason :flow`. That uniformity is the point —
        ;; one walker, one marker shape; the `:reason` records provenance
        ;; (`:flow` here, `:frame` for frame-owned `:large {:app-db …}`,
        ;; `:marks` for marks-sourced). Per EP-0015 §8 schemas no longer
        ;; feed this registry, so `:schema` is no longer a `:reason`.
        (is (= :flow (:reason marker))
            "marker rides the walker's :reason :flow stamp (reg-flow-sourced)")))))

(deftest reg-flow-large-subpath-marks-only-that-slot
  (testing "a flow declaring `:large [[:big]]` marks only the :big sub-slot"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
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
;; 4. `:rf.egress/output-sensitivity :rf.egress/public` opt-out — no whole-output mark installed
;; ---------------------------------------------------------------------------

(deftest reg-flow-sensitive-false-installs-no-whole-output-mark
  (testing "a flow declaring `:rf.egress/output-sensitivity :rf.egress/public` over an UNMARKED input
            installs NO whole-output declaration — its result rides raw. With
            an unmarked input there is nothing to propagate, so the result is
            the same under both the old no-propagation model and the new
            propagate-by-default model; the explicit-false override is still
            the absence of a whole-output mark. Per-path `:sensitive`
            declarations on the same flow still apply. (The sensitive-INPUT
            declassify — where `:rf.egress/output-sensitivity :rf.egress/public` actively SUPPRESSES a
            propagated mark — is pinned in `propagation-explicit-false-...`
            below; rf2-ihfz9o.)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    (rf/reg-flow {:id         :mixed
                  :inputs     [[:n]]
                  :output     (fn [_] {:hashed :H :raw :R})
                  :path       [:safe]
                  :rf.egress/output-sensitivity :rf.egress/public
                  :sensitive  [[:hashed]]})
    ;; No whole-output declaration at :path itself.
    (is (not (contains? (sensitive-decls :rf/default) [:safe]))
        ":rf.egress/output-sensitivity :rf.egress/public installs no whole-output declaration at :path")
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    ;; :left — whole output sensitive.
    (rf/reg-flow {:id         :shared
                  :inputs     [[:n]]
                  :output     (fn [_] {:v :LEFT})
                  :path       [:out]
                  :rf.egress/output-sensitivity :rf.egress/sensitive}
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
          ":left's result is redacted (its frame declared :rf.egress/output-sensitivity :rf.egress/sensitive)")
      (is (= {:v :RIGHT} (:result right-tags))
          ":right's result rides raw — its frame declared no marks"))))

;; ---------------------------------------------------------------------------
;; 6. Lifecycle: clear-flow drops the flow's output declarations
;; ---------------------------------------------------------------------------

(deftest clear-flow-drops-output-marks
  (testing "clear-flow removes the flow's :source :flow elision declarations"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:auth :token]
                  :rf.egress/output-sensitivity :rf.egress/sensitive})
    (is (contains? (sensitive-decls :rf/default) [:auth :token])
        "declaration present after reg-flow")
    (flows/clear-flow :token)
    (is (not (contains? (sensitive-decls :rf/default) [:auth :token]))
        "declaration dropped after clear-flow")))

(deftest clear-flow-preserves-other-sources
  (testing "clear-flow drops only :source :flow entries — schema- and
            add-marks-sourced declarations on adjacent paths survive"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    ;; An add-marks-sourced sensitive path the flow does not own.
    (marks/add-marks :rf/default {[:user :ssn] :sensitive})
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:auth :token]
                  :rf.egress/output-sensitivity :rf.egress/sensitive})
    (flows/clear-flow :token)
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:old :token]
                  :rf.egress/output-sensitivity :rf.egress/sensitive})
    (is (contains? (sensitive-decls :rf/default) [:old :token])
        "declaration at the original path")
    ;; Re-register the SAME id on the SAME frame with a NEW path.
    (rf/reg-flow {:id         :token
                  :inputs     [[:n]]
                  :output     (fn [_] {:jwt "x"})
                  :path       [:new :token]
                  :rf.egress/output-sensitivity :rf.egress/sensitive})
    (is (not (contains? (sensitive-decls :rf/default) [:old :token]))
        "the OLD path's flow declaration is dropped on path-change")
    (is (contains? (sensitive-decls :rf/default) [:new :token])
        "the declaration is installed at the NEW path")))

(deftest reg-flow-re-register-with-fewer-marks-replaces-cleanly
  (testing "re-registering with a REDUCED mark set replaces the prior flow
            declarations wholesale (no stale leftovers)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
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

;; ===========================================================================
;; 8. PROPAGATION (rf2-ihfz9o — Mike RULED (a) PROPAGATE)
;;
;; A flow OUTPUT inherits the data-classification of its INPUT paths
;; (Spec 015:313 + the 015:568 conformance fixture). The cases below pin the
;; bead's enumerated propagation coverage:
;;   - default inheritance (no explicit key on the flow);
;;   - explicit `:rf.egress/output-sensitivity :rf.egress/sensitive` over a sensitive input (force-mark holds);
;;   - explicit `:rf.egress/output-sensitivity :rf.egress/public` declassify of a sensitive input (the
;;     opt-out actively SUPPRESSES the propagated mark — previously a no-op);
;;   - per-output-path marks coexisting with whole-output propagation;
;;   - both `:sensitive` AND `:large` axes (the asymmetry: :sensitive
;;     propagates, :large does NOT);
;;   - runtime-db-qualified `[:rf.db/runtime …]` inputs (compose with
;;     rf2-4eisfr — partition-aware);
;;   - flow→flow DAG propagation (a flow reading an upstream flow's :path);
;;   - the t2 `:rf.event/db-pending-post-flow` redaction (Spec 015:568).
;; ===========================================================================

(defn- reg-fw-runtime-handler!
  "A framework-authority handler (the `:rf/machine?` marker the partition
  diagnostic keys on) may emit a `:rf.db/runtime` effect without the dev
  diagnostic — the same shape `partitioned_commit_test` uses to seed the
  runtime-db partition."
  [id f]
  (rf/reg-event id {:doc "framework-authority" :rf/machine? true} f))

(defn- computed-result
  "The `:result` slot of the LAST `:rf.flow/computed` trace for `flow-id`."
  [flow-id]
  (->> (by-op :rf.flow/computed)
       (filterv #(= flow-id (-> % :tags :flow-id)))
       last
       :tags
       :result))

(defn- computed-input-values
  "The `:input-values` slot of the LAST `:rf.flow/computed` trace for
  `flow-id` — the per-input elided values vector."
  [flow-id]
  (->> (by-op :rf.flow/computed)
       (filterv #(= flow-id (-> % :tags :flow-id)))
       last
       :tags
       :input-values))

(deftest propagation-default-sensitive-input-inherits-to-output
  (testing "a flow reading a SENSITIVE input path emits a SENSITIVE output by
            default (no explicit classification key) — Spec 015:313's
            :computed/full-name shape. The propagated whole-output sensitive
            declaration is installed at the flow's :path, and the
            :rf.flow/computed :result is wholesale-redacted."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:user {:first "Ada" :last "Lovelace"}})}))
    ;; Mark the input slot sensitive BEFORE reg-flow (the realistic ordering).
    (marks/add-marks :rf/default {[:user :first] :sensitive})
    (rf/reg-flow {:id     :computed/full-name
                  :inputs [[:user :first] [:user :last]]
                  :output (fn [first last] (str first " " last))
                  :path   [:computed :full-name]})
    ;; The propagated whole-output declaration is installed at reg-flow time
    ;; because the input already overlaps a sensitive declaration.
    (is (contains? (sensitive-decls :rf/default) [:computed :full-name])
        "the flow output :path inherits a propagated whole-output sensitive mark")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (is (= privacy/redacted-sentinel (computed-result :computed/full-name))
        "the format-preserving derived output is redacted (taint propagated)")))

(deftest propagation-fires-when-mark-added-AFTER-reg-flow
  (testing "a sensitive mark added AFTER the flow registered still reaches the
            output — the drain-time topo refresh is the mark-mutation trigger a
            flow needs (it does not recompute on a mark-only change)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:secret-in "S"})}))
    (rf/reg-flow {:id     :derive
                  :inputs [[:secret-in]]
                  :output (fn [s] (str s "-derived"))
                  :path   [:derived]})
    ;; No mark yet — nothing inherited at reg-flow time.
    (is (not (contains? (sensitive-decls :rf/default) [:derived]))
        "no propagated mark before the input is marked")
    ;; Mark the input AFTER registration.
    (marks/add-marks :rf/default {[:secret-in] :sensitive})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (is (contains? (sensitive-decls :rf/default) [:derived])
        "the drain refresh installed the propagated mark after add-marks")
    (is (= privacy/redacted-sentinel (computed-result :derive))
        "the output is redacted on the first drain after the input was marked")))

(deftest propagation-explicit-true-over-sensitive-input-holds
  (testing "explicit `:rf.egress/output-sensitivity :rf.egress/sensitive` over a sensitive input keeps the
            whole-output redaction (force-mark and propagation agree)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:tok "T"})}))
    (marks/add-marks :rf/default {[:tok] :sensitive})
    (rf/reg-flow {:id         :wrap
                  :inputs     [[:tok]]
                  :output     (fn [t] {:wrapped t})
                  :path       [:wrapped-tok]
                  :rf.egress/output-sensitivity :rf.egress/sensitive})
    (is (contains? (sensitive-decls :rf/default) [:wrapped-tok]))
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (is (= privacy/redacted-sentinel (computed-result :wrap))
        "whole output redacted")))

(deftest propagation-explicit-false-declassifies-sensitive-input
  (testing "explicit `:rf.egress/output-sensitivity :rf.egress/public` over a SENSITIVE input is a REAL
            declassify — it SUPPRESSES the propagated whole-output mark (the
            hash/mask/aggregate opt-out, Spec 015's :computed/hashed-token).
            Previously a no-op; rf2-ihfz9o makes it load-bearing."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:tok "secret-token"})}))
    (marks/add-marks :rf/default {[:tok] :sensitive})
    (rf/reg-flow {:id         :computed/hashed-token
                  :inputs     [[:tok]]
                  :output     (fn [t] (hash t))      ; safe — author de-sensitised
                  :path       [:computed :token-hash]
                  :rf.egress/output-sensitivity :rf.egress/public})
    (is (not (contains? (sensitive-decls :rf/default) [:computed :token-hash]))
        ":rf.egress/output-sensitivity :rf.egress/public suppresses the propagated whole-output mark")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (is (integer? (computed-result :computed/hashed-token))
        "the declassified hash output rides RAW (not redacted)")))

(deftest propagation-false-cannot-unmark-schema-or-add-marks-source
  (testing "a flow's `:rf.egress/output-sensitivity :rf.egress/public` declassify suppresses only the FLOW's
            own propagated/whole mark — it CANNOT unmark an add-marks-sourced
            declaration on the SAME output path (union semantics, Spec 015:295)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:in "x"})}))
    (marks/add-marks :rf/default {[:in] :sensitive})
    ;; An independent add-marks declaration directly on the flow's OUTPUT path.
    (marks/add-marks :rf/default {[:out] :sensitive})
    (rf/reg-flow {:id         :passthrough
                  :inputs     [[:in]]
                  :output     (fn [v] (str v "!"))
                  :path       [:out]
                  :rf.egress/output-sensitivity :rf.egress/public})
    ;; The add-marks-sourced declaration on [:out] survives — the flow's
    ;; opt-out only governs its OWN :source :flow contribution.
    (is (contains? (sensitive-decls :rf/default) [:out])
        "the add-marks-sourced [:out] declaration survives the flow opt-out")
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (is (= privacy/redacted-sentinel (computed-result :passthrough))
        "the output is STILL redacted — the union mark from add-marks holds")))

(deftest propagation-per-path-coexists-with-whole-output
  (testing "an explicit per-output-path `:sensitive [[:extra]]` declaration
            coexists with the propagated whole-output mark — both install"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:in "x"})}))
    (marks/add-marks :rf/default {[:in] :sensitive})
    (rf/reg-flow {:id        :combine
                  :inputs    [[:in]]
                  :output    (fn [v] {:body v :extra :E})
                  :path      [:combined]
                  :sensitive [[:extra]]})
    (is (contains? (sensitive-decls :rf/default) [:combined])
        "propagated whole-output mark present")
    (is (contains? (sensitive-decls :rf/default) [:combined :extra])
        "explicit per-path mark present too")))

(deftest propagation-large-input-does-not-auto-propagate
  (testing "the :sensitive / :large asymmetry: a flow reading a LARGE input
            does NOT auto-propagate :large to its output — a flow typically
            shrinks a large input (count / summary / first-N), so :large
            comes ONLY from explicit flow declarations."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:blob "BIG"})}))
    (marks/add-marks :rf/default {[:blob] :large})
    (rf/reg-flow {:id     :summarise
                  :inputs [[:blob]]
                  :output (fn [b] (count b))      ; shrinks — derived-from-large is small
                  :path   [:blob-size]})
    (is (not (contains? (large-decls :rf/default) [:blob-size]))
        ":large is NOT auto-propagated to the (shrunk) flow output")
    (is (not (contains? (sensitive-decls :rf/default) [:blob-size]))
        "no sensitive mark either (the input was marked :large only)")))

(deftest propagation-large-input-sensitive-sibling-marks-only-sensitive
  (testing "reading a SENSITIVE input and a LARGE input: only :sensitive
            propagates to the output (the asymmetry, cleanly separated)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:s "secret" :big "BIG"})}))
    (marks/add-marks :rf/default {[:s] :sensitive [:big] :large})
    (rf/reg-flow {:id     :mix
                  :inputs [[:s] [:big]]
                  :output (fn [s big] {:s s :n (count big)})
                  :path   [:mixed]})
    (is (contains? (sensitive-decls :rf/default) [:mixed])
        ":sensitive propagated from the sensitive input")
    (is (not (contains? (large-decls :rf/default) [:mixed]))
        ":large did NOT propagate from the large input")))

(deftest propagation-runtime-db-qualified-input
  (testing "a flow reading a SENSITIVE runtime-db-qualified input
            `[:rf.db/runtime …]` (rf2-4eisfr) propagates the mark to its
            app-db output — partition-aware, the same machinery, one pass
            (rf2-ihfz9o COMPOSE-WITH-rf2-4eisfr). This closes the leak the
            ruling sharpened: a sensitive runtime-db slot must not write an
            unclassified app-db output."
    ;; Seed the runtime-db slot (framework-authority write) and mark it.
    (reg-fw-runtime-handler! :seed-rt
      (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:token "RT-SECRET"}}}}))
    ;; Mark the STRIPPED runtime-db path (the registry is partition-blind —
    ;; declarations are plain path vectors).
    (marks/add-marks :rf/default {[:rf.runtime/routing :current :token] :sensitive})
    (rf/reg-flow {:id     :route-token-echo
                  :inputs [[:rf.db/runtime :rf.runtime/routing :current :token]]
                  :output (fn [t] {:echo t})
                  :path   [:derived :route-token]})
    (is (contains? (sensitive-decls :rf/default) [:derived :route-token])
        "the runtime-db-qualified sensitive input propagates to the app-db output")
    (reset! *captured* [])
    (rf/dispatch-sync [:seed-rt])
    (is (= privacy/redacted-sentinel (computed-result :route-token-echo))
        "the output derived from a sensitive runtime-db slot is redacted")))

(deftest runtime-db-qualified-input-value-is-elided-on-computed-trace
  ;; rf2-p44r3u — the INPUT-VALUE leg of the runtime-qualified path. The
  ;; `propagation-runtime-db-qualified-input` test above proves the derived
  ;; OUTPUT is redacted; this proves the INPUT VALUE the flow read does not
  ;; egress raw on the `:rf.flow/computed` `:input-values` slot. Pre-fix,
  ;; `elide-inputs` seeded `elide-wire-value` with the DECLARED input path
  ;; `[:rf.db/runtime :rf.runtime/routing :current :token]`, but the sensitive
  ;; declaration is keyed at the STRIPPED runtime-db path
  ;; `[:rf.runtime/routing :current :token]` (the registry is partition-blind),
  ;; so the seed path never matched the declaration and the raw "RT-SECRET"
  ;; rode the input-values slot. The fix normalizes the runtime-qualified
  ;; input path (strip the partition key) before the elision walk, reusing the
  ;; SAME registry normalization (`input-resolve-path`) the propagation path
  ;; already uses.
  (testing "a sensitive runtime-db-qualified input value is elided (not raw)
            on the `:rf.flow/computed` `:input-values` slot"
    (reg-fw-runtime-handler! :seed-rt
      (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:token "RT-SECRET"}}}}))
    (marks/add-marks :rf/default {[:rf.runtime/routing :current :token] :sensitive})
    (rf/reg-flow {:id     :route-token-echo
                  ;; Declassify the OUTPUT so the flow recomputes / emits a
                  ;; non-redacted result — isolating the INPUT-VALUE leg from
                  ;; the (separately-tested) output propagation.
                  :inputs [[:rf.db/runtime :rf.runtime/routing :current :token]]
                  :output (fn [t] {:echo t})
                  :path   [:derived :route-token]
                  :rf.egress/output-sensitivity :rf.egress/public})
    (reset! *captured* [])
    (rf/dispatch-sync [:seed-rt])
    (let [iv (computed-input-values :route-token-echo)]
      (is (vector? iv)
          ":input-values preserves the per-input slot vector shape")
      (is (= [privacy/redacted-sentinel] iv)
          "the sensitive runtime-db input value is redacted on :input-values
           (elided against the STRIPPED runtime declaration path, not the
           raw [:rf.db/runtime …] seed path) — the raw token never egresses"))))

(deftest runtime-db-qualified-input-value-is-elided-on-failed-trace
  ;; rf2-p44r3u — the SAME normalization must apply on the FAILURE path. A
  ;; flow reading a sensitive runtime-db-qualified input whose `:output`
  ;; THROWS must not leak the raw input value on the `:rf.flow/failed`
  ;; `:inputs` slot either (the trace bus is the wire boundary on both the
  ;; success and the failure paths — `elide-inputs` is shared).
  (testing "a sensitive runtime-db-qualified input value is elided (not raw)
            on the `:rf.flow/failed` `:inputs` slot"
    (reg-fw-runtime-handler! :seed-rt
      (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:token "RT-SECRET"}}}}))
    (marks/add-marks :rf/default {[:rf.runtime/routing :current :token] :sensitive})
    (rf/reg-flow {:id     :route-token-boom
                  :inputs [[:rf.db/runtime :rf.runtime/routing :current :token]]
                  :output (fn [_] (throw (ex-info "boom" {})))
                  :path   [:derived :route-token]})
    (reset! *captured* [])
    (rf/dispatch-sync [:seed-rt])
    (let [failures (by-op :rf.flow/failed)
          inputs   (-> failures last :tags :inputs)]
      (is (= 1 (count failures)) ":rf.flow/failed fired")
      (is (vector? inputs) ":inputs preserves the per-input slot vector shape")
      (is (= [privacy/redacted-sentinel] inputs)
          "the sensitive runtime-db input value is redacted on the failure
           path's :inputs slot — the raw token never egresses"))))

(deftest runtime-db-whole-value-effect-preserves-flow-elision-declarations
  ;; rf2-gom797 — a durable `:rf.db/runtime` whole-value effect must NOT clobber
  ;; the elision declaration registry that lives in the SAME runtime-db
  ;; partition at `[:rf.runtime/elision …]`. Pre-fix, `commit-frame-transition!`
  ;; installed the effect's whole-value runtime-db verbatim, dropping every
  ;; reserved `:rf.runtime/*` subsystem child (incl. `:rf.runtime/elision`) the
  ;; effect did not itself carry — so a framework-authority event that seeded
  ;; `:rf.runtime/routing` left the frame with NO flow-sourced elision
  ;; declarations after commit, even though they were correctly read pre-commit
  ;; (the existing `propagation-runtime-db-qualified-input` only asserts the
  ;; SAME-event redaction, which reads the pre-commit registry). This regression
  ;; pins POST-COMMIT declaration survival.
  (testing "a durable `:rf.db/runtime` whole-value commit preserves the
            flow-sourced elision declarations (the registry is a reserved
            `:rf.runtime/elision` sibling, not application runtime-db)"
    ;; Framework-authority handler that seeds the routing subsystem with a
    ;; WHOLE-VALUE runtime-db effect carrying ONLY :rf.runtime/routing — the
    ;; shape `partitioned_commit_test` / the bead repro use.
    (reg-fw-runtime-handler! :seed-rt
      (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:token "RT-SECRET"}}}}))
    (marks/add-marks :rf/default {[:rf.runtime/routing :current :token] :sensitive})
    (rf/reg-flow {:id     :route-token-echo
                  :inputs [[:rf.db/runtime :rf.runtime/routing :current :token]]
                  :output (fn [t] {:echo t})
                  :path   [:derived :route-token]})
    ;; Pre-dispatch: BOTH the directly-marked runtime input slot and the
    ;; propagated flow output declaration are present.
    (is (contains? (sensitive-decls :rf/default) [:rf.runtime/routing :current :token])
        "the directly-marked runtime-db input path is declared before dispatch")
    (is (contains? (sensitive-decls :rf/default) [:derived :route-token])
        "the propagated flow output declaration is present before dispatch")
    (reset! *captured* [])
    (rf/dispatch-sync [:seed-rt])
    ;; POST-COMMIT — the bug: a whole-value :rf.db/runtime commit must NOT have
    ;; wiped the elision registry. Both declarations must survive.
    (is (contains? (sensitive-decls :rf/default) [:rf.runtime/routing :current :token])
        "the directly-marked runtime-db input declaration SURVIVES the durable runtime-db commit")
    (is (contains? (sensitive-decls :rf/default) [:derived :route-token])
        "the propagated flow output declaration SURVIVES the durable runtime-db commit")
    ;; And the runtime-db seed actually committed (the effect's payload landed).
    (is (= "RT-SECRET"
           (get-in (frame/frame-runtime-db-value :rf/default)
                   [:rf.runtime/routing :current :token]))
        "the framework-authority runtime-db seed committed durably")
    ;; The elision sub-tree coexists in the SAME committed runtime-db partition.
    (is (some? (get (frame/frame-runtime-db-value :rf/default) :rf.runtime/elision))
        "the :rf.runtime/elision subsystem child coexists with :rf.runtime/routing post-commit")))

(deftest drain-time-propagated-mark-survives-same-event-runtime-effect
  ;; rf2-upx16k — the PRIVACY FAIL-OPEN the gom797 test above misses.
  ;;
  ;; gom797 (`runtime-db-whole-value-effect-preserves-flow-elision-declarations`)
  ;; marks the input BEFORE dispatch, so the propagated output declaration is
  ;; already in the LIVE registry — and, crucially, also in the chain-start
  ;; `runtime-before` snapshot the router captured by reference. Reconciling the
  ;; runtime effect against THAT snapshot still carries the declaration forward,
  ;; so the bug stays hidden.
  ;;
  ;; This test exercises the missed path: the propagated output mark
  ;; MATERIALISES ONLY AT THE DRAIN-TIME TOPO REFRESH (the input is marked AFTER
  ;; reg-flow, so it is absent at reg-flow time — the
  ;; `propagation-fires-when-mark-added-AFTER-reg-flow` scenario). The refresh
  ;; writes the new `[:derived]` declaration into the LIVE runtime-db
  ;; `[:rf.runtime/elision]` slot DURING the `:after` chain — AFTER the router
  ;; captured `runtime-before` (the pre-handler coeffect, injected by reference
  ;; at chain start, whose elision slot is PRE-refresh). When the SAME event's
  ;; handler ALSO returns a `:rf.db/runtime` effect SILENT about elision,
  ;; reconciling against the stale `runtime-before` carries the PRE-refresh
  ;; (mark-less) registry forward and the commit OVERWRITES the just-written
  ;; `[:derived]` declaration — so the flow-derived sensitive output egresses
  ;; RAW on the same event's db egress (and would stay raw until the next drain
  ;; re-propagates). The fix reconciles against the LIVE runtime-db read at
  ;; commit, so the freshly-propagated mark survives. Framework-authority
  ;; handlers return runtime effects routinely (routing / machines), so this is
  ;; not exotic.
  (testing "a flow output mark freshly propagated at the drain-time refresh
            SURVIVES a same-event `:rf.db/runtime` effect commit — the derived
            slot does NOT egress raw (privacy mark is not overwritten by the
            stale chain-start runtime-before snapshot)"
    ;; Framework-authority handler: seeds app-db (so the flow's input value is
    ;; present and the flow recomputes) AND returns a whole-value
    ;; `:rf.db/runtime` effect SILENT about elision (seeds only
    ;; :rf.runtime/routing — the routine framework-authority shape).
    (reg-fw-runtime-handler! :seed-both
      (fn [{:keys [db]} _]
        {:db (merge db {:secret-in "S"})
         :rf.db/runtime {:rf.runtime/routing {:current {:page :home}}}}))
    (rf/reg-flow {:id     :derive
                  :inputs [[:secret-in]]
                  :output (fn [s] {:copy s})
                  :path   [:derived]})
    ;; Mark the input AFTER reg-flow — so the propagated [:derived] output
    ;; declaration is ABSENT at reg-flow time and materialises ONLY at the
    ;; drain-time topo refresh (the chain-start runtime-before snapshot's
    ;; elision slot will NOT carry it).
    (marks/add-marks :rf/default {[:secret-in] :sensitive})
    (is (not (contains? (sensitive-decls :rf/default) [:derived]))
        "the propagated output mark is ABSENT before the drain (input marked after reg-flow)")
    (reset! *captured* [])
    (rf/dispatch-sync [:seed-both])
    ;; POST-COMMIT: the propagated declaration must survive the same-event
    ;; runtime-effect commit (this is the assertion that fails fail-open).
    (is (contains? (sensitive-decls :rf/default) [:derived])
        "the drain-propagated [:derived] declaration SURVIVES the same-event runtime-db commit")
    ;; The runtime effect actually committed (the routing seed landed) AND the
    ;; elision sub-tree coexists with it post-commit.
    (is (= :home
           (get-in (frame/frame-runtime-db-value :rf/default)
                   [:rf.runtime/routing :current :page]))
        "the framework-authority runtime-db effect committed durably")
    (is (some? (get (frame/frame-runtime-db-value :rf/default) :rf.runtime/elision))
        "the :rf.runtime/elision subsystem child coexists with :rf.runtime/routing post-commit")
    ;; THE LOAD-BEARING PRIVACY ASSERTION (the channel the bug leaks on):
    ;; egress the COMMITTED app-db through the wire-walker, which reads the
    ;; COMMITTED runtime-db `[:rf.runtime/elision]` registry (`registry-of` →
    ;; the live container). This is the db-diff / view / sub egress channel.
    ;; Pre-fix the commit overwrote the freshly-propagated [:derived] mark
    ;; (reconciled against the stale chain-start runtime-before snapshot), so
    ;; the derived slot egressed RAW here. Post-fix the mark survives the
    ;; commit, so the slot redacts. (The t2/computed-result trace assertions
    ;; below project DURING the chain against the post-refresh LIVE registry, so
    ;; they redact in BOTH the buggy and fixed cases — they do NOT distinguish
    ;; the bug; this committed-registry egress is the one that does.)
    (let [committed-db (frame/frame-app-db-value :rf/default)
          egressed     (binding [frame/*current-frame* :rf/default]
                         (elision/elide-wire-value committed-db {:frame :rf/default}))]
      (is (= privacy/redacted-sentinel (get-in egressed [:derived]))
          "the flow-derived sensitive output is REDACTED on egress against the
           COMMITTED elision registry — the freshly-propagated mark was not
           clobbered by the commit's stale runtime-before reconcile (privacy
           fail-open closed). This is the db-diff / view / sub channel the leak
           rode pre-fix.")
      (is (= "S" (get-in committed-db [:secret-in]))
          "the marked INPUT value did commit to app-db (sanity: the flow had a
           value to derive from)"))
    ;; The same-event t2 pending-db snapshot is also redacted (consistency —
    ;; this projects against the post-refresh live registry during the chain).
    (let [t2 (last (filterv #(= :rf.event/db-pending-post-flow (:operation %))
                            @*captured*))
          stamped (-> t2 :tags :rf.event/db)]
      (is (some? t2) "a post-flow pending-db (t2) trace fired")
      (is (= privacy/redacted-sentinel (get-in stamped [:derived]))
          "the flow-derived sensitive output is redacted on the same-event t2 snapshot"))
    ;; And the per-flow :rf.flow/computed :result is redacted too (the mark
    ;; reaches the flow trace, not just the db snapshot).
    (is (= privacy/redacted-sentinel (computed-result :derive))
        "the :rf.flow/computed :result is redacted (the propagated mark holds)")))

(deftest propagation-flow-dag-upstream-to-downstream
  (testing "flow→flow DAG propagation: flow B reading flow A's sensitive
            output :path inherits A's propagated mark. The topo-ordered drain
            refresh resolves A before B so B sees A's freshly-installed mark."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:raw "secret"})}))
    (marks/add-marks :rf/default {[:raw] :sensitive})
    ;; A: reads sensitive [:raw], writes [:step-a] (inherits sensitive).
    (rf/reg-flow {:id     :flow-a
                  :inputs [[:raw]]
                  :output (fn [r] (str r "-a"))
                  :path   [:step-a]})
    ;; B: reads A's output [:step-a], writes [:step-b] (must inherit too).
    (rf/reg-flow {:id     :flow-b
                  :inputs [[:step-a]]
                  :output (fn [a] (str a "-b"))
                  :path   [:step-b]})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (is (contains? (sensitive-decls :rf/default) [:step-a])
        "upstream flow A's output inherits the sensitive mark")
    (is (contains? (sensitive-decls :rf/default) [:step-b])
        "downstream flow B inherits transitively through A's output")
    (is (= privacy/redacted-sentinel (computed-result :flow-b))
        "B's output is redacted — taint flows through the flow DAG")))

(deftest propagation-t2-pending-post-flow-redacts-output
  (testing "Spec 015:568 conformance shape — a flow whose :inputs include a
            sensitive app-db path produces a flow :path write that is marked
            sensitive in the SAME event's t2 `:rf.event/db-pending-post-flow`
            snapshot. Flows transform the pending :db before the single
            deferred install, so the output rides the t2 snapshot of that one
            event (the trace-facing assertion the bead requires)."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:user {:ssn "123-45-6789" :name "Ada"}})}))
    (marks/add-marks :rf/default {[:user :ssn] :sensitive})
    (rf/reg-flow {:id     :ssn-echo
                  :inputs [[:user :ssn]]
                  :output (fn [ssn] {:copy ssn})
                  :path   [:derived :ssn-copy]})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [t2 (last (filterv #(= :rf.event/db-pending-post-flow (:operation %))
                            @*captured*))
          stamped (-> t2 :tags :rf.event/db)]
      (is (some? t2) "a post-flow pending-db (t2) trace fired")
      (is (= privacy/redacted-sentinel (get-in stamped [:derived :ssn-copy]))
          "the flow output written from a sensitive input is redacted on the t2 snapshot")
      ;; The unmarked sibling rides raw (the redaction is path-precise).
      (is (= "Ada" (get-in stamped [:user :name]))
          "an unmarked sibling app-db slot rides raw on the same t2 snapshot"))))

(deftest propagation-clears-when-input-mark-removed
  (testing "removing the input's sensitive mark (set-marks replacing it away)
            DROPS the propagated output mark on the next drain — propagation is
            re-resolved each drain, not latched"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:in "x"})}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :in str "!")}))
    (marks/add-marks :rf/default {[:in] :sensitive})
    (rf/reg-flow {:id     :echo
                  :inputs [[:in]]
                  :output (fn [v] (str v "-out"))
                  :path   [:echoed]})
    (rf/dispatch-sync [:init])
    (is (contains? (sensitive-decls :rf/default) [:echoed])
        "propagated while the input is marked")
    ;; Replace the mark-set with an empty one — clears the input mark.
    (marks/set-marks :rf/default {})
    (reset! *captured* [])
    (rf/dispatch-sync [:bump])        ; input value changes → flow recomputes + refresh runs
    (is (not (contains? (sensitive-decls :rf/default) [:echoed]))
        "the propagated output mark is dropped once the input is no longer marked")
    (is (= "x!-out" (computed-result :echo))
        "the output rides raw after declassification of the input")))

;; ---------------------------------------------------------------------------
;; 8. Malformed classification metadata is rejected FAIL-CLOSED (rf2-cgk0wb)
;;
;; Pre-fix, the OPTIONAL output data-classification keys fail-OPEN:
;;   - `explicit-flow-output-mark-paths` silently `(filter vector?)`-dropped
;;     malformed `:sensitive` / `:large` entries (`:sensitive [:token]`,
;;     `:large "blob"`), and
;;   - only a LITERAL `true` was honoured for `:sensitive?` / `:large?`, so a
;;     present-but-malformed `:sensitive? :yes` / `:large? 1` behaved as ABSENT.
;; A privacy/size declaration typo therefore registered with a normal return
;; value and NO diagnostic, while the intended redaction NEVER happened — the
;; worst failure mode for a safety feature. `validate-flow` now rejects each
;; malformation at the API boundary with the `:rf.error/flow-bad-marks`
;; discriminator BEFORE any registry / app-db / elision-declaration state
;; mutates. These tests pin the rejection id + the canonical thrown-error
;; shape, and prove NO flow row and NO elision declaration is installed.
;; ---------------------------------------------------------------------------

(defn- flow-row?
  "True iff `flow-id` has a registry row on `:rf/default` after a (rejected)
  registration attempt."
  [flow-id]
  (contains? (get (flows/flows-snapshot) :rf/default) flow-id))

(defn- no-decls?
  "True iff neither elision sub-map carries any entry on `:rf/default`."
  []
  (and (empty? (sensitive-decls :rf/default))
       (empty? (large-decls :rf/default))))

(deftest reg-flow-rejects-malformed-sensitive-vector
  (testing ":sensitive [:token] (bare keywords, not vector-of-subpaths) is rejected"
    (let [ex   (try (rf/reg-flow {:id        :bad/sens
                                  :inputs    [[:n]]
                                  :output    identity
                                  :path      [:out]
                                  :sensitive [:token]})    ; should be [[:token]]
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-marks (:rf.error/id data))
          ":rf.error/id carries the bad-marks discriminator")
      ;; rf2-vvixub — message is the human :reason sentence + the trailing
      ;; [:rf.error/<id>] token; assert the token substring, not equality.
      (is (re-find #"\[:rf\.error/flow-bad-marks\]" (ex-message ex))
          "message carries the [:rf.error/flow-bad-marks] token")
      (is (= 'rf/reg-flow (:where data))         ":where names the user-facing surface")
      (is (= :fix-registration (:recovery data))  ":recovery names the disposition")
      (is (= :sensitive (:bad-key data))          ":bad-key names the offending classification key")
      (is (= [:token] (:bad-entries data))        ":bad-entries names the malformed entry")
      (is (not (flow-row? :bad/sens))  "no flow row installed for a rejected registration")
      (is (no-decls?)                  "no elision declaration installed")))
  (testing ":sensitive \"blob\" (non-vector whole value) is rejected with :bad-value"
    (let [ex   (try (rf/reg-flow {:id        :bad/sens2
                                  :inputs    [[:n]]
                                  :output    identity
                                  :path      [:out]
                                  :sensitive "blob"})
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (= :rf.error/flow-bad-marks (:rf.error/id data)))
      (is (= :sensitive (:bad-key data)))
      (is (= "blob" (:bad-value data)) ":bad-value names the non-vector value")
      (is (not (flow-row? :bad/sens2)))
      (is (no-decls?)))))

(deftest reg-flow-rejects-malformed-large-vector
  (testing ":large \"blob\" (a string, not a vector) is rejected"
    (let [ex   (try (rf/reg-flow {:id     :bad/large
                                  :inputs [[:n]]
                                  :output identity
                                  :path   [:out]
                                  :large  "blob"})
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-marks (:rf.error/id data))
          ":rf.error/id carries the bad-marks discriminator")
      (is (= :large (:bad-key data))   ":bad-key names :large")
      (is (= "blob" (:bad-value data)) ":bad-value names the non-vector value")
      (is (not (flow-row? :bad/large)) "no flow row installed")
      (is (no-decls?)                  "no elision declaration installed")))
  (testing ":large [42] (a scalar entry, not a subpath vector) is rejected"
    (let [ex   (try (rf/reg-flow {:id     :bad/large2
                                  :inputs [[:n]]
                                  :output identity
                                  :path   [:out]
                                  :large  [42]})
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (= :rf.error/flow-bad-marks (:rf.error/id data)))
      (is (= :large (:bad-key data)))
      (is (= [42] (:bad-entries data)) ":bad-entries names the malformed entry")
      (is (not (flow-row? :bad/large2)))
      (is (no-decls?)))))

(deftest reg-flow-rejects-malformed-sensitive?-boolean
  (testing ":sensitive? :yes (not a boolean) is rejected"
    (let [ex   (try (rf/reg-flow {:id         :bad/sensq
                                  :inputs     [[:n]]
                                  :output     identity
                                  :path       [:out]
                                  :sensitive? :yes})
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-marks (:rf.error/id data))
          ":rf.error/id carries the bad-marks discriminator")
      (is (= :sensitive? (:bad-key data)) ":bad-key names :sensitive?")
      (is (= :yes (:bad-value data))      ":bad-value names the non-boolean value")
      (is (not (flow-row? :bad/sensq))    "no flow row installed")
      (is (no-decls?)                     "no elision declaration installed")))
  (testing ":sensitive? nil (present-but-nil) is rejected (not a literal false opt-out)"
    (let [ex (try (rf/reg-flow {:id         :bad/sensq-nil
                                :inputs     [[:n]]
                                :output     identity
                                :path       [:out]
                                :sensitive? nil})
                  (catch Throwable t t))]
      (is (= :rf.error/flow-bad-marks (:rf.error/id (ex-data ex)))
          "a present nil is rejected — only literal true/false are valid")
      (is (not (flow-row? :bad/sensq-nil))))))

(deftest reg-flow-rejects-malformed-large?-boolean
  (testing ":large? 1 (not a boolean) is rejected"
    (let [ex   (try (rf/reg-flow {:id     :bad/largeq
                                  :inputs [[:n]]
                                  :output identity
                                  :path   [:out]
                                  :large? 1})
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-marks (:rf.error/id data))
          ":rf.error/id carries the bad-marks discriminator")
      (is (= :large? (:bad-key data)) ":bad-key names :large?")
      (is (= 1 (:bad-value data))     ":bad-value names the non-boolean value")
      (is (not (flow-row? :bad/largeq)) "no flow row installed")
      (is (no-decls?)                   "no elision declaration installed"))))

(deftest reg-flow-accepts-empty-subpath-whole-output-mark
  (testing "the `[[]]` whole-output convention is VALID (a regression guard so
            the tightened validator does not reject the legitimate whole-value
            mark — [] is the one empty subpath that is legal)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1})}))
    (is (= :ok/whole
           (rf/reg-flow {:id        :ok/whole
                         :inputs    [[:n]]
                         :output    (fn [_] {:v 1})
                         :path      [:derived :whole]
                         :sensitive [[]]}))
        "a `:sensitive [[]]` whole-output mark registers cleanly")
    (is (contains? (sensitive-decls :rf/default) [:derived :whole])
        "the whole-output declaration is installed at :path itself")))

(deftest fx-reg-flow-rejects-malformed-marks-no-state-installed
  ;; The runtime `:rf.fx/reg-flow` effect routes through the SAME
  ;; `registry/reg-flow` (via `:flows/reg-flow`), so `validate-flow` — the
  ;; first call in `reg-flow`, before `frame-id` / the `swap!` — rejects a
  ;; malformed-marks registration on the fx path too, BEFORE any flow row or
  ;; elision declaration is installed. Whether the throw escapes the drain or
  ;; is routed to the framework error handler, the post-condition is the same:
  ;; the registry and the elision registry stay clean.
  (testing ":rf.fx/reg-flow with malformed :sensitive installs no flow row / decl"
    (rf/reg-event :enter-bad-sens
      (fn [_ _]
        {:fx [[:rf.fx/reg-flow {:id        :fx/bad-sens
                                :inputs    [[:n]]
                                :output    identity
                                :path      [:out]
                                :sensitive [:token]}]]}))   ; malformed
    (try (rf/dispatch-sync [:enter-bad-sens]) (catch Throwable _ nil))
    (is (not (flow-row? :fx/bad-sens))
        "the malformed fx registration installed no flow row")
    (is (no-decls?)
        "the malformed fx registration installed no elision declaration"))
  (testing ":rf.fx/reg-flow with malformed :sensitive? installs no flow row / decl"
    (rf/reg-event :enter-bad-sensq
      (fn [_ _]
        {:fx [[:rf.fx/reg-flow {:id         :fx/bad-sensq
                                :inputs     [[:n]]
                                :output     identity
                                :path       [:out]
                                :sensitive? :yes}]]}))      ; malformed
    (try (rf/dispatch-sync [:enter-bad-sensq]) (catch Throwable _ nil))
    (is (not (flow-row? :fx/bad-sensq))
        "the malformed fx registration installed no flow row")
    (is (no-decls?)
        "the malformed fx registration installed no elision declaration")))
