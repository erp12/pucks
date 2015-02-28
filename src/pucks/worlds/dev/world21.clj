(ns pucks.worlds.dev.world21  
  (:use [pucks core globals util]
        [pucks.agents nursery linear stone vent zapper swarmer darter barbarian]))

(defn agents []
  (concat (repeatedly 1 stone)
          (repeatedly 3 vent)
          (repeatedly 1 zapper)
          (repeatedly 8 barbarian)
          [(assoc (nursery darter) :position [600 600])
           (assoc (nursery darter) :position [200 200])]))


(defn settings []
  {})

;(run-pucks (agents) (settings))