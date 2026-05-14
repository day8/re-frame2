(ns re-frame.http-machine-wrapper
  "Machine-shape wrapper for `:rf.http/managed` (rf2-ijm7) + test-time
  stub fxs and `with-managed-request-stubs*` helpers.

  Extracted from `re-frame.http-managed` per rf2-3i9b. Per Spec 014
  §Machine-shape wrapper: `:rf.http/managed` ALSO registers as a child-
  invokable state machine, so a parent machine can write

    {:invoke {:machine-id :rf.http/managed
              :data       {:request {:method :get :url \"/api/me\"}}}
     :on     {:succeeded :authenticated
              :failed    :login}}

  and the wrapper:
    1. issues the request on entry to its `:requesting` state,
    2. transitions to `:succeeded` / `:failed` on the reply,
    3. dispatches `[<parent-id> [:succeeded value]]` (or
       `[<parent-id> [:failed failure]]`) back to the parent, where
       `<parent-id>` and `<self-id>` come from spawn-fx's framework-
       reserved injection into the actor's initial `:data` per
       rf2-ijm7 (`:rf/parent-id`, `:rf/self-id`).

  The wrapper machine is registered via the `:machines/reg-machine`
  late-bind hook: re-frame.http-managed must NOT statically `:require`
  re-frame.machines (per rf2-xbtj the machines artefact is optional)
  and re-frame.machines must NOT statically `:require`
  re-frame.http-managed (per rf2-5kpd the http artefact is optional).
  When the machines artefact is absent the wrapper registration is
  skipped; the existing `:rf.http/managed` fx continues to work
  unchanged (the wrapper is purely additive on top of the fx surface).

  The fx (kind `:fx`) and the machine (kind `:event`) coexist under
  the same id `:rf.http/managed` — re-frame.registrar segregates by
  kind, so `:fx [[:rf.http/managed args]]` continues to fire the fx
  AND `{:invoke {:machine-id :rf.http/managed ...}}` resolves to the
  machine.

  ## Stub helpers

  `install-managed-request-stubs!` / `uninstall-managed-request-stubs!`
  / `with-managed-request-stubs*` (the fn form; the macro lives in the
  façade for source-coord capture) — per Spec 014 §Testing."
  (:require [re-frame.fx           :as fx]
            [re-frame.http-encoding :as encoding]
            [re-frame.late-bind    :as late-bind]))

;; rf2-2utlm — the canned stubs delegate to `encoding/dispatch-reply-
;; via-late-bind!`, the same helper `http-transport/dispatch-reply!`
;; uses, so the build-reply-event + late-bind lookup pattern lives in
;; one place.

;; ---- canned stub handlers (used by the registered :rf.http/managed-* fxs
;; AND the per-call stub fx-override that with-managed-request-stubs
;; installs). The fxs themselves are registered in the façade so they fire
;; only when the namespace is loaded — these handlers stay here so the
;; stub-handler below can reach them without circular requires.
;;
;; rf2-622e3 — origin-event resolution lives in
;; `encoding/resolve-origin-event` (single source of truth shared with
;; `http-managed/managed-handler`).

(defn canned-success-handler
  "Stub fx — synthesises a success reply per Spec 014 §Testing."
  [frame-ctx args-map]
  (let [value (get args-map :value {:stubbed true})]
    (encoding/dispatch-reply-via-late-bind!
      {:origin-event  (encoding/resolve-origin-event frame-ctx args-map)
       :explicit-on   {:supplied? (contains? args-map :on-success)
                       :value     (:on-success args-map)}
       :reply-payload {:kind :success :value value}
       :kind          :success}
      (:frame frame-ctx))
    nil))

(defn canned-failure-handler
  [frame-ctx args-map]
  (let [kind    (or (:kind args-map) :rf.http/transport)
        tags    (or (:tags args-map) {})
        failure (assoc tags :kind kind)]
    (encoding/dispatch-reply-via-late-bind!
      {:origin-event  (encoding/resolve-origin-event frame-ctx args-map)
       :explicit-on   {:supplied? (contains? args-map :on-failure)
                       :value     (:on-failure args-map)}
       :reply-payload {:kind :failure :failure failure}
       :kind          :failure}
      (:frame frame-ctx))
    nil))

;; ---- with-managed-request-stubs ------------------------------------------

(defn- stub-handler
  [stubs frame-ctx args-map]
  (let [req    (:request args-map)
        method (or (:method req) :get)
        url    (:url req)
        entry  (get stubs [method url])
        reply  (:reply entry)]
    (cond
      (and entry (contains? reply :ok))
      (canned-success-handler frame-ctx (assoc args-map :value (:ok reply)))

      (and entry (contains? reply :failure))
      (canned-failure-handler frame-ctx
                              (-> args-map
                                  (assoc :kind (or (:kind (:failure reply))
                                                   :rf.http/transport))
                                  (assoc :tags (dissoc (:failure reply) :kind))))

      :else
      (canned-failure-handler frame-ctx
                              (assoc args-map
                                     :kind :rf.http/transport
                                     :tags {:message "no stub matched"
                                            :method  method
                                            :url     url})))))

(def ^:private stub-fx-id :rf.http/managed-test-stub)

(defn install-managed-request-stubs!
  "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
  Registers a per-call fx-override target that consults `stubs` and
  synthesises the configured reply.

  Use with `:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}`
  on `dispatch-sync`, or wrap the test body via `with-managed-request-stubs`.

  Per Spec 014 §Testing — the framework ships canonical stub fxs."
  [stubs]
  (fx/reg-fx stub-fx-id
             {:doc "with-managed-request-stubs synthesised stub"}
             (fn [frame-ctx args-map]
               (stub-handler stubs frame-ctx args-map)))
  stub-fx-id)

(defn uninstall-managed-request-stubs!
  []
  (fx/clear-fx stub-fx-id)
  nil)

(defn with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Test-time helper."
  [stubs thunk]
  (try
    (install-managed-request-stubs! stubs)
    (thunk)
    (finally
      (uninstall-managed-request-stubs!))))

;; ---- machine-shape wrapper spec (rf2-ijm7) --------------------------------

(defn http-managed-machine-spec
  "Return the machine-shape wrapper spec for `:rf.http/managed`.

  The spec is regenerated by the registration form below (rather than
  defined as a static literal) so the action closures' calls into
  `re-frame.late-bind/get-fn` resolve at call time — preserving the
  late-bind discipline that lets the machines artefact be optional.

  Per Spec 014 §Machine-shape wrapper:
   - `:initial :requesting` — entry happens on spawn-time
     `[:rf.machine/spawned]` (the synthetic event the runtime dispatches
     to spawns without an explicit `:start`; per Spec 005 §Spawning).
   - The wrapper's `:data` carries the args map for the underlying
     `:rf.http/managed` fx PLUS the framework-reserved
     `:rf/parent-id` / `:rf/self-id` keys (stamped by spawn-fx, per
     rf2-ijm7).
   - `:fire-request` builds an args map for the underlying fx,
     overriding `:on-success` / `:on-failure` so the reply lands back
     at the wrapper actor as `[:rf.http/succeeded value]` /
     `[:rf.http/failed failure]`.
   - `:succeeded` / `:failed` are terminal leaf states; their
     `:entry` dispatches the parent's `[:succeeded value]` or
     `[:failed failure]`. When `:rf/parent-id` is nil (direct dispatch
     of `[:rf.http/managed ...]` to the wrapper rather than `:invoke`
     spawning), the parent-dispatch is a benign no-op."
  []
  {:doc "Spec 014 — :rf.http/managed as a child-invokable state machine.

         Wraps the :rf.http/managed fx in a machine envelope. Use via
         `:invoke {:machine-id :rf.http/managed :data {:request {...}}}`
         on a parent machine's state node. The wrapper runs the request
         on entry, transitions to :succeeded / :failed on the reply,
         and dispatches `[<parent-id> [:succeeded value]]` (or :failed)
         back to the parent."
   :initial :requesting
   :states
   {:requesting
    ;; Initial-state `:entry` actions fire on spawn as part of the
    ;; bootstrap cascade — the canonical re-frame2 shape for "do this
    ;; when the actor first runs".
    {:entry :fire-request
     :on    {:rf.http/succeeded  {:target :succeeded :action :record-value}
             :rf.http/failed     {:target :failed    :action :record-failure}}}

    :succeeded
    {:entry :dispatch-done
     :meta  {:terminal? true}}

    :failed
    {:entry :dispatch-error
     :meta  {:terminal? true}}}

   :actions
   {:fire-request
    (fn [data _]
      ;; Build the args map for the underlying fx from whatever the
      ;; user passed through the parent's :invoke :data. Strip the
      ;; framework-reserved `:rf/*` keys; pass through every other
      ;; documented arg (Spec 014 §The args map) so the wrapper is a
      ;; transparent envelope around the fx surface.
      (let [self-id   (:rf/self-id data)
            fx-args   (-> data
                          (dissoc :rf/self-id :rf/parent-id :rf/invoke-id)
                          (assoc :on-success [self-id [:rf.http/succeeded]]
                                 :on-failure [self-id [:rf.http/failed]]))]
        {:fx [[:rf.http/managed fx-args]]}))

    :record-value
    (fn [data [_ payload]]
      {:data (assoc data :rf/result payload)})

    :record-failure
    (fn [data [_ payload]]
      {:data (assoc data :rf/result payload)})

    :dispatch-done
    (fn [data _]
      (let [parent-id (:rf/parent-id data)
            result    (:rf/result data)
            value     (:value result)]
        (when parent-id
          {:fx [[:dispatch [parent-id [:succeeded value]]]]})))

    :dispatch-error
    (fn [data _]
      (let [parent-id (:rf/parent-id data)
            result    (:rf/result data)
            failure   (:failure result)]
        (when parent-id
          {:fx [[:dispatch [parent-id [:failed failure]]]]})))}})

(defn register-managed-machine!
  "Register the `:rf.http/managed` machine-shape wrapper via the
  `:machines/reg-machine` late-bind hook. Returns the registered
  machine-id on success, or nil if the machines artefact is not on the
  classpath (in which case the existing fx-only surface is unaffected)."
  []
  (when-let [reg-machine* (late-bind/get-fn :machines/reg-machine)]
    (reg-machine* :rf.http/managed (http-managed-machine-spec))
    :rf.http/managed))
