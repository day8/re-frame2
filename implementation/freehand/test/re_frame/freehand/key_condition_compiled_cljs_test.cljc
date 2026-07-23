(ns re-frame.freehand.key-condition-compiled-cljs-test
  "FH-EVENT-005 through the COMPILED front end.

  The interpreted suite (`events-cljs-test`) proves the key-condition form
  against `events/event-plan` and a committed proxy. This one proves the
  other front end reads the same closed form — string keys, one level, legal
  only on a key listener, every branch an existing dispatching value — at
  BUILD time, and lowers a data branch to the very value the normalizer
  classifies. A declaration that meant one thing interpreted and another
  under `{:compiled true}` is the promotion break the compiled tier exists to
  make impossible, so both front ends are held to one fixture.

  Runs on both hosts: the analyzer is pure and its resolution is injected, so
  the suite is identical under `clojure -M:test` and `npm run test:freehand`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.analyze-accept-cljs-test :refer [mk-env]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.events :as events]))

(def event-005 (conf/fixture :FH-EVENT-005))

(defn- analyzed
  "Analyze `template` and answer its ONE event site from both directions —
  `:handler` as the AST carries it (what the emitters lower) and `:site` as
  the manifest records it (what a tool reads)."
  [template]
  (let [e   (mk-env)
        ast (ana/analyze e template)]
    {:handler (first (get-in ast [:props :events]))
     :site    (first (:events @(:sites e)))}))

(def ^:private accepted
  "What [[compile-error-id]] answers for a template the analyzer accepted."
  ::accepted)

(defn- compile-error-id
  "The `:rf.ui.compile/error` id `template` is refused with, or [[accepted]].
  The compiled tier's diagnostics carry their own id key, so this is the
  build-time counterpart of `conf/caught-id`."
  [template]
  (try
    (ana/analyze (mk-env) template)
    accepted
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.ui.compile/error (ex-data ex)))))

(deftest fh-event-005-a-compiled-key-map-lowers-to-the-interpreted-plan
  (testing "Per FH-EVENT-005 (compiled front end): a literal exact-key map at
            an `:on-key-down` / `:on-key-up` position classifies as the
            key-condition form rather than the listener-options form, and a
            branch that is DATA lowers to itself — so handing the compiled
            lowering to the runtime normalizer yields the very plan the
            interpreted front end classifies from the authored value. That
            equality is the whole claim: one event shape across both modes."
    (is (seq (:compiled-accepted event-005))
        "the fixture's compiled-accept table loaded")
    (doseq [{:keys [note prop template callback-branches] ser :serializable?}
            (:compiled-accepted event-005)]
      (let [authored (get-in template [1 prop])
            {:keys [handler site]} (analyzed template)]
        (is (= :key-map (:classification handler))
            (str note " — the exact-key form, not listener options"))
        (is (false? (:sync? handler))
            (str note " — a key listener is not a controlled-input door slot"))
        (is (= ser (:serializable? handler)) (str note " — serializable?"))
        (if (seq callback-branches)
          (do
            (is (= :opaque (:handler site))
                (str note " — a callback-bearing site is opaque to the manifest"))
            (doseq [key-str callback-branches]
              (let [branch (get (:form handler) key-str)]
                (is (= 're-frame.freehand.events/callback (first branch))
                    (str note " — the " (pr-str key-str) " branch lowers to the "
                         "roster callback the interpreted v/event macro expands to"))
                (is (= :event (second branch))
                    (str note " — in the dispatching role")))))
          (do
            (is (= authored (:form handler))
                (str note " — a data branch lowers to itself"))
            (is (= authored (:handler site))
                (str note " — and the manifest records that data verbatim"))
            (is (= (events/event-plan authored)
                   (events/event-plan (:form handler)))
                (str note " — the compiled lowering IS the interpreted site plan"))))))))

(deftest fh-event-005-the-compiled-tier-refuses-the-same-forms
  (testing "Per FH-EVENT-005 (compiled front end): every boundary the
            one-level exact-key form must not blur is refused at BUILD time,
            before a keystroke exists — an empty map, a map mixing exact keys
            with listener options, a nested key map, a branch that is not
            itself one intent, a branch naming a whole-listener option, and a
            key map on a listener that carries no key. Each is a typed
            authoring error about the map's SHAPE, never a report of an
            unknown listener option, which is what a map read as the wrong
            closed form produces."
    (is (seq (:compiled-rejected event-005))
        "the fixture's compiled-reject table loaded")
    (doseq [{:keys [note template error-id]} (:compiled-rejected event-005)]
      (is (= error-id (compile-error-id template)) note))))

(deftest fh-event-005-the-door-is-asked-in-the-rosters-vocabulary
  (testing "Per FH-EVENT-005: the compiled classification names the key-map
            form in the ROSTER's vocabulary, so the controlled-input door
            weighs the value a key site actually carries rather than falling
            through the bridge's total `:dynamic` answer. It settles the same
            way either route, and that is the point — the parity is a
            structural fact, not a coincidence of two lists."
    (is (= :key-map (controlled/compiled-role :key-map)))
    (is (not (contains? controlled/synchronous-outcomes :key-map))
        "the door never opens on a key listener, so the roster admits nothing")))
