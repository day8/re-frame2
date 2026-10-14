(ns re-frame.machine-handler-meta-test
  "Machine guard/action fn-source handler-meta is a GENERAL source-meta
  surface DERIVED from the machine's `:event` registration spec, NOT a
  registrar kind (Spec 005 §Trace events — guard evaluations and action runs;
  Xray Spec 003 §Focused-transition lens).

  The `reg-machine` macro walks the literal machine spec at expansion time and
  co-locates `{:fn .. :source-coords .. :source-code ..}` (the `pr-str` of the
  fn-form) onto each `:guards` / `:actions` entry; `reg-machine*` stores the
  stamped spec under `:rf/machine` in the machine's `:event` registration.
  Tools read

      (rf/handler-meta {:source :store :kind :machine-guard :id [<machine-id> <guard-id>]})
      (rf/handler-meta {:source :store :kind :machine-action :id [<machine-id> <action-id>]})

  which derives the meta on demand: `:rf/guard-id` / `:rf/action-id`,
  `:rf/machine-id`, `:rf.handler/source`, `:handler-fn`, and the coords from
  the per-element walker. The derivation is gated on
  `re-frame.interop/debug-enabled?`; a production bundle's macro emits no
  `:source-code` slot at all."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.core-machines :as rf.core-machines]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; loading the machines artefact installs the late-bind hooks
            ;; `reg-machine` resolves through
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- guard-meta [machine-id id]
  (rf/handler-meta {:source :store :kind :machine-guard :id [machine-id id]}))

(defn- action-meta [machine-id id]
  (rf/handler-meta {:source :store :kind :machine-action :id [machine-id id]}))

;; ---- the macro captures every guard and action ------------------------------

(deftest reg-machine-captures-every-guard-and-action
  ;; every :guards / :actions entry gets its own derived meta; one entry of
  ;; each carries the whole shape
  (rf/reg-machine :rf2-ypu5i/many
    {:initial :idle
     :guards  {:a? (fn [_] true)
               :b? (fn [{data :data}] (pos? (:n data 0)))}
     :actions {:inc!  (fn [{data :data}] {:data (update data :n inc)})
               :emit! (fn [_] {:fx [[:dispatch [:emitted]]]})}
     :states  {:idle {:on {:probe [{:target :idle :guard :a?}
                                   {:target :idle :guard :b?}]
                           :bump  {:target :idle :action :inc!}
                           :send  {:target :idle :action :emit!}}}}})
  (let [guards  (map #(guard-meta :rf2-ypu5i/many %) [:a? :b?])
        actions (map #(action-meta :rf2-ypu5i/many %) [:inc! :emit!])]
    (is (= [[:a? :b?] [:inc! :emit!] [true true true true]]
           [(mapv :rf/guard-id guards)
            (mapv :rf/action-id actions)
            (mapv #(str/includes? (:rf.handler/source %1) %2)
                  (concat guards actions) ["true" "pos?" "inc" ":dispatch"])]))
    (is (= [[:rf2-ypu5i/many true true true] [:rf2-ypu5i/many true]]
           [((juxt :rf/machine-id (comp fn? :handler-fn) (comp some? :ns) (comp some? :line)) (last guards))
            ((juxt :rf/machine-id (comp fn? :handler-fn)) (last actions))])
        "the scoping machine, the actual fn, and the walker's coords")))

(deftest guards-and-actions-enumerable-via-event-registration-spec
  ;; tools enumerate the source from the machine's :event registration spec,
  ;; not from a (rf/registrations …) side-table — there is none
  (rf/reg-machine :rf2-ftrcv/enum
    {:initial :idle
     :guards  {:ok? (fn [_] true)}
     :actions {:go! (fn [_] nil)}
     :states  {:idle {:on {:e {:target :idle :guard :ok? :action :go!}}}}})
  (let [spec (:rf/machine (rf.registrar/lookup :event :rf2-ftrcv/enum))]
    (is (= [{} {} true true]
           [(rf/registrations {:source :store :kind :machine-guard})
            (rf/registrations {:source :store :kind :machine-action})
            (string? (get-in spec [:guards  :ok? :source-code]))
            (string? (get-in spec [:actions :go! :source-code]))]))))

(deftest re-registration-clears-stale-handler-metas
  ;; re-registering with a renamed guard drops the old slot (hot-reload hygiene)
  (rf/reg-machine :rf2-ypu5i/reload
    {:initial :idle
     :guards  {:old? (fn [_] true)}
     :states  {:idle {:on {:e {:target :idle :guard :old?}}}}})
  (is (some? (guard-meta :rf2-ypu5i/reload :old?)))
  (rf/reg-machine :rf2-ypu5i/reload
    {:initial :idle
     :guards  {:new? (fn [_] true)}
     :states  {:idle {:on {:e {:target :idle :guard :new?}}}}})
  (is (= [nil true] [(guard-meta :rf2-ypu5i/reload :old?) (some? (guard-meta :rf2-ypu5i/reload :new?))])))

(deftest reg-machine-plain-fn-surface-skips-form-source
  ;; `reg-machine*` carries opaque spec data the macro walker never saw, so it
  ;; registers no source; tools fall back to the top-level call-site coords
  ;; (Spec 005 §reg-machine vs reg-machine*)
  (rf.machines/reg-machine* :rf2-ypu5i/programmatic
    {:initial :idle
     :guards  {:any? (fn [_] true)}
     :actions {:noop! (fn [_] nil)}
     :states  {:idle {:on {:e {:target :idle :guard :any? :action :noop!}}}}})
  (is (= [nil nil] [(guard-meta :rf2-ypu5i/programmatic :any?) (action-meta :rf2-ypu5i/programmatic :noop!)])))

(deftest production-elision-suppresses-handler-meta-derivation
  ;; with debug-enabled? false the derivation returns nil even over a spec
  ;; that DOES carry :source-code (a pre-stamped spec simulating the dev macro
  ;; emission); the CLJS bundle's own elision is `npm run test:elision`'s
  (with-redefs [rf.interop/debug-enabled? false]
    (rf.core-machines/reg-machine* :rf2-ftrcv/elided
      {:initial :idle
       :guards  {:g? {:fn (fn [_] true) :source-code "(fn [_] true)"}}
       :actions {:a! {:fn (fn [_] nil)  :source-code "(fn [_] nil)"}}
       :states  {:idle {:on {:e {:target :idle :guard :g? :action :a!}}}}})
    (is (= [nil nil] [(guard-meta :rf2-ftrcv/elided :g?) (action-meta :rf2-ftrcv/elided :a!)]))))
