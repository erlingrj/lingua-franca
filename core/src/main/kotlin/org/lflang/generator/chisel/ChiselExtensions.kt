/**
 * @author erling r. jellum (erling.r.jellum@ntnu.no)
 *
 * copyright (c) 2023, the norwegian university of science and technology.
 *
 * redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * this software is provided by the copyright holders and contributors "as is" and any
 * express or implied warranties, including, but not limited to, the implied warranties of
 * merchantability and fitness for a particular purpose are disclaimed. in no event shall
 * the copyright holder or contributors be liable for any direct, indirect, incidental,
 * special, exemplary, or consequential damages (including, but not limited to,
 * procurement of substitute goods or services; loss of use, data, or profits; or business
 * interruption) however caused and on any theory of liability, whether in contract,
 * strict liability, or tort (including negligence or otherwise) arising in any way out of
 * the use of this software, even if advised of the possibility of such damage.
 */
package org.lflang.generator.chisel

import org.lflang.*
import org.lflang.AttributeUtils.*
import org.lflang.lf.*

// Ports commmon
val Port.getReadMaster: String
    get() {
        if (isArrayPort(this)) return "new EventArrayReadMaster(${getDataType}, ${getTokenType})"
        else return "new EventSingleValueReadMaster(${getDataType})"
    }
val Port.getReadSlave: String
    get() {
        if (isArrayPort(this)) return "new EventArrayReadSlave(${getDataType}, ${getTokenType})"
        else return "new EventSingleValueReadSlave(${getDataType})"
    }
val Port.getWriteSlave: String
    get() {
        if (isArrayPort(this)) return "new EventArrayWriteSlave(${getDataType}, ${getTokenType})"
        else return "new EventSingleValueWriteSlave(${getDataType})"
    }
val Port.getWriteMaster: String
    get() {
        if (isArrayPort(this)) return "new EventArrayWriteMaster(${getDataType}, ${getTokenType})"
        else return "new EventSingleValueWriteMaster(${getDataType})"
    }

val Port.getDataType: String
    get() {
        if (type.id == null) {
            return type.code.body
        } else if (type.id == "UInt") {
            return "UInt()"
        } else {
            return type.id
        }
    }

val Port.getUnconnectedNamePrefix: String
    get() = "${(this.eContainer() as Reactor).name}_${name}"

val Port.getSwTokenType: String
    get() = "new SwSingleToken($getDataType)"
val Port.getTokenType: String
    get() {
        if (isArrayPort(this)) {
            return "new ArrayToken($getDataType, ${arrayPortLength(this)})"
        } else {
            return "new SingleToken($getDataType)"
        }
    }

val Port.getConnName: String
    get() = "conn_$name"
val Port.getConnFuncName: String
    get() = "conn_${name}_func"

val Port.getConnType: String
    get() {
        if (isArrayPort(this)) {
            return "ArrayToken"

        } else {
            return "SingleToken"
        }
    }

val Port.getName: String
    get() = this.name.replace(".", "__")
val Port.getConnFunc: String
    get() ="(c: ConnectionConfig[$getDataType.type, $getTokenType.type]) => new ${getConnType}(c)"


val Port.getConnectionFactory: String
    get() {
        if (isArrayPort(this)) {
            return "new ArrayConnectionFactory(${getDataType}, ${getTokenType})"
        } else {
            return "new SingleValueConnectionFactory(${getDataType})"
        }
    }

val Port.getUnconnectedInputPort: String
    get() {
        if (isArrayPort(this)) {
            return "new UnconnectedArrayInputPort(${getDataType}, ${getTokenType})"
        } else {
            return "new UnconnectedSingleValueInputPort(${getDataType})"
        }
    }
val Port.getInwardConnectionFactory: String
    get() = "new InputPortInwardConnectionFactory(${getDataType}, ${getTokenType})"

val Port.isExternal: Boolean
    get() = findAttributeByName(this, "external") != null

val Port.getExternalOutputLatchName: String
    get() = "_${this.name}Latch"

fun getChildPortName(child: Instantiation, port: Port): String =
    "${child.name}__${port.name}"

// ------------------------------------------------------------------------------------------------------------------------------
// Input ports
val Input.getInwardConnName: String
    get() = "conn_pt_${name}"

val Port.getInputPort: String
    get() {
        if (isArrayPort(this)) {
            return "InputPortArray"
        } else {
            return "InputPortSingleValue"
        }
    }
// Given an Input port. Return the list of reactions which is triggered or sourcing that port.
val Input.getTriggeredReactions: List<Reaction>
    get() {
        val triggeredReactions = mutableListOf<Reaction>()
        val parent = this.eContainer() as Reactor
        for (r in parent.reactions) {
            for (dep in r.triggers + r.sources) {
                if ((dep is VarRef) && dep.variable == this) {
                    triggeredReactions += r
                }
            }
        }
        return triggeredReactions
    }

