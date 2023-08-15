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

import org.lflang.generator.PrependOperator
import org.lflang.lf.*
import java.lang.StringBuilder

class ChiselPortGenerator(private val reactor: Reactor, private val connectionGenerator: ChiselConnectionGenerator) {
    private val inputs = reactor.inputs.filterNot{it.isExternal}
    private val outputs = reactor.outputs.filterNot {it.isExternal}
    private val externalInputs = reactor.inputs.filter {it.isExternal}
    private val externalOutputs= reactor.outputs.filter {it.isExternal}

    private fun generateInputPortDeclaration(p: Input) =
        if (p.getTriggeredReactions.size > 0) {
            """
                val ${p.name} = Module(new InputPort(InputPortConfig(${p.getDataType}, ${p.getTokenType}, ${p.getTriggeredReactions.size})))
                inPorts += ${p.name}
            """.trimIndent()
        } else {
            ""
        }

    private fun generateOutputPortDeclaration(p: Output): String {
        val numWriters = p.getWritingReactions.size + p.getWritingReactors.size
        if (numWriters > 0) {
            return """
                val ${p.name} = Module(new OutputPort(OutputPortConfig(${p.getDataType}, ${p.getTokenType}, $numWriters)))
                outPorts += ${p.name}     
            """.trimIndent()
        }
        return ""
    }

    fun generateDeclarations() = with(PrependOperator) {
        inputs.joinToString("\n", "// input ports\n", postfix = "\n") { generateInputPortDeclaration(it) } +
        outputs.joinToString("\n", "// output ports\n", postfix = "\n") { generateOutputPortDeclaration(it)} +
        generateExternalIO()
    }

    fun generateConnections() =
        inputs.joinToString("\n", "// input ports\n", postfix = "\n") { generateInputPortConnection(it as Input) } +
        outputs.joinToString("\n", "// output ports\n", postfix = "\n") { generateOutputPortConnection(it as Output) } +
        generateExternalConnections()

    // If any reactions are triggered by this port. Generate an InputPort object and connect the triggered reactions.
    fun generateInputPortConnection(p: Input): String {
        if (p.getTriggeredReactions.size > 0) {
            val reactionConns = p.getTriggeredReactions.joinToString("\n", postfix = "\n") {"${p.name} >> ${it.getInstanceName}.io.${p.name}"}

            return """
            ${p.name} << io.${p.name}
            $reactionConns
        """.trimIndent()
        } else {
            return ""
        }
    }

    fun generateOutputPortConnection(p: Output): String {
        val numWriters = p.getWritingReactions.size + p.getWritingReactors.size

        if (numWriters > 0) {
            val reactionConns =
                p.getWritingReactions.joinToString("\n", postfix = "\n") { "${p.name} << ${it.getInstanceName}.io.${p.name}" }

            return """
                ${p.name} >> io.${p.name}
                $reactionConns
            """.trimIndent()
        } else {
            return ""
        }
    }
    private fun generateExternalConnections(): String =
        generateExternalInputConnections() + generateExternalOutputConnections()
    private fun generateExternalInputConnections(): String =
        externalInputs.joinToString("\n", postfix = "\n") { generateExternalInputConnection(it) }
    private fun generateExternalOutputConnections(): String =
        externalOutputs.joinToString("\n", postfix = "\n") { generateExternalOutputConnection(it) }

    private fun generateExternalInputConnection(port: Input): String {
        val builder = StringBuilder()
        for (r in port.getTriggeredReactions) {
            builder.appendLine("${r.getInstanceName}.io.${port.name} := externalIO.${port.name}")
        }
        return builder.toString()
    }
    private fun generateExternalOutputConnection(port: Output): String {
        val builder = StringBuilder()
        for (r in port.getWritingReactions) {
            builder.appendLine(" ${port.getExternalOutputLatchName} <> ${r.getInstanceName}.io.${port.name}")
        }
        return builder.toString()
    }

    private fun generateExternalInputs(): String =
        externalInputs.joinToString("\n", postfix = "\n") { "val ${it.name} = Input(${it.getDataType})" }
    private fun generateExternalOutputs(): String =
        externalOutputs.joinToString("\n", postfix = "\n") { "val ${it.name} = Output(${it.getDataType})" }

    private fun generateExternalOutputLatch(): String =
        externalOutputs.joinToString("\n", postfix = "\n") {
            """
                |// Create a latch for the external output `${it.getName}`
                |val ${it.getExternalOutputLatchName} = Module(new ExternalOutputPortLatch(${it.getDataType}, ${it.getWritingReactions.size}))
                |externalIO.${it.name} := ${it.getExternalOutputLatchName}.io.read
            """.trimMargin()
        }

    private fun generateExternalIO(): String = with(PrependOperator) {
        """ |// Generate the IO to be forwarded up to the top-level pins of the design.
            |class ExternalIO extends ReactorExternalIO(childReactors) {
         ${"|  "..generateExternalInputs()}
         ${"|  "..generateExternalOutputs()}
            |}
            |val externalIO = IO(new ExternalIO())
         ${"|"..generateExternalOutputLatch()}
            |
        """.trimMargin()
    }
}