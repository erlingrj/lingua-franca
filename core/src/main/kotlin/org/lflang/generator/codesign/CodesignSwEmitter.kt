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
import org.lflang.lf.Model
import org.lflang.lf.Reactor
import java.lang.Boolean
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.String

class CodesignSwEmitter(
    val context: LFGeneratorContext,
    val fileConfig: CodesignFileConfig,
    val reactors: List<Reactor>,
    var targetConfig: TargetConfig,
    val messageReporter: MessageReporter,
){

    val main = reactors.filter {it.isMain}.first();

    val lfFilePath = fileConfig.swSrcPath.resolve("_SwTop.lf")
    val cmakeFilePath = fileConfig.swSrcPath.resolve("fpgaLib.cmake")

    fun generateProject(): Map<Path, CodeMap> {
        Files.createDirectories(fileConfig.swSrcPath)
        messageReporter.nowhere().info("Generating code for the Software-part")

        val hwCode = """
            |// This file is code-generated DO NOT EDIT.
            |${generateTarget()}
            |${generateImports()}
            |${generatePreamble()}
            |${generateReactors()}
        """.trimMargin()

        var ret: MutableMap<Path, CodeMap> = HashMap()
        Files.newBufferedWriter(lfFilePath).use { srcWriter ->
            val codeMap = CodeMap.fromGeneratedCode(hwCode)
            ret.put(lfFilePath, codeMap)
            srcWriter.write(codeMap.generatedCode)
        }

        val cmakeCode = """
            set(HW_SRC_GEN "${"$"}{LF_SRC_PKG_PATH}/src-gen/hardware")
            message(${"$"}{HW_SRC_GEN})
            target_include_directories(${"$"}{LF_MAIN_TARGET} PRIVATE "${"$"}{HW_SRC_GEN}/_FpgaTop/build")
            target_link_libraries(${"$"}{LF_MAIN_TARGET} "${"$"}{HW_SRC_GEN}/_FpgaTop/build/lfFPGA.a")
        """.trimIndent()
        Files.newBufferedWriter(cmakeFilePath).use { srcWriter ->
            val codeMap = CodeMap.fromGeneratedCode(cmakeCode)
            ret.put(cmakeFilePath, codeMap)
            srcWriter.write(codeMap.generatedCode)
        }

        return ret
    }

    // FIXME: Fix this mess...
    fun generateTarget(): String {
        val builder = StringBuilder()
        builder.appendLine("target Cpp {")
        builder.appendLine("  cmake-include: \"fpgaLib.cmake\",")
        if (targetConfig.timeout != null) {
            builder.appendLine("  timeout: ${targetConfig.timeout.time} ${targetConfig.timeout.unit.canonicalName},")
        }
        if (targetConfig.cmakeBuildType != null) {
            builder.appendLine("  build-type: ${targetConfig.cmakeBuildType.name},")
        }

        builder.appendLine("}")
        return builder.toString()
    }

    fun generatePreamble(): String =
        """
            public preamble {=
                #include "platform.h"
                #include "CodesignTopReactor.hpp"
                enum FpgaCmd {NOP=0, WRITE, READ, RESET, TERMINATE};
                // Define a chrono duration for the 32 bit signed tags used by the hardware.
                using hwReactorDuration = std::chrono::duration<std::int32_t, std::nano>;
            =}
        """.trimIndent()

    // Copy over the import statements that are used by the FPGA reactor
    fun generateImports(): String {
        val builder = StringBuilder()
        var imports = (main.eContainer() as Model).imports.toList()
        for (import in imports) {
            if (import.reactorClasses.filter{ ASTUtils.doesReactorDefReferenceOtherReactor(main,it)}.isNotEmpty()) {
                builder.appendLine("import ${import.reactorClasses.joinToString(separator = ",")} from ${import.importURI}")
            }
        }
        return builder.toString()
    }
    fun generateReactors(): String {
        var res =  reactors.joinToString(separator = "\n") {FormattingUtil.render(it, FormattingUtil.DEFAULT_LINE_LENGTH , Target.Chisel, false)}
        // Remove the named main reactors
        var mainNameRegex = """main reactor (\w+)\(""".toRegex();
        res = mainNameRegex.replace(res) {matchResult ->
            matchResult.value.replace(matchResult.groups[1]!!.value, "");
        }
        return res;
    }

    fun compile() {
        val inj = LFStandaloneSetup().createInjectorAndDoEMFRegistration()
        val rs = inj.getInstance(XtextResourceSet::class.java)
        rs.addLoadOption(XtextResource.OPTION_RESOLVE_ALL, Boolean.TRUE)
        // define output path here
        // define output path here
        val fsa = inj.getInstance(JavaIoFileSystemAccess::class.java)
        fsa.setOutputPath("DEFAULT_OUTPUT", fileConfig.swSrcGenPath.toString())
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