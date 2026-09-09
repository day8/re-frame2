(ns xspike.observe
  "SPIKE (rf2-k97c.1) ARM A — the TOOL-LOCAL BINDING, in the two shapes the
  spike measured.

  V1 (the bead's shape) was exactly the four public calls:

      rf/subscribe
      rf.interop/activate-derived-value!   ; interop.cljs:85
      one baseline deref
      add-watch                            ; cljs.core
      rf.interop/add-on-dispose!           ; interop.cljs:121

  mirroring each subscription into a stock-Reagent `r/atom` so Reagent
  tracks it. Measured on the Hicasso host, V1 paints, updates on a real
  dependency change and keeps frame targeting — and LEAKS: the
  `:rf/xray` sub-cache ref-count climbed 22 -> 27 -> 32 across renders
  and never fell on unmount, because `re-frame.subs/unsubscribe`'s
  docstring is literally true — 'Reagent views auto-dispose via the
  reaction lifecycle' — and the reaction lifecycle in question is the
  INSTALLED ADAPTER's, which cannot see a stock-Reagent render.

  V2 (below) is what the leak costs: the subscription is acquired ONCE
  per [frame query-v] and held in a keyed table, and the table is
  released explicitly through a FIFTH public op, `rf/unsubscribe`. That
  is the first step of the same road `hicasso/impl/collector.cljs`
  walks.

  THROWAWAY."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]))

(def ^:private watch-key ::mirror)

;; [frame query-v] -> {:sub s :mirror ratom :frame f :query q}
(defonce ^:private cells (atom {}))

(defonce wire-count (atom 0))
(defonce unwire-count (atom 0))
(defonce subscribe-calls (atom 0))

(declare wire!)

(defn- drop-cell! [k]
  (when-let [c (get @cells k)]
    (swap! cells dissoc k)
    (try (remove-watch (:sub c) watch-key) (catch :default _ nil))
    ;; THE FIFTH OP. Without it the acquire is never balanced on a host
    ;; whose reaction lifecycle cannot see this renderer.
    (try (rf/unsubscribe (:frame c) (:query c)) (catch :default _ nil))
    (swap! unwire-count inc))
  nil)

(defn- wire! [frame query-v]
  (let [k   [frame query-v]
        sub (do (swap! subscribe-calls inc)
                (rf/subscribe query-v {:frame frame}))]
    (when sub
      (let [mirror (r/atom nil)]
        ;; 1. ACTIVATE — the ratom family captures sources only through
        ;;    deref-capture. No-op on the React-hook spine.
        (rf.interop/activate-derived-value! sub)
        ;; 2. ONE baseline deref, before the watch.
        (reset! mirror @sub)
        ;; 3. WATCH — the value channel.
        (add-watch sub watch-key
                   (fn [_ _ old nu] (when (not= old nu) (reset! mirror nu))))
        ;; 4. DISPOSAL — re-wire on invalidation.
        (rf.interop/add-on-dispose! sub (fn [] (drop-cell! k)))
        (swap! cells assoc k {:sub sub :mirror mirror :frame frame :query query-v})
        (swap! wire-count inc)
        mirror))))

(defn <sub
  "Drop-in for `@(rf/subscribe query-v)` inside an Arm-A Reagent render.

  The 1-arity resolves the frame ambiently, which works only where the
  installed adapter's `:adapter/current-component` hook can see this
  renderer. The 2-arity states the frame, which is what a stock-Reagent
  root over a reagent-slim host requires."
  ([query-v] (<sub query-v nil))
  ([query-v opts]
   (let [frame (or (:frame opts) (rf/current-frame-id))
         k     [frame query-v]]
     (if-let [c (get @cells k)]
       @(:mirror c)
       (when-let [m (wire! frame query-v)] @m)))))

(defn live-count [] (count @cells))

(defn release-all!
  "Tear the table down. ONE site for the whole arm."
  []
  (doseq [k (keys @cells)] (drop-cell! k))
  nil)

(defn stats []
  {:live (live-count) :wired @wire-count :unwired @unwire-count
   :subscribeCalls @subscribe-calls})
