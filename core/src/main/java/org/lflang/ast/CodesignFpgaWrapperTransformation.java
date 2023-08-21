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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;

import org.lflang.AttributeUtils;
import org.lflang.MessageReporter;
import org.lflang.ast.ASTUtils;
import org.lflang.ast.AstTransformation;
import org.lflang.generator.CodeBuilder;
import org.lflang.lf.Action;
import org.lflang.lf.ActionOrigin;
import org.lflang.lf.Assignment;
import org.lflang.lf.BuiltinTrigger;
import org.lflang.lf.BuiltinTriggerRef;
import org.lflang.lf.CodeExpr;
import org.lflang.lf.Connection;
import org.lflang.lf.Expression;
import org.lflang.lf.Initializer;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Mode;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Parameter;
import org.lflang.lf.ParameterReference;
import org.lflang.lf.Port;
import org.lflang.lf.Preamble;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.StateVar;
import org.lflang.lf.Time;
import org.lflang.lf.TriggerRef;
import org.lflang.lf.Type;
import org.lflang.lf.TypeParm;
import org.lflang.lf.VarRef;
import org.lflang.lf.Variable;

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
            Input in = factory.createInput();
            Type type = chiselTypeToCppType(input.getType());
            String name = input.getName();
            in.setName(name);
            in.setType(EcoreUtil.copy(type));

            // Add all objects to the wrapper class.
            res.getInputs().add(in);

            VarRef varRef = factory.createVarRef();
            varRef.setVariable(in);
            inputMap.put(in, (TriggerRef) varRef);
        }

        // Duplicate output
        for (Output output : fpga.getOutputs()) {
            Output out = factory.createOutput();
            Type type = chiselTypeToCppType(output.getType());
            String name = output.getName();
            out.setName(name);
            out.setType(EcoreUtil.copy(type));

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
        return """
            platform = initPlatform(vcdTracing);
            fpga = new CodesignTopReactor(platform);
            std::cout << "Attached to FPGA Reactor with Signature: " <<hex << fpga->get_signature() <<dec << std::endl;
            auto NET = reactor::Duration(fpga->get_coordination_nextEventTag());
            // Dont schedule anything if the NET=0 as the main reaction is triggered by startup
            if (NET > 0ns) {
                a.schedule(NET);
            }
            """;
    }
    private String generateMainReactionOutputCheck(Output out) {
        CodeBuilder code = new CodeBuilder();
        code.pr("if (fpga->get_ports_" + out.getName() + "_present()) {");
        code.indent();
        code.pr(out.getName() + ".set(fpga->get_ports_" + out.getName() + "_data());");
        code.unindent();
        code.pr("}");
        return code.toString();
    }

    private String generateMainReactionInputCheck(Input in) {
        CodeBuilder code = new CodeBuilder();
        code.pr("if (" + in.getName() + ".is_present()) {");
        code.indent();
        code.pr("fpga->set_ports_" + in.getName() + "_data(*" + in.getName() + ".get());");
        code.pr("fpga->set_ports_" + in.getName() + "_present(true);");
        code.unindent();
        code.pr("} else {");
        code.pr("fpga->set_ports_" + in.getName() + "_present(false);");
        code.unindent();
        code.pr("}");
        return code.toString();
    }
    private String generateMainReactionBody() {
        CodeBuilder code = new CodeBuilder();
        code.pr("""
                // Early exit if shutdown trigger already handled.
                if(shutdown_handled) {
                    return;
                }
                """);

        code.pr("auto now = get_elapsed_logical_time();");
        code.prComment("Check all the inputs and forward any data");
        for (Input in: inputMap.keySet()) {
            code.pr(generateMainReactionInputCheck(in));
        }

        code.pr("""
            // Handle the shutdown trigger
            if(shutdown.is_present()) {
                auto NET = reactor::Duration(fpga->get_coordination_nextEventTag());
                fpga->set_coordination_shutdownCommand_valid(true);
                fpga->set_coordination_shutdownCommand_independent(NET > now);
                shutdown_handled = true; // To avoid handling twice due to possible runtime bug in Cpp target
            }
            """);

        code.prComment("Set the TAG");
        code.pr("fpga->set_coordination_tagAdvanceGrant(now.count());");
        code.prComment("Fire the FPGA");
        code.pr("fpga->set_cmd(WRITE);");
        code.prComment("Block until FPGA is finished with this tag");
        code.pr("while(reactor::Duration(fpga->get_coordination_logicalTagComplete()) != now) {}");

        code.prComment("Forward any outputs from the FPGA");
        for (Output out: outputMap.keySet()) {
            code.pr(generateMainReactionOutputCheck(out));
        }

        code.prComment("Inform the FPGA that we have consumed its events");
        code.pr("fpga->set_cmd(READ);");

        code.prComment("Lookup next event originating from the FPGA");
        code.pr("auto NET = fpga->get_coordination_nextEventTag();");
        code.pr("auto NET_duration = NET - now.count();");
        code.pr("a.schedule(reactor::Duration(NET_duration));");

        code.prComment("Handle termination of FPGA accelerator");
        code.pr("if (shutdown.is_present()) {");
        code.indent();
        code.pr("delete fpga;");
        code.pr("deinitPlatform(platform);");
        code.unindent();
        code.pr("}");

        return code.toString();
    }

    private Type chiselTypeToCppType(Type chiselType) {
        Type type = factory.createType();

        if (chiselType.getCode().getBody().contains("UInt")) {
            type.setId("uint64_t");
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
}