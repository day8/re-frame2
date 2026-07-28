(ns re-frame.fx-args-trace-egress-cljs-test
  "rf2-6h3c02 — an fx registration's `:sensitive` classification must reach
  EVERY trace slot that carries the fx's args, not only the per-effect
  `:rf.fx/handled` slot.

  Before this fix `re-frame.classification/project-trace-event` applied the fx
  registration's `:sensitive` only inside `project-fx-tags`, gated on op
  `:rf.fx/handled`. Two OTHER slots carried the SAME fx args RAW, unreachable by
  any app-side classification:

    1. `:rf.event/fx` on the `:rf.fx/do-fx` trace — the handler's WHOLE returned
       effect vector, stamped raw. (The projector walked the sibling
       `:rf.event/db` slot but not this one.)
    2. `:rf.fx/args` on the fx error traces (`:rf.error/fx-handler-exception` +
       siblings). The more serious of the two: those CATEGORIES are promoted
       onto the always-on axis, so the failure itself reaches production
       observability.

  This suite is the adversarial regression: it drives a `reg-fx` declaring
  `{:sensitive [[:token]]}` through a SUCCESS arm and an ERROR arm and pins the
  sentinel token absent from every fx-arg-bearing slot, PLUS a non-sensitive
  control fx that must ride RAW (no over-redaction). Each sensitive assertion
  FAILS before the fix and PASSES after.

  Dual-runtime `.cljc` (`*-cljs-test` ns): the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up —
  traces fire in both runtimes (`goog.DEBUG` / JVM `debug-enabled?` default on).

  ## Posture split (rf2-d2841)

  A CORRECTION TO THIS FILE'S OWN PREMISE FIRST. The `.2` bullet above used to
  read \"that trace is production-survivable — it fans out through the always-on
  error-emit listener, not just the dev trace\", and used that to call the error
  arm the more serious of the two slots. The CATEGORY is promoted; the SLOT is
  not. `fx.cljc`'s `:rf.error/no-such-fx` site says so in as many words —
  \"the tight-record discipline is intact: `:rf.fx/args` stays on the dev trace
  and does NOT reach the production record\" — and `error-emit/emit-error-both!`
  lifts only `:failing-id` / `:reason` out of the trace tags onto the always-on
  record. So `:rf.fx/args` is a DEV-TRACE slot on both arms, and there is no
  `:errors`-stream re-aim available for it the way there was for `fx-test`'s
  `:reason` (rf2-d2841 pass 2). Checked against the source, not the story.

  Consequently both live arms read a channel that emits nothing under
  `-Dre-frame.debug=false`, and both are kept VERBATIM — sweeps included —
  inside `(when interop/debug-enabled? …)` arms. The whole-stream sweeps are
  the reason the arm is drawn around the WHOLE body rather than around the
  failing rows: `(is (not (contains-sentinel? v)))` over an empty stream is a
  redaction suite certifying itself green having emitted nothing.

  What stays ALWAYS-ON is what makes the guarded rows mean something:

    * the classified fx BODIES receive the RAW token — redaction is
      egress-only, and a suite that proved absence without proving the secret
      was ever in flight would be pinning nothing;
    * the registration owns its `[:token]` declaration (`:sensitive` is
      load-bearing metadata, NOT pure documentation, so it survives the strip);
    * `classification/project-trace-event` — the rf2-6h3c02 chokepoint itself —
      redacts both fx-arg-bearing slot shapes and leaves the control fx raw,
      driven deterministically on hand-built shapes. That is the same
      \"projector teeth\" pattern `fx-aggregate-classification-cljs-test` and
      `fx-redirect-classification-cljs-test` use for their section A, and it is
      what this file had NO always-on counterpart to before."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as classification]
            [re-frame.interop :as interop]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; A UNIQUE sentinel — appears nowhere else, so a whole-stream scan that finds
;; it can only be hitting THIS fx's token arg.
(def ^:private sentinel "FX-ARGS-EGRESS-SENTINEL-6h3c02")

(def ^:private frame-id :fx-args-egress/frame)

;; What the fx BODIES actually received. Redaction is EGRESS-ONLY, so these are
;; the always-on control: the token must be in flight for its absence from the
;; trace slots to mean anything (rf2-d2841).
(def ^:private body-args (atom {}))

(defn- register! []
  (reset! body-args {})
  (rf/make-frame {:id frame-id})
  ;; A CLASSIFIED fx — its own registration declares [:token] sensitive.
  (rf/reg-fx :fx-args/store {:sensitive [[:token]]}
    (fn [_ args] (swap! body-args assoc :fx-args/store args) nil))
  ;; A CLASSIFIED fx that THROWS — to exercise the error-arm :rf.fx/args slot
  ;; carried by :rf.error/fx-handler-exception.
  (rf/reg-fx :fx-args/store-throwing {:sensitive [[:token]]}
    (fn [_ args]
      (swap! body-args assoc :fx-args/store-throwing args)
      (throw (ex-info "fx blew up" {}))))
  ;; A NON-sensitive control fx — its args must ride RAW (guard against
  ;; over-redaction of unclassified fx).
  (rf/reg-fx :fx-args/audit
    (fn [_ args] (swap! body-args assoc :fx-args/audit args) nil))
  ;; SUCCESS event: returns both the classified store fx and the control fx.
  (rf/reg-event :fx-args/succeed
    (fn [_ _]
      {:fx [[:fx-args/store {:token sentinel}]
            [:fx-args/audit {:msg "a benign audit line"}]]}))
  ;; ERROR event: returns the throwing classified fx.
  (rf/reg-event :fx-args/fail
    (fn [_ _]
      {:fx [[:fx-args/store-throwing {:token sentinel}]]})))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- contains-sentinel?
  "True when the sentinel string appears ANYWHERE in a nested data structure —
  the recursive scan an off-box shipper / dev tool applies."
  [x]
  (cond
    (string? x) #?(:clj (.contains ^String x sentinel)
                   :cljs (not= -1 (.indexOf x sentinel)))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

;; ---------------------------------------------------------------------------
;; SUCCESS arm — :rf.event/fx (on :rf.fx/do-fx) + :rf.fx/handled both redact;
;; the control fx rides raw.
;; ---------------------------------------------------------------------------

(deftest success-arm-bodies-receive-raw-args
  (testing "ALWAYS-ON control (rf2-d2841): redaction is EGRESS-ONLY, so both the
            classified fx and the control fx receive their args RAW — the token
            IS in flight, which is what makes its absence from the trace slots
            below a fact rather than a vacuum"
    (register!)
    (rf/dispatch-sync [:fx-args/succeed] {:frame frame-id})
    (is (= {:token sentinel} (:fx-args/store @body-args))
        "the classified fx body received the RAW token")
    (is (= {:msg "a benign audit line"} (:fx-args/audit @body-args))
        "the control fx body received its args unchanged")))

(deftest success-arm-redacts-classified-fx-args-in-every-slot
 ;; rf2-d2841 — every row below reads the DEV TRACE stream. Under
 ;; -Dre-frame.debug=false nothing is emitted, and the whole-stream sweep at
 ;; the end would then certify "no leak" over an empty stream. Kept verbatim.
 (when interop/debug-enabled?
  (testing "a classified fx's token is redacted in BOTH the :rf.event/fx
            aggregate (on :rf.fx/do-fx) AND the per-effect :rf.fx/handled slot,
            while the non-sensitive control fx rides raw"
    (register!)
    (let [acc (collect-traces! ::success)]
      (rf/dispatch-sync [:fx-args/succeed] {:frame frame-id})
      (rf/unregister-listener! :trace ::success)

      ;; --- :rf.event/fx on :rf.fx/do-fx: the classified entry redacts ---
      (let [do-fx (->> @acc (filterv #(= :rf.fx/do-fx (:operation %))))]
        (is (seq do-fx) ":rf.fx/do-fx was emitted with the effect vector")
        (doseq [ev do-fx]
          (let [fx-vec (get-in ev [:tags :rf.event/fx])
                store  (first (filter #(= :fx-args/store (first %)) fx-vec))
                audit  (first (filter #(= :fx-args/audit (first %)) fx-vec))]
            (is (= privacy/redacted-sentinel (get-in store [1 :token]))
                "the classified fx's :token reads :rf/redacted in :rf.event/fx")
            (is (= :fx-args/store (first store))
                "shape retained — the fx-id survives")
            (is (= {:msg "a benign audit line"} (second audit))
                "the NON-sensitive control fx rides RAW (no over-redaction)"))))

      ;; --- per-effect :rf.fx/handled: unchanged redaction (control) ---
      (let [handled (->> @acc
                         (filterv #(= :fx-args/store (get-in % [:tags :rf.fx/id]))))]
        (is (seq handled) "the classified fx emitted a :rf.fx/handled trace")
        (doseq [ev handled]
          (is (= privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
              "the :token arg reads :rf/redacted in :rf.fx/handled (unchanged)")))

      (let [audit-handled (->> @acc
                               (filterv #(= :fx-args/audit (get-in % [:tags :rf.fx/id]))))]
        (is (seq audit-handled) "the control fx also emitted a :rf.fx/handled trace")
        (doseq [ev audit-handled]
          (is (= {:msg "a benign audit line"} (get-in ev [:tags :rf.fx/args]))
              "the control fx's args ride RAW in :rf.fx/handled (no over-redaction)")))

      ;; --- whole-stream sweep: the sentinel appears in NO trace tag ---
      (let [checked (atom 0)]
        (doseq [ev @acc
                [_ v] (:tags ev)]
          (swap! checked inc)
          (is (not (contains-sentinel? v))
              (str "the token must not appear raw in " (:operation ev))))
        (is (pos? @checked) "the sweep actually inspected trace tags"))))))

;; ---------------------------------------------------------------------------
;; ERROR arm — :rf.error/fx-handler-exception carries :rf.fx/args; it must
;; redact the classified token. The CATEGORY is always-on; the `:rf.fx/args`
;; SLOT rides the dev trace only (see the ns docstring's correction).
;; ---------------------------------------------------------------------------

(deftest error-arm-body-receives-raw-args
  (testing "ALWAYS-ON control (rf2-d2841): the throwing classified fx also
            receives its args RAW before it throws"
    (register!)
    (rf/dispatch-sync [:fx-args/fail] {:frame frame-id})
    (is (= {:token sentinel} (:fx-args/store-throwing @body-args))
        "the throwing fx body received the RAW token")))

(deftest error-arm-redacts-fx-args-on-fx-handler-exception
 ;; rf2-d2841 — dev-trace stream; the trailing `contains-sentinel?` negative
 ;; would pass over an empty stream. Kept verbatim inside the arm.
 (when interop/debug-enabled?
  (testing "when a classified fx throws, :rf.error/fx-handler-exception redacts
            its :rf.fx/args :token"
    (register!)
    (let [acc (collect-traces! ::error)]
      (rf/dispatch-sync [:fx-args/fail] {:frame frame-id})
      (rf/unregister-listener! :trace ::error)

      (let [errs (->> @acc
                      (filterv #(= :rf.error/fx-handler-exception (:operation %))))]
        (is (seq errs)
            "the throwing classified fx emitted an :rf.error/fx-handler-exception trace")
        (doseq [ev errs]
          (is (= privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
              "the :rf.fx/args :token reads :rf/redacted on the error trace")
          (is (= :fx-args/store-throwing (get-in ev [:tags :rf.fx/id]))
              "shape retained — the fx-id survives")
          (is (not (contains-sentinel? (:tags ev)))
              "the token appears nowhere raw in the error trace tags")))))))

;; ---------------------------------------------------------------------------
;; The registration owns its classification (what the projector consumes).
;; ---------------------------------------------------------------------------

(deftest classified-fx-registration-declares-its-path
  (testing "the fx registration owns the [:token] sensitive path the projector
            reads at egress"
    (register!)
    (is (= {:sensitive [[:token]]}
           (classification/registration-classification :fx :fx-args/store))
        ":fx-args/store owns [:token]")
    (is (nil? (classification/registration-classification :fx :fx-args/audit))
        "the control fx declares nothing — precision, not blanket redaction")))

;; ---------------------------------------------------------------------------
;; ALWAYS-ON projector teeth (rf2-d2841) — the rf2-6h3c02 chokepoint itself,
;; driven deterministically on the two fx-arg-bearing slot SHAPES rather than
;; through the dev trace stream. Runs in BOTH postures, so the regression this
;; file exists for is pinned under `scripts/test-core-prod-gate.sh` too.
;;
;; Same pattern as `fx-aggregate-classification-cljs-test` §A and
;; `fx-redirect-classification-cljs-test` §A; this file previously had no
;; always-on counterpart at all.
;; ---------------------------------------------------------------------------

(defn- project [ev] (:tags (classification/project-trace-event ev)))

(deftest projector-redacts-the-do-fx-aggregate-slot
  (testing "the :rf.event/fx aggregate on :rf.fx/do-fx — the slot rf2-6h3c02
            added to the walk — redacts the classified entry's declared path
            while the control entry rides raw"
    (register!)
    (let [t      (project {:operation :rf.fx/do-fx
                           :tags {:frame       frame-id
                                  :rf.event/fx [[:fx-args/store {:token sentinel}]
                                                [:fx-args/audit {:msg "a benign audit line"}]]}})
          fx-vec (:rf.event/fx t)
          store  (first (filter #(= :fx-args/store (first %)) fx-vec))
          audit  (first (filter #(= :fx-args/audit (first %)) fx-vec))]
      (is (= privacy/redacted-sentinel (get-in store [1 :token]))
          "the classified fx's :token reads :rf/redacted in :rf.event/fx")
      (is (= :fx-args/store (first store)) "shape retained — the fx-id survives")
      (is (= {:msg "a benign audit line"} (second audit))
          "the NON-sensitive control fx rides RAW (no over-redaction)")
      (is (not (contains-sentinel? t))
          "the token appears nowhere in the projected aggregate"))))

(deftest projector-redacts-the-per-effect-args-slot-on-every-op
  (testing "the [:rf.fx/id :rf.fx/args] pair redacts on the success op AND on
            the fx error ops that carry the same pair"
    (register!)
    (doseq [op [:rf.fx/handled :rf.error/fx-handler-exception
                :rf.fx/skipped-on-platform]]
      (let [t (project {:operation op
                        :tags {:frame      frame-id
                               :rf.fx/id   :fx-args/store
                               :rf.fx/args {:token sentinel :note "plain"}}})]
        (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args :token]))
            (str op " redacts the declared :token path"))
        (is (= "plain" (get-in t [:rf.fx/args :note]))
            (str op " keeps the non-secret sibling (path-precise)"))
        (is (= :fx-args/store (:rf.fx/id t)) "shape retained — the fx-id survives")
        (is (not (contains-sentinel? t)) (str op " leaks no token")))))
  (testing "precision: the unclassified control fx's args ride raw on the same
            slot shape (the documented fail-open)"
    (register!)
    (let [t (project {:operation :rf.fx/handled
                      :tags {:frame      frame-id
                             :rf.fx/id   :fx-args/audit
                             :rf.fx/args {:token sentinel}}})]
      (is (= sentinel (get-in t [:rf.fx/args :token]))
          "no declaration, no redaction — precision over blanket scrubbing"))))
