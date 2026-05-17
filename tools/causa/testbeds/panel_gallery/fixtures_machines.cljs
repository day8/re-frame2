(ns panel-gallery.fixtures-machines
  "Pure fixture builders for the Causa Machines tab gallery
  (rf2-sszlr — rebuild for new 6-tab Causa shape).

  The Machines panel reads from `:rf.causa/machine-inspector-data`, a
  composite over `:rf.causa/registered-machines`,
  `:rf.causa/machine-snapshots`, `:rf.causa/machine-definitions`,
  `:rf.causa/trace-buffer`, `:rf.causa/selected-machine-id`, and
  `:rf.causa/target-frame`.

  Each of registered-machines / machine-snapshots / machine-
  definitions has a TEST-ONLY override slot the panel reads if
  present:

    - `:rf.causa/set-registered-machines-override-for-test`
    - `:rf.causa/set-machine-snapshots-override-for-test`
    - `:rf.causa/set-machine-definitions-override-for-test`

  These exist precisely for gallery / test fixtures — the production
  path reads through `(rf/machines)` + `(rf/machine-meta id)` +
  app-db's `:rf/machines` slot. Variant `:events` dispatch the
  override events to seed each slot.

  ## Machine-definition shape (per Spec 005)

      {:initial <state-keyword-or-path>
       :data    <initial-data-map>
       :states  {<state-id>
                 {:on {<event-id> [{:target <state> :guard fn :actions [...]}]}}}}")

;; ---- definition builders ------------------------------------------------

(def loader-definition
  "A minimal :loader machine — :idle → :loading → :loaded / :error."
  {:initial :idle
   :data    {:result nil :error nil :attempts 0}
   :states  {:idle    {:on {:start [{:target :loading}]}}
             :loading {:on {:success [{:target :loaded}]
                            :failure [{:target :error}]}}
             :loaded  {:on {:reset [{:target :idle}]}}
             :error   {:on {:retry [{:target :loading}]
                            :reset [{:target :idle}]}}}})

(def auth-definition
  "A simple :auth machine — :anonymous → :authenticating →
  :authenticated / :anonymous (failure rewinds)."
  {:initial :anonymous
   :data    {:user nil :token nil :attempts 0}
   :states  {:anonymous       {:on {:sign-in [{:target :authenticating}]}}
             :authenticating  {:on {:success  [{:target :authenticated}]
                                    :failure  [{:target :anonymous}]}}
             :authenticated   {:on {:sign-out [{:target :anonymous}]
                                    :refresh  [{:target :authenticating}]}}}})

(def checkout-definition
  "A multi-step :checkout machine — :empty → :collecting → :reviewing
  → :paying → :complete / :failed."
  {:initial :empty
   :data    {:items [] :total 0}
   :states  {:empty       {:on {:add-item [{:target :collecting}]}}
             :collecting  {:on {:proceed  [{:target :reviewing}]
                                :clear    [{:target :empty}]}}
             :reviewing   {:on {:pay      [{:target :paying}]
                                :revise   [{:target :collecting}]}}
             :paying      {:on {:success  [{:target :complete}]
                                :failure  [{:target :failed}]}}
             :complete    {:on {:reset    [{:target :empty}]}}
             :failed      {:on {:retry    [{:target :paying}]
                                :reset    [{:target :empty}]}}}})

;; ---- snapshot builders -------------------------------------------------

(defn snapshot
  "Build a `{:state s :data d}` snapshot map."
  [state data]
  {:state state :data data})

;; ---- transition-trace buffers ------------------------------------------

(defn no-transitions-buffer
  "Empty trace buffer — Machines panel's transition-history ribbon
  renders empty."
  []
  [])

(defn loader-transition-buffer
  "Three `:rf.machine/transition` rows on the :loader machine —
  :idle → :loading → :loaded → :idle (reset). Exercises the
  transition-history ribbon at a comfortable depth."
  []
  [{:id 1 :time 1000 :op-type :rf.machine/transition
    :operation :rf.machine/transition
    :tags {:machine-id :loader :from :idle :to :loading
           :event [:start] :dispatch-id 100}}
   {:id 2 :time 1010 :op-type :rf.machine/transition
    :operation :rf.machine/transition
    :tags {:machine-id :loader :from :loading :to :loaded
           :event [:success {:result :data}] :dispatch-id 101}}
   {:id 3 :time 1020 :op-type :rf.machine/transition
    :operation :rf.machine/transition
    :tags {:machine-id :loader :from :loaded :to :idle
           :event [:reset] :dispatch-id 102}}])

(defn many-transitions-buffer
  "Many transitions including microsteps — populates the ribbon at
  scroll depth + exercises microstep rendering."
  []
  (vec
    (for [i (range 18)]
      (let [microstep? (zero? (mod i 4))
            from       (if microstep? :loading :loaded)
            to         (if microstep? :loaded :loading)]
        {:id (inc i) :time (+ 1000 (* 5 i))
         :op-type   (if microstep?
                      :rf.machine.microstep/transition
                      :rf.machine/transition)
         :operation (if microstep?
                      :rf.machine.microstep/transition
                      :rf.machine/transition)
         :tags {:machine-id :loader :from from :to to
                :event [(if microstep? :tick :pulse)]
                :dispatch-id (+ 100 i)}}))))

;; ---- multi-machine override seeds --------------------------------------

(defn registered-machines-multi
  "Three registered machines for the multi-machine variant."
  []
  [:loader :auth :checkout])

(defn machine-snapshots-multi
  "Live snapshots for each of the three multi-machine ids."
  []
  {:loader   (snapshot :loaded      {:result :ok :attempts 1})
   :auth     (snapshot :authenticated {:user {:id 7 :name "Ada"} :token "tok-abc"})
   :checkout (snapshot :reviewing   {:items [{:id :apple :qty 2}] :total 24})})

(defn machine-definitions-multi
  "Definitions for the three multi-machine ids."
  []
  {:loader   loader-definition
   :auth     auth-definition
   :checkout checkout-definition})
