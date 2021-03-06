package ammonite

import ammonite.interp.Interpreter
import ammonite.repl.{FrontEnd, Repl, ReplApiImpl, SessionApiImpl}
import ammonite.runtime.{History, Storage}
import ammonite.util._
import utest.asserts._

import scala.collection.mutable

/**
 * A test REPL which does not read from stdin or stdout files, but instead lets
 * you feed in lines or sessions programmatically and have it execute them.
 */
class TestRepl {
  var allOutput = ""
  def predef: (String, Option[ammonite.ops.Path]) = ("", None)

  val tempDir = ammonite.ops.Path(
    java.nio.file.Files.createTempDirectory("ammonite-tester")
  )

  val outBuffer = mutable.Buffer.empty[String]
  val warningBuffer = mutable.Buffer.empty[String]
  val errorBuffer = mutable.Buffer.empty[String]
  val infoBuffer = mutable.Buffer.empty[String]
  val printer = Printer(
    outBuffer.append(_),
    x => warningBuffer.append(x + Util.newLine),
    x => errorBuffer.append(x + Util.newLine),
    x => infoBuffer.append(x + Util.newLine)
  )
  val storage = new Storage.Folder(tempDir)
  val interp: Interpreter  = try {

    new Interpreter(
      printer,
      storage = storage,
      wd = ammonite.ops.pwd,
      customPredefs = Seq(
        PredefInfo(
          Name("defaultPredef"),
          ammonite.main.Defaults.replPredef + ammonite.main.Defaults.predefString,
          true,
          None
        ),
        PredefInfo(Name("testPredef"), predef._1, false, predef._2)
      ),
      extraBridges = Seq((
        "ammonite.repl.ReplBridge",
        "repl",
        new ReplApiImpl {
          def printer = ???

          def sess = sess0
          val prompt = Ref("@")
          val frontEnd = Ref[FrontEnd](null)
          def lastException: Throwable = null
          def fullHistory = storage.fullHistory()
          def history = new History(Vector())
          val colors = Ref(Colors.BlackWhite)
          def newCompiler() = interp.compilerManager.init(force = true)
          def compiler = interp.compilerManager.compiler.compiler
          def imports = interp.eval.imports.toString
          def width = 80
          def height = 80
        }
      )),
      colors = Ref(Colors.BlackWhite)

    )

  }catch{ case e: Throwable =>
    println(infoBuffer.mkString)
    println(outBuffer.mkString)
    println(warningBuffer.mkString)
    println(errorBuffer.mkString)
    throw e
  }

  val sess0 = new SessionApiImpl(interp.compilerManager.frames)

  def session(sess: String): Unit = {
    // Remove the margin from the block and break
    // it into blank-line-delimited steps
    val margin = sess.lines.filter(_.trim != "").map(_.takeWhile(_ == ' ').length).min
    // Strip margin & whitespace

    val steps = sess.replace(
      Util.newLine + margin, Util.newLine
    ).replaceAll(" *\n", "\n").split("\n\n")

    for((step, index) <- steps.zipWithIndex){
      // Break the step into the command lines, starting with @,
      // and the result lines
      val (cmdLines, resultLines) =
        step.lines.toArray.map(_.drop(margin)).partition(_.startsWith("@"))

      val commandText = cmdLines.map(_.stripPrefix("@ ")).toVector

      println(cmdLines.mkString(Util.newLine))
      // Make sure all non-empty, non-complete command-line-fragments
      // are considered incomplete during the parse
      //
      // ...except for the empty 0-line fragment, and the entire fragment,
      // both of which are complete.
      for (incomplete <- commandText.inits.toSeq.drop(1).dropRight(1)){
        assert(ammonite.interp.Parsers.split(incomplete.mkString(Util.newLine)).isEmpty)
      }

      // Finally, actually run the complete command text through the
      // interpreter and make sure the output is what we expect
      val expected = resultLines.mkString(Util.newLine).trim
      allOutput += commandText.map(Util.newLine + "@ " + _).mkString(Util.newLine)

      val (processed, out, warning, error, info) = run(commandText.mkString(Util.newLine), index)
      interp.handleOutput(processed)

      if (expected.startsWith("error: ")) {
        val strippedExpected = expected.stripPrefix("error: ")
        assert(error.contains(strippedExpected))

      }else if (expected.startsWith("warning: ")){
        val strippedExpected = expected.stripPrefix("warning: ")
        assert(warning.contains(strippedExpected))

      }else if (expected.startsWith("info: ")){
        val strippedExpected = expected.stripPrefix("info: ")
        assert(info.contains(strippedExpected))

      }else if (expected == "") {
        processed match{
          case Res.Success(_) => // do nothing
          case Res.Skip => // do nothing
          case _: Res.Failing =>
            assert{
              identity(error)
              identity(warning)
              identity(out)
              identity(info)
              false
            }
        }

      }else {
        processed match {
          case Res.Success(str) =>
            // Strip trailing whitespace
            def normalize(s: String) =
              s.lines
                .map(_.replaceAll(" *$", ""))
                .mkString(Util.newLine)
                .trim()
            failLoudly(
              assert{
                identity(error)
                identity(warning)
                identity(info)
                normalize(out) == normalize(expected)
              }
            )

          case Res.Failure(failureMsg) =>
            assert{
              identity(error)
              identity(warning)
              identity(out)
              identity(info)
              identity(expected)
              false
            }
          case Res.Exception(ex, failureMsg) =>
            val trace = Repl.showException(
              ex, fansi.Attrs.Empty, fansi.Attrs.Empty, fansi.Attrs.Empty
            ) + Util.newLine +  failureMsg
            assert({identity(trace); identity(expected); false})
          case _ => throw new Exception(
            s"Printed $out does not match what was expected: $expected"
          )
        }
      }
    }
  }



  def run(input: String, index: Int) = {

    outBuffer.clear()
    warningBuffer.clear()
    errorBuffer.clear()
    infoBuffer.clear()
    val splitted = ammonite.interp.Parsers.split(input).get.get.value
    val processed = interp.processLine(
      input,
      splitted
    )
    processed match{
      case Res.Failure(s) => printer.error(s)
      case Res.Exception(throwable, msg) =>
        printer.error(
          Repl.showException(throwable, fansi.Attrs.Empty, fansi.Attrs.Empty, fansi.Attrs.Empty)
        )

      case _ =>
    }
    interp.handleOutput(processed)
    (
      processed,
      outBuffer.mkString,
      warningBuffer.mkString,
      errorBuffer.mkString,
      infoBuffer.mkString
    )
  }


  def fail(input: String,
           failureCheck: String => Boolean = _ => true) = {
    val (processed, out, warning, error, info) = run(input, 0)

    processed match{
      case Res.Success(v) => assert({identity(v); identity(allOutput); false})
      case Res.Failure(s) =>
        failLoudly(assert(failureCheck(s)))
      case Res.Exception(ex, s) =>
        val msg = Repl.showException(
          ex, fansi.Attrs.Empty, fansi.Attrs.Empty, fansi.Attrs.Empty
        ) + Util.newLine + s
        failLoudly(assert(failureCheck(msg)))
      case _ => ???
    }
  }


  def result(input: String, expected: Res[Evaluated]) = {
    val (processed, allOut, warning, error, info) = run(input, 0)
    assert(processed == expected)
  }
  def failLoudly[T](t: => T) =
    try t
    catch{ case e: utest.AssertionError =>
      println("FAILURE TRACE" + Util.newLine + allOutput)
      throw e
    }

}
