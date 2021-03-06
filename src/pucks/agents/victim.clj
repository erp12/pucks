(ns pucks.agents.victim
  (:use [pucks globals util]
        [pucks.agents linear]))
  
(defn victim-proposals [p]
  {:transfer (into [] (for [other (filter :mobile (:overlaps p))]
                        {:self (:id p)
                         :other (:id other)
                         :bid {:energy 0.0}
                         :ask {:energy 0.5}}))
   :acceleration 0})

(defn victim []
  (merge (linear)
         {:victim true
          :proposal-function victim-proposals
          :color [0 0 255]}))


