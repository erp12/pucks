;; Definitions for barbarian agents.
;;
;; Barbarians always ask for engergy from overlaps. If they have the
;; oportuntiy, they will attempt a plunder on their overlaps.

(ns pucks.agents.barbarian
    (:use [pucks globals util vec2D]
        pucks.agents.active))

(defn barbarianr-proposals [p]
  {:acceleration 1
   :rotation (relative-position->rotation 
              (+v (if (empty? (filter :darter (:sensed p)))
                    (rotation->relative-position (:rotation p)) 
                    (apply avgv (map :velocity (filter :darter (:sensed p)))))
                  (pucks.agents.swarmer/rand-direction)))
   :transfer (into [] (for [victim (filter :mobile (:overlaps p))]
                        {:self (:id p)
                         :other (:id victim)
                         :bid {}
                         :ask {:energy 0.1}}))})

(defn barbarian []
  (merge (active)
         {:swarmer true ;; has movement like a swarmer.
          :barbarian true
          :proposal-function barbarianr-proposals
          :color [255 0 0]}))

