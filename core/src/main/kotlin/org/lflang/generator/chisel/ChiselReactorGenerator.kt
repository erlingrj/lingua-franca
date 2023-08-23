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

import org.lflang.MessageReporter
import org.lflang.TargetConfig
import org.lflang.generator.PrependOperator
import org.lflang.generator.cpp.fileComment
import org.lflang.lf.Input
import org.lflang.lf.Output
import org.lflang.lf.Reactor

/**
 * A C++ code generator that produces a C++ class representing a single reactor
 */
class ChiselReactorGenerator(private val reactor: Reactor, private val fileConfig: ChiselFileConfig, messageReporter: MessageReporter, private val targetConfig: TargetConfig, private val isMain: Boolean) {

    private val states = ChiselStateGenerator(reactor)
    private val instances = ChiselInstanceGenerator(reactor, fileConfig, messageReporter)
    private val timers = ChiselTimerGenerator(reactor)
    private val triggers = ChiselTriggerGenerator(reactor)
    private val reactions = ChiselReactionGenerator(reactor)
    private val connections = ChiselConnectionGenerator(reactor)
    private val ports = ChiselPortGenerator(reactor, connections)
    private val preambles = ChiselPreambleGenerator(reactor)

    private fun generateIOInput(input: Input): String {
        var localConnections = 1
        if (input.getTriggeredReactions.size == 0) {
            localConnections = 0
        }

        if (connections.hasInwardPassThroughConnection(input)) {
            return "val ${input.name} = Vec($localConnections + ${input.getInwardConnName}.nDownstreamInwards, ${input.getReadMaster})"
        } else {
            return "val ${input.name} = Vec($localConnections, ${input.getReadMaster})"

        }
    }

    private fun generateDriveDefaultsFlipped(): String = with(PrependOperator) {
        val inputs = reactor.inputs.joinToString("\n") {"${it.name}.foreach(_.driveDefaultsFlipped())" }
        val outputs = reactor.outputs.joinToString("\n") {"${it.name}.driveDefaultsFlipped()" }
        return """
            // Drive all input and ouput ports to default inactive values, from context external to the Reactor.
            def driveDefaultsFlipped(): Unit = {
                ${inputs}
                ${outputs}
            }
        """.trimIndent()
    }
    private fun generateDriveDefaults(): String = with(PrependOperator) {
        val inputs = reactor.inputs.joinToString("\n") {"${it.name}.foreach(_.driveDefaults())" }
        val outputs = reactor.outputs.joinToString("\n") {"${it.name}.driveDefaults()" }
        return """
            // Drive all input and ouput ports to default inactive values, from context internal within the Reactor.
            def driveDefaults(): Unit = {
                ${inputs}
                ${outputs}
            }
        """.trimIndent()
    }

    private fun generateIOOutput(output: Output): String =
        "val ${output.name} = ${output.getWriteMaster}"

    private fun generateIO(): String = with(PrependOperator) {
            val inputs = reactor.inputs.joinToString("\n"){generateIOInput(it)}
            val outputs = reactor.outputs.joinToString("\n"){generateIOOutput(it)}
            val driveDefaultsFlipped = generateDriveDefaultsFlipped()
            val driveDefaults = generateDriveDefaults()
        return """
                |// The IO declaration of Reactor `${reactor.name}.
                |class ${reactor.name}IO extends ReactorIO {
             ${"|  "..inputs}
             ${"|  "..outputs}
             ${"|  "..driveDefaults}
             ${"|  "..driveDefaultsFlipped}
                |}
                |// The IO definition of this module
                |val io = IO(new ${reactor.name}IO())
                |// Drive the IOs to default value in case some of them are not connected.
                |io.driveDefaults()
            """.trimMargin()
    }

    fun generateSource() = with(PrependOperator) {
        """
            |package lf.${reactor.name}
            |import chisel3._
            |import chisel3.util._
            |import reactor._
            |import reactor.lib._
            |import scala.collection.mutable.ArrayBuffer
            |${preambles.generatePreamble()}
            |// Reaction declarations
            |${reactions.generateDeclarations()} 
            |
            |// Reactor declaration
            |class ${reactor.name} extends Reactor {
        ${" |  "..instances.generateDeclarations()}
        ${" |  "..timers.generateDeclarations()}
        ${" |  "..triggers.generateDeclarations()}
        ${" |  "..ports.generateDeclarations()}
        ${" |  "..reactions.generateDefinitions()}
        ${" |  "..reactions.generatePrecedenceConstraints()}
        ${" |  "..states.generateDeclarations()}
        ${" |  "..connections.generateDeclarations()}
        ${" |  "..generateIO()}
        ${" |  "..connections.generateDeclarationsPostIO()}
        ${" |  "..ports.generateConnections()}
        ${" |  "..connections.generateUnconnectedChildPortsDriver()}
            |  val triggerIO = connectTimersAndCreateIO()
            |  reactorMain()
            |}
            |
        ${" |  "..if (targetConfig.codesign && isMain) ports.generateSwIO() else ""}
        """.trimMargin()
    }
}



