package org.lflang.generator.chisel

import org.lflang.MessageReporter
import org.lflang.TargetConfig
import org.lflang.lf.Reactor
import org.lflang.reactor

class ChiselMainFileGenerator(private val mainReactor: Reactor, val fileConfig: ChiselFileConfig, val targetConfig: TargetConfig, val messageReporter: MessageReporter) {


    fun generateSource(): String {
        if (targetConfig.codesign) {
            return generateSourceCodesign()
        } else {
            return generateSourceStandalone()
        }
    }

    fun generateSourceCodesign(): String {
        var timeOut = "Time.NEVER"

        if (targetConfig.timeout != null) {
            timeOut = "Time.nsec(${targetConfig.timeout.toNanoSeconds()})"
        }

        val mainReactorName = mainReactor.instantiations.first().reactor.name
        return """
            |package reactor
            |import scala.sys.process.Process
            |import java.nio.file.{Files, Paths}
            |import fpgatidbits.PlatformWrapper.ZedBoardParams
            |import fpgatidbits.TidbitsMakeUtils._
            |import reactor.util.CharacterizeUtils
            |object LfMain {
            |  def main(args: Array[String]): Unit = {
            |    val targetDir = if (args.length == 1) args(0) else "build"
            |    val mainReactorFunc = () => new lf.${mainReactorName}.${mainReactorName}()
            |    val mainReactorSwIOFunc = () => new lf.${mainReactorName}.${mainReactorName}SwIO()
            |    implicit val globalConfig = GlobalReactorConfig(timeout = ${timeOut}, standalone=false)
            |    if (args.length >= 1 && args(0).equals("characterize")) {
            |      CharacterizeUtils.codesign(() => new CodesignTopReactor(ZedBoardParams, mainReactorFunc, mainReactorSwIOFunc)(globalConfig), targetDir)
            |    } else {
            |      ReactorChisel.mainCodesign(mainReactorFunc, mainReactorSwIOFunc, targetDir, globalConfig)
            |    }
            |  }
            |}
            | 
        """.trimMargin()

    }

    fun generateSourceStandalone(): String {
        var timeOut = "Time.NEVER"
        var topReactor = "StandaloneTopReactor"
        if (targetConfig.codesign) {
            topReactor = ""
        }

        if (targetConfig.timeout != null) {
            timeOut = "Time.nsec(${targetConfig.timeout.toNanoSeconds()})"
        }
        return """
            |package reactor
            |import scala.sys.process.Process
            |import java.nio.file.{Files, Paths}
            |import reactor.util.CharacterizeUtils
            |object LfMain {
            |  def main(args: Array[String]): Unit = {
            |    val targetDir = "build"
            |    val mainReactorFunc = () => new lf.${mainReactor.name}.${mainReactor.name}()
            |    implicit val globalConfig = GlobalReactorConfig(timeout = ${timeOut}, standalone=true)
            |    if (args.length == 1 && args(0).equals("characterize")) {
            |       CharacterizeUtils.standalone(() => new StandaloneTopReactor(mainReactorFunc), targetDir)
            |    } else {
            |       ReactorChisel.mainStandalone(mainReactorFunc, targetDir, "${mainReactor.name}", globalConfig)
            |    }
            |  }
            |}
            | 
        """.trimMargin()
    }
}