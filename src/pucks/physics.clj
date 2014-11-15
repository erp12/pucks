;; This is the "physics engine" for pucks, which means that it handles
;; the generation and arbitration of proposals that pucks may make to change
;; the state of the world.

(ns pucks.physics
  (:use quil.core 
        pucks.globals pucks.util pucks.vec2D pucks.agents.torpedo))

(defn generate-proposals
  "Annotates each agent with its proposals, which are generated by calling
the agent's proposal function on the agent itself, but with the agent's
neighbors and position removed."
  []
  (swap! all-agents
         (fn [pucks]
           (into [] 
                 (pmapallv ;; do this concurrently if not in single-thread-mode
                   #(assoc % :proposals 
                           ((:proposal-function %) 
                             (-> %
                               (assoc :position [0 0])
                               (assoc :neighbors []))))
                   pucks)))))

(defn colliding?
  "Returns true if self and neighbor have colliding cores."
  [self neighbor]
  (< (length (:position neighbor))
     (+ (/ (:radius self) 2)
        (/ (:radius neighbor) 2))))

(defn update-properties
  "Returns the given puck with the allowed property changes specified
in properties-proposals."
  [puck properties-proposals]
  (loop [p puck
         remaining properties-proposals]
    (if (empty? remaining)
      p
      (let [[k v] (first remaining)]
        (case k
          :solid (recur (if (:mobile p)
                          p
                          (assoc p :solid v))
                        (rest remaining))
          :color (recur (assoc p :color v)
                        (rest remaining))
          :eye-color (recur (assoc p :eye-color v)
                            (rest remaining))
          :core-color (recur (assoc p :core-color v)
                             (rest remaining)))))))

(defn acceptable 
  "Returns a truthy value if the bids are acceptable to the ask, and false
otherwise. If an ask is a map then it must match the other agent's bid exactly.
If it is not a map then it should be a function of two arguments (bids)." 
  [my-ask my-bid your-bid]
  (if (map? my-ask)
    (= my-ask your-bid)
    (my-ask my-bid your-bid)))

