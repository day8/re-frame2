(ns re-frame.ssr.error-projector
  "Error projector registry + default + projector application. Per
  Spec 011 §Server error projection.

  The trace surface carries INTERNAL error detail (stack traces,
  exception messages, internal codes) for monitoring; the HTTP response
  carries a PUBLIC projection — a sanitised, client-safe shape that
  crawlers / unauthenticated users may see. The two surfaces have
  different audiences and different security profiles. The projector is
  the boundary.

  A projector is a fn `(trace-event) → public-error-map`. The public
  shape is locked to four keys:

      {:status     500             ;; HTTP status integer
       :code       :internal-error ;; stable category keyword
       :message    \"...\"           ;; one-sentence human string
       :retryable? false}          ;; boolean

  In dev mode (`:dev-error-detail? true` via the frame's `:ssr` config),
  the public shape carries an additional `:details` key with the raw
  trace event. In prod (default), `:details` is absent.

  The trace-listener side (buffer + drain + `get-response`) lives in
  `re-frame.ssr.error-listener`. This namespace stays effect-free
  (apart from registering the built-in default projector) so the
  projector logic can be exercised standalone.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- always-on error-emit helper (EP-0008 rf2-hhutya) ---------------------
;;
;; `:rf.error/sanitised-on-projection` fires when the active projector
;; itself throws or returns a non-conforming shape and the runtime falls
;; back to the locked generic-500. It rides the always-on error-emit axis
;; (surface #4) ALONGSIDE the dev-gated `trace/emit-error!` so off-box
;; shippers see when the public boundary fell back — making the Spec
;; 009:1906/§sanitised-on-projection promise ("monitor dashboards see when
;; the public boundary fell back to the generic-500 shape") TRUE under
;; `-Dre-frame.debug=false`, where it is currently undeliverable.
;;
;; RE-ENTRY GUARD (rf2-hhutya RULED): this emit is the FALLBACK path itself.
;; It MUST be one-shot and MUST never re-enter projection. Two facts close
;; that by construction: (1) `error-emit-projection-listener` (the always-on
;; status-projection buffering listener) explicitly SKIPS
;; `:rf.error/sanitised-on-projection` (its recursion guard), so the fanned
;; record is never buffered for projection — it ships to off-box shippers
;; only; and (2) `project-error` fires the sanitisation trace AT MOST ONCE
;; per call (the catch arm and the non-conforming-shape arm are mutually
;; exclusive). So the always-on record cannot drive a second projection of
;; the same frame. NON-PROJECTING by design.

(defn emit-always-on-error!
  "Fan a PRE-BUILT always-on SSR error record out through the corpus-wide
  error-emit registry (surface #4), via the `:error-emit/dispatch-error-
  record` late-bind hook. `record` is the union shape `{:error <kw> :frame
  <id-or-nil> :time <ms> + flat category keys}`. A no-op when the
  `error-emit` producer hasn't loaded (the hook is nil) — the dev
  `trace/emit-error!` companion still fires. Returns nil.

  SINGLE SOURCE within the ssr artefact (rf2-50e82k): both the projector
  (`project-error`) and the listener (`re-frame.ssr.error-listener`, which
  already :requires this ns) call this one definition, closing the drift
  risk on the `:error-emit/dispatch-error-record` hook keyword + the
  nil-return contract. (The ssr-ring artefact keeps its own local wrapper
  in `re-frame.ssr.ring.lifecycle` — a deliberate artefact boundary.)"
  [record]
  (when-let [dispatch-error-record! (late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record! record))
  nil)

(def public-error-keys
  "The four locked keys on the :rf/public-error shape per Spec 011
  §Server error projection. Conformance ensures projector output
  carries exactly these (plus optional :details in dev mode)."
  #{:status :code :message :retryable?})

(defn- public-error-shape?
  "True if x is a conformant :rf/public-error map."
  [x]
  (and (map? x)
       (integer?  (:status     x))
       (keyword?  (:code       x))
       (string?   (:message    x))
       (boolean?  (:retryable? x))))

(def fallback-public-error
  "The locked generic-500 shape per Spec 011 §Default projector. The
  runtime falls back to this whenever the active projector throws or
  returns a non-conforming shape."
  {:status     500
   :code       :internal-error
   :message    "Something went wrong"
   :retryable? false})

(defn default-error-projector-fn
  "The runtime's default projector. Implements Spec 011 §Default
  projector verbatim:

    :rf.error/no-such-handler                  → 404 :not-found
    :rf.error/no-such-route                    → 404 :not-found
    :rf.error/schema-validation-failure
      AND (get-in trace-event [:tags :where])
          ∈ #{:event :cofx}                    → 400 :bad-request
    anything else                              → 500 :internal-error
                                                 (fallback-public-error)

  The 400 arm is GATED on the failure's `:where` tag (rf2-37o5by). A
  `:rf.error/schema-validation-failure` is client-facing — a 400
  `:bad-request` — only when the rejected value entered through a
  client-supplied surface: an inbound EVENT payload (`:where :event`)
  or a request COFX (`:where :cofx`), per Spec 011 §Default projector.
  Server-side schema surfaces — notably server-fx arg schemas
  (`:where :fx-args`, per Spec 010 §Validation order step 5) — fail
  validation because of a SERVER-side defect (a handler built a
  malformed fx args map), not because the client sent bad input. A
  client-facing 400 would mislabel a server bug as a user error, so a
  non-:event/:cofx schema-validation-failure falls through to the
  locked generic-500.

  The non-enumerated default covers the 500-class trace events
  (:rf.error/handler-exception, :rf.error/sub-exception,
  :rf.error/fx-handler-exception, :rf.error/drain-depth-exceeded), the
  server-side `:where :fx-args` (and any other non-client) schema-
  validation-failure, and any future :rf.error/* category that should
  fall through to the locked generic-500 shape — no new case arm
  required.

  Pure: takes a trace event, returns a public-error map. No I/O, no
  config. Custom projectors may inject auth-specific 401/403 codes
  and override the message strings; the runtime's default is the
  prod-safe baseline."
  [trace-event]
  (case (:operation trace-event)
    (:rf.error/no-such-handler
      :rf.error/no-such-route)
    {:status     404
     :code       :not-found
     :message    "Page not found"
     :retryable? false}

    :rf.error/schema-validation-failure
    ;; GATED (rf2-37o5by): only a client-surface failure
    ;; (`:where :event` / `:cofx`) is a client-facing 400. A server-side
    ;; failure (`:where :fx-args`, etc.) falls through to the 500 default.
    (if (#{:event :cofx} (get-in trace-event [:tags :where]))
      {:status     400
       :code       :bad-request
       :message    "Invalid input"
       :retryable? false}
      fallback-public-error)

    ;; default — generic 500. Reuses the locked fallback shape so the
    ;; "anything not enumerated → 500" mapping is the literal default
    ;; rather than a ceremonial copy of the fallback literal.
    fallback-public-error))

(defn reg-error-projector
  "Register a projector under :error-projector kind. The fn maps an
  internal trace event to the public-error shape:

    (rf/reg-error-projector :myapp/public-error
      {:doc \"Project internal error trace events to public response shapes.\"}
      (fn [trace-event]
        (case (:operation trace-event)
          :rf.error/no-such-handler           {:status 404 :code :not-found
                                               :message \"Not found\" :retryable? false}
          :rf.error/schema-validation-failure {:status 400 :code :bad-request
                                               :message \"Invalid input\" :retryable? false}
          ;; ...
          {:status 500 :code :internal-error
           :message \"Something went wrong\" :retryable? false})))

  Frames opt into a projector by name in their :ssr config:

    (rf/make-frame {:platform :server
                    :ssr {:public-error-id   :myapp/public-error
                          :dev-error-detail? false}})

  When a frame's :ssr config is absent, the runtime falls back to the
  built-in :rf.ssr/default-error-projector. Per Spec 011 §Server error
  projection."
  ([id projector-fn]
   (reg-error-projector id {} projector-fn))
  ([id metadata projector-fn]
   (registrar/register! :error-projector id
                        (assoc (source-coords/merge-coords metadata)
                               :handler-fn projector-fn))
   id))

(defn- frame-projector-id
  "Read the :ssr config's :public-error-id for a frame, falling back
  to :rf.ssr/default-error-projector when no config / no id."
  [frame-id]
  (or (get-in (frame/frame-meta frame-id) [:ssr :public-error-id])
      :rf.ssr/default-error-projector))

(defn- frame-dev-error-detail?
  "Read the :ssr config's :dev-error-detail? for a frame. Defaults to
  false (prod-safe). When true the projection result carries an extra
  :details key with the raw trace event."
  [frame-id]
  (boolean
    (get-in (frame/frame-meta frame-id) [:ssr :dev-error-detail?])))

(defn project-error
  "Resolve the active projector for the given frame and apply it to
  trace-event. Returns a :rf/public-error map. If the projector throws
  or returns a non-conforming shape, emits :rf.error/sanitised-on-projection
  and returns the locked generic-500 fallback. Per Spec 011
  §Server error projection — \"the fallback ensures the boundary
  cannot be bypassed by a bug in the projector.\""
  [frame-id trace-event]
  (let [projector-id  (frame-projector-id frame-id)
        projector-fn  (registrar/handler :error-projector projector-id)
        dev-detail?   (frame-dev-error-detail? frame-id)
        ;; Two failure modes for the projector:
        ;;   :threw      — the catch path returns this in `result`
        ;;   conforming  — usable
        ;;   anything else (nil from no-projector, wrong-shape map, etc.)
        ;; Both failure modes fall back to the locked-500 shape; the
        ;; sanitisation trace fires AT MOST ONCE per call.
        result
        (try
          (if projector-fn
            (projector-fn trace-event)
            ::no-projector)
          (catch #?(:clj Throwable :cljs :default) e
            (trace/emit-error! :rf.error/sanitised-on-projection
                               {:projector-id      projector-id
                                :frame             frame-id
                                :exception         e
                                :exception-message #?(:clj  (.getMessage e)
                                                      :cljs (.-message e))
                                :reason            "Error projector threw — using fallback."
                                :recovery          :warned-and-replaced})
            ;; EP-0008 (rf2-hhutya): ALSO ride the always-on axis — NON-
            ;; PROJECTING + one-shot (the projection listener skips this
            ;; category, so no re-entry; see §always-on error-emit helper).
            (emit-always-on-error!
              {:error :rf.error/sanitised-on-projection
               :frame frame-id
               :time  (interop/now-ms)
               :projector-id      projector-id
               :exception         e
               :exception-message #?(:clj  (.getMessage e)
                                     :cljs (.-message e))
               :reason            "Error projector threw — using fallback."
               :recovery          :warned-and-replaced})
            ::threw))
        public-error
        (cond
          (public-error-shape? result)
          result

          ;; Already-warned cases (the catch handled the trace) and the
          ;; no-projector-registered case (silent — the user named an id
          ;; that doesn't exist; the fallback is the safe behaviour).
          (or (= ::threw result) (= ::no-projector result))
          fallback-public-error

          :else
          (do
            (trace/emit-error! :rf.error/sanitised-on-projection
                               {:projector-id      projector-id
                                :frame             frame-id
                                :returned          result
                                :reason            "Error projector returned a non-conforming shape — using fallback."
                                :recovery          :warned-and-replaced})
            ;; EP-0008 (rf2-hhutya): ALSO ride the always-on axis — NON-
            ;; PROJECTING + one-shot (the non-conforming-shape arm and the
            ;; catch arm above are mutually exclusive, so at most one emit
            ;; per call; the projection listener skips this category).
            (emit-always-on-error!
              {:error :rf.error/sanitised-on-projection
               :frame frame-id
               :time  (interop/now-ms)
               :projector-id      projector-id
               :returned          result
               :reason            "Error projector returned a non-conforming shape — using fallback."
               :recovery          :warned-and-replaced})
            fallback-public-error))]
    (cond-> public-error
      dev-detail? (assoc :details trace-event))))

(defn server-frame?
  "True when the frame's :platform is :server. The error-projection
  hook only fires for server frames."
  [frame-id]
  (= :server (:platform (frame/frame-meta frame-id))))
