package org.yamcs.xtce;

import java.io.IOException;
import java.net.URISyntaxException;
import javax.xml.stream.XMLStreamException;
import org.junit.Before;
import org.junit.Test;
import org.yamcs.YConfiguration;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.xml.XtceLoadException;
import org.yamcs.xtceproc.XtceDbFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests that an XTCE document with a variable-length binary data types can be
 * parsed successfully.
 */
public class VariableBinaryXtceTest {

    private static final String SIZE_QN = "/VariableBinaryTest/size";
    private static final String DATA_QN = "/VariableBinaryTest/data";

    private static final String COMMAND_QN = "/VariableBinaryTest/Command";

    private XtceDb db;

    @Before
    public void setup() throws URISyntaxException, XtceLoadException,
            XMLStreamException, IOException {

        YConfiguration.setupTest(null);
        db = XtceDbFactory.createInstanceByConfig("VariableBinaryTest");

        TimeEncoding.setUp();
    }

    @Test
    public void testReadXtce() throws URISyntaxException, XtceLoadException,
            XMLStreamException, IOException {

        Parameter dataParameter = db.getParameter(DATA_QN);
        ParameterType parameterType = dataParameter.getParameterType();
        DataEncoding de = parameterType.getEncoding();
        assertTrue(de instanceof BinaryDataEncoding);
        BinaryDataEncoding bde = (BinaryDataEncoding) de;
        assertTrue(bde.isVariableSize());

        Parameter sizeParameter = db.getParameter(SIZE_QN);
        assertEquals(sizeParameter.getQualifiedName(), bde.getDynamicSize().getDynamicInstanceRef().getName());

        Argument dataArgument = db.getMetaCommand(COMMAND_QN)
                .getArgument("data");
        ArgumentType argumentType = dataArgument.getArgumentType();
        assertTrue(argumentType instanceof BinaryArgumentType);
        BinaryArgumentType binaryType = (BinaryArgumentType) argumentType;
        de = binaryType.getEncoding();
        assertTrue(de instanceof BinaryDataEncoding);
        bde = (BinaryDataEncoding) de;
        assertTrue(bde.isVariableSize());

        Argument sizeArgument = db.getMetaCommand(COMMAND_QN)
                .getArgument("size");
        assertEquals(sizeArgument.getName(), bde.getDynamicSize().getDynamicInstanceRef().getName());

    }

}
