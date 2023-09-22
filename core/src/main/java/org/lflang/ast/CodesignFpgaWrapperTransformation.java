/*************
    * Copyright (c) 2023, The Norwegian University of Science and Technology.
    * Redistribution and use in source and binary forms, with or without modification,
    * are permitted provided that the following conditions are met:
    * 1. Redistributions of source code must retain the above copyright notice,
    * this list of conditions and the following disclaimer.
    * 2. Redistributions in binary form must reproduce the above copyright notice,
    * this list of conditions and the following disclaimer in the documentation
    * and/or other materials provided with the distribution.
    * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
    * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
    * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
    * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
    * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
    * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
    * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
    * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
    * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
    ***************/

package org.lflang.ast;

import static org.lflang.AttributeUtils.arrayPortLength;
import static org.lflang.AttributeUtils.copyArrayAttribute;
import static org.lflang.AttributeUtils.isArrayPort;
import static org.lflang.AttributeUtils.isEnclave;
import static org.lflang.AttributeUtils.isExternalPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;

import org.lflang.AttributeUtils;
import org.lflang.MessageReporter;
import org.lflang.generator.CodeBuilder;
import org.lflang.lf.Action;
import org.lflang.lf.ActionOrigin;
import org.lflang.lf.Assignment;
import org.lflang.lf.BuiltinTrigger;
import org.lflang.lf.BuiltinTriggerRef;
import org.lflang.lf.CodeExpr;
import org.lflang.lf.Connection;
import org.lflang.lf.Initializer;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Parameter;
import org.lflang.lf.ParameterReference;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.StateVar;
import org.lflang.lf.TriggerRef;
import org.lflang.lf.Type;
import org.lflang.lf.VarRef;

public class CodesignFpgaWrapperTransformation implements AstTransformation {

    public static final LfFactory factory = ASTUtils.factory;
    private final Resource mainResource;
    private final MessageReporter messageReporter;

    Map<Input, TriggerRef> inputMap = new LinkedHashMap<>();
    Map<Output, VarRef> outputMap = new LinkedHashMap<>();

    public CodesignFpgaWrapperTransformation(
        Resource mainResource, MessageReporter messageReporter) {
        this.mainResource = mainResource;
        this.messageReporter = messageReporter;
    }


    public void applyTransformation(List<Reactor> reactors) {
        // This function performs the whole AST transformation consisting in:
        // 1. Find the FPGA reactor

        Instantiation fpgaInst = getFpgaInst(reactors);

        Reactor fpgaWrapper = createFpgaSwWrapper(ASTUtils.toDefinition(fpgaInst.getReactorClass()));

        replaceFpgaWithWrapper(fpgaInst, fpgaWrapper);
    }

    private Instantiation getFpgaInst(List<Reactor> reactors) {
        for (Reactor container : reactors) {
            if (container.isMain()) {
                for (Instantiation inst : container.getInstantiations()) {
                    if (AttributeUtils.isFpgaTopLevel(inst)) {
                        return inst;
                    }
                }
            }
        }
        throw new RuntimeException("Did not find reactor instantiation with @fpga attribute in main");
    }

