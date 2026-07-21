(ns re-frame2-pair-mcp.view-tool-test
  "Unit tests for the five re-frame.ui.tool projection reads (rf2-vxgfnd.95.8):
  read-view-manifest / read-view-dependencies / read-view-event-sites /
  read-mounted-views / explain-render.

  Two layers (mirroring read-ui-test):

    1. Form composition — each tool ships ONE exists?-guarded self-describing
       form calling `re-frame.ui.tool/<projection>`. We check the pure
       `projection-form` builder and, via a capturing eval stub, that the tool
       emits an exists? guard, embeds the (optional) view-id, is READ-ONLY, and
       does NOT route through a `re-frame2-pair.runtime` wrapper (the deliberate
       divergence — the tier is the OPTIONAL day8/re-frame2-ui substrate, absent
       in a plain-Reagent app, so it must not be hard-required in the preload).

    2. Tool wiring — the missing-view-id short-circuit (no nREPL round-trip) and
       the map-envelope-result passthrough of the guarded form's envelope.

  The browser-side semantics (does the tier actually resolve? does a projection
  return?) run in a real tab — the live conformance corpus covers the wire shape
  against canned envelopes; this suite pins the tool's outer contract."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.view-tool :as view-tool]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-form!
  [seen canned body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build form] (reset! seen form) (js/Promise.resolve canned))
               ([_conn _build form _opts] (reset! seen form) (js/Promise.resolve canned)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

;; ---------------------------------------------------------------------------
;; projection-form — the pure guarded-form builder
;; ---------------------------------------------------------------------------

(deftest projection-form-guards-the-tier-and-embeds-the-view-id
  (let [form (view-tool/projection-form "view-manifest" [":my.app/counter"]
                                        :view-not-available :my.app/counter)]
    (is (str/includes? form "cljs.core/exists? re-frame.ui.tool/view-manifest")
        "the projection is called only when the tier is present")
    (is (str/includes? form "(re-frame.ui.tool/view-manifest :my.app/counter)")
        "the view-id rides into the projection call")
    (is (str/includes? form ":view-tier-unavailable")
        "an absent tier degrades honestly, not a fabricated emptiness")
    (is (str/includes? form ":reason :view-not-available")
        "a nil manifest is the unregistered-view reason")
    (is (str/includes? form ":view-id :my.app/counter")
        "the nil envelope echoes the view-id")
    (is (str/includes? form "(cljs.core/assoc p :ok? true)")
        "a present projection is forwarded verbatim, stamped :ok? true")
    (is (str/includes? form "catch :default e")
        "a throwing projection degrades to :view-tier-error")))

(deftest projection-form-no-arg-and-inactive-reason
  (let [form (view-tool/projection-form "mounted-views" [] :view-tier-inactive nil)]
    (is (str/includes? form "(re-frame.ui.tool/mounted-views)")
        "a no-arg projection takes no arguments")
    (is (str/includes? form ":reason :view-tier-inactive")
        "a nil no-arg projection means a production-inactive tier")
    (is (not (str/includes? form ":view-id"))
        "no view-id is echoed for the no-arg read")))

(deftest projection-form-is-read-only
  (doseq [form [(view-tool/projection-form "view-manifest" [":v"] :view-not-available :v)
                (view-tool/projection-form "explain-render" [] :view-tier-inactive nil)]]
    (doseq [mutator [".setAttribute" ".dispatchEvent" "set! (.-" ".innerHTML"
                     "reset!" "swap!" "dispatch" "app-db-reset"]]
      (is (not (str/includes? form mutator))
          (str "the projection form must be a pure read — found " mutator)))))

;; ---------------------------------------------------------------------------
;; Tool wiring — form composition through the real tool fns
;; ---------------------------------------------------------------------------

(deftest view-id-tools-emit-a-guarded-projection-call
  (async done
    (let [seen (atom nil)]
      (-> (with-captured-form! seen {:ok? true :rf.ui.tool/version 3}
            (fn []
              (view-tool/read-view-manifest-tool (fresh-conn)
                                                 #js {:view-id ":my.app/counter"})))
          (.then (fn [_]
                   (let [form @seen]
                     (is (str/includes? form "re-frame.ui.tool/view-manifest")
                         "calls the framework projection directly (no runtime wrapper)")
                     (is (not (str/includes? form "re-frame2-pair.runtime/"))
                         "does NOT route through the preload runtime — the tier is optional")
                     (is (str/includes? form "cljs.core/exists?") "guards the tier presence")
                     (is (str/includes? form ":my.app/counter")
                         "the coerced keyword view-id is embedded"))
                   (done)))))))

(deftest explain-render-omits-the-view-id-when-absent
  (async done
    (let [seen (atom nil)]
      (-> (with-captured-form! seen {:ok? true :rf.ui.tool/version 3 :occurrences []}
            (fn []
              (view-tool/explain-render-tool (fresh-conn) #js {})))
          (.then (fn [_]
                   (let [form @seen]
                     (is (str/includes? form "(re-frame.ui.tool/explain-render)")
                         "with no view-id the 0-arity projection is called")
                     (is (not (str/includes? form ":view-id"))
                         "no view-id is fabricated into the form"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Version gate — the boundary is REAL, not nominal (rf2-vxgfnd.98.1.1)
;; ---------------------------------------------------------------------------

(deftest a-version-matching-projection-is-forwarded-as-success
  ;; The ordinary version-`consumed-schema-version` read is unchanged: the
  ;; envelope passes the gate and rides through as a successful (non-error) read.
  (async done
    (let [seen (atom nil)]
      (-> (with-captured-form! seen {:ok? true
                                     :rf.ui.tool/version view-tool/consumed-schema-version
                                     :views []}
            (fn []
              (view-tool/read-mounted-views-tool (fresh-conn) #js {})))
          (.then (fn [result]
                   (is (false? (tu/error? result))
                       "a version-matched projection is a successful read")
                   (let [edn (tu/extract-edn result)]
                     (is (true? (:ok? edn)))
                     (is (= [] (:views edn))
                         "a genuinely-empty version-matched read is NOT mislabelled as an error"))
                   (done)))))))

(deftest a-producer-version-this-build-does-not-understand-is-a-typed-mismatch
  ;; rf2-vxgfnd.98.1.1 — Pair connects to an ARBITRARY running app, so it cannot
  ;; trust the producer's own stamp to define support. A `:ok? true` projection
  ;; stamped a `:rf.ui.tool/version` this build was NOT written against is a typed
  ;; `:ok? false :view-tier-version-mismatch` (isError), never forwarded as
  ;; success. RED before the fix, where every stamped envelope rode through
  ;; `map-envelope-result` as `:ok? true` with no supported-version check.
  (async done
    (let [seen           (atom nil)
          producer-ahead (+ 100 view-tool/consumed-schema-version)] ; unmistakably foreign
      (-> (with-captured-form! seen {:ok? true :rf.ui.tool/version producer-ahead :views []}
            (fn []
              (view-tool/read-mounted-views-tool (fresh-conn) #js {})))
          (.then (fn [result]
                   (is (true? (tu/error? result))
                       "a producer version this build does not understand is an isError")
                   (let [edn (tu/extract-edn result)]
                     (is (= false (:ok? edn)))
                     (is (= :view-tier-version-mismatch (:reason edn)))
                     (is (= producer-ahead (:actual edn)) "the producer's stamp is reported")
                     (is (= view-tool/consumed-schema-version (:expected edn))
                         "…against the consumer-owned expected version")
                     (is (string? (:hint edn)) "carries an alignment hint"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Tool wiring — the missing-view-id short-circuit
;; ---------------------------------------------------------------------------

(deftest view-id-tools-short-circuit-on-missing-view-id
  (async done
    (let [seen (atom :never-called)]
      (-> (with-captured-form! seen {:ok? true}
            (fn []
              (view-tool/read-view-dependencies-tool (fresh-conn) #js {})))
          (.then (fn [result]
                   (is (true? (tu/error? result))
                       "a missing view-id is an isError envelope")
                   (let [edn (tu/extract-edn result)]
                     (is (= :missing-view-id (:reason edn)))
                     (is (string? (:hint edn)) "carries a recovery hint"))
                   (is (= :never-called @seen)
                       "no nREPL round-trip — short-circuits before eval")
                   (done)))))))
