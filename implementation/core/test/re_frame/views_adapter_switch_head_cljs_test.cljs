(ns re-frame.views-adapter-switch-head-cljs-test
  "The `rf.views/view-head` cache across an adapter switch.

  A registration made after init under a componentizing substrate A1 stores
  A1's head H1 in the registrar slot, and a later re-derivation for another
  substrate deliberately leaves the slot alone (re-registering would publish
  a phantom `:rf.registry/handler-replaced`). So the cache must recognise its
  own registration by the object the slot still holds: after a switch to A2,
  the SECOND lookup must still answer A2's head rather than the stale H1. A
  `:view` slot this ns did not build is handed back untouched, even over a
  stale cache entry for the same id.

  The adapters are inert `:custom` maps whose `:adapter/componentize-view`
  impls are routed through the real `route-hook!`; nothing mounts."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views :as rf.views]))

;; `:kind :custom` routes by object identity, so these are two substrates.
(def ^:private adapter-a1 {:kind :custom :rf.test/substrate :a1})
(def ^:private adapter-a2 {:kind :custom :rf.test/substrate :a2})

(def ^:private marker-prop "rf2Oz7wrShellMarker")

(defn- shell-marker
  "Which test substrate componentized `x`, or nil when nothing did."
  [x]
  (when (some? x) (gobj/get x marker-prop)))

(defn- make-componentize-view
  "A forwarding-shell `:adapter/componentize-view` impl, stamped with
  `marker` so a returned head names the substrate that produced it."
  [marker]
  (fn componentize-view [_id _metadata wrapped]
    (let [shell (fn view-component [& args] (apply wrapped args))]
      (gobj/set shell marker-prop marker)
      shell)))

;; Routed rather than `set-fn!`'d: each impl fires only while its own map is
;; the installed adapter, so it stays inert for the rest of the node bundle.
(defonce ^:private routed-componentize-hooks
  (do (rf.substrate.adapter/route-hook! adapter-a1 :adapter/componentize-view
                           (make-componentize-view :a1))
      (rf.substrate.adapter/route-hook! adapter-a2 :adapter/componentize-view
                           (make-componentize-view :a2))
      true))

(defn- render-fn [& _args] [:div "row"])

(defn- switch-adapter! [next-adapter]
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! next-adapter))

(defn- slot-handler [id]
  (:handler-fn (rf.registrar/lookup :view id)))

;; Each test installs its own adapter; the tail leaves the slot never-installed
;; for whichever namespace runs next.
(use-fixtures :each
  (let [reset-runtime (rf.test-support/make-reset-runtime-fixture {})]
    (fn [t]
      (reset-runtime
        (fn []
          (try
            (t)
            (finally
              (rf.substrate.adapter/dispose-adapter!)
              (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))))))

(deftest post-init-componentized-head-survives-an-adapter-switch
  (rf.substrate.adapter/install-adapter! adapter-a1)
  (let [h1 (rf.views/reg-view* ::switch-row {} render-fn)]
    (is (= :a1 (shell-marker h1))
        "precondition: registering after install produced A1's componentized head H1")
    (is (identical? h1 (rf/view ::switch-row))
        "while A1 is installed the lookup is a cache hit on H1")
    (switch-adapter! adapter-a2)
    (is (identical? h1 (slot-handler ::switch-row))
        "precondition: the switch leaves the registrar slot at H1")
    (let [first-lookup  (rf/view ::switch-row)
          second-lookup (rf/view ::switch-row)]
      (is (= :a2 (shell-marker first-lookup))
          "the first lookup re-derives the head against A2")
      (is (identical? first-lookup second-lookup)
          "the second lookup still answers A2's head, not the stale H1"))))

(deftest foreign-re-registration-over-a-composed-slot-passes-through
  (rf.substrate.adapter/install-adapter! adapter-a1)
  (rf.views/reg-view* ::hijacked-row {} render-fn)
  (let [foreign (fn foreign-view [& _args] [:div "foreign"])]
    ;; The cache still describes the composed registration; the slot does not.
    (rf.registrar/register! :view ::hijacked-row {:handler-fn foreign})
    (switch-adapter! adapter-a2)
    (is (identical? foreign (rf/view ::hijacked-row))
        "the replaced slot is served raw, not re-derived from the superseded registration")))
