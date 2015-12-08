package fs2.async.mutable

import fs2._
import fs2.async.AsyncExt.Change

import fs2.async.{immutable, AsyncExt}



/**
 * Asynchronous queue interface. Operations are all nonblocking in their
 * implementations, but may be 'semantically' blocking. For instance,
 * a queue may have a bound on its size, in which case enqueuing may
 * block until there is an offsetting dequeue.
 */
trait Queue[F[_],A] {


  /**
   * Enqueue one element in this `Queue`.
   *
   * Evaluates to `false` if the queue is full, indicating the `a` was not queued up.
   * Evaluates to `true` if the `a` was queued up successfully.
   *
   * @param a `A` to enqueue
   */
  def enqueueOne(a: A): F[Boolean]

  /**
   * Provides a process that dequeue from this queue.
   * When multiple consumers dequeue from this queue,
   * they dequeue in first-come, first-serve order.
   *
   * Please use `Topic` instead of `Queue` when all subscribers
   * need to see each value enqueued.
   *
   * This process is equivalent to `dequeueBatch(1)`.
   */
  def dequeue: Stream[F, A]

  /**
   * The time-varying size of this `Queue`. This signal refreshes
   * only when size changes. Offsetting enqueues and de-queues may
   * not result in refreshes.
   */
  def size: Stream[F,immutable.Signal[F,Int]]

  /**
   * The size bound on the queue.
   * Returns None if the queue is unbounded.
   */
  def upperBound: Option[Int]

  /**
   * Returns the available number of entries in the queue.
   * Always returns `Int.MaxValue` when the queue is unbounded.
   */
  def available: Stream[F,immutable.Signal[F,Int]]

  /**
   * Returns `true` when the queue has reached its upper size bound.
   * Always returns `false` when the queue is unbounded.
   */
  def full: Stream[F,immutable.Signal[F,Boolean]]




}

object Queue {

    private type DeQueue[F[_],A] = (A => F[Unit])


  /**
    * Internal state of the queue
    * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
    * @param deq      A list of De-Queuer (filled up when queue is empty)
    * @tparam F
    * @tparam A
    */
    private case class State[F[_],A](
      queue: Vector[A]
      , deq:Vector[DeQueue[F,A]]
    )


    def apply[F[_],A](bound:Int = 0)(implicit F:AsyncExt[F]):Stream[F,Queue[F,A]] = {
      Signal(0).flatMap { szSignal =>
        Stream.eval {
          F.bind(F.ref[State[F,A]]) { qref =>
            F.map(F.set(qref)(F.pure(State(Vector.empty,Vector.empty)))) { _ =>

              // Signals size change of queue, if that has changed
              def signalSize(s:State[F,A], ns:State[F,A]) : F[Unit] = {
                if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
                else F.pure(())
              }

              // enqueueAll implementation
              // tries to satisfy or waiting dequeuers on this enqueue and/or attaches the remainder to the queue
              // also updates size of the queue.
              def _enqueueOne(a: A): F[Boolean] = {
                F.bind(F.ref[Boolean]) { enqRef =>
                  F.bind(F.modify(qref){ s =>
                    if (bound >= 0 && s.queue.size -1 >= bound) F.map(F.setPure(enqRef)(false)){ _ => s}
                    else {
                      s.deq headOption match {
                        case None =>
                          F.map(F.setPure(enqRef)(true)){ _ => s.copy(queue = s.queue :+ a ) }
                        case Some(deq) =>
                          F.bind(deq(a)){_ =>
                            F.map(F.setPure(enqRef)(true)) { _ => s }
                          }
                      }
                    }
                  }){ case Change(s,ns) =>
                    F.bind(signalSize(s,ns)) { _ => F.get(enqRef)}
                  }
                }
              }

              // implementation of dequeueBatch
              // repeatedly dequeue from the queue, while completing any waiting enqueue on bounded queue
              // and taking the chunks up to `limit` that are available in queue
              def _dequeue: Stream[F, A] = {
                val dequeF:F[A] = {
                  F.bind(F.ref[A]) { deqRef =>
                    F.bind(F.modify(qref) { s =>
                      s.queue.headOption match {
                        case Some(a) => F.map(F.setPure(deqRef)(a)){ _ => s.copy(queue = s.queue.tail)}
                        case None => F.pure(s.copy(deq = s.deq :+ (F.setPure(deqRef)(_))))
                      }
                    }) { case Change(s,ns) =>
                      F.bind(signalSize(s,ns)) { _ => F.get(deqRef)}
                    }
                  }
                }
                Stream.eval(dequeF) ++ _dequeue
              }

              new Queue[F,A] {
                lazy val upperBound: Option[Int] = if (bound <= 0) None else Some(bound)

                def enqueueOne(a: A): F[Boolean] = _enqueueOne(a)
                def dequeue: Stream[F, A] = _dequeue
                def size: Stream[F,immutable.Signal[F, Int]] = Stream(szSignal)
                def full: Stream[F,immutable.Signal[F, Boolean]] =
                  upperBound match {
                    case None => Signal[F,Boolean](false)
                    case Some(limit) => Stream(szSignal.map(_ >= limit))
                  }
                def available: Stream[F,immutable.Signal[F, Int]] = {
                  upperBound match {
                    case None => Signal[F,Int](Int.MaxValue)
                    case Some(limit) => Stream(szSignal.map(sz => (limit - sz) max 0))
                  }
                }
              }


            }
          }
        }

      }

    }



}