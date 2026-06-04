(ns realworld.ssr-test
  "Headless test for realworld.ssr — `hydration-payload` selects the
   correct SSR-safe slice keys.

   `.cljc` mirrors the source: the helper is portable and the test only
   touches portable data."
  (:require [realworld.ssr :as ssr]))

(defn hydration-payload-test []
  (let [db {:rf/runtime {:routing {:current {:id :realworld/home}}}
            :auth {:user {:username "alice"} :token "jwt"}
            :articles {:status :loaded :data [] :error nil :loaded-at 1 :attempt 1}
            :transient {:popup true}}
        payload (ssr/hydration-payload db [:div "hello"])
        exported-auth (get-in payload [:rf/app-db :auth])]
    (assert (= #{:rf/runtime :auth :articles}
               (set (keys (:rf/app-db payload)))))
    ;; rf2-ygh4m ITEM 7 — the bearer JWT must NOT cross the SSR seam.
    ;; The :auth slice still rides along (the client needs :user), but
    ;; :token is redacted at the payload boundary (ssr/exportable-db);
    ;; the client re-derives it from localStorage on hydrate.
    (assert (= {:username "alice"} (:user exported-auth))
            "the :auth :user payload survives hydration")
    (assert (not (contains? exported-auth :token))
            "the JWT must be redacted from the SSR hydration payload")))