    private Reactor createFpgaSwWrapper(Reactor fpga) {
        Reactor res = factory.createReactor();
        res.setName(wrapperClassName());

        // Duplicate inputs
        for (Input input : fpga.getInputs()) {
            if (isExternalPort(input)) {
                continue;
            }
            Input in = factory.createInput();
            Type type = chiselPortToCppType(input);
            String name = input.getName();
            in.setName(name);
            in.setType(EcoreUtil.copy(type));

            copyArrayAttribute(input, in);
            // Add all objects to the wrapper class.
            res.getInputs().add(in);

            VarRef varRef = factory.createVarRef();
            varRef.setVariable(in);
            inputMap.put(in, (TriggerRef) varRef);
        }

        // Duplicate output
        for (Output output : fpga.getOutputs()) {
            if (isExternalPort(output)) {
                continue;
            }
            Output out = factory.createOutput();
            Type type = chiselPortToCppType(output);
            String name = output.getName();
            out.setName(name);
            out.setType(EcoreUtil.copy(type));
            copyArrayAttribute(output, out);

            // Add all objects to the wrapper class.
            res.getOutputs().add(out);

            VarRef varRef = factory.createVarRef();
            varRef.setVariable(out);
            outputMap.put(out, varRef);
        }


        // Create a parameter for enabling/disabling tracing
        Parameter tracing = factory.createParameter();
        Type boolType = factory.createType();
        boolType.setId("bool");
        Initializer falseInit = factory.createInitializer();
        CodeExpr falseExpr = factory.createCodeExpr();
        falseExpr.setCode(factory.createCode());
        falseExpr.getCode().setBody("false");
        falseInit.getExprs().add(falseExpr);
        tracing.setName("vcdTracing");
        tracing.setType(EcoreUtil.copy(boolType));
        tracing.setInit(EcoreUtil.copy(falseInit));
        res.getParameters().add(tracing);

        // Create a logical action
        Action act = factory.createAction();
        act.setName("a");
        act.setOrigin(ActionOrigin.LOGICAL);
        res.getActions().add(act);
        VarRef actRef = factory.createVarRef();
        actRef.setVariable(act);

        // Create state variables for driver objects
        StateVar sPlatform = factory.createStateVar();
        Type wrapperRegDriverPtrType = factory.createType();
        wrapperRegDriverPtrType.setId("WrapperRegDriver*");
        sPlatform.setName("platform");
        sPlatform.setType(wrapperRegDriverPtrType);
        res.getStateVars().add(sPlatform);

        StateVar sFpga = factory.createStateVar();
        Type codesignTopReactorPtrType = factory.createType();
        codesignTopReactorPtrType.setId("CodesignTopReactor*");
        sFpga.setName("fpga");
        sFpga.setType(codesignTopReactorPtrType);
        res.getStateVars().add(sFpga);

        StateVar sShutdownHandled = factory.createStateVar();
        sShutdownHandled.setName("shutdown_handled");
        sShutdownHandled.setType(EcoreUtil.copy(boolType));
        sShutdownHandled.setInit(EcoreUtil.copy(falseInit));
        res.getStateVars().add(sShutdownHandled);

        // Create state vars for storing buffers and sizes for any array ports.
        Stream.concat(fpga.getOutputs().stream(), fpga.getInputs().stream()).forEach( it -> {
            // If array port add some state vars for it
            if (isArrayPort(it)) {
                StateVar portBuf = factory.createStateVar();
                portBuf.setName(it.getName()+"_buf");
                portBuf.setType(getType("void*"));
                res.getStateVars().add(portBuf);

                StateVar portBufSize = factory.createStateVar();
                portBufSize.setName(it.getName()+"_buf_size");
                portBufSize.setType(getType("int"));
                res.getStateVars().add(portBufSize);
            }
        });

        // Create startup reaction
        BuiltinTriggerRef startupTrigger = factory.createBuiltinTriggerRef();
        startupTrigger.setType(BuiltinTrigger.STARTUP);
        Reaction startup = factory.createReaction();
        startup.getTriggers().add(startupTrigger);
        startup.getEffects().addAll(outputMap.values());
        startup.getEffects().add(actRef);

        startup.setCode(factory.createCode());
        startup.getCode().setBody(generateStartupReactionBody());
        res.getReactions().add(startup);

        // Create main reaction
        BuiltinTriggerRef mainStartupTrigger = factory.createBuiltinTriggerRef();
        BuiltinTriggerRef mainShutdownTrigger = factory.createBuiltinTriggerRef();
        mainStartupTrigger.setType(BuiltinTrigger.STARTUP);
        mainShutdownTrigger.setType(BuiltinTrigger.SHUTDOWN);
        Reaction main = factory.createReaction();
        main.getTriggers().add(mainStartupTrigger);
        main.getTriggers().add(mainShutdownTrigger);
        main.getTriggers().add(EcoreUtil.copy(actRef));
        main.getTriggers().addAll(inputMap.values());
        main.getEffects().addAll(outputMap.values());
        main.getEffects().add(EcoreUtil.copy(actRef));

        main.setCode(factory.createCode());
        main.getCode().setBody(generateMainReactionBody());
        res.getReactions().add(main);

        // Create shutdown reaction
        // Create main reaction
        BuiltinTriggerRef shutShutdownTrigger = factory.createBuiltinTriggerRef();
        shutShutdownTrigger.setType(BuiltinTrigger.SHUTDOWN);
        Reaction shut = factory.createReaction();
        shut.getTriggers().add(shutShutdownTrigger);

        shut.setCode(factory.createCode());
        shut.getCode().setBody(generateShutdownReactionBody());
        res.getReactions().add(shut);


        // Hook the new reactor class into the AST.
        EObject node =
            IteratorExtensions.findFirst(mainResource.getAllContents(), Model.class::isInstance);
        ((Model) node).getReactors().add(res);

        return res;
    }

