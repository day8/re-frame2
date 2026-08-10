(ns re-frame2-pair-mcp.hicasso-tool-test
  "Unit tests for the three re-frame.hicasso.tool reads:
  read-mounted-boundaries / read-read-attribution / explain-render.

  Two layers (mirroring read-ui-test):

    1. Form composition — each tool ships ONE exists?-guarded self-describing
       form calling `re-frame.hicasso.tool/<read>`. We check the pure
       `projection-form` builder and, via a capturing eval stub, that the tool
       emits an exists? guard, is READ-ONLY, and does NOT route through a
       `re-frame2-pair.runtime` wrapper (the deliberate divergence — the door
       is optional and absent in a Reagent/UIx app, so it must not be
       hard-required in the preload).

    2. Tool wiring — the schema gate, and the map-envelope-result passthrough
       of the guarded form's envelope.

  What this suite deliberately does NOT prove is that the provider actually
  publishes the reads these forms name — that is
  [[re-frame2-pair-mcp.hicasso-wire-test]], and it is a separate file because
  it asserts against a DIFFERENT artefact's source. A suite that only exercised
  the emitter cannot see the failure this coupling is prone to."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.hicasso-tool :as hicasso-tool]))

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

(deftest projection-form-guards-the-door-and-calls-it-nullary
  (let [form (hicasso-tool/projection-form "read-mounted-boundaries")]
    (is (str/includes? form "cljs.core/exists? re-frame.hicasso.tool/read-mounted-boundaries")
        "the read is called only when the door is present")
    (is (str/includes? form "(re-frame.hicasso.tool/read-mounted-boundaries)")
        "the read is nullary — this door has no id to narrow by")
    (is (str/includes? form ":evidence-tier-unavailable")
        "an absent door degrades honestly, not a fabricated emptiness")
    (is (str/includes? form ":reason :evidence-tier-inactive")
        "a nil read is a production build — the door is dev-only")
    (is (str/includes? form "(cljs.core/assoc p :ok? true)")
        "a present projection is forwarded verbatim, stamped :ok? true")
    (is (str/includes? form "catch :default e")
        "a throwing read degrades to :evidence-tier-error")))

(deftest projection-form-carries-no-view-shaped-vocabulary
  ;; The donor family this replaced took a `:view-id` and could answer
  ;; `:view-not-available`. Hicasso mints no boundary identity, so neither
  ;; concept exists here and neither may leak back in as a fabricated arg.
  (doseq [read-fn hicasso-tool/tier-reads]
    (let [form (hicasso-tool/projection-form read-fn)]
      (is (not (str/includes? form "view-id"))
          (str read-fn ": no view-id is fabricated into the form"))
      (is (not (str/includes? form "view-not-available"))
          (str read-fn ": there is no undeclared-view case on this door")))))

(deftest projection-form-is-read-only
  (doseq [read-fn hicasso-tool/tier-reads]
    (let [form (hicasso-tool/projection-form read-fn)]
      (doseq [mutator [".setAttribute" ".dispatchEvent" "set! (.-" ".innerHTML"
                       "reset!" "swap!" "dispatch" "app-db-reset"]]
        (is (not (str/includes? form mutator))
            (str "the projection form must be a pure read — found " mutator
                 " in " read-fn))))))

;; ---------------------------------------------------------------------------
;; Tool wiring — form composition through the real tool fns
;; ---------------------------------------------------------------------------

(deftest each-tool-emits-a-guarded-door-call
  (async done
    (let [seen (atom nil)
          canned {:ok? true :schema hicasso-tool/consumed-evidence-schema}
          cases  [["read-mounted-boundaries" hicasso-tool/read-mounted-boundaries-tool]
                  ["read-read-attribution"   hicasso-tool/read-read-attribution-tool]
                  ["explain-render"          hicasso-tool/explain-render-tool]]]
      (-> (reduce
            (fn [p [read-fn tool-fn]]
              (.then p (fn [_]
                         (-> (with-captured-form! seen canned
                               (fn [] (tool-fn (fresh-conn) #js {})))
                             (.then (fn [_]
                                      (let [form @seen]
                                        (is (str/includes? form (str "re-frame.hicasso.tool/" read-fn))
                                            (str read-fn " calls the framework read directly"))
                                        (is (not (str/includes? form "re-frame2-pair.runtime/"))
                                            "does NOT route through the preload runtime — the door is optional")
                                        (is (not (str/includes? form "freehand"))
                                            "no donor name survives on the wire")
                                        (is (str/includes? form "cljs.core/exists?")
                                            "guards the door's presence"))))))))
            (js/Promise.resolve nil)
            cases)
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Schema gate — the boundary is REAL, not nominal
;; ---------------------------------------------------------------------------

(deftest a-schema-matching-projection-is-forwarded-as-success
  ;; The ordinary `consumed-evidence-schema` read is unchanged: the envelope
  ;; passes the gate and rides through as a successful (non-error) read.
  (async done
    (let [seen (atom nil)]
      (-> (with-captured-form! seen {:ok?        true
                                     :schema     hicasso-tool/consumed-evidence-schema
                                     :boundaries []}
            (fn []
              (hicasso-tool/read-mounted-boundaries-tool (fresh-conn) #js {})))
          (.then (fn [result]
                   (is (false? (tu/error? result))
                       "a schema-matched projection is a successful read")
                   (let [edn (tu/extract-edn result)]
                     (is (true? (:ok? edn)))
                     (is (= [] (:boundaries edn))
                         "a genuinely-empty schema-matched read is NOT mislabelled as an error"))
                   (done)))))))

(deftest a-producer-schema-this-build-does-not-understand-is-a-typed-mismatch
  ;; Pair connects to an ARBITRARY running app, so it cannot trust the producer's
  ;; own stamp to define support. A `:ok? true` projection stamped a `:schema`
  ;; this build was NOT written against is a typed
  ;; `:ok? false :evidence-tier-version-mismatch` (isError), never forwarded as
  ;; success. `re-frame.hicasso.evidence/schema` says in as many words that
  ;; there is no v1 acceptance path and no compatibility adapter — so the
  ;; superseded v1 stamp is the honest fixture for this, not an invented v99.
  (async done
    (let [seen        (atom nil)
          superseded  :re-frame.hicasso.evidence/v1]
      (-> (with-captured-form! seen {:ok? true :schema superseded :boundaries []}
            (fn []
              (hicasso-tool/read-mounted-boundaries-tool (fresh-conn) #js {})))
          (.then (fn [result]
                   (is (true? (tu/error? result))
                       "a producer schema this build does not understand is an isError")
                   (let [edn (tu/extract-edn result)]
                     (is (= false (:ok? edn)))
                     (is (= :evidence-tier-version-mismatch (:reason edn)))
                     (is (= superseded (:actual edn)) "the producer's stamp is reported")
                     (is (= hicasso-tool/consumed-evidence-schema (:expected edn))
                         "…against the consumer-owned expected schema")
                     (is (string? (:hint edn)) "carries an alignment hint"))
                   (done)))))))
