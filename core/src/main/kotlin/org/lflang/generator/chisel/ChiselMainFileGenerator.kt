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
            |import fpgatidbits.PlatformWrapper.VerilatedTesterWrapper
            |import fpgatidbits.TidbitsMakeUtils._
            |object LfMain {
            |  def main(args: Array[String]): Unit = {
            |    val targetDir = if (args.length == 1) args(0) else "build"
            |    val binDir = "${fileConfig.binPath}"
            |    val mainReactorFunc = () => new lf.${mainReactorName}.${mainReactorName}()
            |    val mainReactorSwIOFunc = () => new lf.${mainReactorName}.${mainReactorName}SwIO()
            |    println("------------------------------------------------------------------------------------------------------")
            |    println("Running Chisel compiler to generate verilator project")
            |    println("------------------------------------------------------------------------------------------------------")
            |    implicit val globalConfig = GlobalReactorConfig(timeout = ${timeOut})
            |    val chiselArgs = Array("--target-dir", s"${"$"}targetDir")
            |    (new chisel3.stage.ChiselStage).emitVerilog(new VerilatedTesterWrapper(_ => new CodesignTopReactor(mainReactorFunc, mainReactorSwIOFunc), targetDir),chiselArgs)
            |    // Copy main cpp file for emulation
            |    resourceCopyBulk("simulator/codesign", targetDir, Seq("main.cpp", "Makefile"))
            |    println("------------------------------------------------------------------------------------------------------")
            |    println("Building verilator project")
            |    println("------------------------------------------------------------------------------------------------------")
            |    val result = Process("make lib", new java.io.File(targetDir)).!
            |    println("------------------------------------------------------------------------------------------------------")
            |    println(s"Library produced to ${"$"}targetDir/lfFPGA.a.")
            |    println("------------------------------------------------------------------------------------------------------")
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
            |object LfMain {
            |  def main(args: Array[String]): Unit = {
            |    val targetDir = "build"
            |    val binDir = "${fileConfig.binPath}"
            |    val mainReactorFunc = () => new lf.${mainReactor.name}.${mainReactor.name}()
            |    implicit val globalConfig = GlobalReactorConfig(timeout = ${timeOut})
            |    if (args.length == 1 && args.last.equals("characterize")) {
            |       CharacterizeUtils.standalone(targetDir, () => new StandaloneTopReactor((mainReactorFunc)))
            |       return
            |    }
            |    println("------------------------------------------------------------------------------------------------------")
            |    println("Running Chisel compiler to generate verilator project")
            |    println("------------------------------------------------------------------------------------------------------")
            |    val chiselArgs = Array("--target-dir", s"${"$"}targetDir/tmp")
            |    val verilog = (new chisel3.stage.ChiselStage).emitVerilog(new StandaloneTopReactor((mainReactorFunc)),chiselArgs)
            |    val saveLocation = targetDir + "/ReactorChisel.v"
            |    Settings.writeVerilogToFile(verilog, saveLocation)
            |    println(s"Wrote the generated verilog to `${"$"}saveLocation`")
            |    // Copy main cpp file for emulation
            |    fpgatidbits.TidbitsMakeUtils.fileCopy("reactor-chisel/src/main/resources/simulator/standalone/main.cpp", targetDir + "/main.cpp")
            |    fpgatidbits.TidbitsMakeUtils.fileCopy("reactor-chisel/src/main/resources/simulator/standalone/Makefile", targetDir + "/Makefile")
            |    println("------------------------------------------------------------------------------------------------------")
            |    println("Building verilator project")
            |    println("------------------------------------------------------------------------------------------------------")
            |    val result = Process("make", new java.io.File(targetDir)).!
            |    println("------------------------------------------------------------------------------------------------------")
            |    println("Copying executable into bin dir. ")
            |    println("------------------------------------------------------------------------------------------------------")
            |    if (!Files.exists(Paths.get(binDir))) {
            |      Files.createDirectories(Paths.get(binDir))
            |    }
            |    fpgatidbits.TidbitsMakeUtils.fileCopy(targetDir + "/emu", binDir + "/${mainReactor.name}")   
            |    println("------------------------------------------------------------------------------------------------------")
            |    println("To execute program run:`" + binDir + "/${mainReactor.name}`")
            |    println("------------------------------------------------------------------------------------------------------")
            |  }
            |}
            | 
        """.trimMargin()
    }
}