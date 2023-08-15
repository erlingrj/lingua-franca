package org.lflang.generator.chiselcpp

import org.eclipse.emf.ecore.resource.Resource
import org.lflang.AttributeUtils.findAttributeByName
import org.lflang.Target
import org.lflang.generator.*
import org.lflang.generator.chisel.ChiselTypes
import org.lflang.lf.Instantiation
import org.lflang.lf.Reactor
import org.lflang.reactor
import org.lflang.scoping.LFGlobalScopeProvider
import java.nio.file.Path

class CodesignGenerator(
    val context: LFGeneratorContext,
    private val scopeProvider: LFGlobalScopeProvider
) : GeneratorBase(context) {

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

        // Generate LF code for each federate.
        var lf2lfCodeMapMap: MutableMap<Path, CodeMap> = HashMap()
        val hwEmitter = CodesignHwEmitter(context, fileConfig,fpgaInst,mainReactor,targetConfig, messageReporter)
        lf2lfCodeMapMap.putAll(hwEmitter.generateProject())
        hwEmitter.compile()
    }


    override fun getTarget() = Target.Codesign
    override fun getTargetTypes(): TargetTypes = ChiselTypes
}
