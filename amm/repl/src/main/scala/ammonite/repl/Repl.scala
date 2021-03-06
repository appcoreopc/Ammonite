package ammonite.repl

import java.io.{InputStream, InputStreamReader, OutputStream}

import ammonite.runtime._
import ammonite.terminal.Filter
import ammonite.util.Util.newLine
import ammonite.util._
import ammonite.interp.Interpreter

import scala.annotation.tailrec

class Repl(input: InputStream,
           output: OutputStream,
           info: OutputStream,
           error: OutputStream,
           storage: Storage,
           defaultPredef: String,
           mainPredef: String,
           wd: ammonite.ops.Path,
           welcomeBanner: Option[String],
           replArgs: IndexedSeq[Bind[_]] = Vector.empty,
           initialColors: Colors = Colors.Default,
           remoteLogger: Option[RemoteLogger]) { repl =>

  val prompt = Ref("@ ")

  val frontEnd = Ref[FrontEnd](AmmoniteFrontEnd(Filter.empty))

  var lastException: Throwable = null

  var history = new History(Vector())

  val (colors, printStream, errorPrintStream, printer) =
    Interpreter.initPrinters(initialColors, output, info, error, true)

  val argString = replArgs.zipWithIndex.map{ case (b, idx) =>
    s"""
    val ${b.name} =
      ammonite.repl.ReplBridge.value.replArgs($idx).value.asInstanceOf[${b.typeTag.tpe}]
    """
  }.mkString(newLine)

  val sess0 = new SessionApiImpl(interp.compilerManager.frames)

  val interp: Interpreter = new Interpreter(
    printer,
    storage,
    Seq(
      PredefInfo(Name("DefaultPredef"), defaultPredef, true, None),
      PredefInfo(Name("ArgsPredef"), argString, false, None),
      PredefInfo(Name("MainPredef"), mainPredef, false, Some(wd))
    ),
    Seq((
      "ammonite.repl.ReplBridge",
      "repl",
      new ReplApiImpl {
        def printer = repl.printer
        val colors = repl.colors
        def sess = repl.sess0
        val prompt = repl.prompt
        val frontEnd = repl.frontEnd

        def lastException = repl.lastException
        def fullHistory = storage.fullHistory()
        def history = repl.history
        def newCompiler() = interp.compilerManager.init(force = true)
        def compiler = interp.compilerManager.compiler.compiler
        def imports = interp.eval.imports.toString
        def width = frontEnd().width
        def height = frontEnd().height
      }
    )),
    wd,
    colors
  )

  sess0.save()

  val reader = new InputStreamReader(input)

  def action() = for{
    _ <- Catching {
      case Ex(e: ThreadDeath) =>
        Thread.interrupted()
        Res.Failure("Interrupted!")

      case ex => Res.Exception(ex, "")
    }

    (code, stmts) <- frontEnd().action(
      input,
      reader,
      output,
      colors().prompt()(prompt()).render,
      colors(),
      interp.compilerManager.complete(_, interp.eval.imports.toString, _),
      storage.fullHistory(),
      addHistory = (code) => if (code != "") {
        storage.fullHistory() = storage.fullHistory() :+ code
        history = history :+ code
      }
    )
    _ <- Signaller("INT") {
      // Put a fake `ThreadDeath` error in `lastException`, because `Thread#stop`
      // raises an error with the stack trace of *this interrupt thread*, rather
      // than the stack trace of *the mainThread*
      lastException = new ThreadDeath()
      lastException.setStackTrace(Repl.truncateStackTrace(interp.mainThread.getStackTrace))
      interp.mainThread.stop()
    }
    out <- interp.processLine(code, stmts)
  } yield {
    printStream.println()
    out
  }

  def run(): Any = {
    welcomeBanner.foreach(printStream.println)
    @tailrec def loop(): Any = {
      val actionResult = action()
      remoteLogger.foreach(_.apply("Action"))
      interp.handleOutput(actionResult)
      Repl.handleRes(
        actionResult,
        printer.info,
        printer.error,
        lastException = _,
        colors()
      ) match{
        case None => loop()
        case Some(value) => value
      }
    }
    loop()
  }

  def beforeExit(exitValue: Any): Any = {
    Function.chain(interp.beforeExitHooks)(exitValue)
  }
}

object Repl{

  def handleRes(res: Res[Any],
                printInfo: String => Unit,
                printError: String => Unit,
                setLastException: Throwable => Unit,
                colors: Colors): Option[Any] = {
    res match{
      case Res.Exit(value) =>
        printInfo("Bye!")
        Some(value)
      case Res.Failure(msg) =>
        printError(msg)
        None
      case Res.Exception(ex, msg) =>
        setLastException(ex)
        printError(
          Repl.showException(ex, colors.error(), fansi.Attr.Reset, colors.literal())
        )
        printError(msg)
        None
      case _ =>
        None
    }
  }
  def highlightFrame(f: StackTraceElement,
                     error: fansi.Attrs,
                     highlightError: fansi.Attrs,
                     source: fansi.Attrs) = {
    val src =
      if (f.isNativeMethod) source("Native Method")
      else if (f.getFileName == null) source("Unknown Source")
      else {
        val lineSuffix =
          if (f.getLineNumber == -1) fansi.Str("")
          else error(":") ++ source(f.getLineNumber.toString)

        source(f.getFileName) ++ lineSuffix
      }

    val prefix :+ clsName = f.getClassName.split('.').toSeq
    val prefixString = prefix.map(_+'.').mkString("")
    val clsNameString = clsName //.replace("$", error("$"))
    val method =
      error(prefixString) ++ highlightError(clsNameString) ++ error(".") ++
        highlightError(f.getMethodName)

    fansi.Str(s"  ") ++ method ++ "(" ++ src ++ ")"
  }
  val cutoff = Set("$main", "evaluatorRunPrinter")
  def truncateStackTrace(x: Array[StackTraceElement]) = {
    x.takeWhile(x => !cutoff(x.getMethodName))
  }

  def showException(ex: Throwable,
                    error: fansi.Attrs,
                    highlightError: fansi.Attrs,
                    source: fansi.Attrs) = {

    val traces = Ex.unapplySeq(ex).get.map(exception =>
      error(exception.toString + newLine +
        truncateStackTrace(exception.getStackTrace)
          .map(highlightFrame(_, error, highlightError, source))
          .mkString(newLine))
    )
    traces.mkString(newLine)
  }
}
