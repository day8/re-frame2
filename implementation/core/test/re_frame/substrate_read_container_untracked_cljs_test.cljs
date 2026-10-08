(ns re-frame.substrate-read-container-untracked-cljs-test
  "`re-frame.substrate.adapter/read-container-untracked` at kernel level, on
  BOTH ratom implementations — stock Reagent and reagent2.

  The reader returns exactly what `read-container` returns and records no
  dependency on the reaction being computed. Binding the capture context to
  nil would suppress that dependency too, but a Reaction read under a nil
  context takes its NON-reactive branch, whose first act is to `flush!` the
  whole reaction queue in the middle of the enclosing compute. So beside the
  edge itself, two fixtures pin that the published hook does not do that, and
  each runs the nil spelling as the control that proves the fixture can see a
  flush:

    * NEIGHBOUR — an unrelated reaction sits dirty in the queue while an
      auto-run reaction performs the read. The neighbour must not run inside
      that compute.
    * RE-ENTRY — a reaction sits dirty in the queue and its auto-run parent
      pulls it before the queue reaches it, so the pulled reaction's own
      compute performs the read. It must run once, never re-entered while its
      compute is on the stack.

  Each fixture also runs the tracked spelling (`read-container`) as the
  control that proves the edge assertion can see an edge.

  Also pinned: a declared input still re-runs the reader; outside any
  reactive context the read is plain, leaving a never-run Reaction watching
  nothing exactly as `read-container` does; and under a non-capturing adapter
  the reader answers `read-container`'s value through the routed hook's
  no-opinion sentinel.

  The container throughout is a non-auto-run Reaction kept live by a watch —
  the shape of a frame's runtime-db projection.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.ratom :as stock-ratom]
            [reagent2.ratom :as slim-ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; ---- per-kernel configuration ---------------------------------------------
;;
;; The two kernels differ only in which library supplies the primitives, and
;; in the name of the first capture run: reagent2 spells stock's `IRunnable`
;; `run` as `activate!`. The nil and sink spellings are the two ways of
;; suppressing capture by hand; each kernel's dynamic var is bound directly.

(def ^:private stock-config
  {:label     "reagent"
   :adapter   rf.adapter.reagent/adapter
   :ratom     stock-ratom/atom
   :reaction  stock-ratom/make-reaction
   :activate! stock-ratom/run
   :flush!    stock-ratom/flush!
   :dispose!  stock-ratom/dispose!
   :nil-read  (fn [c] (binding [stock-ratom/*ratom-context* nil] @c))
   :sink-read (fn [c] (binding [stock-ratom/*ratom-context* (js-obj)] @c))})

(def ^:private slim-config
  {:label     "reagent-slim"
   :adapter   rf.adapter.reagent-slim/adapter
   :ratom     slim-ratom/atom
   :reaction  slim-ratom/make-reaction
   :activate! slim-ratom/activate!
   :flush!    slim-ratom/flush!
   :dispose!  slim-ratom/dispose!
   :nil-read  (fn [c] (binding [slim-ratom/*ratom-context* nil] @c))
   :sink-read (fn [c] (binding [slim-ratom/*ratom-context* (js-obj)] @c))})

;; ---- harness --------------------------------------------------------------

(defn- with-adapter
  "Cold-start `adapter`, run `body-fn`, then tear the lifecycle back down so
  the next scenario starts from a never-installed state. The routed hook
  answers for the INSTALLED adapter, so every scenario needs one seated."
  [adapter body-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! adapter)
  (try
    (body-fn)
    (finally
      (when (rf.substrate.adapter/current-adapter)
        (rf.substrate.adapter/dispose-adapter!))
      (reset! rf.frame/frames {})
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))

(defn- watches?
  "True when reaction `rx` lists `source` among the sources it captured on its
  last run."
  [rx source]
  (boolean (some #(identical? % source) (array-seq (.-watching ^clj rx)))))

(defn- noop-watch [_ _ _ _] nil)

(defn- live-container
  "A non-auto-run Reaction over `src`, run once so it watches `src` and kept
  live by a watch of its own."
  [{:keys [reaction activate!]} src]
  (let [c (reaction (fn [] @src))]
    (activate! c)
    (add-watch c ::keep-live noop-watch)
    c))

(defn- release-container!
  [{:keys [dispose!]} c]
  (remove-watch c ::keep-live)
  (dispose! c))

(defn- spellings
  "The three ways a compute can read a container: tracked (the plain
  `read-container`), the nil control, and the reader under test."
  [{:keys [nil-read]}]
  [[:tracked   rf.substrate.adapter/read-container]
   [:nil       nil-read]
   [:untracked rf.substrate.adapter/read-container-untracked]])

;; ---- edge -----------------------------------------------------------------

(defn- edge-scenario
  [{:keys [label ratom reaction activate! flush! dispose!] :as config}]
  (testing (str label ": the reader records no edge to the container, returns "
                "read-container's value, and still re-runs on a declared input")
    (let [src     (ratom {:n 1})
          c       (live-container config src)
          input   (ratom 1)
          runs    (volatile! 0)
          seen    (volatile! nil)
          reader  (reaction (fn []
                              (vswap! runs inc)
                              @input
                              (vreset! seen (rf.substrate.adapter/read-container-untracked c)))
                            :auto-run true)
          t-runs  (volatile! 0)
          tracked (reaction (fn []
                              (vswap! t-runs inc)
                              (rf.substrate.adapter/read-container c))
                            :auto-run true)]
      (activate! reader)
      (activate! tracked)
      (flush!)
      (is (= [1 (rf.substrate.adapter/read-container c)] [@runs @seen])
          (str label ": one run under capture, returning read-container's value"))
      (swap! src update :n inc)
      (flush!)
      (is (= [2 1] [@t-runs @runs])
          (str label ": the container's change re-ran the tracked control, not the reader"))
      (swap! input inc)
      (flush!)
      (is (= [2 {:n 2}] [@runs @seen])
          (str label ": the declared input re-runs the reader, which reads the current value"))
      (dispose! reader)
      (dispose! tracked)
      (release-container! config c))))

;; ---- neighbour ------------------------------------------------------------

(defn- neighbour-run
  "Leave an unrelated reaction dirty in the queue, then fire an auto-run
  reaction whose compute reads the container with `read-fn`. Reports how
  often the neighbour ran INSIDE that compute, what the read returned, and
  whether the reader ended up watching the container."
  [{:keys [ratom reaction activate! flush! dispose!] :as config} read-fn]
  (let [src       (ratom {:n 1})
        c         (live-container config src)
        in-reader (volatile! false)
        inside    (volatile! 0)
        n-src     (ratom 0)
        neighbour (reaction (fn []
                              (when @in-reader (vswap! inside inc))
                              @n-src))
        _         (activate! neighbour)
        _         (add-watch neighbour ::keep-live noop-watch)
        fire      (ratom 0)
        seen      (volatile! nil)
        reader    (reaction (fn []
                              @fire
                              (vreset! in-reader true)
                              (try
                                (vreset! seen (read-fn c))
                                (finally (vreset! in-reader false))))
                            :auto-run true)]
    (activate! reader)
    (flush!)
    (vreset! inside 0)
    (swap! n-src inc)
    (swap! fire inc)
    (let [result {:inside   @inside
                  :value    @seen
                  :watches? (watches? reader c)}]
      (flush!)
      (dispose! reader)
      (remove-watch neighbour ::keep-live)
      (dispose! neighbour)
      (release-container! config c)
      result)))

(defn- neighbour-scenario
  [{:keys [label] :as config}]
  (testing (str label ": a dirty neighbour does not run inside the reader's compute")
    (let [r (into {} (map (fn [[k f]] [k (neighbour-run config f)])) (spellings config))]
      ;; The nil spelling is the control: it DOES flush the neighbour mid-compute.
      (is (= [1 0 0 true false true]
             [(get-in r [:nil :inside]) (get-in r [:untracked :inside]) (get-in r [:tracked :inside])
              (get-in r [:tracked :watches?]) (get-in r [:untracked :watches?])
              (apply = (map :value (vals r)))])
          label))))

;; ---- re-entry -------------------------------------------------------------

(defn- reentry-run
  "Leave reaction D dirty in the queue, then fire its auto-run parent, which
  pulls D before the queue reaches it. D's own compute reads the container
  with `read-fn`. Reports D's runs for the one change and the deepest D was
  ever nested inside itself."
  [{:keys [ratom reaction activate! flush! dispose!] :as config} read-fn]
  (let [src       (ratom {:n 1})
        c         (live-container config src)
        d-src     (ratom 0)
        runs      (volatile! 0)
        depth     (volatile! 0)
        max-depth (volatile! 0)
        d         (reaction (fn []
                              (vswap! runs inc)
                              (vswap! depth inc)
                              (vswap! max-depth max @depth)
                              (try
                                (read-fn c)
                                @d-src
                                (finally (vswap! depth dec)))))
        p-src     (ratom 0)
        parent    (reaction (fn [] @p-src @d) :auto-run true)]
    (activate! parent)
    (flush!)
    (vreset! runs 0)
    (vreset! max-depth 0)
    (swap! d-src inc)
    (swap! p-src inc)
    (let [result {:runs @runs :max-depth @max-depth}]
      (flush!)
      (dispose! parent)
      (dispose! d)
      (release-container! config c)
      result)))

(defn- reentry-scenario
  [{:keys [label] :as config}]
  (testing (str label ": a reaction pulled out of the queue is not re-entered by its own read")
    (let [r (into {} (map (fn [[k f]] [k (reentry-run config f)])) (spellings config))]
      (is (< 1 (get-in r [:nil :max-depth]))
          (str label ": control — the nil spelling re-enters the reaction on the stack: " (:nil r)))
      (is (= [{:runs 1 :max-depth 1} {:runs 1 :max-depth 1}] [(:untracked r) (:tracked r)])
          (str label ": the untracked read, like the tracked one, runs it once")))))

;; ---- outside a reactive context -------------------------------------------

(defn- plain-read-scenario
  [{:keys [label ratom reaction dispose! sink-read]}]
  (testing (str label ": outside any reactive context the reader is a plain read")
    (let [src      (ratom {:n 1})
          fresh    (reaction (fn [] @src))
          sunk     (reaction (fn [] @src))]
      (sink-read sunk)
      ;; The sink-bound read is the control: it runs the capturing branch, so
      ;; the Reaction it reads ends up watching its source.
      (is (= [{:n 1} true false]
             [(rf.substrate.adapter/read-container-untracked fresh)
              (nil? (.-watching ^clj fresh)) (nil? (.-watching ^clj sunk))])
          (str label ": a plain read leaves a never-run Reaction watching nothing"))
      (dispose! sunk))))

;; ---- the two kernels ------------------------------------------------------

(defn- kernel-scenarios [config]
  (with-adapter (:adapter config)
    (fn []
      (edge-scenario config)
      (neighbour-scenario config)
      (reentry-scenario config)
      (plain-read-scenario config))))

(deftest stock-reagent-untracked-read
  (kernel-scenarios stock-config))

(deftest reagent-slim-untracked-read
  (kernel-scenarios slim-config))

;; ---- a non-capturing adapter ----------------------------------------------

(deftest non-capturing-adapter-reads-through-read-container
  ;; Under plain-atom the routed hook has no opinion, and the reader answers
  ;; read-container's value for base and derived containers.
  (with-adapter rf.substrate.plain-atom/adapter
    (fn []
      (let [base    (rf.substrate.adapter/make-state-container {:n 1})
            derived (rf.substrate.adapter/make-derived-value [base] (fn [v] (update v :n inc)))]
        (is (= [{:n 1} {:n 2}]
               (mapv rf.substrate.adapter/read-container-untracked [base derived])))))))
