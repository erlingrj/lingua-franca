/**
 * @author Erling R. Jellum (erling.r.jellum@ntnu.no)
 *
 * Copyright (c) 2023, The Norwegian University of Science and Technology.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.lflang.generator.chisel

import org.lflang.*
import org.lflang.generator.PrependOperator
import org.lflang.generator.cpp.name
import org.lflang.lf.*

// This "record" class duplicates a lot of info that is actuallt already there in the AST. However, I found it most convenient
// to extract the different dependencies and antiDependencies of the reaction into various sets. Particularily it is important
// to deal with the "childReactorInputs" and OUtputs differently. This is because we need to name them differently and also
// code-generate differently to bring them properly into scope. E.g
// reaction(childReactor.out) {==} should reference the `out` port with `childReactor.out`. So some tricks are needed.
class ReactionInfo {
    val portInputs = mutableListOf<Port>()
    val portExternalInputs = mutableListOf<Port>()
    val portExternalOutputs= mutableListOf<Port>()
    val portOutputs = mutableListOf<Port>()
    val timerInputs = mutableListOf<Timer>()
    val builtinInputs = mutableListOf<BuiltinTriggerRef>()
    val childReactorInputs = mutableListOf<Pair<Port, Instantiation>>()
    val childReactorOutputs = mutableListOf<Pair<Port, Instantiation>>()
    val childReactorToInputMap = mutableMapOf<Instantiation, MutableList<Port>>()
    val childReactorToOutputMap = mutableMapOf<Instantiation, MutableList<Port>>()
}

class ChiselReactionGenerator(
    private val reactor: Reactor,
) {
    val reactionInfos = mutableMapOf<Reaction, ReactionInfo>()
    private val preambles = ChiselPreambleGenerator(reactor)

    fun generateDeclarations(): String =
        reactor.reactions.joinToString(separator = "\n", postfix = "\n") {
            reactionInfos.putIfAbsent(it, ReactionInfo())
            "${generateDeclaration(it)}".trimMargin()
        }

    fun generateDefinitions(): String =
        reactor.reactions.joinToString(separator = "\n", prefix = "// Reaction definitions\n") {
            "${generateDefinition(it)}".trimMargin()
        }

    fun generatePrecedenceConstraints(): String =
        if (reactor.reactions.size > 1) {
            val builder = StringBuilder()
            builder.appendLine("// Generate precedence constraints connection between the reactions")
            for (r in reactor.reactions) {
                if (r == reactor.reactions.first())
                    builder.append(r.getInstanceName)
                else
                    builder.append(" > ${r.getInstanceName}")
            }
            builder.toString()
        } else {
            ""
        }

    private fun generateDefinition(r: Reaction): String =
        """
            val ${r.getInstanceName} = Module(new ${r.getClassName}(${generateReactionConfig(r)}))
            reactions += ${r.getInstanceName}
        """.trimIndent()

    private fun generateReactionConfig(r: Reaction): String {
        val nPrecedenceInPorts = if (r.indexInContainer >= 1) 1 else 0
        val nPrecedenceOutPorts = if (r.indexInContainer < r.containingReactor.reactions.size - 1) 1 else 0
        return "ReactionConfig(nPrecedenceIn = $nPrecedenceInPorts, nPrecedenceOut = $nPrecedenceOutPorts)"
    }

    private fun generateClassDefinition(r: Reaction): String =
        "class ${r.getClassName}(c: ReactionConfig) extends Reaction(c)"

    private fun generateInputPortFromChildIO(r: Reaction, child: Instantiation, p: Port): String {
        // Store all child reactors and the ports in a dictionary so we can bring them into scope
        // properly later on.
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()

        if (rInfo.childReactorToInputMap.containsKey(child)) {
            rInfo.childReactorToInputMap[child]?.add(p)
        } else {
            rInfo.childReactorToInputMap[child] = mutableListOf(p)
        }
        rInfo.childReactorInputs.add(Pair(p, child))

        return "val ${child.name}__${p.name} = new EventReadMaster(${p.getDataType}, ${p.getTokenType})"
    }

    private fun generateOutputPortToChildIO(r: Reaction, child: Instantiation, p: Port): String {
        // Store all child reactors and the ports in a dictionary so we can bring them into scope
        // properly later on.
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()

        if (rInfo.childReactorToOutputMap.containsKey(child)) {
            rInfo.childReactorToOutputMap[child]?.add(p)
        } else {
            rInfo.childReactorToOutputMap[child] = mutableListOf(p)
        }
        rInfo.childReactorOutputs.add(Pair(p, child))

        return "val ${child.name}__${p.name} = new EventWriteMaster(${p.getDataType}, ${p.getTokenType})"
    }

    private fun generateInputPortIO(r: Reaction, p: Port): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        rInfo.portInputs.add(p)
        return "val ${p.getName} = new EventReadMaster(${p.getDataType}, ${p.getTokenType})"
    }

    private fun generateExternalInputPortIO(r: Reaction, p: Port): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        rInfo.portExternalInputs.add(p)
        return "val ${p.getName} = Input(${p.getDataType})"
    }

    private fun generateExternalOutputPortIO(r: Reaction, p: Port): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        rInfo.portExternalOutputs.add(p)
        return "val ${p.getName} = new StateReadWriteMaster(${p.getDataType}, ${p.getTokenType})"
    }

    private fun generateOutputPortIO(r: Reaction, p: Port): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        rInfo.portOutputs.add(p)
        return "val ${p.getName} = new EventWriteMaster(${p.getDataType}, ${p.getTokenType})"
    }

    private fun generateTimerIO(r: Reaction, t: Timer): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        rInfo.timerInputs.add(t)
        return "val ${t.name} = new EventReadMaster(${t.getDataType}, ${t.getTokenType})"
    }

    private fun generateBuiltinTriggerIO(r: Reaction, t: BuiltinTriggerRef): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        rInfo.builtinInputs.add(t)

        if (t.type == BuiltinTrigger.STARTUP) {
            return "val startup = new EventReadMaster(UInt(0.W), new PureToken)"
        } else if (t.type == BuiltinTrigger.SHUTDOWN) {
            return "val shutdown = new EventReadMaster(UInt(0.W), new PureToken)"
        } else {
            require(false)
            return ""
        }
    }
    private fun getNormalInputs(inputs: List<TriggerRef>): List<VarRef> = inputs.filter{it is VarRef}.map{it as VarRef}
        .filterNot{it.container is Instantiation}
        .filter{it.variable is Port}
        .filterNot{(it.variable as Port).isExternal}

    private fun getExternalInputs(inputs: List<TriggerRef>): List<VarRef> = inputs.filter{it is VarRef}.map{it as VarRef}
        .filterNot{it.container is Instantiation}
        .filter{it.variable is Port}
        .filter{(it.variable as Port).isExternal}

    private fun getInputsFromChild(inputs: List<TriggerRef>): List<VarRef> = inputs.filter{it is VarRef}.map{it as VarRef}
        .filter{it.container is Instantiation}
        .filter{it.variable is Port}

    private fun getNormalOutputs(outputs: List<VarRef>): List<VarRef> = outputs.filterNot{it.container is Instantiation}
        .filter{it.variable is Port}
        .filterNot{(it.variable as Port).isExternal}

    private fun getExternalOutputs(outputs: List<VarRef>): List<VarRef> = outputs.filterNot{it.container is Instantiation}
        .filter{it.variable is Port}
        .filter{(it.variable as Port).isExternal}

    private fun getOutputsFromChild(inputs: List<VarRef>): List<VarRef> = inputs
        .filter{it.container is Instantiation}
        .filter{it.variable is Port}

    private fun getBuiltinInputs(inputs: List<TriggerRef>): List<BuiltinTriggerRef> = inputs.filter{it is BuiltinTriggerRef}.map{it as BuiltinTriggerRef}

    private fun generatePortTriggerIOs(r: Reaction): String =
        getNormalInputs(r.triggers).joinToString(separator = "\n", postfix = "\n") { generateInputPortIO(r, it.variable as Port)} +
        getInputsFromChild(r.triggers).joinToString(separator = "\n", prefix = "// Port Triggers \n", postfix = "\n") {
            generateInputPortFromChildIO(r, it.container as Instantiation, it.variable as Port) }

    private fun generateBuiltinTriggerIOs(r: Reaction): String =
        getBuiltinInputs(r.triggers).joinToString(separator = "\n", prefix = "// Builtin triggers\n", postfix = "\n") { generateBuiltinTriggerIO(r, it) }

    private fun generateSourceIOs(r: Reaction): String =
        getNormalInputs(r.sources).joinToString(separator = "\n", prefix = "// Port Sources \n", postfix = "\n") { generateInputPortIO(r, it.variable as Port) } +
        getInputsFromChild(r.sources).joinToString(separator = "\n", postfix = "\n") {
            generateInputPortFromChildIO(r, it.container as Instantiation, it.variable as Port)
        }

    private fun generateEffectIOs(r: Reaction): String =
        getNormalOutputs(r.effects).joinToString(separator = "\n", prefix = "// Port Effects \n", postfix = "\n") { generateOutputPortIO(r, it.variable as Port) } +
        getOutputsFromChild(r.effects).joinToString(separator = "\n", postfix = "\n") {
            generateOutputPortToChildIO(r, it.container as Instantiation, it.variable as Port)
        }

    private fun generateTimerIOs(r: Reaction): String =
        r.triggers.filter{it is VarRef}.map{it as VarRef}.map{it.variable}.filterIsInstance<Timer>().joinToString(separator = "\n", prefix = "// Timers \n", postfix = "\n") { generateTimerIO(r, it) }

    private fun generateExternalInputIOs(r: Reaction): String =
        getExternalInputs(r.sources).joinToString(separator = "\n", prefix = "// External inputs\n",postfix = "\n") { generateExternalInputPortIO(r, it.variable as Port)}

    private fun generateExternalOutputIOs(r: Reaction): String =
        getExternalOutputs(r.effects).joinToString(separator = "\n", prefix = "// External outputs\n", postfix = "\n") { generateExternalOutputPortIO(r, it.variable as Port)}

    private fun generateReactionIOClass(r: Reaction): String = with(PrependOperator) {
        """
            |// IO definition
            |class ${r.getIOClassName} extends ReactionIO {
         ${"|  "..generatePortTriggerIOs(r)}
         ${"|  "..generateSourceIOs(r)}
         ${"|  "..generateEffectIOs(r)}
         ${"|  "..generateTimerIOs(r)}
         ${"|  "..generateBuiltinTriggerIOs(r)}
         ${"|  "..generateExternalInputIOs(r)}
         ${"|  "..generateExternalOutputIOs(r)}
            |}
            |
        """.trimMargin()
    }

    private fun generateReactionIODefinition(r: Reaction): String =
        """
           val io = IO(new ${r.getIOClassName})
        """.trimIndent()

    private fun generatePortSeqs(r: Reaction): String =
        """
            ${generateTriggerSeq(r)}
            ${generateAntiDependencySeq(r)}
        """.trimIndent()

    private fun generateTriggerSeq(r: Reaction): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        return  (rInfo.portInputs).joinToString( ",", "override val triggers = Seq("){ "io.${it.name}"} +
                (rInfo.timerInputs).joinToString(separator = ",") {"io.${it.name}"} +
                (rInfo.builtinInputs).joinToString(separator = ",") {"io.${it.name}"} +
                (rInfo.childReactorInputs).joinToString(separator = ",", postfix = ")") {"io.${getChildPortName(it.second, it.first)}"}

    }

    private fun generateAntiDependencySeq(r: Reaction): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        return (rInfo.portOutputs).joinToString( ",", "override val antiDependencies = Seq("){ "io.${it.name}" } +
                (rInfo.childReactorOutputs).joinToString(separator = "\n", postfix = ")") {"io.${getChildPortName(it.second, it.first)}"}

    }

    private fun generateIOInScope(r: Reaction): String {
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        return (rInfo.portInputs + rInfo.portOutputs + rInfo.timerInputs + rInfo.portExternalInputs + rInfo.portExternalOutputs).joinToString(separator = "\n", prefix = "// Bring IO into scope \n", postfix = "\n")
            { "val ${it.name} = io.${it.name}" } +
                rInfo.builtinInputs.joinToString(separator = "\n", postfix = "\n") {"val ${it.name} = io.${it.name}"} +
            generatePortsFromChildrenInScope(r) +
            reactor.stateVars.joinToString(separator = "\n", postfix = "\n") {"val ${it.name} = stateIO.${it.name}"}
    }

    private fun generatePortsFromChildrenInScope(r: Reaction): String {
        val builder = StringBuilder()
        val rInfo = reactionInfos[r] ?: throw NoSuchElementException()
        val childReactorToPortMap = rInfo.childReactorToInputMap + rInfo.childReactorToOutputMap
        for (child in childReactorToPortMap.keys) {
            builder.appendLine("// Bring ports from child reactor `${child.name}` into scope")
            builder.appendLine("object ${child.name} {")
            for (port in childReactorToPortMap[child]!!) {
                builder.appendLine("  def ${port.name} = io.${getChildPortName(child,port)}")
            }
            builder.appendLine("}")
        }

        return builder.toString()
    }

    private fun generateReactionBody(reaction: Reaction): String {return with(PrependOperator) {
        """
            |// Function wrapping user-written reaction-body
            |def reactionBody(): Unit = {
            |   // Everything below this lines are copied directly from the user-reactions
            |   //------------------------------------------------------------------------------------
        ${" |  "..reaction.code.toText()}
            |}
        """.trimMargin() }
    }

    private fun generateStateIOClass(r: Reaction): String {
        val states = reactor.stateVars.joinToString("\n") {
            "val ${it.name} = new StateReadWriteMaster(${it.getDataType}, ${it.getTokenType})"
        }

        return """
            class StateIO extends ReactionStateIO {
                ${states}
            }
        """.trimIndent()
    }
    private fun generateStateIODefinition(r: Reaction): String =
        "val stateIO = IO(new StateIO())"

    private fun generateDeclaration(r: Reaction): String = with(PrependOperator) {
        """
            |${generateClassDefinition(r)} {
            |  // Bring the reacition API (lf_time_logical() etc) into scope
            |  import ReactionApi._
         ${"|  "..generateReactionIOClass(r)}
         ${"|  "..generateReactionIODefinition(r)}
         ${"|  "..generateStateIOClass(r)}
         ${"|  "..generateStateIODefinition(r)}
         ${"|  "..generatePortSeqs(r)}
         ${"|  "..generateIOInScope(r)}
         ${"|  "..generateReactionBody(r)}
         ${"|  "..preambles.generatePreamble()}
            |   // Finally reactionMain is called which organizes when to 
            |   // trigger the reactionBody and more.
            |   reactionMain()
            | }
            |
        """.trimIndent()
    }
}