    private void replaceFpgaWithWrapper(Instantiation fpga, Reactor wrapper) {
        Instantiation wrapperInst = ASTUtils.createInstantiation(wrapper);
        wrapperInst.setName(wrapperInstName());
        Reactor parent = (Reactor) fpga.eContainer();

        for (Connection conn : parent.getConnections()) {
            for (VarRef lhs : conn.getLeftPorts()) {
                if (lhs.getContainer().equals(fpga)) {
                    lhs.setContainer(wrapperInst);
                }
            }

            for (VarRef rhs : conn.getRightPorts()) {
                if (rhs.getContainer().equals(fpga)) {
                    rhs.setContainer(wrapperInst);
                }
            }
        }

        // Add a top-level parameter for vcdTracing of the HW
        Parameter tracing = factory.createParameter();
        Type boolType = factory.createType();
        boolType.setId("bool");
        Initializer falseInit = factory.createInitializer();
        CodeExpr falseExpr = factory.createCodeExpr();
        falseExpr.setCode(factory.createCode());
        falseExpr.getCode().setBody("false");
        falseInit.getExprs().add(falseExpr);
        tracing.setName("vcdTracing");
        tracing.setType(EcoreUtil.copy(boolType));
        tracing.setInit(EcoreUtil.copy(falseInit));
        parent.getParameters().add(tracing);

        Assignment ass = factory.createAssignment();
        ParameterReference parentAssRef = factory.createParameterReference();
        parentAssRef.setParameter(tracing);
        Initializer init = factory.createInitializer();
        init.getExprs().add(parentAssRef);
        ass.setLhs(wrapper.getParameters().get(wrapper.getParameters().size()-1)); // FIXME: Only works when we have no prior parameters. Add validator check
        ass.setRhs(init);
        wrapperInst.getParameters().add(ass);

        parent.getInstantiations().add(wrapperInst);
        parent.getInstantiations().remove(fpga);
    }

    private String generateStartupReactionBody() {
        CodeBuilder code = new CodeBuilder();
        code.pr("""
            platform = initPlatform(vcdTracing);
            fpga = new CodesignTopReactor(platform);
            reactor::log::Info() << "Attached to FPGA Reactor with Signature: " <<hex << fpga->get_signature() <<dec;
            // Fetch both 32 bit and 64 bit representation of the NET
            auto NET32 = hwReactorDuration(fpga->get_coordination_nextEventTag());
            auto NET = reactor::Duration(fpga->get_coordination_nextEventTag());
            // Dont schedule anything if the NET=0 as the main reaction is triggered by startup
            // If there are no local events at the FPGA the NET=NEVER (-inf)
            if (NET32 > 0ns) {
                a.schedule(NET);
            }
            // Allocate buffers for shared memory ports (i.e. array ports)
            """);

        Stream.concat(outputMap.keySet().stream(), inputMap.keySet().stream()).forEach(it -> {
            if (isArrayPort(it)) {
                code.pr(it.getName() + "_buf_size = " + arrayPortLength(it) + " * sizeof("+ getBaseTypeFromCppArray(it.getType())+");");
                code.pr(it.getName() + "_buf = platform->allocAccelBuffer("+it.getName()+"_buf_size);");
                code.pr("fpga->set_ports_"+it.getName()+"_addr(static_cast<uint32_t>(reinterpret_cast<intptr_t>("+it.getName()+"_buf)));");
            }
        });
        return code.toString();
    }
    private String generateMainReactionOutputCheck(Output out) {
        CodeBuilder code = new CodeBuilder();
        code.pr("if (fpga->get_ports_" + out.getName() + "_present()) {");
        code.indent();
        if (isArrayPort(out)) {
            code.pr("std::array<"+getBaseTypeFromCppArray(out.getType())+","+arrayPortLength(out)+"> "+out.getName()+"_tmp;");
            code.pr("platform->copyBufferAccelToHost("+out.getName()+"_buf, "+out.getName()+"_tmp.data(), "+out.getName()+"_buf_size);");
            code.pr(out.getName() + ".set(std::move("+out.getName() +"_tmp));");
        } else {
            code.pr(out.getName() + ".set(fpga->get_ports_" + out.getName() + "_data());");
        }
        code.unindent();
        code.pr("}");
        return code.toString();
    }