(defn can-afford 
  "Returns true if the puck can afford to pay the bid."
  [puck bid]
  (every? (fn [[k v]]
            (case k
              :energy (> (:energy puck) v)
              :inventory (some #{v} (:inventory puck))
              true)) ;; can afford anything else
          bid))

(defn without 
  "Returns the puck that results after paying the bid."
  [puck bid]
  (loop [remaining bid
         result puck]
    (if (empty? remaining)
      result
      (let [[k v] (first remaining)]
        (recur (rest remaining)
               (assoc result 
                      k
                      (case k
                        :energy (- (:energy result) v)
                        :inventory (remove-one v (:inventory result))
                        :promise (merge (:promise result) v)
                        :memory (:memory result)
                        nil)))))))

(defn with 
  "Returns the puck that results after being paid the bid."
  [puck bid]
  (loop [remaining bid
         result puck]
    (if (empty? remaining)
      result
      (let [[k v] (first remaining)]
        (recur (rest remaining)
               (assoc result
                      k
                      (case k
                        :energy (min 1.0 (+ (:energy result) v))
                        :inventory (conj (:inventory result) v)
                        :memory (merge (:memory result) v)
                        nil)))))))

(defn process-transfer-bid-in-agent-map
  "Takes a transfer and a map from ids to agents, and returns the map changed
to reflect the transfer (meaning that the transfer's bid is paid by the 
transfer's :self to the transfer's :other)."
  [{:keys [self other bid]} agent-map]
  (-> agent-map
    (dissoc self)
    (dissoc other)
    (assoc self (without (self agent-map) bid))
    (assoc other (with (other agent-map) bid))))

(defn all-pairs 
  "Returns a vector of all possible pairs of items from vector v."
  [v]
  (if (< (count v) 3)
    [v]
    (vec (for [item1 v item2 v :when (not= item1 item2)]
           [item1 item2]))))

(defn affect-same-agents?
  "Returns true if the two transfers affect the same agents, and false
otherwise."
  [xfer1 xfer2]
  (= #{(:self xfer1) (:other xfer1)}
     #{(:self xfer2) (:other xfer2)}))

(defn one-sided?
  "Returns true if the transfer is one-sided, meaning that no matching
transfer from another agent is required for the transfer to proceed."
  [xfer] 
  (or (and (coll? (:ask xfer)) (empty? (:ask xfer)))
      (empty? (:bid xfer))))
            
(defn arbitrate-proposals
  "Processes all of the proposals of all of the agents and makes appropriate
changes to the world."
  []
  (swap! 
    all-agents       
    (fn [agents]
      ;; First we collect and process all proposed transfers. Each 
      ;; "transaction" here is a vector one transfer or two transfers between
      ;; the same pair of agents. Transactions are processed one at a time 
      ;; in random order. Each is accepted if the source would not be 
      ;; depeleted and no other constaints would be violated.
      (let [transfers (apply concat
                             (mapv :transfer
                                   (mapv :proposals agents)))
            grouped-by-one-sided (group-by one-sided? transfers)
            transactions (loop [remaining (get grouped-by-one-sided false)
                                result (mapv vector (get grouped-by-one-sided true))]
                           (if (empty? remaining)
                             (shuffle result)
                             (recur (filter #(not (affect-same-agents? 
                                                    % (first remaining)))
                                            remaining)
                                    (concat result 
                                            (all-pairs 
                                              (filterv #(affect-same-agents? 
                                                          % (first remaining))
                                                       remaining))))))
            post-xfer-agents (loop [remaining transactions
                                    agent-map (zipmap (map :id agents) agents)]
                               (if (empty? remaining)
                                 (vec (vals agent-map))
                                 (let [transaction (first remaining)
                                       xfer1 (first transaction)
                                       self-id (:self xfer1)
                                       self (get agent-map self-id)
                                       other-id (:other xfer1)
                                       other (get agent-map other-id)]
                                   (if (:zapper self) ;; zapper
                                     (if (:mobile other)
                                       (recur (rest remaining)
                                              (assoc agent-map 
                                                     other-id 
                                                     (assoc other 
                                                            :energy 
                                                            (max 0 (- (:energy other)
                                                                      (:energy (:ask xfer1)))))))
                                       (recur (rest remaining)
                                              agent-map))
                                     (if (one-sided? xfer1)
                                       ;; vent and other one-sided
                                       (recur (rest remaining)
                                              (if (can-afford self (:bid xfer1))
                                                (-> agent-map
                                                  (dissoc self-id)
                                                  (dissoc other-id)
                                                  (assoc self-id (without self (:bid xfer1)))
                                                  (assoc other-id (with other (:bid xfer1))))
                                                agent-map))
                                       (if (empty? (rest transaction)) ;; no partner; skip
                                         (recur (rest remaining)
                                                agent-map)
                                         ;; other transactions
                                         (let [xfer2 (second transaction)]
                                           (if (and (= (:self-id xfer1) (:other-id xfer2)) ;; participants are symmetric
                                                    (= (:self-id xfer2) (:other-id xfer1))
                                                    (can-afford self (:bid xfer1))
                                                    (can-afford other (:bid xfer2))
                                                    ;; an ask can be a funcion of two bids, or it can be a map, 
                                                    ;; in which case it is satisfied if it is = to the other agent's bid
                                                    (acceptable (:ask xfer1) (:bid xfer1) (:bid xfer2))
                                                    (acceptable (:ask xfer2) (:bid xfer2) (:bid xfer1)))
                                             ;; if both asks are happy then both bids are processed
                                             ;; there may be system loss, but should not be system gain
                                             (recur (rest remaining)
                                                    (->> agent-map 
                                                      (process-transfer-bid-in-agent-map xfer1)
                                                      (process-transfer-bid-in-agent-map xfer2)))
                                             ;; otherwise
                                             (recur (rest remaining) agent-map)))))))))]
        ;; The world after all transactions have been conducted is now in post-xfer-agents.
        ;; Now we can process all other proposals for agents taken individually. 
        (vec (apply concat
                    (pmapallv ;; do this concurrently if not in single-thread mode
                              (fn [{:keys [position velocity rotation neighbors proposals mobile energy radius] :as agent}]                                
                                (let [colliding-neighbors (filter #(and (:solid %)
                                                                        (colliding? agent %))
                                                                  neighbors)
                                      proposed-a (*v (or (:acceleration proposals) 0) 
                                                     (rotation->direction rotation)) ;; vec from proposed scalar * rotation
                                      anti-collision-a (if mobile
                                                         (if (empty? colliding-neighbors)
                                                           [0 0]
                                                           (*v (:collision-resolution-acceleration @pucks-settings)
                                                               (apply avgv 
                                                                      (mapv -v (mapv :position
                                                                                     colliding-neighbors)))))
                                                         [0 0])
                                      just-collided (not (zero? (length anti-collision-a)))
                                      new-a (limit-vec2D (+v proposed-a anti-collision-a) 
                                                         (* (if just-collided 10 1)
                                                            (:max-acceleration @pucks-settings)))
                                      proposed-v (+v velocity new-a)
                                      new-v (if mobile 
                                              (limit-vec2D proposed-v 
                                                           (if just-collided
                                                             (max 0.5
                                                                  (min (/ (:max-velocity @pucks-settings) radius)
                                                                       (length (apply +v 
                                                                                      (concat [velocity]
                                                                                              (mapv :velocity
                                                                                                    colliding-neighbors))))))
                                                             (/ (:max-velocity @pucks-settings) radius)))
                                              [0 0])
                                      new-p (wrap-position (+v position new-v))
                                      proposed-r (if  (:rotation proposals) (wrap-rotation (:rotation proposals)) nil)
                                      new-r (if (and mobile proposed-r)
                                              (wrap-rotation
                                                (let [max-rotational-velocity (:max-rotational-velocity @pucks-settings)]
                                                  (cond 
                                                    ;; already there
                                                    (== proposed-r rotation) 
                                                    rotation
                                                    ;; go up normal
                                                    (and (> proposed-r rotation) 
                                                         (< (- proposed-r rotation) pi))
                                                    (+ rotation (min max-rotational-velocity
                                                                     (- proposed-r rotation)))
                                                    ;; go up to wrap
                                                    (and (< proposed-r rotation)
                                                         (> (- rotation proposed-r) pi))
                                                    (+ rotation (min max-rotational-velocity
                                                                     (+ (- pi rotation)
                                                                        (- proposed-r minus-pi))))
                                                    ;; go down normal
                                                    (< proposed-r rotation)
                                                    (- rotation (min max-rotational-velocity
                                                                     (- rotation proposed-r)))
                                                    ;; go down to wrap
                                                    :else
                                                    (- rotation (min max-rotational-velocity
                                                                     (+ (- rotation minus-pi)
                                                                        (- pi proposed-r)))))))
                                              rotation)]
                                  (concat (if (and (:spawn proposals)
                                                   (> energy (+ 0.1 (* 0.1 (count (:spawn proposals))))))
                                            (mapv (fn [proposed-puck]
                                                    (derelativize-position 
                                                      position
                                                      (merge proposed-puck
                                                             (if (:nursery agent)
                                                               {:id (gensym "puck-")
                                                                :energy 1.0
                                                                :steps 0}
                                                               {:id (gensym "puck-")
                                                                :energy 0.1
                                                                :steps 0
                                                                :memory (if (:genome (:memory proposed-puck))
                                                                          {:genome (:genome (:memory proposed-puck))}
                                                                          {})
                                                                :inventory []
                                                                :sensed []}))))
                                                  (:spawn proposals))
                                            [])
                                          (if (and (:fire-torpedo proposals)
                                                   (> energy (:torpedo-energy @pucks-settings)))
                                            [(derelativize-position 
                                               position
                                               (merge (torpedo) (let [dirxy (rotation->direction new-r)
                                                                      len (length dirxy)
                                                                      dirxy-norm (map #(/ % len) dirxy)]
                                                                  {:energy (:torpedo-energy @pucks-settings) 
                                                                   :rotation rotation 
                                                                   :velocity (*v 10 dirxy-norm)
                                                                   :position (*v 35 dirxy-norm)})))]
                                            [])
                                          [(-> agent
                                             (assoc :velocity new-v) ;; new velocity
                                             (assoc :position new-p) ;; new position
                                             (assoc :rotation new-r) ;; new rotation
                                             (assoc :energy          ;; new energy (deducting costs)
                                                    (min 1
                                                         (max 0
                                                              (- energy 
                                                                 (if mobile (:cost-of-living @pucks-settings) 0)
                                                                 (if just-collided (:cost-of-collision @pucks-settings) 0)
                                                                 (if (:vent agent) -0.005 0)
                                                                 (if (and (:fire-torpedo proposals)
                                                                          (> energy 0.1))
                                                                   0.1
                                                                   0)
                                                                 (if (and (:spawn proposals)
                                                                          (not (:nursery agent))
                                                                          (> energy (+ 0.1 (* 0.1 (count (:spawn proposals))))))
                                                                   (* 0.1 (count (:spawn proposals)))
                                                                   0)))))
                                             (assoc :just-collided just-collided) ;; store collision for GUI
                                             (assoc :memory (merge (:memory agent) 
                                                                   (:memory proposals) 
                                                                   (:promise agent)))
                                             (dissoc :promise)
                                             (update-properties (:properties proposals)))])))
                              post-xfer-agents)))))))
