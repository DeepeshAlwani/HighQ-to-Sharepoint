package com.hq2sp.sharepoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphClientPathTest {

    @Test
    void addressesItemsByEncodedPath() {
        assertEquals("drives/D/root", GraphClient.itemRef("D", ""));
        assertEquals("drives/D/root:/1%20Structure/19%20Policy.docx:", GraphClient.itemRef("D", "1 Structure/19 Policy.docx"));
        assertEquals("drives/D/root:/a%23b/100%25.txt:", GraphClient.itemRef("D", "a#b/100%.txt"));
        assertEquals("drives/D/root:/Form%5BN1%5D.docx:", GraphClient.itemRef("D", "Form[N1].docx"));
    }

    @Test
    void cleansTargetFolderPaths() {
        assertEquals("Migrated/Client A", GraphClient.cleanPath("/Migrated\\Client A/"));
        assertEquals("", GraphClient.cleanPath(null));
    }

    @Test
    void chunksAreMultiplesOf320KiB() {
        assertEquals(0, GraphClient.CHUNK % (320 * 1024));
    }
}
