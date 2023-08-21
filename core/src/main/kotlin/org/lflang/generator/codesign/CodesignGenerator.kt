package org.lflang.generator.codesign

import org.eclipse.emf.ecore.resource.Resource
import org.lflang.AttributeUtils.findAttributeByName
import org.lflang.Target
import org.lflang.ast.CodesignFpgaWrapperTransformation
import org.lflang.generator.*
import org.lflang.generator.chisel.ChiselTypes
import org.lflang.lf.Reactor
import org.lflang.reactor
import org.lflang.scoping.LFGlobalScopeProvider
import java.nio.file.Files
import java.nio.file.Path

class CodesignGenerator(
    val context: LFGeneratorContext,
    private val scopeProvider: LFGlobalScopeProvider
) : GeneratorBase(context) {

    val swAstTransformation = CodesignFpgaWrapperTransformation(context.fileConfig.resource, messageReporter);
    val fileConfig: CodesignFileConfig = context.fileConfig as CodesignFileConfig

    override fun doGenerate(resource: Resource, context: LFGeneratorContext) {
        super.doGenerate(resource, context)
        if (!GeneratorUtils.canGenerate(errorsOccurred(), mainDef, messageReporter, context)) return

        resource


        // Find the FPGA reactor
        var mainReactor: Reactor = mainDef.reactor
        val fpgaInst = mainReactor.instantiations.filter { findAttributeByName(it, "fpga") != null}.getOrNull(0)

        if (fpgaInst == null) {
            messageReporter.nowhere().error("Could not find @fpga reactor in main");
            return
        }

        // Generate LF code for the HW
        var lf2lfCodeMapMapHw: MutableMap<Path, CodeMap> = HashMap()
        val hwEmitter = CodesignHwEmitter(context, fileConfig,fpgaInst,mainReactor,targetConfig, messageReporter)
        lf2lfCodeMapMapHw.putAll(hwEmitter.generateProject())
        hwEmitter.compile()


        // Do AST here, after we have generated the HW project
        swAstTransformation.applyTransformation(reactors)
        setReactorsAndInstantiationGraph(context.getMode())

        // Generate LF code for the SW
        var lf2lfCodeMapMapSw: MutableMap<Path, CodeMap> = HashMap()
        val swEmitter = CodesignSwEmitter(context, fileConfig,reactors,targetConfig, messageReporter)
        lf2lfCodeMapMapSw.putAll(swEmitter.generateProject())
        swEmitter.compile()

        // Copy the produced binary to bin directory
        if (!Files.exists(fileConfig.binPath)) {
            Files.createDirectories(fileConfig.binPath)
        }
        Files.copy(fileConfig.codesignGenPath.resolve("bin/_SwTop"), fileConfig.binPath.resolve(mainReactor.name))
    }


    override fun getTarget() = Target.Codesign
    override fun getTargetTypes(): TargetTypes = ChiselTypes
}
