(ns clojure.core.logic.dcg
  (:refer-clojure :exclude [reify == inc])
  (:use [clojure.core.logic minikanren prelude nonrel tabled]
        [clojure.pprint :only [pprint]]))

;; TODO: think about indexing
;; TODO: note that rest args are problematic since we add two invisible args
;; TODO: make handle-clause polymorphic, we don't want to futz around with
;;       with forcing macroexpand - to support DCG tranform on forms other than
;;       exist
;; TODO: exist? and !dcg? are odd, why can't we check w/ `sym

;; TODO: ->lcons, add groudness check
;; TODO: get rid of intermediate lvar generation between goals
;; TODO: defne, add groundness check

(defn lsym [n]
  (gensym (str "l" n "_")))

(defn !dcg? [clause]
  (and (sequential? clause)
       (let [f (first clause)]
         (and (symbol? f)
              (= (name f) "!dcg")))))
 
(defn ->lcons
  ([env [m :as c] i] (->lcons env c i false))
  ([env [m :as c] i quoted]
     (cond
      (empty? c) `(exist []
                    (== ~(env (dec i)) ~(env i)))
      :else (let [m (if quoted `(quote ~m) m)]
              `(== ~(env (dec i)) (lcons ~m ~(env i)))))))

(defn exist? [clause]
  (and (seq? clause)
       (let [f (first clause)]
         (and (symbol? f)
              (= (name f) "exist")))))

;; TODO: make tail recursive

(defn count-clauses [clauses]
  (if (exist? clauses)
    (count-clauses (drop 2 clauses))
    (reduce (fn [s c]
              (cond
               (exist? c) (+ s (count-clauses (drop 2 c)))
               (!dcg? c) s
               :else (clojure.core/inc s)))
            0 clauses)))

;; TODO: might as well make this a lazy-seq

(defn mark-clauses
  ([cs] (mark-clauses cs (atom 0)))
  ([[c & r :as cs] i]
     (cond
      (nil? (seq cs)) ()
      (exist? c) (cons `(exist ~(second c)
                          ~@(mark-clauses (drop 2 c) i))
                       (mark-clauses r i))
      (!dcg? c) (cons c (mark-clauses r i))
      :else (cons (with-meta c
                    {:index (swap! i clojure.core/inc)})
                  (mark-clauses r i)))))

;; TODO: same as above
;; combine this step with the above

(defn handle-clauses [env [c & r :as cs]]
  (cond
   (nil? (seq cs)) ()
   (exist? c) (cons `(exist ~(second c)
                       ~@(handle-clauses env (drop 2 c)))
                    (handle-clauses env r))
   (!dcg? c) (cons (second c) (handle-clauses env r))
   (vector? c) (cons (->lcons env c (-> c meta :index))
                     (handle-clauses env r))
   (and (seq? c)
        (= (first c) `quote)
        (vector? (second c))) (cons (->lcons env (second c) (-> c meta :index) true)
                                     (handle-clauses env r))
   :else (let [i (-> c meta :index)
               c (if (seq? c) c (list c))]
           (cons (concat c [(env (dec i)) (env i)])
                 (handle-clauses env r)))))

(defmacro --> [name & clauses]
  (let [r (range 1 (+ (count-clauses clauses) 2))
        lsyms (into [] (map lsym r))
        clauses (mark-clauses clauses)
        clauses (handle-clauses lsyms clauses)]
    `(defn ~name [~(first lsyms) ~(last lsyms)]
       (exist [~@(butlast (rest lsyms))]
         ~@clauses))))

(defmacro def--> [name args & clauses]
  (let [r (range 1 (+ (count-clauses clauses) 2))
        lsyms (map lsym r)
        clauses (mark-clauses clauses)
        clauses (handle-clauses lsyms clauses)]
   `(defn ~name [~@args ~(first lsyms) ~(last lsyms)]
      (exist [~@(butlast (rest lsyms))]
        ~@clauses))))

(defn handle-cclause [fsym osym cclause]
  (let [c (count-clauses cclause)
        r (range 2 (clojure.core/inc c))
        lsyms (conj (into [fsym] (map lsym r)) osym)
        clauses (mark-clauses cclause)
        clauses (handle-clauses lsyms clauses)]
    `(exist [~@(butlast (rest lsyms))]
       ~@clauses)))

(defmacro -->e [name & cclauses]
  (let [fsym (gensym "l1_")
        osym (gensym "o")]
   `(defn ~name [~fsym ~osym]
      (conde
       ~@(map list (map (partial handle-cclause fsym osym) cclauses))))))

