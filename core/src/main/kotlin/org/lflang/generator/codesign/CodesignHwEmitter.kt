package org.lflang.generator.codesign

import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.Resource
import org.eclipse.xtext.generator.JavaIoFileSystemAccess
import org.eclipse.xtext.resource.XtextResource
import org.eclipse.xtext.resource.XtextResourceSet
import org.eclipse.xtext.util.CancelIndicator
import org.lflang.*
import org.lflang.Target
import org.lflang.ast.ASTUtils
import org.lflang.ast.FormattingUtil
import org.lflang.generator.*
import org.lflang.lf.Instantiation
import org.lflang.lf.Model
import org.lflang.lf.Reactor
import java.lang.Boolean
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.String
import kotlin.with

class CodesignHwEmitter(
    val context: LFGeneratorContext,
    val fileConfig: CodesignFileConfig,
    val inst: Instantiation,
    val main: Reactor,
    var targetConfig: TargetConfig,
    val messageReporter: MessageReporter,
){


    val lfFilePath = fileConfig.hwSrcPath.resolve("_FpgaTop.lf")
    fun generateProject(): Map<Path, CodeMap> {
        Files.createDirectories(fileConfig.hwSrcPath)
        messageReporter.nowhere().info("Generating code for HW")

        val hwCode = """
            |// This file is code-generated DO NOT EDIT.
            |${generateTarget()}
            |${generateReactors(inst.reactor)}
            |${generateMain()}
        """.trimMargin()

        var ret: MutableMap<Path, CodeMap> = HashMap()
        Files.newBufferedWriter(lfFilePath).use { srcWriter ->
            val codeMap = CodeMap.fromGeneratedCode(hwCode)
            ret.put(lfFilePath, codeMap)
            srcWriter.write(codeMap.generatedCode)
        }
        return ret
    }

    fun generateTarget(): String {
        val builder = StringBuilder()
        builder.appendLine("target Chisel {")
        builder.appendLine("  codesign: true,")
        if (targetConfig.timeout != null) {
            builder.appendLine("  timeout: ${targetConfig.timeout.time} ${targetConfig.timeout.unit.canonicalName},")
        }
        if (targetConfig.fpgaBoard != null) {
            builder.appendLine("  fpgaBoard: ${targetConfig.fpgaBoard},")
        }
        if (targetConfig.clockPeriod != null) {
            builder.appendLine("  clock-period: ${targetConfig.clockPeriod.time} ${targetConfig.clockPeriod.unit.canonicalName},")
        }
        builder.appendLine("}")
        return builder.toString()
    }

    fun generateReactors(reactor: Reactor): String {
        val builder = StringBuilder()
        for (i in reactor.instantiations) {
            builder.appendLine(generateReactors(i.reactor))
        }
        builder.appendLine(FormattingUtil.render(reactor, FormattingUtil.DEFAULT_LINE_LENGTH , Target.Chisel, false))
        return builder.toString()
    }
    fun generateInstantiatedReactorDefinitions(): String {

        return FormattingUtil.render(inst.reactor, FormattingUtil.DEFAULT_LINE_LENGTH , Target.Chisel, false)
    }
    fun generateMain(): String {
        // Consider removing the @fpga attribute
        return with(PrependOperator) {
            """
            |main reactor {
         ${"|  "..FormattingUtil.render(inst, FormattingUtil.DEFAULT_LINE_LENGTH, Target.Chisel, false)}
            |}
        """.trimMargin()
        }
    }

    fun compile() {

        val inj = LFStandaloneSetup().createInjectorAndDoEMFRegistration()
        val rs = inj.getInstance(XtextResourceSet::class.java)
        rs.addLoadOption(XtextResource.OPTION_RESOLVE_ALL, Boolean.TRUE)
        // define output path here
        // define output path here
        val fsa = inj.getInstance(JavaIoFileSystemAccess::class.java)
        fsa.setOutputPath("DEFAULT_OUTPUT", fileConfig.hwSrcGenPath.toString())
        val res: Resource = rs.getResource(
            URI.createFileURI(
                lfFilePath.toString()
            ),
            true
        )
        val ctx = MainContext(
                LFGeneratorContext.Mode.STANDALONE,
                res,
                fsa,
                CancelIndicator.NullImpl)

        inj.getInstance<LFGenerator>(LFGenerator::class.java).doGenerate(res, fsa, ctx)
    }
}