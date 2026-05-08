(ns realworld.ssr-test
  "Headless test for realworld.ssr — `hydration-payload` selects the
   correct SSR-safe slice keys. Extracted from realworld/ssr.cljc under
   rf2-4v73.

   `.cljc` mirrors the source: the helper is portable and the test only
   touches portable data."
  (:require [realworld.ssr :as ssr]))

(defn hydration-payload-test []
  (let [db {:route {:id :route/home}
            :auth {:user {:username "alice"} :token "jwt"}
            :articles {:status :loaded :data [] :error nil :loaded-at 1 :attempt 1}
            :transient {:popup true}}
        payload (ssr/hydration-payload db [:div "hello"])]
    (assert (= #{:route :auth :articles}
               (set (keys (:rf/app-db payload)))))))
