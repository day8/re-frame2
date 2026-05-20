(ns re-frame.http-source-coords-test
  "Pin test for rf2-may3f: `:rf/http-interceptor-meta` source-coords
  must actually flow into the stored interceptor slot.

  rf2-gsl5v (PR #1165) promoted `rf/reg-http-interceptor` to a
  `defreg-macro` form so source-coords (`:ns` / `:line` / `:column` /
  `:file`) auto-capture at the call site per Spec 001 §Source-coordinate
  capture, and `Spec-Schemas.md` documents `:rf/http-interceptor-meta`
  as `[:merge RegistrationMetadata ...]`. Other reg-* surfaces have
  analogous coverage in `core/test/re_frame/source_coords_test.clj`;
  this file is the http-artefact sibling.

  Asserted invariants:

  1. A user-facing `rf/reg-http-interceptor` call site stamps :ns,
     :line, :column, :file on the stored slot.
  2. User-supplied `:doc` / `:tags` / `:sensitive?` flow through
     into the slot alongside the auto-captured coords.
  3. User-supplied `:ns` / `:line` overrides the auto-captured
     values (the source-coords contract per Spec 001 — explicit user
     keys win over framework auto-capture)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.http-managed :as http-managed]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http-managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  (t))

(use-fixtures :each reset-runtime)

(defn- slot-for
  "Locate the stored slot for `id` on `frame-id` in @http-managed/interceptors."
  [frame-id id]
  (->> (get @http-managed/interceptors frame-id)
       (filter #(= id (:id %)))
       first))

(deftest reg-http-interceptor-stamps-auto-captured-source-coords-rf2-may3f
  (testing "rf2-may3f — `rf/reg-http-interceptor` stamps :ns / :line /
            :column / :file from the call site into the stored slot per
            Spec 001 §Source-coordinate capture + the
            :rf/http-interceptor-meta schema."
    (rf/reg-http-interceptor :rf2-may3f/auth (fn [c] c))
    (let [slot (slot-for :rf/default :rf2-may3f/auth)]
      (is (some? slot)
          "the interceptor must actually land in the chain")
      (is (= 're-frame.http-source-coords-test (:ns slot))
          ":ns is captured as a symbol matching the call-site ns")
      (is (pos-int? (:line slot))
          ":line is a positive integer")
      (is (pos-int? (:column slot))
          ":column is a positive integer")
      (is (string? (:file slot))
          ":file is a string (the source filename)"))))

(deftest reg-http-interceptor-preserves-user-metadata-rf2-may3f
  (testing "rf2-may3f — user-supplied :doc / :tags / :sensitive? flow
            into the stored slot alongside the auto-captured source
            coords. None of the registration-metadata keys are dropped
            by the merge."
    (rf/reg-http-interceptor :rf2-may3f/with-meta
      {:doc        "auth header attacher"
       :tags       #{:auth :security}
       :sensitive? true}
      identity)
    (let [slot (slot-for :rf/default :rf2-may3f/with-meta)]
      (is (some? slot))
      (is (= "auth header attacher" (:doc slot)))
      (is (= #{:auth :security} (:tags slot)))
      (is (true? (:sensitive? slot)))
      ;; Auto-captured coords still present.
      (is (some? (:ns slot)))
      (is (pos-int? (:line slot))))))

(deftest reg-http-interceptor-user-coord-keys-override-rf2-may3f
  (testing "rf2-may3f — explicit user-supplied :ns / :line / :column /
            :file override the auto-captured values per the source-
            coords contract (Spec 001). The merge order is
            auto-capture-then-user-keys, so user keys win."
    (rf/reg-http-interceptor :rf2-may3f/forwarded
      {:ns     'app.wrappers.http-interceptor-builder
       :line   42
       :column 7
       :file   "src/app/wrappers/http.clj"}
      identity)
    (let [slot (slot-for :rf/default :rf2-may3f/forwarded)]
      (is (some? slot))
      (is (= 'app.wrappers.http-interceptor-builder (:ns slot))
          "explicit :ns wins over auto-captured")
      (is (= 42 (:line slot))
          "explicit :line wins")
      (is (= 7 (:column slot))
          "explicit :column wins")
      (is (= "src/app/wrappers/http.clj" (:file slot))
          "explicit :file wins"))))

(deftest reg-http-interceptor-frame-key-not-leaked-into-slot-rf2-may3f
  (testing "rf2-may3f — the opts :frame argument is consumed (set on the
            slot for in-chain lookup) and dissoc'd from the user-meta
            merge so it doesn't appear twice; per rf2-eyjbn :id and
            :before are positional, not part of the opts map."
    (rf/reg-http-interceptor :rf2-may3f/api-scoped
      {:frame :rf/api
       :doc   "scoped to :rf/api"}
      identity)
    (let [slot (slot-for :rf/api :rf2-may3f/api-scoped)]
      (is (some? slot)
          "the slot lands on :rf/api, not :rf/default")
      (is (= :rf/api (:frame slot))
          ":frame is stamped on the slot for in-chain lookup")
      (is (= "scoped to :rf/api" (:doc slot)))
      (is (= :rf2-may3f/api-scoped (:id slot)))
      (is (fn? (:before slot))))))
