package org.yamcs.xtceproc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.yamcs.YConfiguration;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SequenceContainer;
import org.yamcs.xtce.SpaceSystem;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtce.util.NameReference.Type;
import org.yamcs.xtce.util.UnresolvedNameReference;
import org.yamcs.xtceproc.XtceDbFactory.ResolvedReference;

public class XtceDbFactoryTest {

    /*
     * This test constructs the following tree:
     * a [p1]
     * - b1 [p1]
     * - b2
     * - c1 [p1]
     * - b3
     * 
     */
    @Test
    public void testNamespaces() throws Exception {
        YConfiguration.setupTest("refmdb");
        XtceDbFactory.reset();

        Map<String, Object> mdbConfig = new HashMap<>();
        mdbConfig.put("type", "sheet");
        mdbConfig.put("spec", "mdb/refmdb.xls");

        List<Object> mdbConfigs = Arrays.asList(mdbConfig);
        XtceDb db = XtceDbFactory.createInstance(mdbConfigs, true, true);

        SequenceContainer pkt1 = db.getSequenceContainer("/REFMDB/SUBSYS1/PKT1");
        assertNotNull(pkt1);
        // assertEquals(pkt1, db.getSequenceContainer("/REFMDB", "SUBSYS1/PKT1")); // Not supported yet
        assertEquals(pkt1, db.getSequenceContainer("/REFMDB/SUBSYS1", "PKT1"));

        Parameter p = db.getParameter("/REFMDB/SUBSYS1/IntegerPara1_1");
        assertNotNull(p);
        assertEquals(p, db.getParameter("/REFMDB/SUBSYS1", "IntegerPara1_1"));

        SpaceSystem ss = db.getSpaceSystem("/REFMDB/SUBSYS1");
        assertNotNull(ss);
        assertEquals(ss, db.getSpaceSystem("/REFMDB", "SUBSYS1"));

        ResolvedReference rr = XtceDbFactory.findReference(db.getRootSpaceSystem(),
                new UnresolvedNameReference("/REFMDB/SUBSYS1/IntegerPara1_1", Type.PARAMETER), ss);
        assertNotNull(rr);
        assertEquals("/REFMDB/SUBSYS1/IntegerPara1_1", rr.nd.getQualifiedName());

        rr = XtceDbFactory.findReference(db.getRootSpaceSystem(),
                new UnresolvedNameReference("../SUBSYS1/IntegerPara1_1", Type.PARAMETER), ss);
        assertNotNull(rr);
        assertEquals("/REFMDB/SUBSYS1/IntegerPara1_1", rr.nd.getQualifiedName());
    }

    @Test
    public void testResolveReference() {
        SpaceSystem root = new SpaceSystem("");

        SpaceSystem a = new SpaceSystem("a");
        root.addSpaceSystem(a);

        SpaceSystem a_b1 = new SpaceSystem("b1");
        a.addSpaceSystem(a_b1);
        SpaceSystem a_b2 = new SpaceSystem("b2");
        a.addSpaceSystem(a_b2);
        SpaceSystem a_b3 = new SpaceSystem("b3");
        a.addSpaceSystem(a_b3);

        SpaceSystem a_b2_c1 = new SpaceSystem("c1");
        a_b2.addSpaceSystem(a_b2_c1);

        Parameter a_p1 = new Parameter("p1");
        a_p1.addAlias("MDB:OPS Name", "a_p1");
        a.addParameter(a_p1);

        Parameter a_b1_p1 = new Parameter("p1");
        a_b1_p1.addAlias("MDB:OPS Name", "a_b1_p1");
        a_b1.addParameter(a_b1_p1);

        Parameter a_b2_c1_p1 = new Parameter("p1");
        a_b2_c1.addParameter(a_b2_c1_p1);

        ResolvedReference rr;

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("/a/b2/c1/p1", Type.PARAMETER), a_b1);
        assertEquals(a_b2_c1_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("p1", Type.PARAMETER), a_b2_c1);
        assertEquals(a_b2_c1_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("p1", Type.PARAMETER), a_b2);
        assertEquals(a_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("p1", Type.PARAMETER), a_b1);
        assertEquals(a_b1_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("b2/c1/p1", Type.PARAMETER), a_b2_c1);
        assertEquals(a_b2_c1_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("b2/.//../b2/c1/p1", Type.PARAMETER),
                a_b2_c1);
        assertEquals(a_b2_c1_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("../p1", Type.PARAMETER), a_b2_c1);
        assertNull(rr);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("../../p1", Type.PARAMETER), a_b2_c1);
        assertEquals(a_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("../../p1", Type.PARAMETER), a_b3);
        assertNull(rr);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("./p1", Type.PARAMETER), a_b2_c1);
        assertEquals(rr.nd, a_b2_c1_p1);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("./p1", Type.PARAMETER), a_b2);
        assertNull(rr);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("/a/b2/c1/.//p1", Type.PARAMETER), a_b2);
        assertEquals(a_b2_c1_p1, rr.nd);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("/a/..", Type.PARAMETER), a_b2);
        assertNull(rr);

        rr = XtceDbFactory.findReference(root, new UnresolvedNameReference("p2", Type.PARAMETER), a_b2);
        assertNull(rr);
    }
}
