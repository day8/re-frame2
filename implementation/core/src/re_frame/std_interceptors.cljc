(ns re-frame.std-interceptors
  "Standard interceptors. Per Spec 002 / API.md §Standard interceptors and
  Spec 001 §Hot-reload semantics M-21.

  Ships THREE specific helpers plus the ->interceptor primitive:
    inject-cofx — in re-frame.cofx (cofx-registry lookup)
    path        — focus a handler on an app-db sub-slice (this ns)
    unwrap      — assert [id payload-map] event shape (this ns)

  The principle: keep helpers that do specific, non-trivial work; drop
  those that are just (->interceptor :before f) or (->interceptor :after f)
  with no other logic. Custom before/after work uses ->interceptor directly."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- path -----------------------------------------------------------------
;;
;; Focus a handler on an app-db sub-slice. The handler sees and returns
;; only the slice; the interceptor splices the result back into the
;; parent app-db. Per Spec 002 / API.md.

(defn path
  "(path :a :b :c) returns an interceptor that focuses the handler on the
  sub-slice at [:a :b :c]. The handler receives the slice value as :db
  (not the full app-db); its returned :db is spliced back into the
  full app-db at the same path."
  [& path-segs]
  (let [path-vec (vec path-segs)]
    (interceptor/->interceptor
      :id    :path
      :before
      (fn [ctx]
        (-> ctx
            ;; Stash the original db on a stack (supports nested path
            ;; interceptors). Reserved-namespace slot per Spec
            ;; Conventions §Reserved namespaces — framework keys on the
            ;; interceptor context belong under :rf/...
            (update :rf/path-stack (fnil conj []) (:db (:coeffects ctx)))
            (assoc-in [:coeffects :db]
                      (get-in (:db (:coeffects ctx)) path-vec))))
      :after
      (fn [ctx]
        ;; The splice-back only fires when the handler actually emitted
        ;; a `:db` effect. If the handler returned no `:db`, the slice
        ;; didn't change and we MUST NOT synthesise a `:db` effect —
        ;; downstream tools rely on "no `:db` effect = no DB write" (the
        ;; docstring contract). Synthesising would be idempotent at the
        ;; value level (same `original-db` re-spliced with the same
        ;; pre-handler slice) but allocated a fresh map per path-walk-
        ;; step and produced a spurious `:db` effect from a no-`:db`
        ;; handler.
        (let [stack       (:rf/path-stack ctx [])
              original-db (peek stack)
              new-stack   (pop stack)
              handler-emitted-db? (contains? (:effects ctx) :db)]
          (cond-> (assoc ctx :rf/path-stack new-stack)
            handler-emitted-db?
            (assoc-in [:effects :db]
                      (assoc-in original-db path-vec
                                (get-in ctx [:effects :db])))))))))

;; ---- unwrap ---------------------------------------------------------------
;;
;; Asserts that the event is exactly [<id> <payload-map>] (per the M-19
;; canonical map-payload form), and replaces :event with the payload map.
;; The handler then destructures the map directly (one level less of
;; destructuring): (fn [_ {:keys [...]}] ...) instead of (fn [_ [_ {:keys [...]}]] ...).

(def unwrap
  "Pre-registered interceptor (a value, not a fn). Use as
  `(reg-event-fx :foo [unwrap] (fn [_ {:keys [a b]}] ...))`.
  The :event coeffect inside the handler is the payload map."
  (interceptor/->interceptor
    :id    :unwrap
    :before
    (fn [ctx]
      (let [event (interceptor/get-coeffect ctx :event)]
        (if-not (and (vector? event)
                     (= 2 (count event))
                     (map? (second event)))
          (do (trace/emit-error! :rf.error/unwrap-bad-event-shape
                                 {:event event
                                  :expected "[event-id payload-map]"
                                  :recovery :no-recovery})
              ctx)
          ;; Stash the unwrapped event under :rf/unwrap-stash so :after
          ;; can restore the original vector for downstream consumers.
          (-> ctx
              (assoc :rf/unwrap-stash event)
              (interceptor/assoc-coeffect :event (second event))))))
    :after
    (fn [ctx]
      (if-let [original (:rf/unwrap-stash ctx)]
        (-> ctx
            (dissoc :rf/unwrap-stash)
            (interceptor/assoc-coeffect :event original))
        ctx))))
