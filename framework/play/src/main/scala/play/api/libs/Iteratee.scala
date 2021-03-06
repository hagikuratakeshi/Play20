package play.api.libs.iteratee

import play.api.libs.concurrent._

object Iteratee {

  def flatten[E, A](i: Promise[Iteratee[E, A]]): Iteratee[E, A] = new Iteratee[E, A] {

    def fold[B](done: (A, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, A]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = i.flatMap(_.fold(done, cont, error))
  }

  def fold[E, A](state: A)(f: (A, E) => A): Iteratee[E, A] =
    {
      def step(s: A)(i: Input[E]): Iteratee[E, A] = i match {

        case EOF => Done(s, EOF)
        case Empty => Cont[E, A](i => step(s)(i))
        case El(e) => { val s1 = f(s, e); Cont[E, A](i => step(s1)(i)) }
      }
      (Cont[E, A](i => step(state)(i)))
    }

  def mapChunk_[E](f: E => Unit): Iteratee[E, Unit] = fold[E, Unit](())((_, e) => f(e))

}

trait Input[+E] {
  def map[U](f: (E => U)): Input[U] = this match {
    case El(e) => El(f(e))
    case Empty => Empty
    case EOF => EOF
  }
}

case class El[E](e: E) extends Input[E]
case object Empty extends Input[Nothing]
case object EOF extends Input[Nothing]

trait Iteratee[E, +A] {
  self =>
  def run[AA >: A]: Promise[AA] = fold((a, _) => Promise.pure(a),
    k => k(EOF).fold((a1, _) => Promise.pure(a1),
      _ => error("diverging iteratee after EOF"),
      (msg, e) => error(msg)),
    (msg, e) => error(msg))

  def fold[B](done: (A, Input[E]) => Promise[B],
    cont: (Input[E] => Iteratee[E, A]) => Promise[B],
    error: (String, Input[E]) => Promise[B]): Promise[B]

  def mapDone[B](f: A => B): Iteratee[E, B] =
    Iteratee.flatten(this.fold((a, e) => Promise.pure(Done(f(a), e)),
      k => Promise.pure(Cont((in: Input[E]) => k(in).mapDone(f))),
      (err, e) => Promise.pure[Iteratee[E, B]](Error(err, e))))

  def flatMap[B](f: A => Iteratee[E, B]): Iteratee[E, B] = new Iteratee[E, B] {

    def fold[C](done: (B, Input[E]) => Promise[C],
      cont: (Input[E] => Iteratee[E, B]) => Promise[C],
      error: (String, Input[E]) => Promise[C]) =

      self.fold({
        case (a, Empty) => f(a).fold(done, cont, error)
        case (a, e) => f(a).fold((a, _) => done(a, e),
          k => cont(k),
          error)
      },
        ((k) => cont(e => (k(e).flatMap(f)))),
        error)

  }

}

object Done {
  def apply[E, A](a: A, e: Input[E]): Iteratee[E, A] = new Iteratee[E, A] {
    def fold[B](done: (A, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, A]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = done(a, e)

  }

}

object Cont {
  def apply[E, A](k: Input[E] => Iteratee[E, A]): Iteratee[E, A] = new Iteratee[E, A] {
    def fold[B](done: (A, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, A]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = cont(k)

  }
}
object Error {
  def apply[E](msg: String, e: Input[E]): Iteratee[E, Nothing] = new Iteratee[E, Nothing] {
    def fold[B](done: (Nothing, Input[E]) => Promise[B],
      cont: (Input[E] => Iteratee[E, Nothing]) => Promise[B],
      error: (String, Input[E]) => Promise[B]): Promise[B] = error(msg, e)

  }
}

trait Enumerator[+E] {

  parent =>
  def apply[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]]
  def <<:[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]] = apply(i)

  def andThen[F >: E](e: Enumerator[F]): Enumerator[F] = new Enumerator[F] {
    def apply[A, FF >: F](i: Iteratee[FF, A]): Promise[Iteratee[FF, A]] = parent.apply(i).flatMap(e.apply) //bad implementation, should remove EOF in the end of first
  }

  def map[U](f: Input[E] => Input[U]) = new Enumerator[U] {
    def apply[A, UU >: U](it: Iteratee[UU, A]) = {

      case object OuterEOF extends Input[Nothing]
      type R = Iteratee[E, Iteratee[UU, A]]

      def step(ri: Iteratee[UU, A])(in: Input[E]): R =

        in match {
          case OuterEOF => Done(ri, EOF)
          case any =>
            Iteratee.flatten(
              ri.fold((a, _) => Promise.pure(Done(ri, any)),
                k => {
                  val next = k(f(any))
                  next.fold((a, _) => Promise.pure(Done(next, in)),
                    _ => Promise.pure(Cont(step(next))),
                    (msg, _) => Promise.pure[R](Error(msg, in)))
                },
                (msg, _) => Promise.pure[R](Error(msg, any))))
        }

      parent.apply(Cont(step(it)))
        .flatMap(_.fold((a, _) => Promise.pure(a),
          k => k(OuterEOF).fold(
            (a1, _) => Promise.pure(a1),
            _ => error("diverging iteratee after EOF"),
            (msg, e) => error(msg)),
          (msg, e) => error(msg)))
    }
  }

}

object Enumerator {

  def enumInput[E](e: Input[E]) = new Enumerator[E] {
    def apply[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]] =
      i.fold((a, e) => Promise.pure(i),
        k => Promise.pure(k(e)),
        (_, _) => Promise.pure(i))

  }

  def empty[A] = enumInput[A](EOF)

  def apply[E](in: E*): Enumerator[E] = new Enumerator[E] {

    def apply[A, EE >: E](i: Iteratee[EE, A]): Promise[Iteratee[EE, A]] = enumerate(in, i)

  }
  def enumerate[E, A]: (Seq[E], Iteratee[E, A]) => Promise[Iteratee[E, A]] = { (l, i) =>
    l.foldLeft(Promise.pure(i))((i, e) =>
      i.flatMap(_.fold((_, _) => i,
        k => Promise.pure(k(El(e))),
        (_, _) => i)))
  }
}
