(ns re-frame.frame-construction-atomic-jvm-test
  "Frame-container allocation is failure-atomic (Spec 006). `new-frame-record`
  acquires the state container, then the app-db and runtime-db projections; if a
  later step throws, every projection already returned is disposed in reverse
  order, so a conforming adapter is not left owning a watch for a frame that
  was never installed. A throwing `make-derived-value` unwinds its own partial
  work.

  The custom ten-fn adapter can be armed to throw at any position and tracks
  the watch each returned projection installs. Each case asserts no residual
  watch, no frame row, no trace-policy residue, and a clean retry through the
  same adapter."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

;; :throw-at        nil | :state | :first-derived | :second-derived, an atom so a
;;                  case can disarm it and retry through the same adapter
;; :derived-count   make-derived-value calls so far
;; :watched         projection ids whose watch is installed (the leak surface)
;; :disposed-order  ids of returned projections, in dispose order
;; :containers      every state container handed out

(defn- fresh-state []
  {:throw-at        (atom nil)
   :derived-count   (atom 0)
   :watched         (atom #{})
   :disposed-order  (atom [])
   :containers      (atom [])})

(defn- tracking-adapter [{:keys [throw-at derived-count watched disposed-order
                                 containers]}]
  {:kind :custom
   :make-state-container
   (fn [initial]
     (let [c (atom initial)]
       (swap! containers conj c)
       (if (= :state @throw-at)
         (throw (ex-info "make-state-container armed to throw" {:pos :state}))
         c)))
   :read-container     (fn [c] @c)
   :replace-container! (fn [c v] (reset! c v))
   :make-derived-value
   (fn [source-containers compute-fn]
     (let [n      (swap! derived-count inc)
           pid    (keyword "proj" (str "p" n))
           pos    (if (= n 1) :first-derived :second-derived)
           source (first source-containers)]
       ;; the watch a real derived value installs, and what leaks
       (add-watch source pid (fn [_ _ _ _] nil))
       (swap! watched conj pid)
       (if (= pos @throw-at)
         ;; not returned, so it unwinds its own watch before throwing
         (do
           (remove-watch source pid)
           (swap! watched disj pid)
           (throw (ex-info "make-derived-value armed to throw" {:pos pos})))
         (let [dv (reify clojure.lang.IDeref
                    (deref [_] (apply compute-fn (map deref source-containers))))]
           (rf.interop/add-on-dispose! dv
             (fn []
               (remove-watch source pid)
               (swap! watched disj pid)
               (swap! disposed-order conj pid)))
           dv))))
   :render           (fn [_ _ _] nil)
   :render-to-string (fn [_ _] nil)
   :dispose-adapter! (fn [] nil)})

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf.trace/clear-frame-no-emit!)
  ;; each case installs its own tracking adapter
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (try
    (test-fn)
    (finally
      ;; a different adapter left installed would make the next namespace's
      ;; plain-atom init! throw
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
      (rf/init! rf.substrate.plain-atom/adapter))))

(use-fixtures :each reset-runtime)

(defn- err [thunk]
  (try (thunk) ::no-throw
       (catch Throwable e (or (:pos (ex-data e)) e))))

;; the watches actually on the containers, read off the JVM atoms
(defn- residual-watches [{:keys [containers]}]
  (reduce (fn [m c] (merge m (.getWatches ^clojure.lang.ARef c))) {} @containers))

(deftest state-container-throw-leaves-no-residue
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :state)
    ;; the throw surfaces, nothing is installed or watched, make-derived-value
    ;; is never reached, and the requested no-emit policy was never published
    (is (= [:state nil #{} true 0 false]
           [(err #(rf.frame/upsert-frame! :atomic/state-throw
                                          {:rf.trace/frame-no-emit? true}))
            (rf.frame/frame :atomic/state-throw)
            @(:watched state)
            (empty? (residual-watches state))
            @(:derived-count state)
            (rf.trace/frame-trace-disabled? :atomic/state-throw)]))
    (reset! (:throw-at state) nil)
    (is (= [:atomic/state-throw true]
           [(rf.frame/upsert-frame! :atomic/state-throw {})
            (some? (rf.frame/frame-state-container :atomic/state-throw))])
        "a clean retry through the same adapter")))

(deftest first-projection-throw-unwinds-own-partial-work
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :first-derived)
    ;; the throwing projection unwound its own watch, and nothing had been
    ;; returned for new-frame-record to dispose
    (is (= [:first-derived nil 1 #{} true [] false]
           [(err #(rf.frame/upsert-frame! :atomic/first-throw
                                          {:rf.trace/frame-no-emit? true}))
            (rf.frame/frame :atomic/first-throw)
            @(:derived-count state)
            @(:watched state)
            (empty? (residual-watches state))
            @(:disposed-order state)
            (rf.trace/frame-trace-disabled? :atomic/first-throw)]))
    (reset! (:throw-at state) nil)
    (reset! (:derived-count state) 0)
    (is (= [:atomic/first-throw true]
           [(rf.frame/upsert-frame! :atomic/first-throw {})
            (some? (rf.frame/frame-state-container :atomic/first-throw))]))))

(deftest second-projection-throw-disposes-first-in-reverse-order
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :second-derived)
    ;; the one returned projection (:proj/p1) is disposed exactly once; without
    ;; the rollback its watch would be stranded
    (is (= [:second-derived nil 2 [:proj/p1] #{} true false]
           [(err #(rf.frame/upsert-frame! :atomic/second-throw
                                          {:rf.trace/frame-no-emit? true}))
            (rf.frame/frame :atomic/second-throw)
            @(:derived-count state)
            @(:disposed-order state)
            @(:watched state)
            (empty? (residual-watches state))
            (rf.trace/frame-trace-disabled? :atomic/second-throw)]))
    (reset! (:throw-at state) nil)
    (reset! (:derived-count state) 0)
    (reset! (:disposed-order state) [])
    (is (= [:atomic/second-throw true #{:proj/p1 :proj/p2}]
           [(rf.frame/upsert-frame! :atomic/second-throw {})
            (some? (rf.frame/frame-state-container :atomic/second-throw))
            @(:watched state)])
        "a clean retry installs one frame owning exactly its two projection watches")))
