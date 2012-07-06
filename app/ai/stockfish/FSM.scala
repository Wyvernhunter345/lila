package lila
package ai.stockfish

import model._

import akka.actor.{ Props, Actor, ActorRef, FSM ⇒ AkkaFSM, LoggingFSM }
import scalaz.effects._

final class FSM(
  processBuilder: (String ⇒ Unit, String ⇒ Unit) ⇒ Process,
  config: Config)
    extends Actor with LoggingFSM[model.State, model.Data] {

  val process = processBuilder(out ⇒ self ! Out(out), err ⇒ self ! Err(err))

  startWith(Starting, Todo(Vector.empty))

  when(Starting) {
    case Event(Out(t), _) if t startsWith "Stockfish" ⇒ {
      process write "uci"
      stay
    }
    case Event(Out(t), data) if t contains "uciok" ⇒ {
      config.init foreach process.write
      next(data)
    }
    case Event(play: Play, data) ⇒
      stay using (data enqueue Task(play, sender))
  }
  when(Ready) {
    case Event(Out(t), _) ⇒ { log.warning(t); stay }
  }
  when(UciNewGame) {
    case Event(Out(t), data @ Doing(Task(play, _), _)) if t contains "readyok" ⇒ {
      process write play.position
      process write (play go config.moveTime)
      goto(Go)
    }
  }
  when(Go) {
    case Event(Out(t), data @ Doing(Task(_, ref), _)) if t contains "bestmove" ⇒ {
      ref ! BestMove(t.split(' ') lift 1)
      goto(Ready) using data.done
    }
  }
  whenUnhandled {
    case Event(play: Play, data) ⇒
      next(data enqueue Task(play, sender))
    case Event(Out(""), _)                               ⇒ stay
    case Event(Out(t), _) if t startsWith "id "          ⇒ stay
    case Event(Out(t), _) if t startsWith "info "        ⇒ stay
    case Event(Out(t), _) if t startsWith "option name " ⇒ stay
    case Event(Err(t), _)                                ⇒ { log.error(t); stay }
  }

  def next(data: Data) = data match {
    case todo: Todo ⇒ todo.doing(
      doing ⇒ {
        config game doing.current.play foreach process.write
        process write "ucinewgame"
        process write "isready"
        goto(UciNewGame) using doing
      },
      t ⇒ goto(Ready) using t
    )
    case doing: Doing ⇒ stay using data
  }

  def onTermination() {
    process.destroy()
  }
}
