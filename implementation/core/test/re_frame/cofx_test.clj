(ns re-frame.cofx-test
  "Edge-case coverage for the cofx subsystem (rf2-mq9o, rf2-gey3d).

  Per Spec 002 §Effects and coeffects and Spec 009 §Error contract.
  Pre-rf2-mq9o the inject-cofx interceptor used `println` to warn on
  a missing cofx-id. This file pins the structured-trace replacement:

    1. inject-cofx against an unregistered cofx-id emits
       :rf.error/no-such-cofx (not println) and leaves the ctx
       unchanged so sibling interceptors continue.
    2. The 1-arity form carries :cofx-id and :event-id; no :cofx-value.
    3. The 2-arity form additionally carries :cofx-value.
    4. :platforms gating skips with :rf.cofx/skipped-on-platform
       (rf2-gey3d — Spec 011 §634-642 contract; mirrors reg-fx's gate)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; Framework registrations live at namespace-load time; clear-all!
  ;; wiped them. Reload so :rf/route, :rf.route/* subs and the framework
  ;; fx (e.g. :rf.fx/reg-flow) survive between tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that
  accumulates events. Tests must (rf/remove-trace-cb! id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- Missing cofx-id → :rf.error/no-such-cofx -----------------------------
;;
;; Per Spec 009 §Error contract, an `inject-cofx` interceptor referencing
;; an unregistered cofx-id emits :rf.error/no-such-cofx with :recovery
;; :no-recovery, and the ctx flows through unchanged so sibling
;; interceptors (and the handler) still run.

(deftest unknown-cofx-id-emits-structured-trace-1-arity
  (testing "the 1-arity inject-cofx form emits :rf.error/no-such-cofx
            with :cofx-id and :event-id and leaves ctx unchanged"
    (let [traces (collect-traces! ::no-cofx-1)
          fired? (atom false)]
      (rf/reg-event-fx :cofx-test/run-no-cofx
        [(rf/inject-cofx :cofx-test/never-registered)]
        (fn [_ _]
          (reset! fired? true)
          {}))
      (rf/dispatch-sync [:cofx-test/run-no-cofx])
      (rf/remove-trace-cb! ::no-cofx-1)

      (is (true? @fired?)
          "the event handler still fired — the unknown cofx did not halt the chain")

      (let [missing (filter #(= :rf.error/no-such-cofx (:operation %)) @traces)]
        (is (= 1 (count missing))
            "exactly one :rf.error/no-such-cofx trace was emitted")
        (let [t (first missing)]
          (is (= :error (:op-type t))
              ":op-type is :error per Spec 009 §Error contract")
          (is (= :rf.error/no-such-cofx (get-in t [:tags :category]))
              ":tags :category mirrors :operation per Spec 009 §Core fields")
          (is (= :cofx-test/never-registered (get-in t [:tags :cofx-id]))
              ":cofx-id identifies the offending cofx")
          (is (= :cofx-test/run-no-cofx (get-in t [:tags :event-id]))
              ":event-id carries the event-id whose interceptor chain missed")
          (is (= :no-recovery (:recovery t))
              ":recovery is :no-recovery per Spec 009 §Recovery table")
          (is (not (contains? (:tags t) :cofx-value))
              "1-arity form: no :cofx-value in the tags"))))))

(deftest unknown-cofx-id-emits-structured-trace-2-arity
  (testing "the 2-arity inject-cofx form additionally carries :cofx-value"
    (let [traces (collect-traces! ::no-cofx-2)]
      (rf/reg-event-fx :cofx-test/run-no-cofx-2
        [(rf/inject-cofx :cofx-test/also-missing {:k :payload})]
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/run-no-cofx-2])
      (rf/remove-trace-cb! ::no-cofx-2)

      (let [missing (filter #(= :rf.error/no-such-cofx (:operation %)) @traces)]
        (is (= 1 (count missing))
            "exactly one :rf.error/no-such-cofx trace was emitted")
        (let [t (first missing)]
          (is (= :cofx-test/also-missing (get-in t [:tags :cofx-id])))
          (is (= {:k :payload} (get-in t [:tags :cofx-value]))
              "2-arity form: :cofx-value carries the value arg")
          (is (= :cofx-test/run-no-cofx-2 (get-in t [:tags :event-id]))))))))

(deftest unknown-cofx-id-does-not-println
  (testing "the missing-cofx path no longer writes to *out* — the trace is the
            sole diagnostic surface (the println pre-rf2-mq9o is gone)"
    (let [out (java.io.StringWriter.)]
      (rf/reg-event-fx :cofx-test/silent-no-cofx
        [(rf/inject-cofx :cofx-test/silent-missing)]
        (fn [_ _] {}))
      (binding [*out* out]
        (rf/dispatch-sync [:cofx-test/silent-no-cofx]))
      (is (not (clojure.string/includes? (str out) "re-frame2: no cofx registered"))
          "no stray println of the legacy `re-frame2: no cofx registered` warning"))))

;; ---- :platforms gating (rf2-gey3d) ----------------------------------------
;;
;; Per Spec 011 §634-642 the `:platforms` metadata gates BOTH `reg-fx`
;; AND `reg-cofx`. A cofx registered with `:platforms #{:client}` must
;; no-op when injected on a server-side frame; the runtime emits a
;; `:rf.cofx/skipped-on-platform` trace event (warning, :recovery
;; :skipped) mirroring `:rf.fx/skipped-on-platform` (fx_test.clj
;; §platforms-gating-skips-client-only-fx-on-jvm).
;;
;; The default platform on JVM is `:server` (re-frame.interop/platform);
;; a cofx with `:platforms #{:client}` is therefore silently inert under
;; JVM tests — exactly the SSR contract for request-cofx like browser
;; locale, localStorage, navigator-info that don't make sense
;; server-side.

(deftest platforms-gating-skips-client-only-cofx-on-jvm
  (testing ":platforms #{:client} cofx is skipped on JVM (:server) and emits a trace"
    (let [traces       (collect-traces! ::cofx-plat)
          cofx-fired?  (atom false)
          event-fired? (atom false)]
      (rf/reg-cofx :cofx-test/browser-locale
        {:platforms #{:client}}
        (fn [ctx]
          (reset! cofx-fired? true)
          (assoc-in ctx [:coeffects :cofx-test/browser-locale] "en-US")))
      (rf/reg-event-fx :cofx-test/read-locale
        [(rf/inject-cofx :cofx-test/browser-locale)]
        (fn [_ _]
          (reset! event-fired? true)
          {}))
      (rf/dispatch-sync [:cofx-test/read-locale])
      (rf/remove-trace-cb! ::cofx-plat)

      (is (false? @cofx-fired?)
          "the client-only cofx handler did NOT run on the JVM (:server)")
      (is (true? @event-fired?)
          "the event handler still ran — only the injection was skipped, not the dispatch")

      (let [skip-traces (filter #(= :rf.cofx/skipped-on-platform (:operation %))
                                @traces)]
        (is (= 1 (count skip-traces))
            "exactly one :rf.cofx/skipped-on-platform trace was emitted")
        (let [t (first skip-traces)]
          (is (= :warning (:op-type t))
              ":op-type is :warning per Spec 009 §Error contract (the trace rides the same envelope as :rf.fx/skipped-on-platform)")
          (is (= :skipped (:recovery t))
              ":recovery is :skipped — the runtime declined to act")
          (is (= :cofx-test/browser-locale (get-in t [:tags :cofx-id])))
          (is (= :server (get-in t [:tags :platform]))
              ":platform carries the active platform that excluded the cofx")
          (is (= #{:client} (get-in t [:tags :registered-platforms]))
              ":registered-platforms surfaces the cofx's declared set"))))))

(deftest platforms-matching-cofx-runs-normally
  (testing ":platforms #{:server} cofx runs on JVM (:server) — no skipped-on-platform trace"
    (let [traces (collect-traces! ::cofx-plat-match)
          fired? (atom false)]
      (rf/reg-cofx :cofx-test/server-time
        {:platforms #{:server}}
        (fn [ctx]
          (reset! fired? true)
          (assoc-in ctx [:coeffects :cofx-test/server-time] "2026-05-13T00:00:00Z")))
      (rf/reg-event-fx :cofx-test/read-server-time
        [(rf/inject-cofx :cofx-test/server-time)]
        (fn [{:keys [cofx-test/server-time]} _]
          (is (= "2026-05-13T00:00:00Z" server-time)
              "the cofx-injected value flows through to the handler")
          {}))
      (rf/dispatch-sync [:cofx-test/read-server-time])
      (rf/remove-trace-cb! ::cofx-plat-match)

      (is (true? @fired?)
          "the matching-platform cofx handler ran")
      (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %)) @traces)]
        (is (empty? skips)
            "no :rf.cofx/skipped-on-platform trace when the platform matches")))))

(deftest platforms-absent-cofx-runs-unconditionally
  (testing "absent :platforms key defaults to #{:client :server} — universal (current behaviour preserved)"
    (let [traces (collect-traces! ::cofx-plat-absent)
          fired? (atom false)]
      (rf/reg-cofx :cofx-test/universal
        ;; no :platforms — default is #{:client :server}
        {:doc "Universal cofx with no :platforms key."}
        (fn [ctx]
          (reset! fired? true)
          (assoc-in ctx [:coeffects :cofx-test/universal] :ran)))
      (rf/reg-event-fx :cofx-test/read-universal
        [(rf/inject-cofx :cofx-test/universal)]
        (fn [{:keys [cofx-test/universal]} _]
          (is (= :ran universal)
              "the universal cofx was injected on JVM (:server)")
          {}))
      (rf/dispatch-sync [:cofx-test/read-universal])
      (rf/remove-trace-cb! ::cofx-plat-absent)

      (is (true? @fired?)
          "the universal cofx ran on the JVM (:server)")
      (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %)) @traces)]
        (is (empty? skips)
            "no :rf.cofx/skipped-on-platform trace when :platforms is absent")))))

(deftest platforms-gating-honours-frame-config-platform
  (testing "a per-frame :platform :server override gates a :platforms #{:client} cofx
            on a frame that would otherwise inherit the host's :client default
            — mirrors fx.cljc's :ssr-server preset path"
    (let [traces (collect-traces! ::cofx-plat-frame)
          fired? (atom false)]
      ;; Register a dedicated SSR frame with :platform :server in its config.
      ;; This mirrors the `:ssr-server` preset (frame.cljc §preset-expansion)
      ;; which sets `:platform :server` regardless of the host's
      ;; `interop/platform` marker.
      (rf/reg-frame :cofx-test/ssr-frame
        {:preset :ssr-server})
      (rf/reg-cofx :cofx-test/local-storage
        {:platforms #{:client}}
        (fn [ctx]
          (reset! fired? true)
          (assoc-in ctx [:coeffects :cofx-test/local-storage] {:token "abc"})))
      (rf/reg-event-fx :cofx-test/read-ls
        [(rf/inject-cofx :cofx-test/local-storage)]
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-ls] {:frame :cofx-test/ssr-frame})
      (rf/remove-trace-cb! ::cofx-plat-frame)

      (is (false? @fired?)
          "the client-only cofx did NOT run under the :ssr-server frame")
      (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %))
                          @traces)]
        (is (= 1 (count skips))
            "exactly one :rf.cofx/skipped-on-platform trace was emitted")
        (let [t (first skips)]
          (is (= :cofx-test/ssr-frame (get-in t [:tags :frame]))
              ":frame carries the dispatching frame id")
          (is (= :server (get-in t [:tags :platform]))
              ":platform is :server — the per-frame override took effect"))))))

(deftest platforms-gating-carries-value-on-2-arity
  (testing "the 2-arity inject-cofx form surfaces :cofx-value on the skip trace
            so consumers can see what was being injected when skipped"
    (let [traces (collect-traces! ::cofx-plat-valued)]
      (rf/reg-cofx :cofx-test/valued-client-cofx
        {:platforms #{:client}}
        (fn [ctx v]
          (assoc-in ctx [:coeffects :cofx-test/valued-client-cofx] v)))
      (rf/reg-event-fx :cofx-test/read-valued
        [(rf/inject-cofx :cofx-test/valued-client-cofx {:key :payload})]
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-valued])
      (rf/remove-trace-cb! ::cofx-plat-valued)

      (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %))
                          @traces)]
        (is (= 1 (count skips)))
        (let [t (first skips)]
          (is (= {:key :payload} (get-in t [:tags :cofx-value]))
              "2-arity form: :cofx-value carries the value arg on the skip trace"))))))
