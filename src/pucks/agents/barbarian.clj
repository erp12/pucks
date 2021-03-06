(ns pucks.agents.barbarian
    (:use [pucks globals util vec2D]
        pucks.agents.active pucks.agents.swarmer))

(defn barbarianr-proposals [p]
  {:acceleration 1
   :rotation (let [v (first (filter :victim (:sensed p)))]
               (if v
                 (relative-position->rotation (:position v))
                 (relative-position->rotation (:velocity p))))
   :transfer (into [] (for [victim (filter :mobile (:overlaps p))]
                        {:self (:id p)
                         :other (:id victim)
                         :bid {:energy 0.0}
                         :ask {:energy 0.1}
                         :wound 0.05}))})

(defn barbarian []
  (merge (active)
         {:barbarian true
          :proposal-function barbarianr-proposals
          :color [255 0 0]}))