// ------------------------------------------------------------------------------------------------------------------------------
val Port.getOutputPort: String
    get() {
        if (isArrayPort(this)) {
            return "OutputPortArray"
        } else {
            return "OutputPortSingleValue"
        }
    }

// Output ports
// Given an output port. Return the list of Reactions which writes to that port.
val Output.getWritingReactions: List<Reaction>
    get() {
        val writingReactions = mutableListOf<Reaction>()
        val parent = this.eContainer() as Reactor
        for (r in parent.reactions) {
            for (dep in r.effects) {
                if (dep.variable == this)
                    writingReactions += r
            }
        }
        return writingReactions
    }

// Given an Output port. Return a list of the Instantiations which writes directly to that port.
val Output.getWritingReactors: List<Instantiation>
    get() {
        val writingReactors = mutableListOf<Instantiation>()
        val parent = this.eContainer() as Reactor
        for (c in parent.connections) {
            if (c.isIterated) {
                for (rhs in c.rightPorts) {
                    if (rhs.variable == this) {
                        writingReactors += (c.leftPorts.get(0).container as Instantiation)
                    }
                }
            } else {
                val numConns = Math.max(c.leftPorts.size, c.rightPorts.size)
                for (i in 0 until numConns) {
                    val lhs = c.leftPorts.get(i)
                    val rhs = c.rightPorts.get(i)

                    if (rhs.variable == this) {
                        writingReactors += (lhs.container as Instantiation)
                    }
                }
            }
        }
        return writingReactors
    }

// FIXME: Actually get the correct data type
val StateVar.getDataType: String
    get() {
        if (type.id == null) {
            return type.code.body
        } else if (type.id == "UInt") {
            return "UInt()"
        } else {
            return type.id
        }
    }

val StateVar.getStateDecl: String
    get() {
        val nReactions = (this.eContainer() as Reactor).reactions.size
        return "new SingleValueState(StateConfig(${getDataType}, ${getTokenType}, ${nReactions}, ${getStateProtocol}))"
    }

val StateVar.getStateProtocol: String
    get() = "Immediate"

// FIXME: Actually get the right token type
val StateVar.getTokenType: String
    get() = "new SingleToken(${getDataType})"



val Reaction.getClassName: String
    get() = "Reaction${indexInContainer}"
val Reaction.getInstanceName: String
    get() = "reaction${indexInContainer}"
val Reaction.getIOClassName: String
    get() = "Reaction${indexInContainer}IO"

val Connection.getName: String
    get() {
        val lhsPort = this.leftPorts.get(0).variable as Port
        val lhsParentInst = this.leftPorts.get(0).container as Instantiation
        return "_conn_${lhsParentInst.name}_${lhsPort.name}"
    }

val VarRef.getConnectionName: String
    get() {
        val port = this.variable as Port
        val parentInst = this.container  as Instantiation
        return "_conn_${parentInst.name}_${port.name}"
    }

val Action.getDataType: String
    get() {
        if (type == null) {
            return "UInt(0.W)"
        }
        if (type.id == null) {
            return type.code.body
        } else {
            return type.id
        }
    }

val Action.getTokenType: String
    get() {
        if (isArrayAction(this)) {
            return "new ArrayToken($getDataType, ${arrayPortLength(this)})"
        } else if (type==null){
            return "new PureToken"
        } else {
            return "new SingleToken($getDataType)"
        }
    }

val Action.getVirtualTrigger: String
    get() {
        return "new PhysicalActionTriggerPureVirtual(offset=Time.nsec(0))"
    }
val Action.getReadMaster: String
    get() {
        if (type == null) {
            return "new EventPureReadMaster"
        } else if (isArrayAction(this)) {
            return "new EventArrayReadMaster(${getDataType}, ${getTokenType})"
        } else {
            return "new EventSingleValueReadMaster(${getDataType})"
        }
    }

val Action.getInputPort: String
    get() {
        if (isArrayAction(this)) {
            return "Module(new InputPortArray(InputPortConfig(${getDataType}, ${getTokenType},${getTriggeredReactions.size})))"
        } else if (type == null){
            return "Module(new InputPortPure(InputPortConfig(${getDataType}, ${getTokenType},${getTriggeredReactions.size})))"
        } else {
            return "Module(new InputPortSingleValue(InputPortConfig(${getDataType}, ${getTokenType},${getTriggeredReactions.size})))"
        }
    }

val Action.getTriggeredReactions: List<Reaction>
    get() {
        val triggeredReactions = mutableListOf<Reaction>()
        val parent = this.eContainer() as Reactor
        for (r in parent.reactions) {
            for (dep in r.triggers + r.sources) {
                if ((dep is VarRef) && dep.variable == this) {
                    triggeredReactions += r
                }
            }
        }
        return triggeredReactions
    }