    private String generateMainReactionInputCheck(Input in) {
        CodeBuilder code = new CodeBuilder();
        code.pr("if (" + in.getName() + ".is_present()) {");
        code.indent();
        code.pr("fire_fpga = true;");
        code.pr("fpga->set_ports_" + in.getName() + "_present(true);");
        if (isArrayPort(in)) {
            code.pr("platform->copyBufferHostToAccel("+in.getName()+".get()->data(), "+in.getName()+"_buf, "+in.getName()+"_buf_size);");
        } else {
            code.pr("fpga->set_ports_" + in.getName() + "_data(*" + in.getName() + ".get());");
        }
        code.unindent();
        code.pr("} else {");
        code.indent();
        code.pr("fpga->set_ports_" + in.getName() + "_present(false);");
        code.unindent();
        code.pr("}");
        return code.toString();
    }
    private String generateMainReactionBody() {
        CodeBuilder code = new CodeBuilder();
        code.pr("""
            // Early exit if shutdown trigger already handled.
            if(shutdown_handled) return;
            // Get the current time, both 32 bit and 64 bit representation.
            auto now = get_elapsed_logical_time();
            auto now32 = hwReactorDuration(static_cast<std::int32_t>(now.count()));
            // Fetch NET (both 32 and 64 bit representations)
            auto NET32 = hwReactorDuration(fpga->get_coordination_nextEventTag());
            auto NET = reactor::Duration(fpga->get_coordination_nextEventTag() +  (now.count() >> 32));
            reactor::log::Debug() <<"Fpga Wrapper: Triggered @ " <<now;
            auto fire_fpga = false;
            """);

        code.prComment("Check all the inputs and forward any data");
        for (Input in: inputMap.keySet()) {
            code.pr(generateMainReactionInputCheck(in));
        }

        code.pr("""
            // Handle the shutdown trigger
            if(shutdown.is_present()) {
                fpga->set_coordination_shutdownCommand_valid(true);
                fpga->set_coordination_shutdownCommand_independent(NET != now);
            }
            
            // Handle events originating from the FPGA
            if (a.is_present()) {
                fire_fpga = true;
                if (NET != now) {
                    reactor::log::Error() <<"Fpga Wrapper: Logical action present, but NET != now";
                    exit(1);
                }
            }
            
            """);

        code.pr("if (fire_fpga) {");
        code.indent();
        code.prComment("Set the TAG");
        code.pr("fpga->set_coordination_tagAdvanceGrant(now32.count());");
        code.prComment("Fire the FPGA");
        code.pr("fpga->set_cmd(WRITE);");
        code.prComment("Block until FPGA is finished with this tag");
        code.pr("while(hwReactorDuration(fpga->get_coordination_logicalTagComplete()) != now32) {}");

        code.prComment("Forward any outputs from the FPGA");
        for (Output out: outputMap.keySet()) {
            code.pr(generateMainReactionOutputCheck(out));
        }

        code.pr("""
            //Inform the FPGA that we have consumed its events
            fpga->set_cmd(READ);
            //Lookup next event originating from the FPGA
            auto NET32 = hwReactorDuration(fpga->get_coordination_nextEventTag());
            auto NET = reactor::Duration(fpga->get_coordination_nextEventTag() +  (now.count() >> 32));
            if (NET32 < hwReactorDuration::max() && NET32 > 0ns) {
                auto NET_duration = NET - now;
                reactor::log::Debug() <<"Fpga Wrapper: Scheduling next FPGA event @ " <<NET <<" in " <<NET_duration;
                a.schedule(NET_duration);
            } else {
                reactor::log::Debug() <<"Fpga Wrapper: No events for the FPGA";
            }
            }
                        
            reactor::log::Debug() <<"Fpga Wrapper: Finished @ " <<now;
            """);
        return code.toString();
    }

    private String generateShutdownReactionBody() {
        CodeBuilder code = new CodeBuilder();
        code.pr("if(!shutdown_handled) {");
        code.indent();
        code.pr("reactor::log::Info() <<\"Fpga Wrapper: Do shutdown and deinitialize\";");

        Stream.concat(outputMap.keySet().stream(), inputMap.keySet().stream()).forEach( it ->{
            if (isArrayPort(it)) {
                code.pr("platform->deallocAccelBuffer("+it.getName() + "_buf);");
            }
        });
        code.pr("delete fpga;");
        code.pr("deinitPlatform(platform);");
        code.pr("shutdown_handled=true;");
        code.unindent();
        code.pr("}");
        return code.toString();
    }

    private Type chiselPortToCppType(Port chiselPort) {
        Type type = factory.createType();
        var s = chiselPort.getType().getCode().getBody();
        Pattern pattern = Pattern.compile("UInt\\((\\d+)\\.W\\)");
        Matcher matcher = pattern.matcher(s);

        if (matcher.matches()) {
            var w = Integer.parseInt(matcher.group(1));
            if (w != 8 && w != 16 && w !=32 && w != 64) {
                throw new RuntimeException("The UInts between HW and SW must be either 8,16,32 or 64 bit");
            }
            var datatype = "uint" + Integer.toString(w) + "_t";

            if (isArrayPort(chiselPort)) {
                datatype += "[" + arrayPortLength(chiselPort) + "]";
            }
            type.setId(datatype);
        } else {
            throw new RuntimeException("Did not recognize port type between HW and SW. Must be UInt(8/16/32/64.W). Got: " + s);
        }
        return type;
    }

    /** Create the name of a wrapper. */
    private String wrapperClassName() {
        return "_FpgaWrapper";
    }

    private String wrapperInstName() {
        return "_fpgaWrapper";
    }
    private Type getType(String t) {
        Type res = factory.createType();
        res.setId(t);
        return res;
    }

    private String getBaseTypeFromCppArray(Type t) {
        return t.getId().replaceAll("\\[.*?\\]", "");
    }
}