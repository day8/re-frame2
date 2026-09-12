(ns re-frame.story.trace-listener-bracket-test
  "JVM pins for the register/try/finally trace-listener bracket that
  runtime's phase-1/2 capture and frames' setup + teardown capture share
  (rf2-5zgx).

  The three call sites used to carry private copies of the bracket that
  differed only by the listener-id prefix. Folding them must not change
  what lands on the trace bus, so these tests pin the ids each call site
  registers, observed through the trace registry itself:

  - `:re-frame.story.runtime/capture-<n>`        — phases 1 and 2;
  - `:re-frame.story.frames/setup-capture-<n>`    — the `:frame-setup`
                                                    `:init` walk;
  - `:re-frame.story.frames/teardown-capture-<n>` — the `:loaders-teardown`
                                                    and decorator `:teardown`
                                                    walks.

  Each prefix counts on its own: consecutive brackets of one prefix carry
  consecutive `<n>`, whatever the other prefixes did in between. And every
  bracket removes its listener when its body returns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play       :as rf.story.play]
            [re-frame.trace.tooling    :as rf.trace.tooling]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.play/pending-exceptions {})
  (reset! rf.story.play/stepper-state      {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ---- the registry probe ---------------------------------------------------

(def ^:private bracket-shapes
  {["re-frame.story.runtime" "capture"]          :runtime
   ["re-frame.story.frames"  "setup-capture"]    :setup
   ["re-frame.story.frames"  "teardown-capture"] :teardown})

(defn- bracket-id
  "`[shape n]` when `id` is one of the three brackets' listener ids, else nil."
  [id]
  (when (keyword? id)
    (when-let [[_ prefix n] (re-matches #"(.+)-(\d+)" (name id))]
      (when-let [shape (bracket-shapes [(namespace id) prefix])]
        [shape (Long/parseLong n)]))))

(defn- with-registry-probe
  "Run `body-fn` with the trace registry instrumented. Returns
  `{:registered [id …] :live #{id …}}` — every id registered, in order, and
  the ids still registered once `body-fn` returns. Redefining the
  `re-frame.trace.tooling` vars reaches every registration: the facade's
  `:trace` arm calls them at call time (rf2-kuky.52)."
  [body-fn]
  (let [registered (atom [])
        live       (atom #{})]
    (with-redefs [rf.trace.tooling/register-listener!
                  (fn [id f]
                    (swap! registered conj id)
                    (swap! live conj id)
                    (swap! @#'rf.trace.tooling/listeners assoc id f)
                    id)
                  rf.trace.tooling/unregister-listener!
                  (fn [id]
                    (swap! live disj id)
                    (swap! @#'rf.trace.tooling/listeners dissoc id)
                    nil)]
      (body-fn))
    {:registered @registered :live @live}))

;; ---- the pins -------------------------------------------------------------

(deftest every-bracket-registers-its-own-prefixed-id
  (testing "two run/destroy cycles of a variant that reaches all three
            brackets register exactly today's ids: runtime's capture-<n>,
            frames' setup-capture-<n> and teardown-capture-<n>, each prefix
            counting on its own, and none survives its bracket"
    (rf/reg-event :tlb/noop (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-decorator :tlb-frame-setup
      {:kind :frame-setup :init [[:tlb/noop]] :teardown [[:tlb/noop]]})
    (rf.story/reg-variant :story.tlb/v
      {:decorators       [[:tlb-frame-setup]]
       :loaders          [[:tlb/noop]]
       :loaders-teardown [[:tlb/noop]]
       :setup            [[:tlb/noop]]})
    (let [{:keys [registered live]}
          (with-registry-probe
            (fn []
              (dotimes [_ 2]
                (rf.story.async/deref-blocking (rf.story/run-variant :story.tlb/v) 5000)
                (rf.story/destroy-variant! :story.tlb/v))))
          brackets (keep bracket-id registered)
          by-shape (reduce (fn [m [shape n]] (update m shape (fnil conj []) n))
                           {}
                           brackets)]
      (is (= {:runtime 4 :setup 2 :teardown 4}
             (update-vals by-shape count))
          "per cycle: phases 1 and 2 bracket once each, the :init walk once,
           the :loaders-teardown and decorator :teardown walks once each")
      (doseq [[shape ns] by-shape]
        (is (= ns (vec (range (first ns) (+ (first ns) (count ns)))))
            (str shape " ids count on their own prefix's counter — "
                 "consecutive, never interleaved with the other two")))
      (is (not-any? (comp some? bracket-id) live)
          "every bracket unregistered its listener"))))
