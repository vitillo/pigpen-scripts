(ns pigpen-scripts.core
  (:require [pigpen.core :as pig]
            [clojure.data.json :as json]
            [incanter.stats :as incanter :refer [median]]))

(defn osdistribution []
  (->>
    (pig/load-tsv "input/ping")
    (pig/map (fn [[id data]]
               (let [parsed (json/read-str data :key-fn keyword)]
                 (get-in parsed [:info :OS]))))
    (pig/group-by (fn [data] data))
    (pig/map (fn [[key data]]
               [key (count data)]))))


(defn mainthreadio []
  (let [reports (->>
                  (pig/load-tsv "input/ping")
                  (pig/mapcat (fn [[id data]]
                                (let [parsed (json/read-str data :key-fn keyword)
                                      {{:keys [appName appUpdateChannel]} :info
                                       reports :fileIOReports} parsed]
                                  (mapcat (fn [[filename array]]
                                            (let [filtered (filter #(not= nil (second %))
                                                                   (zipmap ["startup" "excecution" "shutdown"] array))]
                                              (map (fn [[interval data]]
                                                     {:appName appName
                                                      :appUpdateChannel appUpdateChannel
                                                      :interval interval
                                                      :time (first data)
                                                      :ops (apply + (rest data))
                                                      :filename (name filename)})
                                                   filtered)))
                                          reports)))))
        grouped-reports (pig/group-by (juxt :appName :appUpdateChannel :interval :filename) reports)
        grouped-reports-summary (let [grouped (pig/group-by (juxt :appName :appUpdateChannel :interval) reports)]
                                  (pig/map (fn [[key values]]
                                             {:appName (get key 0)
                                              :appUpdateChannel (get key 1)
                                              :interval (get key 2)
                                              :summary-count (count values)})
                                           grouped))]
    (pig/join [(grouped-reports :on (fn [[key value]] (subvec key 0 3)))
               (grouped-reports-summary :on (juxt :appName :appUpdateChannel :interval))]
              (fn [group-entries group-summary]
                (let [[group-entries-key reports] group-entries
                      filename (last group-entries-key)
                      n-entries (count reports)
                      median-time (incanter/median (map :time reports))
                      median-ops (incanter/median (map :ops reports))]
                  (assoc group-summary
                         :n-entries n-entries
                         :median-time median-time
                         :filename filename
                         :median-ops median-ops))))))

(pig/write-script "my-script.pig" (mainthreadio))
