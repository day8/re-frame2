(ns re-frame2-pair-mcp.hicasso-tool-test
  "Unit tests for the three re-frame.hicasso.tool reads:
  read-mounted-boundaries / read-read-attribution / explain-render.

  Two layers (mirroring read-ui-test):

    1. Form composition — each tool ships ONE self-describing form that
       RESOLVES `re-frame.hicasso.tool` at runtime and calls the read off it.
       We check the pure `projection-form` builder and, via a capturing eval
       stub, that the tool resolves rather than references the door, is
       READ-ONLY, and does NOT route through a `re-frame2-pair.runtime`
       wrapper (the deliberate divergence — the door is optional and absent in
       a Reagent/UIx app, so it must not be hard-required in the preload).

    2. Tool wiring — the schema gate, and the map-envelope-result passthrough
       of the form's envelope.

  ## The absent-door rung is EXERCISED here, not asserted (rf2-t2ec)

  This suite used to satisfy itself that the emitted form CONTAINED the
  `:evidence-tier-unavailable` branch. It did, always — and the branch was
  unreachable in every real app, because the form referenced
  `re-frame.hicasso.tool/<read>` as a var and shadow's analyzer rejects a var
  in a namespace the build has never loaded, before any of the form runs. A
  string assertion about an emitted branch is the same fail-open shape as a
  census that cannot fail: it passes whether or not the branch can be reached.

  So the absent path is now RUN. `re-frame.hicasso.tool` is genuinely absent
  from this Node test process — Pair must never require it, and the
  bundle-isolation fence means it never will — which makes this process a
  faithful stand-in for a Reagent/UIx app. The test lifts the door name and the
  read name out of the ACTUAL emitted form and drives the SAME `cljs.core`
  lookup the form performs, with a loaded namespace as the positive control so
  a nil answer is proof of absence rather than of a broken probe.

  What no in-process test can see is the analyzer half — the emitted form is
  compiled by shadow in somebody else's JVM. That is
  `test/live-hicasso-wire.cjs`, which drives the three tools against a build
  with the door NOT loaded and asserts the rung is reached with its hint.

  What this suite also deliberately does NOT prove is that the provider
  actually publishes the reads these forms name — that is
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

(deftest projection-form-resolves-the-door-and-calls-it-nullary
  (let [form (hicasso-tool/projection-form "read-mounted-boundaries")]
    (is (str/includes? form "(cljs.core/find-ns-obj \"re-frame.hicasso.tool\")")
        "the door is looked up by NAME — the question the unavailable rung asks")
    (is (str/includes? form
                       "((cljs.core/unchecked-get d (cljs.core/munge \"read-mounted-boundaries\")))")
        "the read is resolved off the namespace object and called NULLARY — this door has no id to narrow by")
    (is (str/includes? form ":evidence-tier-unavailable")
        "an absent door degrades honestly, not a fabricated emptiness")
    (is (str/includes? form ":reason :evidence-tier-inactive")
        "a nil read is a production build — the door is dev-only")
    (is (str/includes? form "(cljs.core/assoc p :ok? true)")
        "a present projection is forwarded verbatim, stamped :ok? true")
    (is (str/includes? form "catch :default e")
        "a throwing read degrades to :evidence-tier-error")))

(deftest no-form-references-a-door-var-as-a-symbol
  ;; rf2-t2ec, the regression fence. A fully-qualified `re-frame.hicasso.tool/…`
  ;; symbol ANYWHERE in the emitted form is resolved by shadow's analyzer before
  ;; the form runs, so against an app without the door the whole eval comes back
  ;; :rf.error/eval-cljs-compile-error and every branch below it — including the
  ;; unavailable rung this door's honesty rests on — is dead. The door's name may
  ;; ride as a STRING (that is how it is resolved, and how the hint spells it);
  ;; what may never ride is `<tier-ns>/<anything>`.
  (doseq [read-fn hicasso-tool/tier-reads]
    (let [form (hicasso-tool/projection-form read-fn)]
      (is (nil? (re-find (re-pattern (str (str/replace hicasso-tool/tier-ns "." "\\.")
                                          "/[a-zA-Z0-9?!*<>=+_-]"))
                         form))
          (str read-fn ": the form must not reference a var in the door namespace — "
               "the analyzer rejects it in an app that has not loaded the door, and "
               "the :evidence-tier-unavailable branch never runs")))))

;; ---------------------------------------------------------------------------
;; The absent-door rung, EXERCISED (rf2-t2ec)
;;
;; `re-frame.hicasso.tool` is genuinely absent from this process — Pair does not
;; and must not require it — so the same lookup the emitted form performs can be
;; run here against a real absence. See the ns docstring for why a
;; `str/includes?` on the emitted branch was not a test of this at all.
;; ---------------------------------------------------------------------------

(defn- emitted-door-ns
  "The namespace name the ACTUAL emitted form hands to `find-ns-obj`."
  [form]
  (second (re-find #"\(cljs\.core/find-ns-obj \"([^\"]+)\"\)" form)))

(defn- emitted-read-name
  "The read name the ACTUAL emitted form hands to `munge`."
  [form]
  (second (re-find #"\(cljs\.core/munge \"([^\"]+)\"\)" form)))

(defn- resolve-door-read
  "The emitted form's own resolution step, run in THIS process: the namespace
  object by name, then the read off it by munged name. `nil` on either leg is
  exactly what drives the form to `:evidence-tier-unavailable`."
  [door-ns read-fn]
  (some-> (cljs.core/find-ns-obj door-ns)
          (cljs.core/unchecked-get (cljs.core/munge read-fn))))

(deftest the-lookup-the-form-performs-resolves-a-namespace-that-IS-loaded
  ;; The positive control, and it is what makes the absence test below mean
  ;; something: `this` suite's own namespace is loaded here, so the same two
  ;; steps must hand back a callable. Without it a broken probe would report
  ;; every door in the world as absent and the suite would still be green.
  (let [f (resolve-door-read "re-frame2-pair-mcp.tools.hicasso-tool" "projection-form")]
    (is (fn? f)
        "find-ns-obj + unchecked-get + munge resolves a read off a LOADED namespace")
    (is (string? (f "read-mounted-boundaries"))
        "…and what it resolves is the real fn, callable")))

(deftest an-absent-door-drives-the-form-to-the-unavailable-rung
  (doseq [read-fn hicasso-tool/tier-reads]
    (let [form     (hicasso-tool/projection-form read-fn)
          door-ns  (emitted-door-ns form)
          read-nm  (emitted-read-name form)]
      (is (= hicasso-tool/tier-ns door-ns)
          (str read-fn ": the form resolves the door this build targets"))
      (is (= read-fn read-nm)
          (str read-fn ": …and names this read at the resolution site"))
      ;; The rung's own condition, evaluated: this process has no
      ;; re-frame.hicasso.tool, exactly like a Reagent or UIx app.
      (is (nil? (cljs.core/find-ns-obj door-ns))
          (str read-fn ": re-frame.hicasso.tool is absent here — if this ever "
               "resolves, Pair has acquired a dependency on the provider and the "
               "bundle-isolation fence is broken"))
      (is (nil? (resolve-door-read door-ns read-nm))
          (str read-fn ": the resolution the form performs yields nil, so the form "
               "takes the :evidence-tier-unavailable branch")))))

(deftest the-unavailable-rung-carries-the-load-the-door-hint
  ;; The rung is only useful if it tells the operator what to do; the live
  ;; witness asserts the same hint arrives at the wire.
  (let [form (hicasso-tool/projection-form "read-mounted-boundaries")
        i    (str/index-of form ":evidence-tier-unavailable")]
    (is (some? i))
    (is (str/includes? (subs form i (min (count form) (+ i 600)))
                       "Load re-frame.hicasso.tool into the running build and retry")
        "the unavailable branch carries the load-the-door instruction, not just a reason")))

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

(deftest each-tool-emits-a-runtime-resolved-door-call
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
                                        (is (= read-fn (emitted-read-name form))
                                            (str read-fn " calls the framework read directly"))
                                        (is (= hicasso-tool/tier-ns (emitted-door-ns form))
                                            "…off the adapter-neutral door, resolved by name")
                                        (is (not (str/includes? form "re-frame2-pair.runtime/"))
                                            "does NOT route through the preload runtime — the door is optional")
                                        (is (not (str/includes? form "freehand"))
                                            "no donor name survives on the wire")
                                        (is (nil? (resolve-door-read (emitted-door-ns form)
                                                                     (emitted-read-name form)))
                                            (str "the door is absent in this process, so the form this tool "
                                                 "sent would take the :evidence-tier-unavailable branch")))))))))
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