(defmacro def-->e [name args & pcss]
  (let [fsym (gensym "l1_")
        osym (gensym "o")]
   `(defne ~name [~@args ~fsym ~osym]
      ~@(map (fn [[p & cs]]
               (list (-> p (conj '_) (conj '_))
                     (handle-cclause fsym osym cs)))
             pcss))))

(defmacro def-->a [name args & pcss]
  (let [fsym (gensym "l1_")
        osym (gensym "o")]
    `(defna ~name [~@args ~fsym ~osym]
       ~@(map (fn [[p & cs]]
                (list (-> p (conj '_) (conj '_))
                      (handle-cclause fsym osym cs)))
              pcss))))

(defmacro def-->u [name args & pcss]
    (let [fsym (gensym "l1_")
          osym (gensym "o")]
      `(defnu ~name [~@args ~fsym ~osym]
         ~@(map (fn [[p & cs]]
                  (list (-> p (conj '_) (conj '_))
                        (handle-cclause fsym osym cs)))
                pcss))))

(comment
  (-->e det
    ('[the])
    ('[a]))
  
  (-->e n
    ('[witch])
    ('[wizard]))

  (--> v '[curses])

  (--> np det n)
  (--> vp v np)
  (--> s np vp)

  ;; we can stop the dcg transform
  (--> s np (!dcg (== 1 1)) vp)

  ;; success
  (run* [q]
    (np '[the witch] []))

  ;; success
  (run* [q]
    (s '[a witch curses the wizard] []))

  (def-->e verb [v]
    ([[:v 'eats]] '[eats]))

  (def-->e noun [n]
    ([[:n 'bat]] '[bat])
    ([[:n 'cat]] '[cat]))

  (def-->e det [d]
    ([[:d 'the]] '[the])
    ([[:d 'a]] '[a]))

  (def-->e noun-phrase [n]
    ([[:np ?d ?n]] (det ?d) (noun ?n)))
  
  (def-->e verb-phrase [n]
    ([[:vp ?v ?np]] (verb ?v) (noun-phrase ?np)))

  (def-->e sentence [s]
    ([[:s ?np ?vp]] (noun-phrase ?np) (verb-phrase ?vp)))

  (run 1 [parse-tree]
    (sentence parse-tree '[the bat eats a cat] []))

  ;; ([:s [:np [:d the] [:n bat]] [:vp [:v eats] [:np [:d a] [:n cat]]]])

  ;; ~90-100ms
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e3]
       (run 1 [parse-tree]
         (sentence parse-tree '[the bat eats a cat] [])))))

  ;; parsing lisp

  (def digits (into #{} "1234567890"))
  (defn cr [c1 c2]
    (map char (range (int c1) (int c2))))
  (def alpha (into #{} (concat (cr \a \z) (cr \A \Z))))
  (def alnum (into digits (concat (cr \a \z) (cr \A \Z))))
  (def nonalnum (into #{} "+/-*><="))

  ;; creating atoms are cheap

  ;; IMPORTANT: the reason we don't need to propagate in this case is because
  ;; we have Clojure rest - that we have a function that directly corresponds
  ;; to the relation. However most relations don't have any such analog. Thus
  ;; we have to figure out some way to move information between goals.

  ;; hmm but how will the retv solution work, each goal takes substitution

  ;; the problem with atom solution is that is that we don't have any notion
  ;; of freshness, we don't know what we're looking at, whether we should
  ;; unify with it or not. is this actually a problem? A user never introduces
  ;; the atom, only the DCG syntax. That is we ourselves guarantee that it
  ;; is alway fresh. And as long as the goal succeeds it's OK to update this
  ;; value.

  ;; NON-GOAL: we don't want to have to write our code twice. While it is
  ;; nice that we get backtracking for free from core.logic, this is just
  ;; requiring too much work for common tasks.

  (-->e wso
    ([\space] wso)
    ([]))

  (def-->e digito [x]
    ([_] [x]
       (!dcg
        (project [x]
          (== (contains? digits x) true)))))

  (def-->e numo [x]
    ([[?d . ?ds]] (digito ?d) (numo ?ds))
    ([[?d]] (digito ?d)))

  (declare symro)

  ;; TODO: if we know ?a is ground don't need to unify
  ;; this case is trickier, if x is ground sequential?
  ;; NOTE: optimizing x might be unwise, we should focus on
  ;; the two invisible vars for now
  (def-->e symo [x]
    ([[?a . ?as]] [?a]
       (!dcg
        (project [?a]
          (conde
            ((== (contains? alpha ?a) true))
            ((== (contains? nonalnum ?a) true)))))
       (symro ?as)))

  (def-->e symro [x]
    ([[?a . ?as]] [?a]
       (!dcg
        (project [?a]
          (conde
            ((== (contains? alnum ?a) true))
            ((== (contains? nonalnum ?a) true)))))
       (symro ?as))
    ([[]] []))

  (declare exprso)

  ;; can we secretly create a second function that takes one more parameter?
  ;; if this parameter true? the fn returns a value instead of a goal
  ;; then we can have a cascade in a series of bindings.
  ;; we can at any point return u#
  (def-->e expro [e]
    ([[:sym ?a]] (symo ?a))
    ([[:num ?n]] (numo ?n))
    ([[:list ?list]] [\(] (exprso ?list) [\)])
    ([[:sym :quote ?q]] [\'] (expro ?q)))

  ;; FIXME: this handles trailing whitespace but not the next one
  (def-->e exprso [exs]
    ([[?e . ?es]] wso (expro ?e) wso (exprso ?es))
    ([[]] []))

  (defne exprso [exs l1 o]
    ([[?e . ?es] _ _]
       (exist [l2 l3 l4]
         (condu
           ((exist []
              (wso l1 l2)
              (expro ?e l2 l3)
              (wso l3 l4))
            (exprso ?es l4 o)))))
    ([[] _ _] (== l1 o)))

  ;; (_.0)
  (run* [q]
    (wso (vec "  ") []))

  (run* [q]
    (wso (vec "   ") q))

  ;; grows linearly with the number of spaces
  ;; 1.4s, 18spaces
  ;; 180000 characters,  going through whitespace seems pretty darn fast
  ;; 1000 characters
  ;; 60ms for 1e3
  ;; 6000ms for 1e4, 100X slower
  (let [s (take 10000 (repeat \space))]
   (dotimes [_ 10]
     (time
      (dotimes [_ 1]
        (lazy-run 1 [q] (wso s []))))))

  ;; this won't work for lists which have
  ;; vars in them, but that's not how DCGs
  ;; are used.
  (defn wso-fast
    ([l1 o]
       (conde
         ((if (lvar? l1)
            (exist [l3]
              (== l1 (lcons \space l3))
              (wso-fast l3 o))
            (cond ;; TODO: check sequential?
             (= (first l1) \space) (wso-fast (rest l1) o)
             (empty? l1) (if (instance? clojure.lang.Atom o)
                           (fn [a] (reset! o ()) a)
                           (== o ()))
             :else u#)))
         ((if (instance? clojure.lang.Atom o)
            (fn [a] (reset! o l1) a)
            (== l1 o))))))

  ;; 3ms!, this is the ideal of course
  (let [s (conj (vec (take 10000 (repeat \space))) \f)]
   (dotimes [_ 10]
     (time
      (dotimes [_ 1]
        (run 1 [q] (wso-fast s []))))))

  ;; I think I'm wrong about the output var, it doesn't introduce
  ;; a var

  (let [s (conj (vec (take 10000 (repeat \space))) \f)]
    (run 1 [q] (wso-fast s [])))

  ;; this takes no times at all
  (dotimes [_ 10]
    (time
     (dotimes [_ 1]
       (count (take 10000 (repeat \space))))))

  ;; 60ms
  (dotimes [_ 10]
    (let [s (vec " ")]
     (time
      (dotimes [_ 1e4]
        (run* [q]
          (wso s []))))))

  ;; ()
  (run* [q]
    (wso (vec " f ") []))

  ;; (\1)
  (run* [q]
    (digito q [\1] []))

  ;; ((\1 \2 \3))
  (run* [q]
    (numo q (vec "123") []))

  ;; ~170ms
  (dotimes [_ 10]
    (time
     (dotimes [_ 1e3]
       (run* [q]
         (numo q (vec "1234567890") [])))))

  (run* [q]
    (exist [x]
     (numo x (vec "1234567890") q)))

  ;; ((\a \b \c))
  (run* [q]
    (symo q (vec "abc") []))

  ;; ([:n (\1 \2 \3)])
  (run* [q]
    (expro q (vec "123") []))

  ;; ([:s (\a \b \c)])
  (run* [q]
    (expro q (vec "abc") []))

  ;; (([:list ([:sym (\+)] [:sym (\a \b \c)] [:sym (\b)] [:sym :quote [:list ([:num [\1]] [:num (\2 \3)])]])]))
  ;; TODO: doesn't handle trailing whitespace
  (run 1 [q]
    (exprso q (vec " (+ abc b '(1 23)) ") []))

  (run 1 [q]
    (exprso q (vec "((+ 1 2) '(- 3 4))") []))

  (dotimes [_ 10]
    (time
     (dotimes [_ 1e3]
       (run 1 [q]
         (exprso q (vec "((+ 1 2) '(- 3 4))") [])))))

  ;; this seems slow
  (dotimes [_ 10]
    (let [v (vec "((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4)) 
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))
                  ((+ 1 2) '(- 3 4)) ((+ 1 2) '(- 3 4))")]
     (time
      (dotimes [_ 1]
        (run 1 [q]
          (exprso q v []))))))
  )