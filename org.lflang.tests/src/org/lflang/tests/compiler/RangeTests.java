package org.lflang.tests.compiler;

import java.util.Set;

import org.eclipse.xtext.testing.InjectWith;
import org.eclipse.xtext.testing.extensions.InjectionExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lflang.DefaultErrorReporter;
import org.lflang.ErrorReporter;
import org.lflang.generator.PortInstance;
import org.lflang.generator.Range;
import org.lflang.generator.ReactorInstance;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Port;
import org.lflang.lf.Reactor;
import org.lflang.tests.LFInjectorProvider;

@ExtendWith(InjectionExtension.class)
@InjectWith(LFInjectorProvider.class)
public class RangeTests {

    private ErrorReporter reporter = new DefaultErrorReporter();
    private static LfFactory factory = LfFactory.eINSTANCE;
    
    @Test
    public void createRange() throws Exception {
        Reactor main = factory.createReactor();
        ReactorInstance maini = new ReactorInstance(main, reporter);
        
        Reactor a = factory.createReactor();
        a.setName("A");
        ReactorInstance ai = new ReactorInstance(a, reporter, maini);
        ai.setWidth(2);
        
        Reactor b = factory.createReactor();
        b.setName("B");
        ReactorInstance bi = new ReactorInstance(b, reporter, ai);
        bi.setWidth(2);

        Port p = factory.createPort();
        p.setName("P");
        PortInstance pi = new PortInstance(p, bi, reporter);
        pi.setWidth(2);

        Assertions.assertEquals(".A.B.P", pi.getFullName());
        
        Range<PortInstance> range = new Range.Port(pi, 3, 4, null);
        
        Assertions.assertEquals(8, range.maxWidth);
        
        // The results expected below are derived from the class comment for Range,
        // which includes this example.
        Set<Integer> instances = range.instances(pi);
        Assertions.assertEquals(Set.of(3, 4, 5, 6), instances);
        
        instances = range.instances(bi);
        Assertions.assertEquals(Set.of(1, 2, 3), instances);

        instances = range.instances(ai);
        Assertions.assertEquals(Set.of(0, 1), instances);
        
        range = range.toggleInterleaved(bi);
        instances = range.instances(pi);
        Assertions.assertEquals(Set.of(3, 4, 6, 5), instances);

        range = range.toggleInterleaved(ai);
        instances = range.instances(pi);
        Assertions.assertEquals(Set.of(6, 1, 5, 3), instances);

        range = range.toggleInterleaved(bi);
        instances = range.instances(pi);
        Assertions.assertEquals(Set.of(5, 2, 6, 3), instances);
    }
}